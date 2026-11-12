package com.akun.findphone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** LAN内で署名付きの呼び出しを待ち受け、端末を鳴らす常駐サービス。 */
public class RingService extends Service {
    static final String ACT_STOP = "com.akun.findphone.STOP";
    static final String ACT_TEST = "com.akun.findphone.TEST";

    /** 画面から状態を読むための公開フラグ。 */
    static volatile boolean listening;
    static volatile boolean ringing;

    private static final int LISTEN_NOTIF_ID = 1;
    private static final int RING_NOTIF_ID = 2;
    private static final String CH_LISTEN = "l";
    private static final String CH_RING = "ring";
    private static final long CHALLENGE_TTL_MS = 10_000;
    private static final int MAX_PENDING = 64;
    private static final int MAX_HELLO_PER_SEC = 40;
    private static final long HELLO_LOG_INTERVAL_MS = 30_000;
    private static final long TORCH_BLINK_MS = 400;

    private static final AudioAttributes ALARM_AUDIO = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable autoStop = this::stopRing;

    // 待受
    private volatile boolean running;
    private volatile boolean rebinding;
    private volatile DatagramSocket sock;
    private volatile Network boundWifi;
    private Thread thread;
    private WifiManager.MulticastLock multicastLock;
    private PowerManager.WakeLock wakeLock;
    private ConnectivityManager.NetworkCallback wifiWatcher;

