package app.xingluo.video.ui;

import android.content.Context;
import android.content.Intent;
import android.widget.*;
import app.xingluo.video.BrowserActivity;
import app.xingluo.video.auth.AccountManager;
import static app.xingluo.video.ui.StarTheme.*;

/** Platform Account Center bottom sheet displaying login state and launching fixed WebView login mode. */
public final class AccountCenterSheet {
    private AccountCenterSheet() {}

    public static void show(Context context, Runnable onChanged) {
        StarSheet.show(context, "账号通行证中心", "登录状态仅保存在手机本地 Cookie，用于高清、图文、Pixiv 及网盘加速", true, b -> {
            LinearLayout body = b.body();
            for (AccountManager.Platform p : AccountManager.Platform.values()) {
                AccountManager.AccountStatus st = AccountManager.status(p);
                LinearLayout card = row(context);
                card.setBackground(border(SURFACE, st.loggedIn() ? PURPLE : LINE, R_CARD, context));
                pad(card, 14, 12, 12, 12);

                LinearLayout info = column(context);
                info.addView(text(context, p.displayName(), 14, INK, true));
                gap(info, 4);
                info.addView(text(context, st.loggedIn() ? "✓ " + st.summaryLabel() : "未登录 · 点击登录", 11, st.loggedIn() ? MINT_INK : MUTED, st.loggedIn()));
                card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

                if (st.loggedIn()) {
                    TextView logout = pillBadge(context, "退出", ROSE_BG, ROSE_INK);
                    StarMotion.attachSpringPress(logout, () -> {
                        AccountManager.clearPlatformCookies(p);
                        b.dismiss();
                        if (onChanged != null) onChanged.run();
                        Toast.makeText(context, "已清除 " + p.displayName() + " 登录态", Toast.LENGTH_SHORT).show();
                    });
                    card.addView(logout);
                    hGap(card, 6);
                }

                TextView go = pillBadge(context, st.loggedIn() ? "去管理" : "去登录", LILAC, PURPLE_DARK);
                StarMotion.attachSpringPress(go, () -> {
                    b.dismiss();
                    context.startActivity(new Intent(context, BrowserActivity.class)
                            .putExtra("url", p.loginUrl())
                            .putExtra("loginMode", true));
                });
                card.addView(go);
                body.addView(card);
                gap(body, 8);
            }

            gap(body, 4);
            TextView clearAll = secondaryButton(context, "清空全部平台 Cookie", () -> {
                AccountManager.clearAllCookies();
                b.dismiss();
                if (onChanged != null) onChanged.run();
                Toast.makeText(context, "已退出全部内置网页登录", Toast.LENGTH_SHORT).show();
            });
            body.addView(clearAll, new LinearLayout.LayoutParams(-1, -2));
        });
    }
}
