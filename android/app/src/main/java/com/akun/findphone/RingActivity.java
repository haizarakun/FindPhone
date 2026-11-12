package com.akun.findphone;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 鳴動中に全画面で点滅する画面。ロック画面の上にも表示され、大きなボタンで止められる。 */
public class RingActivity extends LocalizedActivity {
    // 点滅は約1.4回/秒。光過敏への配慮で毎秒3回未満に抑える
    private static final long BLINK_MS = 350;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private TextView title;
    private boolean bright;

    private final Runnable blink = new Runnable() {
        @Override
        public void run() {
            if (!RingService.ringing) {
                finish();
                return;
            }
            bright = !bright;
            root.setBackgroundColor(bright ? 0xFFFFFFFF : 0xFFE5173F);
            title.setTextColor(bright ? 0xFF111111 : 0xFFFFFFFF);
            ui.postDelayed(this, BLINK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!RingService.ringing) {
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 1f; // 見つけやすいよう最大輝度
        getWindow().setAttributes(lp);

        root = Ui.column(this);
        root.setGravity(Gravity.CENTER);
        int p = Ui.dp(this, 32);
        root.setPadding(p, p, p, p);

        title = Ui.text(this, getString(R.string.ring_title), 40, 0xFFFFFFFF, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView stop = Ui.text(this, getString(R.string.ring_stop), 28, 0xFFFFFFFF, true);
        stop.setGravity(Gravity.CENTER);
        stop.setBackground(Ui.round(this, 0xFF111111, 28));
        stop.setMinHeight(Ui.dp(this, 96));
        stop.setOnClickListener(v -> {
            startService(new Intent(this, RingService.class).setAction(RingService.ACT_STOP));
            finish();
        });
        root.addView(stop, Ui.margins(this, 48));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (root != null) ui.post(blink);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(blink);
        super.onPause();
    }
}
