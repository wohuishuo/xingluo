package app.xingluo.video.diag;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import app.xingluo.video.LinkInput;
import app.xingluo.video.engine.HttpClient;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Parallel network connectivity doctor for CN vs. global reachability and failure explanation.
 */
public final class NetworkDoctor {
    private static final long PROBE_TIMEOUT_MS = 4000L;

    public enum Cause {
        OK,
        NO_INTERNET,
        NEED_VPN,
        VPN_BLOCKING_CN,
        PLATFORM_LOGIN_REQUIRED,
        RATE_LIMITED,
        LINK_EXPIRED,
        DRM_PROTECTED,
        STORAGE_ERROR,
        UNKNOWN
    }

    public enum Group { CN, GLOBAL }

    private record ProbeTarget(String site, String url, Group group) {}

    private static final List<ProbeTarget> TARGETS = List.of(
            new ProbeTarget("哔哩哔哩", "https://api.bilibili.com/x/web-interface/nav", Group.CN),
            new ProbeTarget("抖音", "https://www.douyin.com/", Group.CN),
            new ProbeTarget("百度", "https://www.baidu.com/", Group.CN),
            new ProbeTarget("GitHub", "https://github.com/robots.txt", Group.GLOBAL),
            new ProbeTarget("YouTube", "https://www.youtube.com/robots.txt", Group.GLOBAL),
            new ProbeTarget("Pixiv", "https://www.pixiv.net/favicon.ico", Group.GLOBAL),
            new ProbeTarget("PeerTube", "https://tube.tchncs.de/api/v1/config", Group.GLOBAL)
    );

    public static final class ProbeResult {
        public final String site;
        public final String url;
        public final Group group;
        public final boolean reachable;
        public final long latencyMs;
        public final int httpCode;
        public final String error;

        public ProbeResult(String site, String url, Group group, boolean reachable, long latencyMs, int httpCode, String error) {
            this.site = site == null ? "" : site;
            this.url = url == null ? "" : url;
            this.group = group == null ? Group.GLOBAL : group;
            this.reachable = reachable;
            this.latencyMs = Math.max(0L, latencyMs);
            this.httpCode = httpCode;
            this.error = error == null ? "" : error;
        }
    }

    public static final class NetworkSnapshot {
        public final boolean hasInternet;
        public final boolean vpnActive;
        public final boolean wifiActive;
        public final boolean cellularActive;

        public NetworkSnapshot(boolean hasInternet, boolean vpnActive, boolean wifiActive, boolean cellularActive) {
            this.hasInternet = hasInternet;
            this.vpnActive = vpnActive;
            this.wifiActive = wifiActive;
            this.cellularActive = cellularActive;
        }
    }

    public static final class DiagnosisReport {
        public final Cause cause;
        public final String headline;
        public final String explanation;
        public final String remediation;
        public final boolean vpnActive;
        public final boolean wifiActive;
        public final boolean cellularActive;
        public final int cnReachableCount;
        public final int globalReachableCount;
        public final List<ProbeResult> probes;

