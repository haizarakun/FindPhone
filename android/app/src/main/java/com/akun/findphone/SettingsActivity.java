package com.akun.findphone;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.Toast;

/** 設定画面: 鳴らし方・起動・言語・ペアリングコードの作り直し。 */
public class SettingsActivity extends Activity {
    private static final int REQ_SOUND = 1;
    private LinearLayout content;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        content = Ui.page(this, getString(R.string.settings), true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    /** 現在の設定値で画面を作り直す。 */
    private void build() {
        content.removeAllViews();

        LinearLayout ring = Ui.card(this);
        ring.addView(Ui.row(this, getString(R.string.sound), soundName(), Ui.chevron(this), v -> pickSound()));
        ring.addView(Ui.row(this, getString(R.string.duration), durationLabel(Prefs.durationSec(this)),
                Ui.chevron(this), v -> pickDuration()));
        ring.addView(toggleRow(R.string.max_volume, R.string.max_volume_desc, Prefs.MAX_VOLUME));
        ring.addView(toggleRow(R.string.flash_screen, R.string.flash_screen_desc, Prefs.FLASH));
        ring.addView(toggleRow(R.string.flashlight, 0, Prefs.TORCH));
        ring.addView(toggleRow(R.string.vibrate, 0, Prefs.VIBRATE));
        content.addView(ring);

        LinearLayout general = Ui.card(this);
        general.addView(toggleRow(R.string.start_on_boot, 0, Prefs.BOOT));
        if (Build.VERSION.SDK_INT >= 33) { // アプリごとの言語設定はAndroid 13以降
            general.addView(Ui.row(this, getString(R.string.language), null, Ui.chevron(this), v -> openLanguage()));
        }
        general.addView(Ui.row(this, getString(R.string.regenerate), getString(R.string.regenerate_desc),
                null, v -> confirmRegenerate()));
        content.addView(general);

        content.addView(Ui.text(this, getString(R.string.version, versionName()), 13, Ui.SUB, false),
                Ui.margins(this, 16));
    }

    private LinearLayout toggleRow(int title, int description, String key) {
        return Ui.switchRow(this, getString(title), description != 0 ? getString(description) : null,
                Prefs.get(this, key), on -> Prefs.set(this, key, on));
    }

    // ---- 鳴らす音 ----

    private String soundName() {
        String chosen = Prefs.of(this).getString(Prefs.SOUND, null);
        if (chosen == null) return getString(R.string.sound_default);
        try {
            Ringtone r = RingtoneManager.getRingtone(this, Uri.parse(chosen));
            String title = r != null ? r.getTitle(this) : null;
            return title != null ? title : getString(R.string.sound_default);
        } catch (Exception e) {
            return getString(R.string.sound_default);
        }
    }

    /** システムの音選択画面(アラーム/着信音/通知音)を開く。 */
    private void pickSound() {
        Intent i = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.sound));
        String current = Prefs.of(this).getString(Prefs.SOUND, null);
        if (current != null) i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(current));
        try {
            startActivityForResult(i, REQ_SOUND);
        } catch (Exception e) {
            AppLog.add(getString(R.string.log_error, e.toString()));
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int request, int result, Intent data) {
        if (request != REQ_SOUND || result != RESULT_OK || data == null) return;
        Uri picked = (Uri) data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
        boolean isDefault = picked == null || RingtoneManager.isDefault(picked);
        if (isDefault) Prefs.of(this).edit().remove(Prefs.SOUND).apply();
        else Prefs.of(this).edit().putString(Prefs.SOUND, picked.toString()).apply();
    }

    // ---- 鳴らす時間 ----

    private String durationLabel(int seconds) {
        return seconds < 120 ? getString(R.string.duration_seconds, seconds)
                : getString(R.string.duration_minutes, seconds / 60);
    }

    private void pickDuration() {
        String[] labels = new String[Prefs.DURATIONS.length];
        int checked = 0;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = durationLabel(Prefs.DURATIONS[i]);
            if (Prefs.DURATIONS[i] == Prefs.durationSec(this)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.duration)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    Prefs.of(this).edit().putInt(Prefs.DURATION, Prefs.DURATIONS[which]).apply();
                    dialog.dismiss();
                    build();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---- その他 ----

    private void openLanguage() {
        try {
            startActivity(new Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            AppLog.add(getString(R.string.log_error, e.toString()));
        }
    }

    private void confirmRegenerate() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.regenerate_confirm)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    Proto.regenerate(this);
                    Toast.makeText(this, R.string.regenerated, Toast.LENGTH_LONG).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }
}
