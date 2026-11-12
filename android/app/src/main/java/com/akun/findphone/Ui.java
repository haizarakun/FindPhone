package com.akun.findphone;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** 全画面で共通の見た目(色・カード・ボタン・行)を作る部品集。 */
final class Ui {
    static final int BG = 0xFF0E1013;
    static final int CARD = 0xFF1A1D22;
    static final int CARD_HIGH = 0xFF272B33;
    static final int TEXT = 0xFFECEFF4;
    static final int SUB = 0xFF9AA3AF;
    static final int ACCENT = 0xFF3DDC84;
    static final int ON_ACCENT = 0xFF06210F;
    static final int WARN = 0xFFFFB300;
    static final int BAD = 0xFFFF5252;

    private Ui() {}

    interface Toggle {
        void changed(boolean on);
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** タイトル付きのスクロール画面を作り、中身を入れる縦並びの入れ物を返す。 */
    static LinearLayout page(Activity a, CharSequence title, boolean showBack) {
        LinearLayout outer = column(a);
        int p = dp(a, 16);
        outer.setPadding(p, p, p, dp(a, 32));

        LinearLayout bar = new LinearLayout(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinimumHeight(dp(a, 48));
        if (showBack) {
            TextView back = text(a, "←", 24, TEXT, false);
            back.setContentDescription(a.getString(R.string.back));
            back.setGravity(Gravity.CENTER);
            back.setMinWidth(dp(a, 48));
            back.setMinHeight(dp(a, 48));
            back.setBackgroundResource(selectable(a));
            back.setOnClickListener(v -> a.finish());
            bar.addView(back);
        }
        bar.addView(text(a, title, 24, TEXT, true));
        outer.addView(bar);

        LinearLayout content = column(a);
        outer.addView(content);

        ScrollView scroll = new ScrollView(a);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(BG);
        scroll.addView(outer);
        a.setContentView(scroll);
        return content;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        l.setBackground(round(c, CARD, 20));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        l.setLayoutParams(margins(c, 12));
        return l;
    }

    /** 横幅いっぱい・上に余白 topDp を持つレイアウト指定。 */
    static LinearLayout.LayoutParams margins(Context c, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, topDp);
        return lp;
    }

    static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static TextView button(Context c, CharSequence label, boolean primary, View.OnClickListener onClick) {
        TextView b = text(c, label, 16, primary ? ON_ACCENT : TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, 52));
        int p = dp(c, 12);
        b.setPadding(p, p, p, p);
        b.setBackground(ripple(c, primary ? ACCENT : CARD_HIGH, 14));
        b.setFocusable(true);
        b.setOnClickListener(onClick);
        return b;
    }

    /** タイトル+説明(+右端の部品)の1行。onClick が null なら押せない行になる。 */
    static LinearLayout row(Context c, CharSequence title, CharSequence sub, View right, View.OnClickListener onClick) {
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(c, 56));
        int v = dp(c, 8);
        r.setPadding(0, v, 0, v);

        LinearLayout texts = column(c);
        texts.addView(text(c, title, 16, TEXT, false));
        if (sub != null) texts.addView(text(c, sub, 13, SUB, false));
        r.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        if (right != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(dp(c, 12));
            r.addView(right, lp);
        }
        if (onClick != null) {
            r.setBackgroundResource(selectable(c));
            r.setFocusable(true);
            r.setOnClickListener(onClick);
        }
        return r;
    }

    static LinearLayout switchRow(Context c, CharSequence title, CharSequence sub, boolean checked, Toggle onChange) {
        Switch sw = new Switch(c);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((button, on) -> onChange.changed(on));
        return row(c, title, sub, sw, v -> sw.toggle());
    }

    static TextView chevron(Context c) {
        return text(c, "›", 22, SUB, false);
    }

    static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    private static Drawable ripple(Context c, int color, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), round(c, color, radiusDp), round(c, 0xFFFFFFFF, radiusDp));
    }

    private static int selectable(Context c) {
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }
}
