package com.akun.findphone;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 通信プロトコルと鍵の保管。
 *
 * PC -> 'H' + cn(8)                      : 探索(ブロードキャスト)
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

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("p", Context.MODE_PRIVATE);
    }

    static byte[] key(Context c) {
        String s = prefs(c).getString("k", null);
        return s != null ? Base64.decode(s, Base64.NO_WRAP) : regenerate(c);
    }

    static byte[] regenerate(Context c) {
        byte[] k = random(16);
        prefs(c).edit().putString("k", Base64.encodeToString(k, Base64.NO_WRAP)).apply();
        return k;
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
