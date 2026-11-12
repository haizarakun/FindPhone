package com.akun.findphone;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** ホーム画面: 待受の状態・ペアリングコード・テスト鳴動。 */
public class MainActivity extends Activity {
    private static final String HIDDEN_CODE = "•••• •••• ••••";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 1000);
        }
    };

    private View dot;
    private TextView statusTitle;
    private TextView statusDesc;
    private TextView network;
    private TextView vpnWarning;
    private Switch toggle;
    private TextView code;
    private TextView showButton;
    private TextView testButton;
    private boolean codeShown;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout content = Ui.page(this, getString(R.string.app_name), false);
        content.addView(statusCard());
        content.addView(pairingCard());

        testButton = Ui.button(this, "", true, v -> toggleTestRing());
        content.addView(testButton, Ui.margins(this, 12));

        LinearLayout nav = Ui.card(this);
        nav.addView(Ui.row(this, getString(R.string.settings), null, Ui.chevron(this),
                v -> startActivity(new Intent(this, SettingsActivity.class))));
        nav.addView(Ui.row(this, getString(R.string.help), null, Ui.chevron(this), v -> openHelp()));
        content.addView(nav);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
        }
        // 初回起動で待受を開始する。オンのままOSに止められていた場合もここで復帰する
        if (!Prefs.of(this).contains(Prefs.ON) || Prefs.get(this, Prefs.ON)) setListening(this, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        showCode(false); // 画面を離れたらコードを隠す
        super.onPause();
    }

    // ---- 画面の部品 ----

    private View statusCard() {
        LinearLayout card = Ui.card(this);

        dot = new View(this);
        statusTitle = Ui.text(this, "", 22, Ui.TEXT, true);
        LinearLayout titleLine = new LinearLayout(this);
        titleLine.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dotSize = new LinearLayout.LayoutParams(Ui.dp(this, 12), Ui.dp(this, 12));
        dotSize.setMarginEnd(Ui.dp(this, 10));
        titleLine.addView(dot, dotSize);
        titleLine.addView(statusTitle);

        statusDesc = Ui.text(this, "", 14, Ui.SUB, false);
        LinearLayout texts = Ui.column(this);
        texts.addView(titleLine);
        texts.addView(statusDesc);

        toggle = new Switch(this);
        toggle.setOnCheckedChangeListener((v, on) -> {
            if (on != Prefs.get(this, Prefs.ON)) setListening(this, on);
            refresh();
        });

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        top.addView(toggle);
        card.addView(top);

        network = Ui.text(this, "", 14, Ui.SUB, false);
        card.addView(network, Ui.margins(this, 12));

        vpnWarning = Ui.text(this, getString(R.string.vpn_warning), 14, Ui.WARN, false);
        vpnWarning.setOnClickListener(v -> openHelp());
        card.addView(vpnWarning, Ui.margins(this, 8));
        return card;
    }

    private View pairingCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, getString(R.string.pairing_title), 16, Ui.TEXT, true));
        card.addView(Ui.text(this, getString(R.string.pairing_hint), 13, Ui.SUB, false));

        code = Ui.text(this, HIDDEN_CODE, 20, Ui.ACCENT, false);
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextIsSelectable(true);
        card.addView(code, Ui.margins(this, 12));

        showButton = Ui.button(this, getString(R.string.show_code), false, v -> showCode(!codeShown));
        TextView share = Ui.button(this, getString(R.string.share_code), false, v -> shareCode());
        LinearLayout buttons = new LinearLayout(this);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        buttons.addView(showButton, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        half2.setMarginStart(Ui.dp(this, 8));
        buttons.addView(share, half2);
        card.addView(buttons, Ui.margins(this, 12));
        return card;
    }

    // ---- 動作 ----

    /** 待受のオン/オフを保存し、サービスを起動・停止する(他の画面からも使う)。 */
    static void setListening(Context c, boolean on) {
        boolean changed = on != Prefs.get(c, Prefs.ON);
        Prefs.set(c, Prefs.ON, on);
        if (changed) AppLog.add(c.getString(on ? R.string.log_on : R.string.log_off));
        Intent service = new Intent(c, RingService.class);
        try {
            if (on) c.startForegroundService(service);
            else c.stopService(service);
        } catch (Exception e) {
            AppLog.add(c.getString(R.string.log_service_fail, e.toString()));
        }
    }

    private void toggleTestRing() {
        if (!Prefs.get(this, Prefs.ON)) return;
        String action = RingService.ringing ? RingService.ACT_STOP : RingService.ACT_TEST;
        startService(new Intent(this, RingService.class).setAction(action));
        ui.postDelayed(this::refresh, 200);
    }

    /** コードの表示中だけ、スクリーンショット・画面録画を禁止する。 */
    private void showCode(boolean show) {
        codeShown = show;
        if (show) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        code.setText(show ? Proto.pairCode(this) : HIDDEN_CODE);
        showButton.setText(getString(show ? R.string.hide_code : R.string.show_code));
    }

    private void shareCode() {
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, Proto.pairCode(this));
        startActivity(Intent.createChooser(send, getString(R.string.share_chooser)));
    }

    private void openHelp() {
        startActivity(new Intent(this, HelpActivity.class));
    }

    private void refresh() {
        boolean on = Prefs.get(this, Prefs.ON);
        boolean ringing = RingService.ringing;
        if (toggle.isChecked() != on) toggle.setChecked(on);

        int color = ringing ? Ui.BAD : on ? Ui.ACCENT : Ui.SUB;
        dot.setBackground(Ui.round(this, color, 6));
        statusTitle.setText(getString(ringing ? R.string.status_ringing : on ? R.string.status_on : R.string.status_off));
        statusDesc.setText(getString(on ? R.string.status_on_desc : R.string.status_off_desc));

        String ip = Net.wifiIp(this);
        network.setText(ip != null ? getString(R.string.wifi_ip, ip) : getString(R.string.wifi_none));
        network.setTextColor(ip != null ? Ui.SUB : Ui.WARN);
        vpnWarning.setVisibility(Net.vpnActive(this) ? View.VISIBLE : View.GONE);

        testButton.setText(getString(ringing ? R.string.stop_ring : R.string.test_ring));
        testButton.setAlpha(on ? 1f : 0.4f);
    }
}
