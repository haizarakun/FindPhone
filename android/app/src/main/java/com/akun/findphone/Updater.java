package com.akun.findphone;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * GitHubの最新リリースを確認し、新しいAPKがあればダウンロードしてインストールする。
 *
 * 安全のための確認:
 *  - 通信はHTTPSのみ。ダウンロードしたAPKのSHA-256を、GitHubが公開している値と照合する
 *  - インストールはOSに任せるため、署名が今のアプリと違うAPKはOSが拒否する
 */
final class Updater {
    private static final String LATEST_RELEASE = "https://api.github.com/repos/haizarakun/FindPhone/releases/latest";
    private static final long CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L;
    private static final int MAX_APK_BYTES = 50 * 1024 * 1024;
    private static final String PREF_LAST_CHECK = "update_checked";
    static final String PREF_AUTO = "auto_update";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Updater() {}

    static final class Release {
        String version; // 例: "1.01"
        String apkUrl;
        String sha256;  // 小文字の16進。GitHubが値を出していない場合は null
    }

    interface OnChecked {
        /** 新しい版があれば release、無ければ null。メインスレッドで呼ばれる。 */
        void done(Release release);
    }

    interface OnInstalled {
        /** インストール画面を出せたら true。メインスレッドで呼ばれる。 */
        void done(boolean ok);
    }

    /** 自動確認: 設定がオンで、前回から24時間以上たっている時だけ問い合わせる。 */
    static void checkIfDue(Context c, OnChecked callback) {
        if (!Prefs.get(c, PREF_AUTO)) return;
        long last = Prefs.of(c).getLong(PREF_LAST_CHECK, 0);
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return;
        check(c, callback);
    }

    static void check(Context c, OnChecked callback) {
        Context app = c.getApplicationContext();
        Prefs.of(app).edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply();
        new Thread(() -> {
            Release found = null;
            try {
                Release latest = fetchLatest();
                if (latest != null && isNewer(latest.version, currentVersion(app))) found = latest;
            } catch (Exception e) {
                // オフライン・未公開などは「更新なし」として扱う
            }
            final Release result = found;
            MAIN.post(() -> callback.done(result));
        }, "findphone-update-check").start();
    }

    static void install(Context c, Release release, OnInstalled callback) {
        Context app = c.getApplicationContext();
        new Thread(() -> {
            boolean ok = false;
            try {
                downloadAndInstall(app, release);
                ok = true;
            } catch (Exception e) {
                AppLog.add(app.getString(R.string.log_error, e.toString()));
            }
            final boolean result = ok;
            MAIN.post(() -> callback.done(result));
        }, "findphone-update").start();
    }

    private static Release fetchLatest() throws Exception {
        HttpURLConnection conn = open(LATEST_RELEASE);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            if (conn.getResponseCode() != 200) return null;
            JSONObject json = new JSONObject(new String(readAll(conn.getInputStream(), 1024 * 1024), "UTF-8"));
            JSONArray assets = json.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                String url = asset.optString("browser_download_url", "");
                if (!asset.optString("name", "").endsWith(".apk") || !url.startsWith("https://github.com/")) continue;
                Release r = new Release();
                r.version = json.getString("tag_name").replaceFirst("^v", "");
                r.apkUrl = url;
                String digest = asset.optString("digest", "");
                if (digest.startsWith("sha256:")) r.sha256 = digest.substring(7).toLowerCase(java.util.Locale.US);
                return r;
            }
            return null;
        } finally {
            conn.disconnect();
        }
    }

    private static void downloadAndInstall(Context c, Release release) throws Exception {
        HttpURLConnection conn = open(release.apkUrl);
        byte[] apk;
        try {
            if (conn.getResponseCode() != 200) throw new IllegalStateException("HTTP " + conn.getResponseCode());
            apk = readAll(conn.getInputStream(), MAX_APK_BYTES);
        } finally {
            conn.disconnect();
        }
        if (release.sha256 != null) {
            String actual = Proto.hex(MessageDigest.getInstance("SHA-256").digest(apk));
            if (!actual.equals(release.sha256)) throw new SecurityException("checksum mismatch");
        }

        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (OutputStream out = session.openWrite("update.apk", 0, apk.length)) {
                out.write(apk);
                session.fsync(out);
            }
            // 結果(ユーザーへの確認画面を含む)は InstallReceiver が受け取る
            PendingIntent result = PendingIntent.getBroadcast(c, sessionId,
                    new Intent(c, InstallReceiver.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(result.getIntentSender());
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("User-Agent", "FindPhone-Android");
        return conn;
    }

    private static byte[] readAll(InputStream in, int limit) throws Exception {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = input.read(buf)) > 0) {
                if (out.size() + n > limit) throw new IllegalStateException("too large");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    static String currentVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** "1.01" と "1.00" のようなバージョンを、区切りごとの数値で比べる。 */
    static boolean isNewer(String candidate, String current) {
        String[] a = candidate.split("\\.");
        String[] b = current.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? number(a[i]) : 0;
            int y = i < b.length ? number(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
