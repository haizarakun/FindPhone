package com.akun.findphone;

import android.os.Handler;
import android.os.Looper;
import android.widget.ScrollView;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 設定画面(リソース不使用の最小UI)。 */
public class MainActivity extends Activity {
    private static final int REQ_SOUND = 1;
    private TextView status;
    private TextView code;
    private Button toggle;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // スクリーンショット・画面録画からペアリングコードを守る
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF111111);
        int pad = dp(24);
        root.setPadding(pad, pad * 2, pad, pad);

        status = text(16, 0xFFCCCCCC, Typeface.DEFAULT);
        toggle = button("", v -> setOn(!isOn()));
        code = text(20, 0xFF7CFC9A, Typeface.MONOSPACE);
        code.setTextIsSelectable(true);

        root.addView(status);
        root.addView(toggle);
        root.addView(button("ペアリングコードを表示", v -> code.setText(Proto.pairCode(this))));
        root.addView(code);
        root.addView(button("コードを共有(PCへ送る)", v -> startActivity(Intent.createChooser(
                new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, Proto.pairCode(this)), "PCへ送る"))));
        root.addView(button("コードを再生成", v -> {
            Proto.regenerate(this);
            code.setText("");
            status.setText("再生成しました。PCで再設定してください");
        }));
        root.addView(button("音を選ぶ", v -> pickSound()));
        root.addView(button("鳴動を止める", v -> {
            if (isOn()) startService(new Intent(this, RingService.class).setAction(RingService.ACT_STOP));
        }));
        TextView logTitle = text(13, 0xFFAAAAAA, Typeface.DEFAULT);
        logTitle.setText("ログ(新しい順)");
        root.addView(logTitle);
        logView = text(11, 0xFF9E9E9E, Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        root.addView(logView);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF111111);
        scroll.addView(root);
        setContentView(scroll);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
        }
        if (!Proto.prefs(this).contains("on")) setOn(true); // 初回起動で待受開始
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        ui.post(logTick);
    }

    @Override
    protected void onPause() {
        code.setText(""); // 画面を離れたらコードを隠す
        ui.removeCallbacks(logTick);
        super.onPause();
    }

    /** システムの音選択画面(アラーム/着信音/端末内の音声ファイル)を開く。 */
    private void pickSound() {
        Intent i = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "鳴らす音");
        String cur = Proto.prefs(this).getString("sound", null);
        if (cur != null) i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(cur));
        startActivityForResult(i, REQ_SOUND);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int res, Intent data) {
        if (req != REQ_SOUND || res != RESULT_OK || data == null) return;
        Uri u = (Uri) data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
        SharedPreferences.Editor e = Proto.prefs(this).edit();
        if (u == null) e.remove("sound"); // 「既定」を選択
        else e.putString("sound", u.toString());
        e.apply();
        status.setText("音を設定しました");
    }

    private boolean isOn() {
        return Proto.prefs(this).getBoolean("on", false);
    }

    private void setOn(boolean on) {
        Proto.prefs(this).edit().putBoolean("on", on).apply();
        Intent svc = new Intent(this, RingService.class);
        if (on) startForegroundService(svc);
        else stopService(svc);
        refresh();
    }

    // ---- ログ表示(画面表示中だけ1秒ごとに更新) ----

    private TextView logView;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable logTick = new Runnable() {
        @Override
        public void run() {
            logView.setText(RingService.logText());
            ui.postDelayed(this, 1000);
        }
    };

    private void refresh() {
        boolean on = isOn();
        toggle.setText(on ? "待受 ON(タップで停止)" : "待受 OFF(タップで開始)");
        status.setText(on ? "PCからの呼び出しを待っています" : "停止中");
    }

    private TextView text(int sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setPadding(0, dp(12), 0, dp(12));
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button x = new Button(this);
        x.setText(label);
        x.setOnClickListener(l);
        return x;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
