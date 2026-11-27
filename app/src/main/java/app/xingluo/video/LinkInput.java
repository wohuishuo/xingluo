package app.xingluo.video;

import java.net.URI;
import java.util.*;
import java.util.regex.*;

public final class LinkInput {
    public enum Platform {
        BILIBILI, DOUYIN, KUAISHOU, XIAOHONGSHU, PIXIV, PEERTUBE,
        YOUTUBE, TWITTER, TIKTOK, REDDIT, VIMEO, WEIBO, ACFUN,
        AI_MEDIA, QUARK, BAIDU_PAN, LANZOU, CHANNELS, TORRENT, LIVE, WEB
    }

    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'【】《》\\u3000-\\u303f\\u4e00-\\u9fff]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAGNET = Pattern.compile("magnet:\\?xt=urn:btih:[A-Za-z0-9]{32,40}[^\\s<>\"'【】《》\\u3000-\\u303f\\u4e00-\\u9fff]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern BV = Pattern.compile("(?i)\\bBV[1-9A-HJ-NP-Za-km-z]{10}\\b");
    private static final Pattern PASSCODE = Pattern.compile("(?:提取码|密码|访问码|口令|pwd|code)[\\s:：=]*([A-Za-z0-9]{4,8})", Pattern.CASE_INSENSITIVE);

    public static String extract(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("还没有链接，粘贴分享文案试试吧");
        Matcher mag = MAGNET.matcher(text);
        if (mag.find()) return mag.group();
        String url = firstUrl(text);
        if (url != null) return url;
        // Chinese keyboard punctuation can turn an otherwise valid pasted/typed URL into full-width text.
        StringBuilder normalized = new StringBuilder(text.length());
        for (char ch : text.toCharArray()) {
            normalized.append(ch == '。' ? '.' : ch == '　' ? ' ' : ch >= 0xff01 && ch <= 0xff5e ? (char) (ch - 0xfee0) : ch);
        }
        Matcher magNorm = MAGNET.matcher(normalized);
        if (magNorm.find()) return magNorm.group();
        url = firstUrl(normalized.toString());
        if (url != null) return url;
        Matcher m = BV.matcher(text);
        if (m.find()) return "https://www.bilibili.com/video/BV" + m.group().substring(2);
        if (text.contains("视频号") || text.contains("微信")) {
            throw new IllegalArgumentException("这是微信卡片，没有网页链接。请分享可访问链接，或先在微信保存视频后分享文件。");
        }
        throw new IllegalArgumentException("没有找到有效链接，请粘贴完整分享文案");
    }

    /** Extracts a 4-8 character share passcode (提取码) if present alongside a cloud drive share text. */
    public static String extractPasscode(String text) {
        if (text == null || text.isBlank()) return "";
        Matcher m = PASSCODE.matcher(text);
        if (m.find()) return m.group(1);
        Matcher urlPwd = Pattern.compile("[?&]pwd=([A-Za-z0-9]{4,8})").matcher(text);
        if (urlPwd.find()) return urlPwd.group(1);
        return "";
    }

    /** Returns true if text contains a recognizable supported link or magnet URI without throwing. */
    public static String tryExtract(String text) {
        try { return extract(text); } catch (Exception e) { return null; }
    }

    private static String firstUrl(String text) {
        Matcher m = URL.matcher(text);
        String fallback = null;
        while (m.find()) {
            String url = m.group().replaceAll("[).,;!，。；！）\\]}>]+$", "").replace("&amp;", "&");
            if (!isWebUrl(url)) continue;
            if (platform(url) != Platform.WEB || PeerTube.apiUrl(url) != null) return url;
            if (fallback == null) fallback = url;
        }
        return fallback;
    }

    public static boolean isBiliWatchUrl(String url) {
        if (!isWebUrl(url)) return false;
        URI uri = URI.create(url);
        String host = uri.getHost();
        if (host == null) return false;
        return hostIs(host.toLowerCase(Locale.ROOT), "bilibili.com")
                && uri.getPath() != null
                && uri.getPath().matches("(?i)/video/(?:BV[1-9A-HJ-NP-Za-km-z]{10}|av\\d+)/?");
    }

    public static boolean hostIs(String host, String domain) {
        return host != null && (host.equals(domain) || host.endsWith("." + domain));
    }

    public static boolean isMagnet(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).startsWith("magnet:?xt=urn:btih:");
    }

    public static boolean isTorrentUrl(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).matches(".*\\.torrent(?:[?#].*)?");
    }

    public static Platform platform(String url) {
        if (url == null || url.isBlank()) return Platform.WEB;
        if (isMagnet(url) || isTorrentUrl(url)) return Platform.TORRENT;
        try {
            URI u = URI.create(url);
            String hostRaw = u.getHost();
            if (hostRaw == null) return Platform.WEB;
            String h = hostRaw.toLowerCase(Locale.ROOT);
            String path = u.getPath() == null ? "" : u.getPath().toLowerCase(Locale.ROOT);
            if (hostIs(h, "live.bilibili.com") || hostIs(h, "live.douyin.com")) return Platform.LIVE;
            if (hostIs(h, "bilibili.com") || hostIs(h, "b23.tv")) return Platform.BILIBILI;
            if (hostIs(h, "douyin.com") || hostIs(h, "iesdouyin.com")) return Platform.DOUYIN;
            if (hostIs(h, "kuaishou.com") || hostIs(h, "chenzhongtech.com")) return Platform.KUAISHOU;
            if (hostIs(h, "xiaohongshu.com") || hostIs(h, "xhslink.com")) return Platform.XIAOHONGSHU;
            if (hostIs(h, "pixiv.net") || hostIs(h, "pximg.net")) return Platform.PIXIV;
            if (hostIs(h, "youtube.com") || hostIs(h, "youtu.be")) return Platform.YOUTUBE;
            if (hostIs(h, "x.com") || hostIs(h, "twitter.com") || hostIs(h, "fxtwitter.com") || hostIs(h, "vxtwitter.com")) return Platform.TWITTER;
            if (hostIs(h, "tiktok.com")) return Platform.TIKTOK;
            if (hostIs(h, "reddit.com") || hostIs(h, "redd.it")) return Platform.REDDIT;
            if (hostIs(h, "vimeo.com")) return Platform.VIMEO;
            if (hostIs(h, "weibo.com") || hostIs(h, "weibo.cn")) return Platform.WEIBO;
            if (hostIs(h, "acfun.cn")) return Platform.ACFUN;
            if (hostIs(h, "civitai.com") || hostIs(h, "jimeng.jianying.com")
                    || hostIs(h, "klingai.kuaishou.com") || hostIs(h, "hailuoai.com")
                    || hostIs(h, "runwayml.com") || hostIs(h, "lumalabs.ai")) return Platform.AI_MEDIA;
            if (hostIs(h, "pan.quark.cn")) return Platform.QUARK;
            if (hostIs(h, "pan.baidu.com") || hostIs(h, "yun.baidu.com")) return Platform.BAIDU_PAN;
            if (h.matches("^(?:[a-z0-9-]+\\.)*lanzou[a-z]?\\.com$")) return Platform.LANZOU;
            if (hostIs(h, "weixin.qq.com") || hostIs(h, "finder.video.qq.com") || hostIs(h, "channels.weixin.qq.com")) return Platform.CHANNELS;
            if (PeerTube.apiUrl(url) != null) return Platform.PEERTUBE;
            if (path.endsWith(".m3u8") || path.endsWith(".flv")) return Platform.LIVE;
        } catch (Exception ignored) {}
        return Platform.WEB;
    }

    public static String platformName(String input) {
        String url;
        try { url = extract(input); } catch (IllegalArgumentException e) { return "网页视频"; }
        return switch (platform(url)) {
            case BILIBILI -> "哔哩哔哩";
            case DOUYIN -> "抖音";
            case KUAISHOU -> "快手";
            case XIAOHONGSHU -> "小红书";
            case PIXIV -> "Pixiv";
            case PEERTUBE -> "PeerTube";
            case YOUTUBE -> "YouTube";
            case TWITTER -> "X / Twitter";
            case TIKTOK -> "TikTok";
            case REDDIT -> "Reddit";
            case VIMEO -> "Vimeo";
            case WEIBO -> "微博";
            case ACFUN -> "AcFun";
            case AI_MEDIA -> "AI 创作";
            case QUARK -> "夸克网盘";
            case BAIDU_PAN -> "百度网盘";
            case LANZOU -> "蓝奏云";
            case CHANNELS -> "视频号";
            case TORRENT -> "磁力 / 种子";
            case LIVE -> "直播 / 流媒体";
            default -> PeerTube.apiUrl(url) != null ? "PeerTube" : "网页媒体";
        };
    }

    public static boolean isWebUrl(String url) {
        try {
            URI u = URI.create(url);
            return ("https".equalsIgnoreCase(u.getScheme()) || "http".equalsIgnoreCase(u.getScheme()))
                    && u.getHost() != null && u.getUserInfo() == null;
        } catch (Exception e) { return false; }
    }

    public static String safeName(String value) {
        String s = (value == null ? "星落视频" : value)
                .replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_")
                .replaceAll("^[.\\s]+|[.\\s]+$", "");
        if (s.isBlank()) s = "星落视频";
        if (s.length() > 90) s = s.substring(0, Character.isHighSurrogate(s.charAt(89)) ? 89 : 90);
        return s;
    }

    public static int page(String url) {
        Matcher m = Pattern.compile("[?&]p=(\\d+)").matcher(url);
        try { return m.find() ? Math.max(1, Integer.parseInt(m.group(1))) : 1; } catch (Exception e) { return 1; }
    }
}
