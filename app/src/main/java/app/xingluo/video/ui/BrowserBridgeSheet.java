package app.xingluo.video.ui;

import android.content.*;
import android.net.Uri;
import android.widget.*;
import static app.xingluo.video.ui.StarTheme.*;

/** Interactive guide & quick bridge launcher for Mobile Browser Extensions & Userscripts. */
public final class BrowserBridgeSheet {
    private BrowserBridgeSheet() {}

    public static void show(Context context, Runnable onToast) {
        StarSheet.show(context, "浏览器插件联动", "把任意手机浏览器的视频与图集一键送进星落", true, b -> {
            LinearLayout body = b.body();

            LinearLayout hero = column(context);
            hero.setBackground(gradient(0xFFECE6F7, 0xFFF5F0FA, R_CARD, context));
            pad(hero, 15, 14, 15, 14);
            hero.addView(text(context, "支持 Firefox Android / Kiwi / 狐猴 / Via / X浏览器 / Edge", 12, PURPLE_DARK, true));
            gap(hero, 6);
            TextView tip = text(context, "在仓库 Releases 中下载 Xingluo-2.0.0-browser-extension.zip（含 MV3 扩展 + 通用油猴脚本 xingluo-bridge.user.js）。\n\n另外，在任何浏览器中长按选中网址 → 选择「用星落下载」也可直接触发！", 11, INK, false);
            tip.setLineSpacing(dp(context, 4), 1f);
            hero.addView(tip);
            body.addView(hero);
            gap(body, 12);

            addStep(body, context, "方式一：Via / X浏览器 / 油猴脚本（最轻便）",
                    "导入 xingluo-bridge.user.js 后，访问 B站、抖音、小红书、快手、Pixiv、夸克、百度网盘、YouTube 时右下角会出现「✦ 星落」悬浮球，点一下直达下载。");
            addStep(body, context, "方式二：Firefox Android / Kiwi / 狐猴（MV3 扩展）",
                    "加载 browser-extension 扩展包，点击扩展图标即可自动扫描当前页视频流、Pixiv 画师作品或网盘分享，一键唤醒 xingluo://download。");
            addStep(body, context, "方式三：系统选中文本直传（免装插件）",
                    "星落已注册 PROCESS_TEXT 菜单。在系统任意浏览器地址栏或网页长按选中链接，点「用星落下载」即可。");

            gap(body, 6);
            LinearLayout row = row(context);
            TextView copyUri = secondaryButton(context, "测试 xingluo:// 协议", () -> {
                b.dismiss();
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("xingluo://download?url=https://b23.tv/y7xiWGg"));
                i.setPackage(context.getPackageName());
                context.startActivity(i);
            });
            row.addView(copyUri, new LinearLayout.LayoutParams(0, -2, 1));
            hGap(row, 10);
            TextView openGh = primaryButton(context, "去 Releases 下载插件包", () -> {
                b.dismiss();
                context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/wohuishuo/xingluo/releases/latest")));
            });
            row.addView(openGh, new LinearLayout.LayoutParams(0, -2, 1));
            body.addView(row);
        });
    }

    private static void addStep(LinearLayout body, Context c, String title, String desc) {
        LinearLayout card = column(c);
        card.setBackground(border(SURFACE, LINE, R_CARD, c));
        pad(card, 14, 12, 14, 12);
        card.addView(text(c, title, 12, INK, true));
        gap(card, 5);
        TextView d = text(c, desc, 11, MUTED, false);
        d.setLineSpacing(dp(c, 3), 1f);
        card.addView(d);
        body.addView(card);
        gap(body, 9);
    }
}
