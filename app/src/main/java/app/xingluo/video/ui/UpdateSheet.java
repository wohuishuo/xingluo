package app.xingluo.video.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.*;
import app.xingluo.video.DownloadService;
import app.xingluo.video.Preferences;
import app.xingluo.video.diag.UpdateChecker;
import java.util.concurrent.Executors;
import static app.xingluo.video.ui.StarTheme.*;

/** Accelerated GitHub Release update checker sheet with mirror fallback & release notes. */
public final class UpdateSheet {
    private UpdateSheet() {}

    public static void show(Context context, String currentVersion) {
        Handler main = new Handler(Looper.getMainLooper());
        StarSheet.show(context, "检查星落更新", "多通道极速探测 GitHub 最新版本（当前 v" + currentVersion + "）", true, b -> {
            LinearLayout body = b.body();
            LinearLayout card = column(context);
            card.setBackground(rounded(LILAC_SOFT, R_CARD, context));
            pad(card, 16, 18, 16, 18);
            card.setGravity(Gravity.CENTER);
            StarProgressBar bar = new StarProgressBar(context);
            bar.setProgressValue(-1);
            card.addView(bar, new LinearLayout.LayoutParams(-1, dp(context, 6)));
            gap(card, 12);
            card.addView(text(context, "正在并行连接 GitHub 官方接口与镜像通道…", 12, PURPLE_DARK, true));
            body.addView(card);

            Executors.newSingleThreadExecutor().execute(() -> {
                UpdateChecker.UpdateResult res = UpdateChecker.check(currentVersion);
                new Preferences(context).lastUpdateCheck(System.currentTimeMillis());
                main.post(() -> {
                    body.removeAllViews();
                    renderResult(context, body, b, res);
                });
            });
        });
    }

    private static void renderResult(Context c, LinearLayout body, StarSheet.Builder b, UpdateChecker.UpdateResult res) {
        if (!res.success) {
            LinearLayout err = column(c);
            err.setBackground(rounded(ROSE_BG, R_CARD, c));
            pad(err, 15, 14, 15, 14);
            err.addView(text(c, "暂未连上 GitHub 发布页", 14, ROSE_INK, true));
            gap(err, 6);
            err.addView(text(c, res.errorMessage, 11, INK, false));
            body.addView(err);
            gap(body, 12);
            LinearLayout row = row(c);
            row.addView(secondaryButton(c, "运行网络/VPN 诊断", () -> {
                b.dismiss();
                NetworkDoctorSheet.show(c, res.errorMessage, "", "https://github.com/wohuishuo/xingluo");
            }), new LinearLayout.LayoutParams(0, -2, 1));
            hGap(row, 8);
            row.addView(primaryButton(c, "浏览器打开 Releases", () -> {
                b.dismiss();
                c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/wohuishuo/xingluo/releases/latest")));
            }), new LinearLayout.LayoutParams(0, -2, 1));
            body.addView(row);
            return;
        }

        LinearLayout card = column(c);
        card.setBackground(rounded(res.updateAvailable ? GOLD_BG : MINT_BG, R_CARD, c));
        pad(card, 15, 14, 15, 14);
        LinearLayout top = row(c);
        top.addView(text(c, res.updateAvailable ? "发现新版本 v" + res.latestVersion + " ✦" : "已是最新版本 v" + res.currentVersion,
                15, res.updateAvailable ? 0xFF8C5D18 : MINT_INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(pillBadge(c, res.sourceLabel, LILAC, PURPLE_DARK));
        card.addView(top);
        if (!res.releaseName.isEmpty()) {
            gap(card, 6);
            card.addView(text(c, res.releaseName, 12, INK, true));
        }
        if (res.assetSizeBytes > 0) {
            gap(card, 4);
            card.addView(text(c, "安装包大小：" + DownloadService.bytes(res.assetSizeBytes)
                    + (res.fromCommunityMirror ? "（社区镜像加速）" : "（GitHub 直连）"), 11, MUTED, false));
        }
        if (!res.releaseNotes.isEmpty()) {
            gap(card, 8);
            String snippet = res.releaseNotes.length() > 380 ? res.releaseNotes.substring(0, 380) + "…" : res.releaseNotes;
            TextView notes = text(c, snippet, 11, INK, false);
            notes.setLineSpacing(dp(c, 3), 1f);
            card.addView(notes);
        }
        body.addView(card);
        gap(body, 12);

        if (!res.downloadUrl.isEmpty()) {
            body.addView(primaryButton(c, res.updateAvailable ? "下载新版 APK / 打开下载页" : "打开 GitHub Releases", () -> {
                b.dismiss();
                c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(res.downloadUrl)));
            }), new LinearLayout.LayoutParams(-1, -2));
        }
    }
}
