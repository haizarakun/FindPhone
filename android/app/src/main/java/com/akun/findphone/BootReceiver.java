package com.akun.findphone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 再起動後、待受ONだった場合にサービスを復帰させる。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        if (Prefs.get(c, Prefs.ON) && Prefs.get(c, Prefs.BOOT)) {
            c.startForegroundService(new Intent(c, RingService.class));
        }
    }
}
