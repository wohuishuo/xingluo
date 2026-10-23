package app.xingluo.video;
import java.util.*;
public final class MediaPlan {
    public final String title, videoUrl, audioUrl, referer;
    public final List<String> videoUrls,audioUrls;
    public MediaPlan(String title, String videoUrl, String audioUrl, String referer) {
        this.title=title; this.videoUrl=videoUrl; this.audioUrl=audioUrl; this.referer=referer;
        this.videoUrls=List.of(videoUrl); this.audioUrls=audioUrl==null?List.of():List.of(audioUrl);
    }
    public MediaPlan(String title,List<String> videoUrls,List<String> audioUrls,String referer) {
        this.title=title;this.videoUrls=List.copyOf(videoUrls);this.audioUrls=List.copyOf(audioUrls);this.videoUrl=videoUrls.get(0);this.audioUrl=audioUrls.isEmpty()?null:audioUrls.get(0);this.referer=referer;
    }
}
