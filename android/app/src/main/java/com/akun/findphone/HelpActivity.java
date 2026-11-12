package com.akun.findphone;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** ヘルプ画面: 動かない原因の自動診断、PC側の確認点、サポート窓口、ログ。 */
public class HelpActivity extends Activity {
    private static final String DISCORD_URL = "https://discord.gg/hXv7G3xtVf";

    private enum Level { OK, WARN, BAD }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            logView.setText(AppLog.text());
            ui.postDelayed(this, 1000);
        }
    };

    private LinearLayout checks;
    private TextView logView;
    private final StringBuilder report = new StringBuilder(); // ログと一緒にコピーする診断結果

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout content = Ui.page(this, getString(R.string.help), true);

        LinearLayout diag = Ui.card(this);
        diag.addView(Ui.text(this, getString(R.string.diag_title), 16, Ui.TEXT, true));
        checks = Ui.column(this);
        diag.addView(checks);
        diag.addView(Ui.button(this, getString(R.string.diag_recheck), false, v -> runChecks()), Ui.margins(this, 12));
        content.addView(diag);

        LinearLayout tips = Ui.card(this);
        tips.addView(Ui.text(this, getString(R.string.pc_tips_title), 16, Ui.TEXT, true));
        TextView tipsText = Ui.text(this, getString(R.string.pc_tips), 14, Ui.SUB, false);
        tipsText.setLineSpacing(Ui.dp(this, 4), 1f);
        tips.addView(tipsText, Ui.margins(this, 8));
        content.addView(tips);

        LinearLayout support = Ui.card(this);
        support.addView(Ui.text(this, getString(R.string.support_title), 16, Ui.TEXT, true));
        support.addView(Ui.button(this, getString(R.string.discord), true, v -> open(
                new Intent(Intent.ACTION_VIEW, Uri.parse(DISCORD_URL)))), Ui.margins(this, 12));
        support.addView(Ui.button(this, getString(R.string.copy_log), false, v -> copyLog()), Ui.margins(this, 8));
        content.addView(support);

        LinearLayout log = Ui.card(this);
        log.addView(Ui.text(this, getString(R.string.log_title), 16, Ui.TEXT, true));
        logView = Ui.text(this, "", 12, Ui.SUB, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        log.addView(logView, Ui.margins(this, 8));
        content.addView(log);
    }

    @Override
    protected void onResume() {
        super.onResume();
        runChecks(); // 設定アプリから戻った時も最新の状態にする
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        super.onPause();
    }

    // ---- 診断 ----

    private void runChecks() {
        checks.removeAllViews();
        report.setLength(0);
        String pkg = "package:" + getPackageName();

        boolean on = Prefs.get(this, Prefs.ON);
        if (on && RingService.listening) {
            check(Level.OK, R.string.diag_service_ok, null, null);
        } else {
            check(Level.BAD, R.string.diag_service_ng, null, () -> {
                MainActivity.setListening(this, true);
                ui.postDelayed(this::runChecks, 1500);
            });
        }

        String ip = Net.wifiIp(this);
        if (ip != null) {
            check(Level.OK, getString(R.string.diag_wifi_ok, ip), null, null);
        } else {
            check(Level.BAD, getString(R.string.diag_wifi_ng), null,
                    () -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        }

        if (Net.vpnActive(this)) {
            check(Level.WARN, R.string.diag_vpn_ng, getString(R.string.diag_vpn_desc),
                    () -> open(new Intent(Settings.ACTION_VPN_SETTINGS)));
        } else {
            check(Level.OK, R.string.diag_vpn_ok, null, null);
        }

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.areNotificationsEnabled()) {
            check(Level.OK, R.string.diag_notif_ok, null, null);
        } else {
            check(Level.WARN, R.string.diag_notif_ng, null, () -> open(
                    new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())));
        }

        if (getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())) {
            check(Level.OK, R.string.diag_battery_ok, null, null);
        } else {
            check(Level.WARN, R.string.diag_battery_ng, null, () -> open(
                    new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse(pkg))));
        }

        if (Build.VERSION.SDK_INT >= 34 && Prefs.get(this, Prefs.FLASH)) { // 全画面通知の許可はAndroid 14以降
            if (nm.canUseFullScreenIntent()) {
                check(Level.OK, R.string.diag_fullscreen_ok, null, null);
            } else {
                check(Level.WARN, R.string.diag_fullscreen_ng, null, () -> open(
                        new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse(pkg))));
            }
        }
    }

    private void check(Level level, int title, String description, Runnable fix) {
        check(level, getString(title), description, fix);
    }

    /** 診断結果を1行追加する。fix があれば「直す」ボタンを付ける。 */
    private void check(Level level, String title, String description, Runnable fix) {
        String mark = level == Level.OK ? "✓" : level == Level.WARN ? "!" : "✕";
        int color = level == Level.OK ? Ui.ACCENT : level == Level.WARN ? Ui.WARN : Ui.BAD;
        report.append(mark).append(' ').append(title).append('\n');

        TextView icon = Ui.text(this, mark, 18, color, true);
        icon.setGravity(Gravity.CENTER);
        icon.setMinWidth(Ui.dp(this, 28));

        TextView fixButton = null;
        if (fix != null) {
            fixButton = Ui.text(this, getString(R.string.fix), 14, Ui.ON_ACCENT, true);
            fixButton.setBackground(Ui.round(this, color, 12));
            int h = Ui.dp(this, 14);
            int v = Ui.dp(this, 8);
            fixButton.setPadding(h, v, h, v);
            fixButton.setOnClickListener(x -> fix.run());
        }

        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(icon);
        line.addView(Ui.row(this, title, description, fixButton, null),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        checks.addView(line);
    }

    // ---- サポート ----

    /** 診断結果・端末情報・ログをまとめてコピーする(サポートへ貼り付ける用。鍵は含まない)。 */
    private void copyLog() {
        String text = "Find Phone " + versionName() + " / Android " + Build.VERSION.RELEASE
                + " / " + Build.MANUFACTURER + " " + Build.MODEL + "\n" + report + "\n" + AppLog.text();
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("log", text));
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show();
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (Exception e) {
            AppLog.add(getString(R.string.log_error, e.toString()));
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }
}
