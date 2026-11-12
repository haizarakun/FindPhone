package com.akun.findphone;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/** アプリ内の言語設定。端末の言語に関係なく、選んだ言語(既定は英語)で表示する。 */
final class Lang {
    static final String DEFAULT = "en";
    static final String[] CODES = {"en", "ja", "zh-CN", "zh-TW", "ko", "es", "fr", "de", "pt", "ru"};
    static final String[] NAMES = {"English", "日本語", "简体中文", "繁體中文", "한국어",
            "Español", "Français", "Deutsch", "Português", "Русский"};
    private static final String PREF = "lang";

    private Lang() {}

    static String current(Context c) {
        return Prefs.of(c).getString(PREF, DEFAULT);
    }

    static void set(Context c, String code) {
        Prefs.of(c).edit().putString(PREF, code).apply();
    }

    static int currentIndex(Context c) {
        String code = current(c);
        for (int i = 0; i < CODES.length; i++) {
            if (CODES[i].equals(code)) return i;
        }
        return 0;
    }

    /** 選んだ言語の文字列リソースを返すコンテキストに包む(Activity / Service の attachBaseContext で使う)。 */
    static Context wrap(Context base) {
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(Locale.forLanguageTag(current(base)));
        return base.createConfigurationContext(config);
    }
}
