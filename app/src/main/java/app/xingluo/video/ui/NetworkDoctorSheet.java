package app.xingluo.video.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.*;
import app.xingluo.video.BrowserActivity;
import app.xingluo.video.LinkInput;
import app.xingluo.video.diag.NetworkDoctor;
import java.util.concurrent.Executors;
import static app.xingluo.video.ui.StarTheme.*;

/** Interactive Network & VPN Doctor bottom sheet explaining failure causes with live CN/Global latency probes. */
public final class NetworkDoctorSheet {
    private NetworkDoctorSheet() {}

    public static void show(Context context, String errorMessage, String errorCode, String failedUrl) {
        Handler main = new Handler(Looper.getMainLooper());
        StarSheet.show(context, "网络与 VPN 诊断", "并行检测国内与海外节点连通性（约 2 秒）", true, b -> {
            LinearLayout body = b.body();

            LinearLayout loadingCard = column(context);
            loadingCard.setBackground(rounded(LILAC_SOFT, R_CARD, context));
            pad(loadingCard, 16, 18, 16, 18);
            loadingCard.setGravity(Gravity.CENTER);
            StarProgressBar bar = new StarProgressBar(context);
            bar.setProgressValue(-1);
            loadingCard.addView(bar, new LinearLayout.LayoutParams(-1, dp(context, 6)));
            gap(loadingCard, 12);
            loadingCard.addView(text(context, "小星正在为你挨个敲门检测国内与海外网站…", 12, PURPLE_DARK, true));
            body.addView(loadingCard);

            Executors.newSingleThreadExecutor().execute(() -> {
                NetworkDoctor.DiagnosisReport report = (errorMessage == null || errorMessage.isBlank())
                        ? NetworkDoctor.diagnose(context)
                        : NetworkDoctor.explainFailure(context, errorMessage, errorCode, failedUrl);
                main.post(() -> {
                    body.removeAllViews();
                    renderReport(context, body, b, report, failedUrl);
                    StarMotion.cascadeEnter(body, 0);
                });
            });
        });
    }

    private static void renderReport(Context c, LinearLayout body, StarSheet.Builder b,
                                     NetworkDoctor.DiagnosisReport report, String failedUrl) {
        boolean healthy = report.cause == NetworkDoctor.Cause.OK;
        int cardBg = healthy ? MINT_BG : (report.cause == NetworkDoctor.Cause.NEED_VPN || report.cause == NetworkDoctor.Cause.VPN_BLOCKING_CN ? GOLD_BG : ROSE_BG);
        int cardInk = healthy ? MINT_INK : (report.cause == NetworkDoctor.Cause.NEED_VPN || report.cause == NetworkDoctor.Cause.VPN_BLOCKING_CN ? 0xFF8C5D18 : ROSE_INK);

        LinearLayout verdict = column(c);
        verdict.setBackground(rounded(cardBg, R_CARD, c));
        pad(verdict, 15, 14, 15, 14);
        LinearLayout topRow = row(c);
        topRow.addView(text(c, report.headline, 15, cardInk, true), new LinearLayout.LayoutParams(0, -2, 1));
        if (report.vpnActive) {
            topRow.addView(pillBadge(c, "VPN 已开启", LILAC, PURPLE_DARK));
        } else {
            topRow.addView(pillBadge(c, report.wifiActive ? "Wi-Fi" : report.cellularActive ? "移动网络" : "离线", SURFACE, MUTED));
        }
        verdict.addView(topRow);
        gap(verdict, 8);
        TextView exp = text(c, report.explanation, 12, INK, false);
        exp.setLineSpacing(dp(c, 4), 1f);
        verdict.addView(exp);
        if (report.remediation != null && !report.remediation.isBlank()) {
            gap(verdict, 8);
            TextView rem = text(c, "✦ 建议：" + report.remediation, 11, cardInk, true);
            rem.setLineSpacing(dp(c, 3), 1f);
            verdict.addView(rem);
        }
        body.addView(verdict);
        gap(body, 12);

        body.addView(text(c, "实时连通性与延迟明细（国内 " + report.cnReachableCount + " · 海外 " + report.globalReachableCount + "）", 11, MUTED, true));
        gap(body, 8);

        for (NetworkDoctor.ProbeResult p : report.probes) {
            LinearLayout r = row(c);
            r.setBackground(border(SURFACE, LINE, 14, c));
            pad(r, 12, 10, 12, 10);
            r.addView(pillBadge(c, p.group == NetworkDoctor.Group.CN ? "国内" : "海外",
                    p.group == NetworkDoctor.Group.CN ? SKY_BG : LILAC_SOFT,
                    p.group == NetworkDoctor.Group.CN ? SKY_INK : PURPLE_DARK));
            hGap(r, 8);
            r.addView(text(c, p.site, 12, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
            String statusText = p.reachable
                    ? (p.latencyMs + " ms")
                    : (p.httpCode > 0 ? "HTTP " + p.httpCode : "超时/阻断");
            r.addView(text(c, p.reachable ? "✓ " + statusText : "✕ " + statusText, 11, p.reachable ? MINT_INK : ROSE_INK, true));
            body.addView(r);
            gap(body, 6);
        }

        gap(body, 6);
        LinearLayout actions = row(c);
        if (failedUrl != null && LinkInput.isWebUrl(failedUrl)) {
            TextView openWeb = secondaryButton(c, "打开网页识别/登录", () -> {
                b.dismiss();
                c.startActivity(new Intent(c, BrowserActivity.class).putExtra("url", failedUrl));
            });
            actions.addView(openWeb, new LinearLayout.LayoutParams(0, -2, 1));
            hGap(actions, 8);
        }
        TextView sysNet = primaryButton(c, "打开系统网络/VPN设置", () -> {
            b.dismiss();
            try {
                c.startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));
            } catch (Exception e) {
                try { c.startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); } catch (Exception ignored) {}
            }
        });
        actions.addView(sysNet, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(actions);
    }
}
