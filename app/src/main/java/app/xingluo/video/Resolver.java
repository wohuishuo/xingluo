package app.xingluo.video;

import org.json.*;
import java.util.regex.*;

public final class Resolver {
    public static MediaPlan resolve(String input,Net.Cancellation cancel) throws Exception {
        return resolve(input,cancel,720);
    }
    public static MediaPlan resolve(String input,Net.Cancellation cancel,int quality) throws Exception {
        String url=LinkInput.extract(input);
        if(url.matches("(?i).*\\.mp4(?:[?#].*)?")) return MediaParser.checkedPlan("星落视频",url,url);
        Exception biliFailure=null;
        if(LinkInput.platform(url)==LinkInput.Platform.BILIBILI) {
            try {
                String watch=Net.biliWatchLocation(url,cancel);
                if(LinkInput.isBiliWatchUrl(watch)) return bili(watch,cancel,false,quality);
            } catch(Exception e) { Net.check(cancel); biliFailure=e; }
        }
        String hostRef=LinkInput.platform(url)==LinkInput.Platform.BILIBILI?"https://www.bilibili.com/":url;
        Net.Page page=Net.page(url,hostRef,cancel);
        if(page.type().startsWith("video/") || page.type().contains("octet-stream")) return MediaParser.checkedPlan("星落视频",page.url(),url);
        LinkInput.Platform platform=LinkInput.platform(page.url());
        if(platform==LinkInput.Platform.BILIBILI) {
            if(biliFailure==null && LinkInput.isBiliWatchUrl(page.url())) {
                try { return bili(page.url(),cancel,false,quality); }
                catch(Exception e) { Net.check(cancel); biliFailure=e; }
            }
            try { return MediaParser.webpage(page.body(),page.url(),quality); }
            catch(IllegalArgumentException e) { if(biliFailure!=null) throw biliFailure; throw e; }
        }
        if(platform==LinkInput.Platform.DOUYIN) {
            try { return MediaParser.webpage(page.body(),page.url(),quality); } catch(IllegalArgumentException ignored) {}
            Matcher m=Pattern.compile("(?:video/|modal_id=|aweme_id=)(\\d{10,})").matcher(page.url());
            if(m.find()) {
                String id=m.group(1);
                // Only the public web response is used. Login challenges remain visible to the user.
                try {
                    Net.Page detail=Net.page("https://www.douyin.com/aweme/v1/web/aweme/detail/?aweme_id="+id,"https://www.douyin.com/",cancel);
                    return MediaParser.douyin(new JSONObject(detail.body()),page.url(),quality);
                } catch(Exception ignored) { Net.check(cancel); }
                try {
                    Net.Page share=Net.page("https://www.iesdouyin.com/share/video/"+id+"/",page.url(),cancel);
                    return MediaParser.webpage(share.body(),share.url(),quality);
                } catch(Exception ignored) { Net.check(cancel); }
            }
            throw new IllegalArgumentException("抖音暂未返回视频。请点网页识别，完成验证或播放后再试。");
        }
        String peerApi=PeerTube.apiUrl(page.url());
        if(peerApi!=null) {
            JSONObject data=null;
            try {
                data=new JSONObject(Net.page(peerApi,page.url(),cancel).body());
            } catch(Exception ignored) { Net.check(cancel); }
            if(data!=null && PeerTube.describesVideo(data)) return PeerTube.select(data,page.url(),quality);
        }
        return MediaParser.webpage(page.body(),page.url(),quality);
    }
    public static MediaPlan alternative(String input,Net.Cancellation cancel) throws Exception {
        return alternative(input,cancel,720);
    }
    public static MediaPlan alternative(String input,Net.Cancellation cancel,int quality) throws Exception {
        return biliAlternatives(input,cancel,quality).get(0);
    }
    static java.util.List<MediaPlan> biliAlternatives(String input,Net.Cancellation cancel,int quality) throws Exception {
        String url=LinkInput.extract(input);if(LinkInput.platform(url)!=LinkInput.Platform.BILIBILI)throw new IllegalArgumentException("没有其他视频格式");
        if(!LinkInput.isBiliWatchUrl(url)) url=Net.biliWatchLocation(url,cancel);
        return biliPlans(url,cancel,true,quality);
    }
    private static MediaPlan bili(String url,Net.Cancellation cancel,boolean mp4Only,int quality) throws Exception {
        return biliPlans(url,cancel,mp4Only,quality).get(0);
    }
    private static java.util.List<MediaPlan> biliPlans(String url,Net.Cancellation cancel,boolean mp4Only,int quality) throws Exception {
        Matcher bv=Pattern.compile("(?i)BV[1-9A-HJ-NP-Za-km-z]{10}").matcher(url);
        Matcher av=Pattern.compile("/av(\\d+)").matcher(url);
        String query;
        if(bv.find()) query="bvid="+bv.group();
        else if(av.find()) query="aid="+av.group(1);
        else throw new IllegalArgumentException("请分享 B 站单个视频的 BV / av 链接");
        JSONObject root=new JSONObject(Net.page("https://api.bilibili.com/x/web-interface/view?"+query,"https://www.bilibili.com/",cancel).body());
        if(root.optInt("code")!=0 || root.optJSONObject("data")==null) throw new IllegalArgumentException("B 站没有返回视频信息，请在网页打开后重试");
        JSONObject data=root.getJSONObject("data"); JSONArray pages=data.optJSONArray("pages");
        int index=LinkInput.page(url)-1;
        if(pages==null || index>=pages.length()) throw new IllegalArgumentException("这个分 P 不存在");
        long cid=pages.getJSONObject(index).getLong("cid");
        String title=data.optString("title","B 站视频")+(pages.length()>1?" · P"+(index+1):"");
        Exception failure=null;
        java.util.ArrayList<MediaPlan> plans=new java.util.ArrayList<>();
        java.util.HashSet<String> urls=new java.util.HashSet<>();
        int qn=quality>=1080?80:quality>=720?64:quality>=480?32:16;
        for(String format:biliFormats(qn,mp4Only)) {
            Net.check(cancel);
            try {
                JSONObject play=new JSONObject(Net.page("https://api.bilibili.com/x/player/playurl?"+query+"&cid="+cid+"&"+format,"https://www.bilibili.com/",cancel).body());
                MediaPlan plan=MediaParser.bilibili(play,title,"https://www.bilibili.com/video/"+data.optString("bvid")+"/",quality);
                if(!mp4Only) return java.util.List.of(plan);
                if(urls.add(plan.videoUrl)) plans.add(plan);
            } catch(Exception e) { failure=e; }
        }
        if(plans.isEmpty()) throw failure;
        return plans;
    }

    static String[] biliFormats(int qn,boolean mp4Only) {
        String mp4="fnval=0&qn="+qn+"&high_quality=1";
        String mobileMp4=mp4+"&platform=html5";
        if(mp4Only) return new String[]{mp4,mobileMp4};
        return new String[]{"fnval=4048&qn="+qn+"&fnver=0&fourk=0",mp4,mobileMp4};
    }
}
