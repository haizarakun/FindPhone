package com.akun.findphone;

import android.content.Context;
import android.content.SharedPreferences;

/** ユーザー設定の読み書き。キー名と既定値をここに集約する。 */
final class Prefs {
    static final String ON = "on";
    static final String SOUND = "sound";
    static final String DURATION = "dur";
    static final String FLASH = "flash";
    static final String TORCH = "torch";
    static final String VIBRATE = "vib";
    static final String MAX_VOLUME = "maxvol";
    static final String BOOT = "boot";

    /** 鳴らす時間の選択肢(秒)。 */
    static final int[] DURATIONS = {30, 90, 180, 600};

    private Prefs() {}

    static SharedPreferences of(Context c) {
        return c.getSharedPreferences("p", Context.MODE_PRIVATE);
    }

    /** 待受以外のスイッチは既定でオン。 */
    static boolean get(Context c, String key) {
        return of(c).getBoolean(key, !ON.equals(key));
    }

    static void set(Context c, String key, boolean value) {
        of(c).edit().putBoolean(key, value).apply();
    }

    static int durationSec(Context c) {
        return of(c).getInt(DURATION, 90);
    }
}
