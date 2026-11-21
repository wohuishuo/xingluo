package app.xingluo.video;

import org.json.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

public final class MediaParser {
    public static MediaPlan bilibili(JSONObject json,String title,String referer) {
        return bilibili(json,title,referer,720);
    }
    public static MediaPlan bilibili(JSONObject json,String title,String referer,int maxHeight) {
        if(json.optInt("code",0)!=0) throw new IllegalArgumentException("B 站暂时拒绝访问："+json.optString("message","请在网页打开并重试"));
        JSONObject data=json.optJSONObject("data");
        if(data==null) data=json.optJSONObject("result");
        if(data==null) data=json;
        if(data.optInt("is_preview",0)==1 || data.optBoolean("is_preview",false)) throw new IllegalArgumentException("平台只返回试看内容，无法保存完整视频");
        JSONArray durl=data.optJSONArray("durl");
        if(durl!=null && durl.length()==1) {
            JSONObject d=durl.optJSONObject(0);
            if(d!=null) {
                String u=d.optString("url");
                if(LinkInput.isWebUrl(u) && !u.contains(".flv")) return new MediaPlan(title,candidates(d),List.of(),referer);
            }
        }
        JSONObject dash=data.optJSONObject("dash");
        if(dash!=null) {
            JSONObject v=bestTrack(dash.optJSONArray("video"),true,maxHeight), a=bestTrack(dash.optJSONArray("audio"),false,maxHeight);
            if(v!=null && a!=null) return new MediaPlan(title,candidates(v),candidates(a),referer);
        }
        throw new IllegalArgumentException("没有获得可保存的完整 MP4，请在网页中打开后重试");
    }
    private static String baseUrl(JSONObject o) { return o.optString("baseUrl",o.optString("base_url",o.optString("url"))); }
    private static List<String> candidates(JSONObject o) {
        LinkedHashSet<String> values=new LinkedHashSet<>();values.add(baseUrl(o));
        for(String key:new String[]{"backupUrl","backup_url"}){JSONArray a=o.optJSONArray(key);if(a!=null)for(int i=0;i<a.length();i++){String u=a.optString(i);if(LinkInput.isWebUrl(u))values.add(u);}}
        values.removeIf(u->!LinkInput.isWebUrl(u));return new ArrayList<>(values);
    }
    private static JSONObject bestTrack(JSONArray list,boolean video,int maxHeight) {
        if(list==null) return null;
        JSONObject best=null;
        int bestSize=0;
        long bestBitrate=-1;
        for(int i=0;i<list.length();i++) {
            JSONObject o=list.optJSONObject(i); if(o==null || !LinkInput.isWebUrl(baseUrl(o))) continue;
            String codec=o.optString("codecs","").toLowerCase(Locale.ROOT);
            if(video && !codec.startsWith("avc")) continue;
            if(!video && !codec.startsWith("mp4a")) continue;
            int size=video?VideoQuality.shortSide(o.optInt("width"),o.optInt("height")):0;
            long bitrate=o.optLong("bandwidth");
            if(best==null || VideoQuality.better(size,bitrate,bestSize,bestBitrate,maxHeight)) {
                best=o;
                bestSize=size;
                bestBitrate=bitrate;
            }
        }
        return best;
    }
    public static MediaPlan douyin(JSONObject root,String referer) {
        return douyin(root,referer,720);
    }
    public static MediaPlan douyin(JSONObject root,String referer,int maxHeight) {
        MediaPlan p=searchVideo(root,referer,0,maxHeight);
        if(p==null) throw new IllegalArgumentException("抖音未返回视频数据，请在网页中打开后重试");
        return p;
    }
    private static MediaPlan searchVideo(Object root,String ref,int depth,int maxHeight) {
        if(depth>28) return null;
        if(root instanceof JSONObject obj) {
            JSONObject video=obj.optJSONObject("video");
            if(video!=null) {
                String chosen=bestDouyinAddress(video,maxHeight);
                if(chosen!=null)return new MediaPlan(cleanTitle(obj.optString("desc",obj.optString("title","抖音视频"))),chosen,null,ref);
                for(String key:new String[]{"play_addr","playAddr","download_addr","downloadAddr"}) {
                    Object addr=video.opt(key); String u=urlFrom(addr);
                    if(u!=null) return new MediaPlan(cleanTitle(obj.optString("desc",obj.optString("title","抖音视频"))),u,null,ref);
                }
            }
            Iterator<String> keys=obj.keys();
            while(keys.hasNext()) { String key=keys.next(); if(Set.of("music","author","cover","avatar","related","recommend").contains(key)) continue; MediaPlan p=searchVideo(obj.opt(key),ref,depth+1,maxHeight); if(p!=null) return p; }
        } else if(root instanceof JSONArray list) {
            for(int i=0;i<list.length();i++) { MediaPlan p=searchVideo(list.opt(i),ref,depth+1,maxHeight); if(p!=null) return p; }
        }
        return null;
    }
    private static String bestDouyinAddress(JSONObject video,int preferred) {
        String best=null;
        int bestSize=0;
        long bestBitrate=-1;
        JSONArray rates=video.optJSONArray("bit_rate");
        if(rates!=null) for(int i=0;i<rates.length();i++) {
            JSONObject rate=rates.optJSONObject(i);
            if(rate==null) continue;
            JSONObject address=rate.optJSONObject("play_addr");
            if(address==null) address=rate.optJSONObject("playAddr");
            if(address==null) continue;
            String candidate=urlFrom(address);
            int size=VideoQuality.shortSide(address.optInt("width",rate.optInt("width")),
                                            address.optInt("height",rate.optInt("height")));
            long bitrate=rate.optLong("bit_rate");
            if(candidate!=null && size>0 && VideoQuality.better(size,bitrate,bestSize,bestBitrate,preferred)) {
                best=candidate;
                bestSize=size;
                bestBitrate=bitrate;
            }
        }
        for(String key:new String[]{"play_addr","playAddr"}) {
            JSONObject address=video.optJSONObject(key);
            String candidate=urlFrom(video.opt(key));
            if(candidate==null) continue;
            int width=address==null?0:address.optInt("width");
            int height=address==null?0:address.optInt("height");
            if(width<=0 || height<=0) {
                width=video.optInt("width");
                height=video.optInt("height");
            }
            int size=VideoQuality.shortSide(width,height);
            if(size>0 && VideoQuality.better(size,0,bestSize,bestBitrate,preferred)) {
                best=candidate;
                bestSize=size;
                bestBitrate=0;
            }
        }
        return best;
    }
    private static String urlFrom(Object addr) {
        if(addr instanceof String u && LinkInput.isWebUrl(u)) return u;
        if(addr instanceof JSONObject o) {
            for(String key:new String[]{"url_list","urlList","src","url"}) { String u=urlFrom(o.opt(key)); if(u!=null) return u; }
        }
        if(addr instanceof JSONArray a) for(int i=0;i<a.length();i++) { String u=urlFrom(a.opt(i)); if(u!=null) return u; }
        return null;
    }
    public static MediaPlan webpage(String html,String referer) {
        return webpage(html,referer,720);
    }
    public static MediaPlan webpage(String html,String referer,int maxHeight) {
        for(String marker:new String[]{"window._ROUTER_DATA", "window.__INITIAL_STATE__", "window.__playinfo__"}) {
            int pos=html.indexOf(marker);
            if(pos<0) continue;
            int start=html.indexOf('{',pos);
            String json=balancedJson(html,start);
            if(json!=null) try {
                JSONObject o=new JSONObject(json);
                if(marker.contains("playinfo")) return bilibili(o,pageTitle(html),referer,maxHeight);
                MediaPlan p=searchVideo(o,referer,0,maxHeight); if(p!=null) return p;
            } catch(Exception ignored) {}
        }
        Matcher scripts=Pattern.compile("(?is)<script[^>]*id\\s*=\\s*['\"](?:RENDER_DATA|SIGI_STATE|__NEXT_DATA__)['\"][^>]*>(.*?)</script>").matcher(html);
        while(scripts.find()) try {
            String raw=scripts.group(1).trim();
            if(raw.startsWith("%")) raw=URLDecoder.decode(raw,"UTF-8");
            MediaPlan p=searchVideo(new JSONObject(raw),referer,0,maxHeight); if(p!=null) return p;
        } catch(Exception ignored) {}
        Matcher tags=Pattern.compile("(?is)<(?:meta|video|source)\\b[^>]*>").matcher(html);
        while(tags.find()) {
            String tag=tags.group(); Map<String,String> attrs=new HashMap<>();
            Matcher a=Pattern.compile("([\\w:-]+)\\s*=\\s*(['\"])(.*?)\\2",Pattern.DOTALL).matcher(tag);
            while(a.find()) attrs.put(a.group(1).toLowerCase(Locale.ROOT),entities(a.group(3)));
            String u=null;
            if(tag.toLowerCase(Locale.ROOT).startsWith("<meta")) {
                String key=attrs.getOrDefault("property",attrs.getOrDefault("name",""));
                if(Set.of("og:video","og:video:url","og:video:secure_url","twitter:player:stream").contains(key)) u=attrs.get("content");
            } else u=attrs.get("src");
            if(u!=null && !u.startsWith("blob:")) {
                try { u=URI.create(referer).resolve(u).toString(); } catch(Exception ignored) {}
                if(LinkInput.isWebUrl(u)) return checkedPlan(pageTitle(html),u,referer);
            }
        }
        String normalized=html.replace("\\/","/").replace("\\u0026","&").replace("&amp;","&");
        Matcher media=Pattern.compile("https?://[^\\s\"'<>\\\\]+\\.mp4(?:\\?[^\\s\"'<>\\\\]*)?",Pattern.CASE_INSENSITIVE).matcher(normalized);
        if(media.find()) return checkedPlan(pageTitle(html),media.group(),referer);
        String note=LinkInput.platform(referer)==LinkInput.Platform.CHANNELS?"视频号可能只有微信内卡片或加密媒体。请使用可访问网页链接，或在微信保存后分享视频文件。":"没有识别到公开视频。可点“网页识别”，登录或播放后再试。";
        throw new IllegalArgumentException(note);
    }
    public static MediaPlan checkedPlan(String title,String url,String referer) {
        String low=url.toLowerCase(Locale.ROOT);
        if(!LinkInput.isWebUrl(url)) throw new IllegalArgumentException("视频地址无效");
        if(low.contains(".m3u8") || low.contains(".mpd")) throw new IllegalArgumentException("这个视频使用分片播放，暂不支持保存；请尝试平台提供的 MP4");
        if(low.contains("encfilekey=") || low.contains("encrypted=")) throw new IllegalArgumentException("该视频使用加密媒体，无法直接保存");
        return new MediaPlan(cleanTitle(title),url,null,referer);
    }
    public static String balancedJson(String input,int start) {
        if(start<0 || start>=input.length() || input.charAt(start)!='{') return null;
        int depth=0; boolean quoted=false,escape=false;
        for(int i=start;i<input.length();i++) {
            char c=input.charAt(i);
            if(quoted) { if(escape) escape=false; else if(c=='\\') escape=true; else if(c=='"') quoted=false; }
            else if(c=='"') quoted=true; else if(c=='{') depth++; else if(c=='}' && --depth==0) return input.substring(start,i+1);
        }
        return null;
    }
    private static String pageTitle(String html) {
        Matcher m=Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
        return m.find()?cleanTitle(entities(m.group(1))):"星落视频";
    }
    private static String cleanTitle(String title) { return title.replaceAll("<[^>]+>", "").strip().replaceAll("\\s+"," "); }
    public static String entities(String s) { return s.replace("&amp;","&").replace("&quot;","\"").replace("&#39;","'").replace("&lt;","<").replace("&gt;",">"); }
}
