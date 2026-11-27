package app.xingluo.video.auth;

import android.webkit.CookieManager;
import app.xingluo.video.LinkInput;
import java.net.URI;
import java.util.*;

/** Manages platform login states and WebView cookie lifecycle without ever exposing raw cookie values. */
public final class AccountManager {
    public enum Platform {
        BILIBILI(
                "哔哩哔哩",
                "https://passport.bilibili.com/login",
                List.of("bilibili.com", "www.bilibili.com", "passport.bilibili.com", "api.bilibili.com", "m.bilibili.com", "live.bilibili.com"),
                List.of("SESSDATA", "DedeUserID")
        ),
        DOUYIN(
                "抖音",
                "https://www.douyin.com/",
                List.of("douyin.com", "www.douyin.com", "login.douyin.com", "sso.douyin.com", "www.iesdouyin.com"),
                List.of("sessionid", "sessionid_ss", "sid_tt", "uid_tt", "ttwid")
        ),
        XIAOHONGSHU(
                "小红书",
                "https://www.xiaohongshu.com/",
                List.of("xiaohongshu.com", "www.xiaohongshu.com", "edith.xiaohongshu.com", "passport.xiaohongshu.com"),
                List.of("web_session", "customer-sso-sid", "galaxy.creator.beaker.session.id")
        ),
        KUAISHOU(
                "快手",
                "https://www.kuaishou.com/",
                List.of("kuaishou.com", "www.kuaishou.com", "id.kuaishou.com", "passport.kuaishou.com"),
                List.of("kuaishou.server.web_st", "passToken", "userId", "kuaishou.server.web_ph")
        ),
        PIXIV(
                "Pixiv",
                "https://accounts.pixiv.net/login",
                List.of("pixiv.net", "www.pixiv.net", "accounts.pixiv.net"),
                List.of("PHPSESSID")
        ),
        QUARK(
                "夸克网盘",
                "https://pan.quark.cn/",
                List.of("pan.quark.cn", "drive-pc.quark.cn", "uop.quark.cn", "quark.cn"),
                List.of("__pus", "__puus")
        ),
        BAIDU(
                "百度网盘",
                "https://pan.baidu.com/",
                List.of("pan.baidu.com", "yun.baidu.com", "passport.baidu.com", "baidu.com"),
                List.of("BDUSS", "BDUSS_BFESS", "STOKEN")
        ),
        YOUTUBE(
                "YouTube",
                "https://accounts.google.com/ServiceLogin?service=youtube",
                List.of("youtube.com", "www.youtube.com", "m.youtube.com", "accounts.google.com", "google.com"),
                List.of("SID", "HSID", "SSID", "SAPISID", "__Secure-1PSID", "__Secure-3PSID", "LOGIN_INFO")
        );

        public final String displayName;
        public final String loginUrl;
        public final List<String> domains;
        public final List<String> authCookieKeys;

        Platform(String displayName, String loginUrl, List<String> domains, List<String> authCookieKeys) {
            this.displayName = displayName;
            this.loginUrl = loginUrl;
            this.domains = List.copyOf(domains);
            this.authCookieKeys = List.copyOf(authCookieKeys);
        }

        public String displayName() { return displayName; }
        public String loginUrl() { return loginUrl; }
        public List<String> domains() { return domains; }
        public List<String> authCookieKeys() { return authCookieKeys; }
    }

    public record AccountStatus(boolean loggedIn, String summaryLabel) {}

    private AccountManager() {}

    /** Inspects WebView CookieManager for auth indicators without storing or returning cookie values. */
    public static AccountStatus status(Platform platform) {
        if (platform == null) return new AccountStatus(false, "未登录");
        try {
            CookieManager cm = CookieManager.getInstance();
            if (cm == null) return new AccountStatus(false, "未登录");
            for (String domain : platform.domains) {
                String cookieHeader = cm.getCookie("https://" + domain + "/");
                if (hasAuthCookie(cookieHeader, platform)) {
                    return new AccountStatus(true, "已登录");
                }
            }
        } catch (Throwable ignored) {}
        return new AccountStatus(false, "未登录");
    }

