package app.xingluo.video;
import android.app.*;
import android.os.*;
import org.json.*;
import java.net.*;
import java.io.*;
import android.content.Context;
import android.net.Uri;
import android.media.*;

public final class NetworkInstrumentation {
    public static String validateDownloads(Context context) throws Exception {
        JSONArray results=new JSONArray();
        for(String input:new String[]{"https://b23.tv/y7xiWGg","https://www.douyin.com/video/6961737553342991651"}){
            TaskStore.Task task=new TaskStore(context).all().stream().filter(t->t.input.equals(input)&&t.status.equals("done")).findFirst().orElseThrow();
            MediaExtractor ex=new MediaExtractor();MediaMetadataRetriever reader=new MediaMetadataRetriever();JSONObject result=new JSONObject();
            try{ex.setDataSource(context,Uri.parse(task.uri),null);JSONArray tracks=new JSONArray();boolean sound=false,picture=false;
                for(int i=0;i<ex.getTrackCount();i++){MediaFormat f=ex.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);sound|=mime.startsWith("audio/");picture|=mime.startsWith("video/");tracks.put(new JSONObject().put("mime",mime).put("format",f.toString()));}
                if(!sound||!picture)throw new AssertionError("Missing sound or picture");
                reader.setDataSource(context,Uri.parse(task.uri));android.graphics.Bitmap frame=reader.getFrameAtTime(0);if(frame==null)throw new AssertionError("Cannot decode picture");frame.recycle();
                result.put("input",input).put("title",task.title).put("bytes",task.bytes).put("duration_ms",reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).put("decoded_frame",true).put("tracks",tracks);results.put(result);
            }finally{ex.release();reader.release();}
        }
        return results.toString(2);
    }
    public static String probe(){
        JSONObject report=new JSONObject();
        try{
            MediaPlan p=Resolver.resolve("https://b23.tv/y7xiWGg",()->false);report.put("title",p.title).put("referer",p.referer);
            JSONArray probes=new JSONArray();
            for(String u:p.videoUrls){HttpURLConnection c=(HttpURLConnection)new URL(u).openConnection();c.setConnectTimeout(10000);c.setReadTimeout(10000);c.setRequestProperty("User-Agent",Net.UA);c.setRequestProperty("Referer",p.referer);c.setRequestProperty("Range","bytes=0-15");probes.put(new JSONObject().put("host",new URL(u).getHost()).put("code",c.getResponseCode()));c.disconnect();}report.put("probes",probes);
            MediaPlan mp4=Resolver.alternative("https://b23.tv/y7xiWGg",()->false);JSONArray variants=new JSONArray();
            for(int i=0;i<4;i++){HttpURLConnection c=(HttpURLConnection)new URL(mp4.videoUrl).openConnection();c.setConnectTimeout(10000);c.setReadTimeout(10000);c.setRequestProperty("User-Agent",i==0?Net.UA:"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");if(i!=3)c.setRequestProperty("Referer","https://www.bilibili.com/");if(i>=2)c.setRequestProperty("Origin","https://www.bilibili.com");c.setRequestProperty("Range","bytes=0-15");variants.put(new JSONObject().put("variant",i).put("code",c.getResponseCode()));c.disconnect();}report.put("mp4Variants",variants);
        }catch(Exception e){try{report.put("error",e.toString());}catch(Exception ignored){}}
        return report.toString();
    }
}
