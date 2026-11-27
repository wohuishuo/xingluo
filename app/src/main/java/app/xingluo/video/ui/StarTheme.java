package app.xingluo.video.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

/** Unified visual tokens and builders shared across every screen, drawer, and sheet in Xingluo. */
public final class StarTheme {
    public static final int BG = 0xFFFAF9F6;
    public static final int SURFACE = 0xFFFFFFFF;
    public static final int LILAC_SOFT = 0xFFF3EFFB;
    public static final int LILAC = 0xFFEAE2F8;
    public static final int PURPLE = 0xFF7E69BD;
    public static final int PURPLE_DARK = 0xFF5D489E;
    public static final int GOLD = 0xFFE5AD48;
    public static final int GOLD_BG = 0xFFFFF5E0;
    public static final int MINT_BG = 0xFFECF5EE;
    public static final int MINT_INK = 0xFF3E7858;
    public static final int ROSE_BG = 0xFFFDF0EE;
    public static final int ROSE_INK = 0xFFB55D53;
    public static final int SKY_BG = 0xFFE7F2FA;
    public static final int SKY_INK = 0xFF42708B;
    public static final int INK = 0xFF302B41;
    public static final int MUTED = 0xFF898396;
    public static final int LINE = 0xFFEBE7EF;

    public static final float R_HERO = 26f;
    public static final float R_CARD = 20f;
    public static final float R_BTN = 16f;
    public static final float R_PILL = 999f;

    private StarTheme() {}

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static GradientDrawable rounded(int color, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable border(int color, int stroke, float radiusDp, Context c) {
        GradientDrawable d = rounded(color, radiusDp, c);
        d.setStroke(dp(c, 1), stroke);
        return d;
    }

    public static GradientDrawable gradient(int start, int end, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{start, end});
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static Drawable ripple(Drawable base, Context c) {
        return new RippleDrawable(ColorStateList.valueOf(0x227E69BD), base, null);
    }

    public static TextView text(Context c, String content, float sp, int color, boolean medium) {
        TextView t = new TextView(c);
        t.setText(content);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        if (medium) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static void pad(View v, int l, int t, int r, int b) {
        Context c = v.getContext();
        v.setPadding(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
    }

    public static void gap(LinearLayout l, int heightDp) {
        l.addView(new View(l.getContext()), new LinearLayout.LayoutParams(1, dp(l.getContext(), heightDp)));
    }

    public static void hGap(LinearLayout l, int widthDp) {
        l.addView(new View(l.getContext()), new LinearLayout.LayoutParams(dp(l.getContext(), widthDp), 1));
    }

    public static TextView pillBadge(Context c, String label, int bg, int fg) {
        TextView t = text(c, label, 10, fg, true);
        pad(t, 9, 5, 9, 5);
        t.setBackground(rounded(bg, R_PILL, c));
        return t;
    }

    public static TextView primaryButton(Context c, String label, Runnable action) {
        TextView b = text(c, label, 14, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        pad(b, 16, 13, 16, 13);
        b.setBackground(ripple(gradient(0xFF9480D1, 0xFF7660B5, R_BTN, c), c));
        StarMotion.attachSpringPress(b, action);
        return b;
    }

    public static TextView secondaryButton(Context c, String label, Runnable action) {
        TextView b = text(c, label, 13, PURPLE, true);
        b.setGravity(Gravity.CENTER);
        pad(b, 14, 12, 14, 12);
        b.setBackground(ripple(rounded(LILAC_SOFT, R_BTN, c), c));
        StarMotion.attachSpringPress(b, action);
        return b;
    }
}