    /** Clears cookies belonging to all domains of the given platform. */
    public static void clearPlatformCookies(Platform platform) {
        if (platform == null) return;
        try {
            CookieManager cm = CookieManager.getInstance();
            if (cm == null) return;
            for (String domain : platform.domains) {
                String url = "https://" + domain + "/";
                String existing = cm.getCookie(url);
                if (existing != null && !existing.isBlank()) {
                    String root = rootDomain(domain);
                    for (String part : existing.split(";")) {
                        int eq = part.indexOf('=');
                        if (eq <= 0) continue;
                        String name = part.substring(0, eq).trim();
                        if (name.isEmpty()) continue;
                        cm.setCookie(url, name + "=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/");
                        cm.setCookie(url, name + "=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Domain=" + domain);
                        if (!root.equals(domain)) {
                            cm.setCookie(url, name + "=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Domain=." + root);
                        }
                    }
                }
            }
            cm.flush();
        } catch (Throwable ignored) {}
    }

    /** Clears all cookies stored by WebView CookieManager. */
    public static void clearAllCookies() {
        try {
            CookieManager cm = CookieManager.getInstance();
            if (cm == null) return;
            cm.removeAllCookies(ignored -> cm.flush());
            cm.removeSessionCookies(null);
            cm.flush();
        } catch (Throwable ignored) {}
    }

    /** Pure-Java check that returns true if cookieHeader contains a non-empty auth indicator for platform. */
    public static boolean hasAuthCookie(String cookieHeader, Platform platform) {
        if (cookieHeader == null || cookieHeader.isBlank() || platform == null) return false;
        String[] parts = cookieHeader.split(";");
        for (String rawPart : parts) {
            String part = rawPart.trim();
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String name = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if (value.isEmpty() || "\"\"".equals(value)
                    || "deleted".equalsIgnoreCase(value)
                    || "null".equalsIgnoreCase(value)
                    || "undefined".equalsIgnoreCase(value)) {
                continue;
            }
            for (String key : platform.authCookieKeys) {
                if (key.equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Maps a web URL to its corresponding AccountManager.Platform, or null if unsupported. */
    public static Platform matchPlatform(String url) {
        if (!LinkInput.isWebUrl(url)) return null;
        try {
            String host = URI.create(url).getHost();
            if (host == null) return null;
            String h = host.toLowerCase(Locale.ROOT);
            if (LinkInput.hostIs(h, "bilibili.com") || LinkInput.hostIs(h, "b23.tv")) return Platform.BILIBILI;
            if (LinkInput.hostIs(h, "douyin.com") || LinkInput.hostIs(h, "iesdouyin.com")) return Platform.DOUYIN;
            if (LinkInput.hostIs(h, "xiaohongshu.com") || LinkInput.hostIs(h, "xhslink.com")) return Platform.XIAOHONGSHU;
            if (LinkInput.hostIs(h, "kuaishou.com") || LinkInput.hostIs(h, "chenzhongtech.com")) return Platform.KUAISHOU;
            if (LinkInput.hostIs(h, "pixiv.net") || LinkInput.hostIs(h, "pximg.net")) return Platform.PIXIV;
            if (LinkInput.hostIs(h, "quark.cn")) return Platform.QUARK;
            if (LinkInput.hostIs(h, "baidu.com")) return Platform.BAIDU;
            if (LinkInput.hostIs(h, "youtube.com") || LinkInput.hostIs(h, "youtu.be") || LinkInput.hostIs(h, "accounts.google.com")) return Platform.YOUTUBE;
        } catch (Exception ignored) {}
        return null;
    }

    private static String rootDomain(String host) {
        String[] segs = host.split("\\.");
        if (segs.length <= 2) return host;
        return segs[segs.length - 2] + "." + segs[segs.length - 1];
    }
}
