package com.akun.findphone;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/** 画面表示・サポート用のメモリ内ログ(鍵などの秘密は書かない)。 */
final class AppLog {
    private static final int MAX_LINES = 200;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();

    private AppLog() {}

    static synchronized void add(String msg) {
        LINES.addLast(new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + msg);
        while (LINES.size() > MAX_LINES) LINES.removeFirst();
    }

    /** 新しい順に並べたログ。 */
    static synchronized String text() {
        StringBuilder b = new StringBuilder();
        for (Iterator<String> it = LINES.descendingIterator(); it.hasNext(); ) b.append(it.next()).append('\n');
        return b.toString();
    }
}
