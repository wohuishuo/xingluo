package app.xingluo.video.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;
import static app.xingluo.video.ui.StarTheme.*;

/** Unified rounded cream/lavender Bottom Sheet dialog with optional peeking XiaoXing mascot. */
public final class StarSheet {
    private final Dialog dialog;
    private final LinearLayout content;
    private final LinearLayout sheetBox;

    private StarSheet(Context context, String title, String subtitle, boolean showMascot) {
        dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout backdrop = new FrameLayout(context);
        backdrop.setBackgroundColor(Color.TRANSPARENT);
        backdrop.setOnClickListener(v -> dialog.dismiss());

        FrameLayout wrapper = new FrameLayout(context);
        wrapper.setClipChildren(false);
        wrapper.setClipToPadding(false);

        sheetBox = column(context);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        float r = dp(context, 26);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheetBox.setBackground(bg);
        pad(sheetBox, 20, 12, 20, 22);
        sheetBox.setOnClickListener(v -> {}); // Consume clicks inside sheet

        View handle = new View(context);
        handle.setBackground(rounded(0xFFD9D1E8, R_PILL, context));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(context, 42), dp(context, 4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        sheetBox.addView(handle, hp);
        gap(sheetBox, 14);

        LinearLayout header = row(context);
        LinearLayout titles = column(context);
        titles.addView(text(context, title, 18, INK, true));
        if (subtitle != null && !subtitle.isEmpty()) {
            gap(titles, 4);
            titles.addView(text(context, subtitle, 11, MUTED, false));
        }
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        TextView closeBtn = text(context, "✕", 15, MUTED, true);
        pad(closeBtn, 10, 6, 6, 6);
        StarMotion.attachSpringPress(closeBtn, dialog::dismiss);
        header.addView(closeBtn);
        sheetBox.addView(header);
        gap(sheetBox, 14);

        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        content = column(context);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        sheetBox.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1));

        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        sp.topMargin = showMascot ? dp(context, 38) : 0;
        wrapper.addView(sheetBox, sp);

        if (showMascot) {
            XiaoXingCompanionView peek = new XiaoXingCompanionView(context, XiaoXingCompanionView.Mode.PEEK);
            FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(dp(context, 78), dp(context, 78), Gravity.TOP | Gravity.END);
            mp.rightMargin = dp(context, 28);
            wrapper.addView(peek, mp);
        }

        FrameLayout.LayoutParams wp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        backdrop.addView(wrapper, wp);
        dialog.setContentView(backdrop);

        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            win.setGravity(Gravity.BOTTOM);
            win.setDimAmount(0.38f);
        }
        if (StarMotion.enabled(context)) {
            wrapper.setTranslationY(dp(context, 140));
            wrapper.setAlpha(0f);
            wrapper.animate().translationY(0).alpha(1f).setDuration(240).setInterpolator(new DecelerateInterpolator(1.6f)).start();
        }
    }

    public static StarSheet show(Context context, String title, String subtitle, boolean showMascot, java.util.function.Consumer<Builder> builderFn) {
        StarSheet sheet = new StarSheet(context, title, subtitle, showMascot);
        Builder b = new Builder(sheet);
        builderFn.accept(b);
        sheet.dialog.show();
        return sheet;
    }

    public void dismiss() { dialog.dismiss(); }

    public static final class Builder {
        private final StarSheet sheet;
        private Builder(StarSheet sheet) { this.sheet = sheet; }
        public Context context() { return sheet.dialog.getContext(); }
        public LinearLayout body() { return sheet.content; }
        public void dismiss() { sheet.dismiss(); }

        public void option(String title, String desc, boolean selected, Runnable onSelect) {
            Context c = context();
            LinearLayout row = row(c);
            pad(row, 14, 13, 14, 13);
            row.setBackground(ripple(border(selected ? LILAC_SOFT : SURFACE, selected ? PURPLE : LINE, R_CARD, c), c));
            LinearLayout words = column(c);
            words.addView(text(c, title, 14, selected ? PURPLE_DARK : INK, true));
            if (desc != null && !desc.isEmpty()) {
                gap(words, 3);
                words.addView(text(c, desc, 11, MUTED, false));
            }
            row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            if (selected) row.addView(pillBadge(c, "当前", LILAC, PURPLE_DARK));
            StarMotion.attachSpringPress(row, () -> {
                sheet.dismiss();
                if (onSelect != null) onSelect.run();
            });
            sheet.content.addView(row);
            gap(sheet.content, 8);
        }
    }
}