    // 鳴動
    private MediaPlayer player;
    private int savedVolume = -1;
    private String torchId;
    private boolean torchOn;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base)); // 通知とログもアプリ内の言語設定に合わせる
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CH_LISTEN, getString(R.string.channel_listen), NotificationManager.IMPORTANCE_LOW));
        NotificationChannel ring = new NotificationChannel(
                CH_RING, getString(R.string.channel_ring), NotificationManager.IMPORTANCE_HIGH);
        ring.setSound(null, null); // 音は自前で鳴らす
        ring.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ring);
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        if (!Prefs.get(this, Prefs.ON)) { // 待受オフ中に届いた操作は無視して終了
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            enterForeground();
            if (thread == null) {
                AppLog.add(getString(R.string.log_service_start));
                startListening();
            }
        } catch (Exception e) {
            AppLog.add(getString(R.string.log_service_fail, e.toString()));
        }

        String action = i != null ? i.getAction() : null;
        if (ACT_STOP.equals(action)) {
            stopRing();
        } else if (ACT_TEST.equals(action)) {
            AppLog.add(getString(R.string.log_test));
            startRing();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (wifiWatcher != null) {
            try {
                getSystemService(ConnectivityManager.class).unregisterNetworkCallback(wifiWatcher);
            } catch (Exception ignored) {
                // 未登録なら何もしない
            }
        }
        DatagramSocket s = sock;
        if (s != null) s.close();
        stopRing();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        listening = false;
        super.onDestroy();
    }

    private void enterForeground() {
        Notification n = new Notification.Builder(this, CH_LISTEN)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.notif_listening))
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(LISTEN_NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(LISTEN_NOTIF_ID, n);
        }
    }

    // ---- 待受 ----

    private void startListening() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        multicastLock = wm.createMulticastLock("findphone");
        multicastLock.setReferenceCounted(false);
        multicastLock.acquire();

        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "findphone:listen");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();

        running = true;
        thread = new Thread(this::listenLoop, "findphone-listen");
        thread.start();
        watchWifi();
    }

    /** Wi-Fiがつながり直したら、待受ソケットを新しいWi-Fiへ張り替える。 */
    private void watchWifi() {
        wifiWatcher = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                if (!n.equals(boundWifi)) rebind();
            }

            @Override
            public void onLost(Network n) {
                if (n.equals(boundWifi)) rebind();
            }
        };
        getSystemService(ConnectivityManager.class).registerNetworkCallback(
                new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                wifiWatcher);
    }

    private void rebind() {
        DatagramSocket s = sock;
        if (s != null && !s.isClosed()) {
            rebinding = true;
            s.close(); // 待受ループが新しいソケットを作り直す
        }
    }

    /** ソケットが落ちても自動で張り直して待ち受けを続ける。 */
    private void listenLoop() {
        while (running) {
            try (DatagramSocket s = new DatagramSocket(null)) {
                s.setReuseAddress(true);
                s.setBroadcast(true);
                s.bind(new InetSocketAddress(Proto.PORT));
                // VPN利用中でも応答がLANへ出るよう、ソケットをWi-Fiに直接結び付ける
                Network wifi = Net.wifi(this);
                if (wifi != null) wifi.bindSocket(s);
                boundWifi = wifi;
                sock = s;
                listening = true;
                AppLog.add(getString(wifi != null ? R.string.log_listen_wifi : R.string.log_listen_any, Proto.PORT));
                serve(s);
            } catch (Exception e) {
                if (running && rebinding) AppLog.add(getString(R.string.log_net_changed));
                else if (running) AppLog.add(getString(R.string.log_socket_error, e.toString()));
            }
            listening = false;
            rebinding = false;
            if (running) SystemClock.sleep(1000);
        }
        AppLog.add(getString(R.string.log_listen_stop));
    }

    private void serve(DatagramSocket s) throws IOException {
        byte[] buf = new byte[128];
        Map<String, Long> pending = new HashMap<>(); // 発行済みチャレンジ(sn -> 期限)
        while (running) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            s.receive(p);
            try {
                handle(s, p, pending);
            } catch (IOException e) {
                throw e; // 送信失敗はソケットを作り直す
            } catch (Exception e) {
                AppLog.add(getString(R.string.log_error, e.toString())); // 1パケットの失敗で待受を止めない
            }
        }
    }

    private void handle(DatagramSocket s, DatagramPacket p, Map<String, Long> pending) throws IOException {
        byte[] buf = p.getData();
        InetAddress from = p.getAddress();
        String who = from.getHostAddress();
        if (!from.isSiteLocalAddress() && !from.isLinkLocalAddress()) {
            logLimited("lan:" + who, getString(R.string.log_non_lan, who));
            return;
        }
        int n = p.getLength();
        long now = SystemClock.elapsedRealtime();
        byte[] key = Proto.key(this);
        for (Iterator<Long> it = pending.values().iterator(); it.hasNext(); ) {
            if (it.next() < now) it.remove(); // 期限切れのチャレンジを掃除
        }

        if (n == 9 && buf[0] == 'H') {
            if (pending.size() >= MAX_PENDING || !helloAllowed(now)) return; // 大量送信への対策
            byte[] cn = Arrays.copyOfRange(buf, 1, 9);
            byte[] sn = Proto.random(16);
            pending.put(Proto.hex(sn), now + CHALLENGE_TTL_MS);
            send(s, Proto.cat(new byte[]{'C'}, cn, sn, Proto.mac(key, new byte[]{'C'}, cn, sn)), p);
            logLimited("hello:" + who, getString(R.string.log_hello, who));
        } else if (n == 49 && (buf[0] == 'R' || buf[0] == 'S')) {
            byte cmd = buf[0];
            byte[] sn = Arrays.copyOfRange(buf, 1, 17);
            byte[] given = Arrays.copyOfRange(buf, 17, 49);
            if (!Proto.same(given, Proto.mac(key, new byte[]{cmd}, sn))) {
                logLimited("sig:" + who, getString(R.string.log_bad_sig, who));
                return;
            }
            Long deadline = pending.remove(Proto.hex(sn)); // 1回限り
            if (deadline == null || deadline < now) {
                logLimited("exp:" + who, getString(R.string.log_expired, who));
                return;
            }
            AppLog.add(getString(cmd == 'R' ? R.string.log_ring : R.string.log_stop, who));
            if (cmd == 'R') main.post(this::startRing);
            else main.post(this::stopRing);
            send(s, Proto.cat(new byte[]{'A', cmd}, sn, Proto.mac(key, new byte[]{'A'}, new byte[]{cmd}, sn)), p);
        }
    }

    private static void send(DatagramSocket s, byte[] data, DatagramPacket to) throws IOException {
        s.send(new DatagramPacket(data, data.length, to.getAddress(), to.getPort()));
    }

    // 探索(H)は1秒あたりの応答数に上限を設ける
    private long helloWindowStart;
    private int helloCount;

    private boolean helloAllowed(long now) {
        if (now - helloWindowStart >= 1000) {
            helloWindowStart = now;
            helloCount = 0;
        }
        return ++helloCount <= MAX_HELLO_PER_SEC;
    }

    // 同じ内容のログは30秒に1回だけ記録する(PCの接続確認は数秒ごとに届くため)
    private final Map<String, Long> lastLogged = new HashMap<>();

    private void logLimited(String topic, String msg) {
        long now = SystemClock.elapsedRealtime();
        if (lastLogged.size() > 100) lastLogged.clear();
        Long last = lastLogged.get(topic);
        if (last == null || now - last > HELLO_LOG_INTERVAL_MS) {
            lastLogged.put(topic, now);
            AppLog.add(msg);
        }
    }

    // ---- 鳴動 ----

    private void startRing() {
        main.removeCallbacks(autoStop);
        main.postDelayed(autoStop, Prefs.durationSec(this) * 1000L);
        if (ringing) return;
        ringing = true;

        startSound();
        if (Prefs.get(this, Prefs.VIBRATE)) startVibration();
        if (Prefs.get(this, Prefs.TORCH)) startTorch();

        boolean flash = Prefs.get(this, Prefs.FLASH);
        getSystemService(NotificationManager.class).notify(RING_NOTIF_ID, ringNotification(flash));
        if (flash) {
            try { // アプリが前面にある時はすぐ開く。裏にいる時は通知の全画面表示が開く
                startActivity(new Intent(this, RingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
                // 起動が制限されている場合は通知に任せる
            }
        }
    }

    private void stopRing() {
        main.removeCallbacks(autoStop);
        if (!ringing) return;
        ringing = false;
        stopSound();
        stopVibration();
        stopTorch();
        getSystemService(NotificationManager.class).cancel(RING_NOTIF_ID);
    }

    private void startSound() {
        if (Prefs.get(this, Prefs.MAX_VOLUME)) {
            try {
                AudioManager am = getSystemService(AudioManager.class);
                savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
            } catch (Exception ignored) {
                // 音量変更不可でも鳴動は続行
            }
        }
        String chosen = Prefs.of(this).getString(Prefs.SOUND, null);
        if (chosen != null) player = createPlayer(Uri.parse(chosen));
        if (player == null) player = createPlayer(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
        if (player == null) player = createPlayer(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
        if (player == null) AppLog.add(getString(R.string.log_sound_fail));
    }

    private MediaPlayer createPlayer(Uri uri) {
        if (uri == null) return null;
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(ALARM_AUDIO);
            mp.setDataSource(this, uri);
            mp.setLooping(true);
            mp.prepare();
            mp.start();
            return mp;
        } catch (Exception e) {
            mp.release();
            return null;
        }
    }

    private void stopSound() {
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
                // 既に停止済み
            }
            player.release();
            player = null;
        }
        if (savedVolume >= 0) {
            try {
                getSystemService(AudioManager.class).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0);
            } catch (Exception ignored) {
                // 戻せなくても致命的ではない
            }
            savedVolume = -1;
        }
    }

    @SuppressWarnings("deprecation")
    private void startVibration() {
        try {
            Vibrator v = getSystemService(Vibrator.class);
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createWaveform(new long[]{0, 600, 400}, 0), ALARM_AUDIO);
            }
        } catch (Exception ignored) {
            // バイブ非対応でも鳴動は続行
        }
    }

    private void stopVibration() {
        try {
            Vibrator v = getSystemService(Vibrator.class);
            if (v != null) v.cancel();
        } catch (Exception ignored) {
            // 何もしない
        }
    }

    private final Runnable torchBlink = new Runnable() {
        @Override
        public void run() {
            torchOn = !torchOn;
            setTorch(torchOn);
            main.postDelayed(this, TORCH_BLINK_MS);
        }
    };

    private void startTorch() {
        try {
            CameraManager cm = getSystemService(CameraManager.class);
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))
                        && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    torchId = id;
                    main.post(torchBlink);
                    return;
                }
            }
        } catch (Exception ignored) {
            // ライト非搭載・使用中なら点滅なしで続行
        }
    }

    private void stopTorch() {
        main.removeCallbacks(torchBlink);
        if (torchId != null) setTorch(false);
        torchId = null;
        torchOn = false;
    }

    private void setTorch(boolean on) {
        try {
            getSystemService(CameraManager.class).setTorchMode(torchId, on);
        } catch (Exception ignored) {
            // カメラ使用中などは無視
        }
    }

    private Notification ringNotification(boolean fullScreen) {
        PendingIntent open = PendingIntent.getActivity(this, 2,
                new Intent(this, RingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, RingService.class).setAction(ACT_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CH_RING)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.notif_ringing))
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(stop)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat), getString(R.string.stop_ring), stop).build());
        if (fullScreen) b.setFullScreenIntent(open, true);
        return b.build();
    }
}
