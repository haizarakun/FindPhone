package com.akun.findphone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 再起動後、待受ONだった場合にサービスを復帰させる。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (Proto.prefs(c).getBoolean("on", false)) {
            c.startForegroundService(new Intent(c, RingService.class));
        }
    }
}
