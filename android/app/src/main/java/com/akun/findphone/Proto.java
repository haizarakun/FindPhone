package com.akun.findphone;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 通信プロトコルと鍵の保管。
 *
 * PC -> 'H' + cn(8)                      : 探索(ブロードキャスト/ユニキャスト)
 * 端末 -> 'C' + cn + sn(16) + HMAC       : チャレンジ(鍵を知っている証明)
 * PC -> 'R'|'S' + sn + HMAC              : 鳴らす / 止める(snは1回限り・10秒有効)
 * 端末 -> 'A' + cmd + sn + HMAC          : 受理通知
 * HMAC = HMAC-SHA256(共有鍵, 種別 + 各フィールド)
 */
final class Proto {
    static final int PORT = 47321;
    private static final SecureRandom RNG = new SecureRandom();
    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Proto() {}

    // ---- 鍵の保管 ----
    // 共有鍵は Android Keystore 内のAES鍵で暗号化して保存する(root等で設定ファイルを読まれても漏れない)。

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String WRAP_ALIAS = "findphone-wrap";
    private static final String PREF_WRAPPED = "kc";
    private static final String PREF_LEGACY_PLAIN = "k";
    private static volatile byte[] cachedKey;

    static byte[] key(Context c) {
        byte[] k = cachedKey;
        if (k == null) {
            synchronized (Proto.class) {
                if (cachedKey == null) cachedKey = load(c);
                k = cachedKey;
            }
        }
        return k;
    }

    static synchronized void regenerate(Context c) {
        byte[] k = random(16);
        store(c, k);
        cachedKey = k;
    }

    private static byte[] load(Context c) {
        SharedPreferences p = Prefs.of(c);
        String wrapped = p.getString(PREF_WRAPPED, null);
        if (wrapped != null) {
            try {
                return unwrap(Base64.decode(wrapped, Base64.NO_WRAP));
            } catch (Exception e) {
                // Keystoreの鍵が失われた場合は作り直す(再ペアリングが必要)
            }
        }
        String plain = p.getString(PREF_LEGACY_PLAIN, null); // 旧版の平文保存から移行
        byte[] k = plain != null ? Base64.decode(plain, Base64.NO_WRAP) : random(16);
        store(c, k);
        return k;
    }

    private static void store(Context c, byte[] k) {
        SharedPreferences.Editor e = Prefs.of(c).edit();
        try {
            e.putString(PREF_WRAPPED, Base64.encodeToString(wrap(k), Base64.NO_WRAP)).remove(PREF_LEGACY_PLAIN);
        } catch (Exception ex) {
            // Keystoreが使えない端末のみ、アプリ専用領域に平文で保存する
            e.putString(PREF_LEGACY_PLAIN, Base64.encodeToString(k, Base64.NO_WRAP)).remove(PREF_WRAPPED);
        }
        e.apply();
    }

    private static SecretKey wrapKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        Key existing = ks.getKey(WRAP_ALIAS, null);
        if (existing != null) return (SecretKey) existing;
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        g.init(new KeyGenParameterSpec.Builder(WRAP_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }

    /** 出力: IV(12) + 暗号文。 */
    private static byte[] wrap(byte[] plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, wrapKey());
        return cat(c.getIV(), c.doFinal(plain));
    }

    private static byte[] unwrap(byte[] data) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, wrapKey(), new GCMParameterSpec(128, data, 0, 12));
        return c.doFinal(data, 12, data.length - 12);
    }

    /** PCに入力するペアリングコード(Base32・4文字区切り)。 */
    static String pairCode(Context c) {
        String s = base32(key(c));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i += 4) {
            if (i > 0) out.append(' ');
            out.append(s, i, Math.min(i + 4, s.length()));
        }
        return out.toString();
    }

    static byte[] random(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    static byte[] mac(byte[] key, byte[]... parts) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            for (byte[] p : parts) m.update(p);
            return m.doFinal();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean same(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    static byte[] cat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    private static String base32(byte[] d) {
        StringBuilder s = new StringBuilder();
        int buf = 0, bits = 0;
        for (byte x : d) {
            buf = (buf << 8) | (x & 255);
            bits += 8;
            while (bits >= 5) {
                s.append(B32.charAt((buf >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) s.append(B32.charAt((buf << (5 - bits)) & 31));
        return s.toString();
    }
}
