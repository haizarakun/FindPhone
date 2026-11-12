package com.akun.findphone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** アップデートのインストール結果を受け取る。OSの確認画面が必要な時はそれを開く。 */
public class InstallReceiver extends BroadcastReceiver {
    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context c, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = (Intent) i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                try {
                    c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) {
                    AppLog.add(c.getString(R.string.log_error, e.toString()));
                }
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            AppLog.add(c.getString(R.string.log_error,
                    "install: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)));
        }
    }
}
