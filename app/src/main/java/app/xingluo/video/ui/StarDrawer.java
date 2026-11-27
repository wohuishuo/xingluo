package app.xingluo.video.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;
import app.xingluo.video.Preferences;
import app.xingluo.video.Ui;
import static app.xingluo.video.ui.StarTheme.*;

/** Smooth slide-out navigation drawer matching Xingluo's pastel cream/lilac design system. */
public final class StarDrawer {
    public enum Action {
        ACCOUNTS, DOCTOR, BROWSER_BRIDGE, ENGINE, TORRENT_INPUT, UPDATE, GITHUB
    }

    private final FrameLayout overlay;
    private final LinearLayout panel;
    private final XiaoXingCompanionView peekMascot;
    private final TextView statsSubtitle;
    private boolean open = false;

    public StarDrawer(Context c, java.util.function.Consumer<Action> onAction) {
        overlay = new FrameLayout(c);
        overlay.setBackgroundColor(0x55241D35);
        overlay.setVisibility(View.GONE);
        overlay.setAlpha(0f);
        overlay.setOnClickListener(v -> close());

        panel = column(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        float r = dp(c, 26);
        bg.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
        panel.setBackground(bg);
        panel.setOnClickListener(v -> {});

        ScrollView scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = column(c);
        pad(content, 18, 28, 18, 24);

        // Mascot hero header inside drawer
        LinearLayout hero = row(c);
        hero.setBackground(gradient(0xFFECE6F7, 0xFFF4EFF6, R_CARD, c));
        pad(hero, 14, 14, 10, 14);
        LinearLayout heroWords = column(c);
        heroWords.addView(text(c, "星落工具箱", 18, INK, true));
        gap(heroWords, 4);
        statsSubtitle = text(c, "v2.0.0 · 4线程加速引擎", 11, PURPLE_DARK, false);
        heroWords.addView(statsSubtitle);
        gap(heroWords, 8);
        heroWords.addView(pillBadge(c, "Android 9+ 原生纯Java", SURFACE, PURPLE));
        hero.addView(heroWords, new LinearLayout.LayoutParams(0, -2, 1));
        peekMascot = new XiaoXingCompanionView(c, XiaoXingCompanionView.Mode.PEEK);
        hero.addView(peekMascot, new LinearLayout.LayoutParams(dp(c, 74), dp(c, 74)));
        content.addView(hero);
        gap(content, 16);

        item(content, c, "账号通行证中心", "B站 / 抖音 / 小红书 / Pixiv / 网盘登录", "globe", () -> { close(); onAction.accept(Action.ACCOUNTS); });
        item(content, c, "网络与 VPN 诊断", "2秒并行检测国内外节点与失败原因", "pulse", () -> { close(); onAction.accept(Action.DOCTOR); });
        item(content, c, "多引擎与并发控制台", "1-5任务并发 · 8线程分段 · HEVC编码", "bolt", () -> { close(); onAction.accept(Action.ENGINE); });
        item(content, c, "浏览器插件联动", "Firefox / Kiwi / Via / 油猴脚本一键传链", "share", () -> { close(); onAction.accept(Action.BROWSER_BRIDGE); });
        item(content, c, "磁力链接 / .torrent 导入", "WebSeed 加速与本地解析", "download", () -> { close(); onAction.accept(Action.TORRENT_INPUT); });
        item(content, c, "加速检查 GitHub 更新", "官方 API + 跳转 + 镜像多通道检查", "star", () -> { close(); onAction.accept(Action.UPDATE); });
        item(content, c, "开源仓库与点 Star ☆", "github.com/wohuishuo/xingluo (MIT)", "link", () -> { close(); onAction.accept(Action.GITHUB); });

        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -1));

        int width = Math.min(dp(c, 308), (int) (c.getResources().getDisplayMetrics().widthPixels * 0.82f));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START);
        overlay.addView(panel, lp);
        panel.setTranslationX(-width);
    }

    public FrameLayout view() { return overlay; }
    public boolean isOpen() { return open; }

    public void open(Context c) {
        Preferences p = new Preferences(c);
        statsSubtitle.setText("v2.0.0 · " + p.threadsPerTask() + "线程 / " + p.maxConcurrentTasks() + "并发 · 好感 " + p.mascotAffection());
        open = true;
        overlay.setVisibility(View.VISIBLE);
        if (StarMotion.enabled(c)) {
            overlay.animate().alpha(1f).setDuration(220).start();
            panel.animate().translationX(0f).setDuration(250).setInterpolator(new DecelerateInterpolator(1.6f)).start();
        } else {
            overlay.setAlpha(1f);
            panel.setTranslationX(0f);
        }
    }

    public void close() {
        if (!open) return;
        open = false;
        int width = panel.getWidth() > 0 ? panel.getWidth() : dp(overlay.getContext(), 308);
        if (StarMotion.enabled(overlay.getContext())) {
            overlay.animate().alpha(0f).setDuration(180).withEndAction(() -> overlay.setVisibility(View.GONE)).start();
            panel.animate().translationX(-width).setDuration(200).start();
        } else {
            overlay.setVisibility(View.GONE);
            panel.setTranslationX(-width);
        }
    }

    private void item(LinearLayout parent, Context c, String title, String desc, String iconName, Runnable action) {
        LinearLayout card = row(c);
        card.setBackground(ripple(border(SURFACE, LINE, 16, c), c));
        pad(card, 12, 12, 10, 12);
        Ui.Icon icon = new Ui.Icon(c, iconName, PURPLE);
        icon.setBackground(rounded(LILAC_SOFT, 12, c));
        pad(icon, 8, 8, 8, 8);
        card.addView(icon, new LinearLayout.LayoutParams(dp(c, 38), dp(c, 38)));
        LinearLayout words = column(c);
        pad(words, 11, 0, 0, 0);
        words.addView(text(c, title, 13, INK, true));
        gap(words, 3);
        words.addView(text(c, desc, 10, MUTED, false));
        card.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(new Ui.Icon(c, "chevron", 0xFFB3A9C2), new LinearLayout.LayoutParams(dp(c, 15), dp(c, 15)));
        StarMotion.attachSpringPress(card, action);
        parent.addView(card);
        gap(parent, 8);
    }
}
