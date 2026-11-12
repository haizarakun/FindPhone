package com.akun.findphone;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;

/** 全画面の土台。アプリ内の言語設定を反映し、言語が変わったら画面を作り直す。 */
abstract class LocalizedActivity extends Activity {
    private String shownLanguage;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        shownLanguage = Lang.current(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Lang.current(this).equals(shownLanguage)) recreate();
    }
}