        public DiagnosisReport(
                Cause cause,
                String headline,
                String explanation,
                String remediation,
                boolean vpnActive,
                boolean wifiActive,
                boolean cellularActive,
                int cnReachableCount,
                int globalReachableCount,
                List<ProbeResult> probes) {
            this.cause = cause;
            this.headline = headline;
            this.explanation = explanation;
            this.remediation = remediation;
            this.vpnActive = vpnActive;
            this.wifiActive = wifiActive;
            this.cellularActive = cellularActive;
            this.cnReachableCount = cnReachableCount;
            this.globalReachableCount = globalReachableCount;
            this.probes = probes == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(probes));
        }
    }

    private NetworkDoctor() {}

    /** Runs full live connectivity probes and classifies the current network state. */
    public static DiagnosisReport diagnose(Context context) {
        return explainFailure(context, "", "", "");
    }

    /** Combines error details with live CN/global probes and ConnectivityManager state. */
    public static DiagnosisReport explainFailure(Context context, String errorMessage, String errorCode, String failedUrl) {
        NetworkSnapshot net = inspectNetwork(context);
        List<ProbeResult> probes = runProbes();
        int cnOk = 0;
        int globalOk = 0;
        for (ProbeResult p : probes) {
            if (p.reachable) {
                if (p.group == Group.CN) cnOk++;
                else globalOk++;
            }
        }
        return classify(net.hasInternet, net.vpnActive, net.wifiActive, net.cellularActive,
                cnOk, globalOk, probes, errorMessage, errorCode, failedUrl);
    }

    /** Pure classification overload for unit testing without live network calls. */
    public static DiagnosisReport classify(
            boolean hasInternet,
            boolean vpnActive,
            int cnReachableCount,
            int globalReachableCount,
            List<ProbeResult> probes,
            String errorMessage,
            String errorCode,
            String failedUrl) {
        return classify(hasInternet, vpnActive, false, false,
                cnReachableCount, globalReachableCount, probes, errorMessage, errorCode, failedUrl);
    }

    /** Full pure classification combining network capabilities, probe counts, and error context. */
    public static DiagnosisReport classify(
            boolean hasInternet,
            boolean vpnActive,
            boolean wifiActive,
            boolean cellularActive,
            int cnReachableCount,
            int globalReachableCount,
            List<ProbeResult> probes,
            String errorMessage,
            String errorCode,
            String failedUrl) {

        String msg = safeLower(errorMessage);
        String code = safeLower(errorCode);
        String combined = (code + " " + msg).trim();
        Group urlGroup = detectUrlGroup(failedUrl);
        boolean hasErrorContext = !combined.isEmpty();

        // 1. Local storage and non-network DRM failures take precedence regardless of probes.
        if (containsAny(combined, "enospc", "erofs", "mediastore", "documentfile", "storage", "permission denied",
                "存储", "空间不足", "写入失败", "保存失败", "目录不可写", "存储权限")) {
            return report(Cause.STORAGE_ERROR,
                    "本地存储写入失败",
                    "网络正常或已完成解析，但保存到相册/文件夹时被系统拒绝或空间不足。",
                    "请检查剩余存储空间，或前往「设置 → 保存位置」重新授权目标文件夹。",
                    vpnActive, wifiActive, cellularActive, cnReachableCount, globalReachableCount, probes);
        }

        if (containsAny(combined, "drm", "widevine", "fairplay", "playready", "encrypted", "cipher",
                "版权保护", "加密保护", "付费专享", "drm_protected")) {
            return report(Cause.DRM_PROTECTED,
                    "媒体受 DRM 版权加密保护",
                    "该内容使用了受保护的加密流，星落无法绕过平台 DRM 解密下载。",
                    "仅支持保存创作者公开可访问的非 DRM 媒体流。",
                    vpnActive, wifiActive, cellularActive, cnReachableCount, globalReachableCount, probes);
        }

        boolean transportFailure = isTransportError(combined);

        // 2. Hard network split / outage when diagnosing directly or encountering transport errors.
        if ((!hasInternet && cnReachableCount == 0 && globalReachableCount == 0)
                || (cnReachableCount == 0 && globalReachableCount == 0)) {
            return report(Cause.NO_INTERNET,
                    "当前无可用网络连接",
                    "国内与海外节点探测均失败，设备可能处于离线、飞行模式或被局域网阻断。",
                    "请检查 Wi-Fi 或移动数据开关，确认系统已授予星落联网权限后重试。",
                    vpnActive, wifiActive, cellularActive, cnReachableCount, globalReachableCount, probes);
        }

        // CN reachable, global unreachable: either NEED_VPN for global URL / general diagnosis.
        if (cnReachableCount > 0 && globalReachableCount == 0) {
            if (urlGroup == Group.GLOBAL || (!hasErrorContext && urlGroup == null) || transportFailure) {
                if (urlGroup != Group.CN) {
                    return report(Cause.NEED_VPN,
                            "海外站点不可达，需要代理 / VPN",
                            "国内站点（B站/抖音/百度）访问正常，但 GitHub / YouTube / Pixiv 等海外节点超时或无法连接。",
                            vpnActive
                                    ? "当前虽检测到 VPN 通道，但海外节点仍不通，请切换代理节点或检查规则分流。"
                                    : "请开启代理 / VPN 工具并确保海外域名走代理通道后再试。",
                            vpnActive, wifiActive, cellularActive, cnReachableCount, globalReachableCount, probes);
                }
            }
        }

        // Global reachable, CN unreachable: VPN/proxy is hijacking or blocking CN traffic.
        if (cnReachableCount == 0 && globalReachableCount > 0) {
            if (urlGroup == Group.CN || (!hasErrorContext && urlGroup == null) || transportFailure || vpnActive) {
                return report(Cause.VPN_BLOCKING_CN,
                        "VPN / 代理可能拦截了国内连接",
                        vpnActive
                                ? "检测到 VPN 处于开启状态，海外节点正常但国内站点均无法连通，常见于全局代理或海外节点被国内风控。"
                                : "海外站点可达而国内站点不可达，可能是路由器透明代理或 DNS 污染导致国内流量走偏。",
                        "请将代理切换为「绕过大陆 / Rule 规则模式」，或临时关闭 VPN 后重试国内平台。",
                        vpnActive, wifiActive, cellularActive, cnReachableCount, globalReachableCount, probes);
            }
        }

        // 3. Platform-level HTTP / anti-bot / auth errors when network itself is reachable.
        if (containsAny(combined, "429", "rate_limit", "rate limit", "too many requests",
                "过于频繁", "限流", "请求太快", "cooldown")) {
            return report(Cause.RATE_LIMITED,
                    "触发平台请求频率限制（429）",
                    "短时间内请求过多，平台暂时限制了当前 IP 或账号的解析接口。",
                    "请等待 1～3 分钟后再重试，或适当降低并发任务数。",
                    vpnActive, wifiActive, cellularActive, cnReachableCount, globalReachableCount, probes);
        }

        if (containsAny(combined, "401", "403", "412", "login", "cookie", "auth", "unauthorized", "forbidden",
                "登录", "风控", "网页识别", "验证", "captcha", "人机", "bili_jct", "sessdata")) {
            return report(Cause.PLATFORM_LOGIN_REQUIRED,
                    "需要平台登录 Cookie 或网页验证",
                    "当前网络链路正常，但平台返回了访问受限（如 403/412）或要求登录态/人机校验。",
                    "请点击「网页识别」在内置浏览器中登录对应平台账号或完成滑块验证后重试。",
                    vpnActive, wifiActive, cellularActive, cnReachableCount, globalReachableCount, probes);
        }

        if (containsAny(combined, "404", "410", "expired", "gone", "not found",
                "失效", "过期", "已删除", "下架", "不存在", "签名超时")) {
            return report(Cause.LINK_EXPIRED,
                    "链接已失效或内容已被删除",
                    "直链签名已过期，或原作者/平台已删除、隐藏该视频或图集。",
                    "请重新从原 App 分享最新链接后再解析；若为网盘分享请确认未过期。",
                    vpnActive, wifiActive, cellularActive, cnReachableCount, globalReachableCount, probes);
        }

        // 4. Clean network with no failure, vs. unclassified error with healthy network.
        if (!hasErrorContext) {
            return report(Cause.OK,
                    "网络状态良好",
                    String.format(Locale.CHINA, "国内节点 %d/3 可达，海外节点 %d/4 可达%s。",
                            cnReachableCount, globalReachableCount, vpnActive ? "（VPN 已开启）" : ""),
                    "当前网络可正常解析国内外主流平台与检查更新。",
                    vpnActive, wifiActive, cellularActive, cnReachableCount, globalReachableCount, probes);
        }

        String targetHint = urlGroup == Group.GLOBAL
                ? "海外站点"
                : (urlGroup == Group.CN ? "国内站点" : "目标平台");
        return report(Cause.UNKNOWN,
                "未定位到明确网络故障",
                "基础探测均正常（" + targetHint + "通道可达），但本次解析仍报错：" + (errorMessage == null || errorMessage.isBlank() ? errorCode : errorMessage),
                "建议复制完整链接再试一次，或使用「网页识别」直接加载原页面。",
                vpnActive, wifiActive, cellularActive, cnReachableCount, globalReachableCount, probes);
    }

    /** Inspects active and auxiliary Android NetworkCapabilities safely. */
    public static NetworkSnapshot inspectNetwork(Context context) {
        if (context == null) return new NetworkSnapshot(true, false, false, false);
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getApplicationContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return new NetworkSnapshot(true, false, false, false);

            boolean hasInternet = false;
            boolean vpn = false;
            boolean wifi = false;
            boolean cellular = false;

            Network active = cm.getActiveNetwork();
            if (active != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                if (caps != null) {
                    hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                    vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
                    wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
                    cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
                }
            }

            // Check secondary networks too because split-tunnel VPNs may appear outside activeNetwork.
            Network[] all = cm.getAllNetworks();
            if (all != null) {
                for (Network n : all) {
                    NetworkCapabilities c = cm.getNetworkCapabilities(n);
                    if (c == null) continue;
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpn = true;
                    if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        hasInternet = true;
                        if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = true;
                        if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) cellular = true;
                    }
                }
            }
            return new NetworkSnapshot(hasInternet, vpn, wifi, cellular);
        } catch (Throwable ignored) {
            return new NetworkSnapshot(true, false, false, false);
        }
    }

    private static List<ProbeResult> runProbes() {
        OkHttpClient probeClient = HttpClient.client().newBuilder()
                .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(4500L, TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .retryOnConnectionFailure(false)
                .build();

        ExecutorService pool = Executors.newFixedThreadPool(TARGETS.size());
        List<Future<ProbeResult>> futures = new ArrayList<>(TARGETS.size());
        try {
            for (ProbeTarget target : TARGETS) {
                futures.add(pool.submit((Callable<ProbeResult>) () -> probeOne(probeClient, target)));
            }
            List<ProbeResult> results = new ArrayList<>(TARGETS.size());
            for (int i = 0; i < TARGETS.size(); i++) {
                ProbeTarget t = TARGETS.get(i);
                try {
                    results.add(futures.get(i).get(5000L, TimeUnit.MILLISECONDS));
                } catch (Exception e) {
                    results.add(new ProbeResult(t.site, t.url, t.group, false, PROBE_TIMEOUT_MS, -1, "probe_timeout"));
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static ProbeResult probeOne(OkHttpClient client, ProbeTarget target) {
        long startNs = System.nanoTime();
        try {
            int code = executeProbe(client, "HEAD", target.url);
            if (code == 405 || code == 501) {
                code = executeProbe(client, "GET", target.url);
            }
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            boolean reachable = code >= 200 && code < 500;
            return new ProbeResult(target.site, target.url, target.group, reachable, ms, code,
                    reachable ? "" : ("HTTP " + code));
        } catch (IOException e) {
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            String msg = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            return new ProbeResult(target.site, target.url, target.group, false, ms, -1, msg);
        }
    }

    private static int executeProbe(OkHttpClient client, String method, String url) throws IOException {
        Request.Builder rb = new Request.Builder()
                .url(url)
                .header("User-Agent", HttpClient.DESKTOP_UA)
                .header("Accept", "*/*");
        if ("HEAD".equals(method)) {
            rb.head();
        } else {
            rb.get().header("Range", "bytes=0-63");
        }
        try (Response resp = client.newCall(rb.build()).execute()) {
            return resp.code();
        }
    }

    private static Group detectUrlGroup(String url) {
        if (url == null || url.isBlank()) return null;
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("github.com") || lower.contains("githubusercontent.com") || lower.contains("ghproxy")) {
            return Group.GLOBAL;
        }
        if (!LinkInput.isWebUrl(url)) return null;
        LinkInput.Platform p = LinkInput.platform(url);
        return switch (p) {
            case BILIBILI, DOUYIN, KUAISHOU, XIAOHONGSHU, WEIBO, ACFUN, QUARK, BAIDU_PAN, LANZOU, CHANNELS -> Group.CN;
            case YOUTUBE, TWITTER, TIKTOK, REDDIT, PIXIV, VIMEO, PEERTUBE -> Group.GLOBAL;
            default -> null;
        };
    }

    private static boolean isTransportError(String combined) {
        return containsAny(combined,
                "timeout", "timed out", "unknownhost", "connectexception", "socket",
                "sslexception", "handshake", "econnrefused", "enetunreach", "ehostunreach",
                "reset by peer", "broken pipe", "eof", "超时", "连接失败", "无法连接", "dns", "网络不可达");
    }

    private static boolean containsAny(String haystack, String... needles) {
        if (haystack == null || haystack.isEmpty()) return false;
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }

    private static String safeLower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static DiagnosisReport report(
            Cause cause,
            String headline,
            String explanation,
            String remediation,
            boolean vpnActive,
            boolean wifiActive,
            boolean cellularActive,
            int cnReachable,
            int globalReachable,
            List<ProbeResult> probes) {
        return new DiagnosisReport(cause, headline, explanation, remediation,
                vpnActive, wifiActive, cellularActive, cnReachable, globalReachable, probes);
    }
}
