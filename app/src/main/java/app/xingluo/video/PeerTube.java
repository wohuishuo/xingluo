package app.xingluo.video;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** PeerTube instances are federated, so their video paths identify the API candidate. */
final class PeerTube {
    private static final String UUID="[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}";
    private static final String SHORT_ID="[1-9A-HJ-NP-Za-km-z]{22}";
    private static final Pattern PATH=Pattern.compile("^/(?:w|videos/(?:watch|embed))/("+UUID+"|"+SHORT_ID+")/?$");

    private PeerTube() {}

    static String apiUrl(String page) {
        if(!LinkInput.isWebUrl(page)) return null;
        URI uri=URI.create(page);
        Matcher match=PATH.matcher(uri.getPath());
        return match.matches() ? "https://"+uri.getRawAuthority()+"/api/v1/videos/"+match.group(1) : null;
    }

    static boolean describesVideo(JSONObject data) {
        return data.has("uuid") && data.has("name") && data.has("downloadEnabled")
                && (data.has("files") || data.has("streamingPlaylists"));
    }

    static MediaPlan select(JSONObject data,String page,int preferred) {
        if(data.optBoolean("isLive")) throw new IllegalArgumentException("PeerTube 直播暂不支持保存");
        if(!data.optBoolean("downloadEnabled")) throw new IllegalArgumentException("作者未开启此 PeerTube 视频的下载");

        JSONArray files=data.optJSONArray("files");
        String best=null;
        String bestFile=null;
        int bestSize=0;
        long bestBytes=-1;
        if(files!=null) for(int i=0;i<files.length();i++) {
            JSONObject file=files.optJSONObject(i);
            if(file==null) continue;
            JSONObject resolution=file.optJSONObject("resolution");
            if(resolution==null) continue;
            int size=resolution.optInt("id");
            String url=file.optString("fileDownloadUrl");
            String fileUrl=file.optString("fileUrl");
            if(size<=0 || !LinkInput.isWebUrl(fileUrl)
                    || !URI.create(fileUrl).getPath().toLowerCase(Locale.ROOT).endsWith(".mp4")
                    || !LinkInput.isWebUrl(url)) continue;
            long bytes=file.optLong("size");
            if(best==null || VideoQuality.better(size,bytes,bestSize,bestBytes,preferred)) {
                best=url;
                bestFile=fileUrl;
                bestSize=size;
                bestBytes=bytes;
            }
        }
        if(best==null) throw new IllegalArgumentException("这个 PeerTube 视频没有开放下载的完整 MP4，暂不支持分片视频");
        MediaPlan download=MediaParser.checkedPlan(data.optString("name","PeerTube 视频"),best,page);
        if(best.equals(bestFile)) return download;
        MediaParser.checkedPlan(download.title,bestFile,page);
        return new MediaPlan(download.title,List.of(best,bestFile),List.of(),page);
    }
}
