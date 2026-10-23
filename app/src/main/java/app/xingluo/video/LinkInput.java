package app.xingluo.video;

import java.net.URI;
import java.util.*;
import java.util.regex.*;

public final class LinkInput {
    public enum Platform { BILIBILI, DOUYIN, CHANNELS, WEB }
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'【】《》\\u3000-\\u303f\\u4e00-\\u9fff]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern BV = Pattern.compile("(?i)\\bBV[1-9A-HJ-NP-Za-km-z]{10}\\b");
    public static String extract(String text) {
        if(text==null || text.isBlank()) throw new IllegalArgumentException("还没有链接，粘贴分享文案试试吧");
        Matcher m=URL.matcher(text);
        String fallback=null;
        while(m.find()) {
            String url=m.group().replaceAll("[).,;!，。；！）\\]}>]+$", "").replace("&amp;","&");
            if(!isWebUrl(url)) continue;
            if(platform(url)!=Platform.WEB) return url;
            if(fallback==null) fallback=url;
        }
        if(fallback!=null) return fallback;
        m=BV.matcher(text);
        if(m.find()) return "https://www.bilibili.com/video/BV"+m.group().substring(2);
        if(text.contains("视频号") || text.contains("微信")) throw new IllegalArgumentException("这是微信卡片，没有网页链接。请分享可访问链接，或先在微信保存视频后分享文件。");
        throw new IllegalArgumentException("没有找到有效链接，请粘贴完整分享文案");
    }
    public static boolean hostIs(String host,String domain) { return host!=null && (host.equals(domain)||host.endsWith("."+domain)); }
    public static Platform platform(String url) {
        try {
            String h=URI.create(url).getHost().toLowerCase(Locale.ROOT);
            if(hostIs(h,"bilibili.com") || hostIs(h,"b23.tv")) return Platform.BILIBILI;
            if(hostIs(h,"douyin.com") || hostIs(h,"iesdouyin.com")) return Platform.DOUYIN;
            if(hostIs(h,"weixin.qq.com") || hostIs(h,"finder.video.qq.com") || hostIs(h,"channels.weixin.qq.com")) return Platform.CHANNELS;
        } catch(Exception ignored) {}
        return Platform.WEB;
    }
    public static String platformName(String url) { return switch(platform(url)) { case BILIBILI -> "哔哩哔哩"; case DOUYIN -> "抖音"; case CHANNELS -> "视频号"; default -> "网页视频"; }; }
    public static boolean isWebUrl(String url) {
        try { URI u=URI.create(url); return ("https".equalsIgnoreCase(u.getScheme())||"http".equalsIgnoreCase(u.getScheme())) && u.getHost()!=null && u.getUserInfo()==null; }
        catch(Exception e) { return false; }
    }
    public static String safeName(String value) {
        String s=(value==null?"星落视频":value).replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_").replaceAll("^[.\\s]+|[.\\s]+$", "");
        if(s.isBlank()) s="星落视频";
        if(s.length()>90) s=s.substring(0,Character.isHighSurrogate(s.charAt(89))?89:90);
        return s;
    }
    public static int page(String url) {
        Matcher m=Pattern.compile("[?&]p=(\\d+)").matcher(url);
        try { return m.find()?Math.max(1,Integer.parseInt(m.group(1))):1; } catch(Exception e) { return 1; }
    }
}
