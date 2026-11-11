package com.akun.findphone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** LAN内で署名付きの呼び出しを待ち受け、端末を最大音量で鳴らす常駐サービス。 */
public class RingService extends Service {
    static final String ACT_STOP = "com.akun.findphone.STOP";
    private static final int NOTIF_ID = 1;
    private static final long RING_MAX_MS = 90_000;
    private static final long CHALLENGE_TTL_MS = 10_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable autoStop = this::stopRing;

    private volatile boolean running;
    private volatile DatagramSocket sock;
    private Thread thread;
    private WifiManager.MulticastLock multicastLock;
    private PowerManager.WakeLock wakeLock;

    private MediaPlayer player;
    private int savedVolume = -1;

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        if (i != null && ACT_STOP.equals(i.getAction())) {
            stopRing();
            return START_STICKY;
        }
        Notification n = notification(false);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
        if (thread == null) startListening();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        DatagramSocket s = sock;
        if (s != null) s.close();
        stopRing();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    // ---- 待受 ----

    private void startListening() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        multicastLock = wm.createMulticastLock("findphone");
        multicastLock.setReferenceCounted(false);
        multicastLock.acquire();

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "findphone:listen");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();

        running = true;
        thread = new Thread(this::listenLoop, "findphone-listen");
        thread.start();
    }

    private void listenLoop() {
        try (DatagramSocket s = new DatagramSocket(null)) {
            sock = s;
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(Proto.PORT));
            byte[] buf = new byte[128];
            Map<String, Long> pending = new HashMap<>(); // 発行済みチャレンジ(sn -> 期限)
            long lastHello = 0;

            while (running) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                InetAddress from = p.getAddress();
                // LAN(プライベート/リンクローカル)以外は無視
                if (!from.isSiteLocalAddress() && !from.isLinkLocalAddress()) continue;

                int n = p.getLength();
                long now = SystemClock.elapsedRealtime();
                byte[] key = Proto.key(this);

                if (n == 9 && buf[0] == 'H') {
                    if (now - lastHello < 300) continue; // 連打対策
                    lastHello = now;
                    if (pending.size() > 16) pending.clear();
                    byte[] cn = Arrays.copyOfRange(buf, 1, 9);
                    byte[] sn = Proto.random(16);
                    byte[] mac = Proto.mac(key, new byte[]{'C'}, cn, sn);
                    pending.put(Proto.hex(sn), now + CHALLENGE_TTL_MS);
                    send(s, Proto.cat(new byte[]{'C'}, cn, sn, mac), p);

                } else if (n == 49 && (buf[0] == 'R' || buf[0] == 'S')) {
                    byte cmd = buf[0];
                    byte[] sn = Arrays.copyOfRange(buf, 1, 17);
                    byte[] given = Arrays.copyOfRange(buf, 17, 49);
                    byte[] expect = Proto.mac(key, new byte[]{cmd}, sn);
                    if (!Proto.same(given, expect)) continue;
                    Long deadline = pending.remove(Proto.hex(sn)); // 1回限り
                    if (deadline == null || deadline < now) continue;

                    if (cmd == 'R') main.post(this::startRing);
                    else main.post(this::stopRing);
                    byte[] ack = Proto.mac(key, new byte[]{'A'}, new byte[]{cmd}, sn);
                    send(s, Proto.cat(new byte[]{'A', cmd}, sn, ack), p);
                }
            }
        } catch (Exception ignored) {
            // ソケットclose(サービス終了)で抜ける
        }
    }

    private static void send(DatagramSocket s, byte[] data, DatagramPacket to) throws java.io.IOException {
        s.send(new DatagramPacket(data, data.length, to.getAddress(), to.getPort()));
    }

    // ---- 鳴動 ----

    private void startRing() {
        main.removeCallbacks(autoStop);
        main.postDelayed(autoStop, RING_MAX_MS);
        if (player != null) return;

        AudioManager am = getSystemService(AudioManager.class);
        try {
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
        } catch (Exception ignored) {
            // 音量変更不可でも鳴動は続行
        }

        player = createPlayer(chosenSound());
        if (player == null) player = createPlayer(defaultSound()); // 選択音が使えない時は既定音
        if (player == null) {
            stopRing();
            return;
        }
        getSystemService(NotificationManager.class).notify(NOTIF_ID, notification(true));
    }

    /** 設定画面で選んだ音。未選択なら既定のアラーム音。 */
    private Uri chosenSound() {
        String s = Proto.prefs(this).getString("sound", null);
        return s != null ? Uri.parse(s) : defaultSound();
    }

    private Uri defaultSound() {
        Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        return uri != null ? uri : RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
    }

    private MediaPlayer createPlayer(Uri uri) {
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setDataSource(this, uri);
            mp.setLooping(true);
            mp.prepare();
            mp.start();
            return mp;
        } catch (Exception e) {
            return null;
        }
    }

    private void stopRing() {
        main.removeCallbacks(autoStop);
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            player.release();
            player = null;
            getSystemService(NotificationManager.class).notify(NOTIF_ID, notification(false));
        }
        if (savedVolume >= 0) {
            try {
                getSystemService(AudioManager.class).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0);
            } catch (Exception ignored) {
            }
            savedVolume = -1;
        }
    }

    // ---- 通知 ----

    private Notification notification(boolean ringing) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("l", "待受", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel rc = new NotificationChannel("r", "鳴動", NotificationManager.IMPORTANCE_HIGH);
        rc.setSound(null, null);
        nm.createNotificationChannel(rc);

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, ringing ? "r" : "l")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(ringing ? "鳴動中" : "待受中")
                .setContentIntent(open)
                .setOngoing(true);
        if (ringing) {
            PendingIntent stop = PendingIntent.getService(this, 1,
                    new Intent(this, RingService.class).setAction(ACT_STOP),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause), "停止", stop).build());
        }
        return b.build();
    }
}
