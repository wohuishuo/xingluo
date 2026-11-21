package app.xingluo.video;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import java.io.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

public final class SmokeInstrumentation extends Instrumentation {
    private int passed=0;private final StringBuilder log=new StringBuilder();
    private boolean diagnostic=false,playback=false;
    @Override public void onCreate(Bundle b){super.onCreate(b);diagnostic=b!=null&&"true".equals(b.getString("diagnostic"));playback=b!=null&&"true".equals(b.getString("playback"));start();}
    interface Check { void run() throws Exception; }
    private void check(String name,Check c) throws Exception {c.run();passed++;log.append("PASS ").append(name).append('\n');Bundle b=new Bundle();b.putString("stream","PASS "+name+"\n");sendStatus(0,b);}
    private void require(boolean condition,String msg){if(!condition)throw new AssertionError(msg);}
    @Override public void onStart(){
        if(playback){Bundle out=new Bundle();try{out.putString("stream",NetworkInstrumentation.validateDownloads(getTargetContext()));finish(Activity.RESULT_OK,out);}catch(Throwable e){out.putString("stream",android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,out);}return;}
        if(diagnostic){Bundle out=new Bundle();out.putString("stream",NetworkInstrumentation.probe());finish(Activity.RESULT_OK,out);return;}
        Context target=getTargetContext();File sample=new File(target.getCacheDir(),"test-sample.mp4");
        List<Uri> cleanup=new ArrayList<>();
        try{
            try(InputStream in=getContext().getAssets().open("sample.mp4");OutputStream out=new FileOutputStream(sample)){in.transferTo(out);}
            new Preferences(target).destination("gallery");
            check("quality and destination preferences persist",()->{Preferences p=new Preferences(target);p.quality(480);p.destination("downloads");Preferences restored=new Preferences(target);require(restored.quality()==480,"quality lost");require(restored.destination().equals("downloads"),"destination lost");p.quality(720);p.destination("gallery");});
            check("selected Download folder receives complete video",()->{Preferences p=new Preferences(target);p.destination("downloads");try{Uri u=MediaFiles.publish(target,sample,"下载目录验证",()->false);cleanup.add(u);try(Cursor c=target.getContentResolver().query(u,new String[]{"relative_path","_size","is_pending"},null,null,null)){require(c!=null&&c.moveToFirst(),"missing download");require(c.getString(0).equals("Download/星落/"),"wrong destination");require(c.getLong(1)==sample.length()&&c.getInt(2)==0,"incomplete download");}}finally{p.destination("gallery");}});
            check("valid MP4 reports the fixture's actual dimensions",()->{
                MediaFiles.VideoInfo info=MediaFiles.inspect(sample);
                require(info.width()==160 && info.height()==160,"file dimensions do not match the fixture");
            });
            check("HTML cannot masquerade as a video",()->{
                File bad=new File(target.getCacheDir(),"invalid.mp4");try(FileOutputStream out=new FileOutputStream(bad)){out.write("<!doctype html><html>Access denied</html>".getBytes());}
                try{MediaFiles.validate(bad);throw new AssertionError("HTML accepted");}catch(IOException expected){}finally{bad.delete();}
            });
            check("DASH merge preserves both picture and sound",()->{
                File merged=new File(target.getCacheDir(),"test-merged.mp4");MediaFiles.mux(sample,sample,merged,()->false);MediaFiles.validate(merged);MediaExtractor ex=new MediaExtractor();
                try{ex.setDataSource(merged.getAbsolutePath());require(ex.getTrackCount()==2,"expected audio + video");boolean video=false,audio=false;for(int i=0;i<ex.getTrackCount();i++){String mime=ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);video|=mime.startsWith("video/");audio|=mime.startsWith("audio/");}require(video&&audio,"missing track");}finally{ex.release();merged.delete();}
            });
            check("publish writes a playable visible gallery file",()->{
                Uri uri=MediaFiles.publish(target,sample,"测试/安全标题",()->false);cleanup.add(uri);try(Cursor c=target.getContentResolver().query(uri,new String[]{MediaStore.Video.Media.SIZE,MediaStore.Video.Media.IS_PENDING,MediaStore.Video.Media.RELATIVE_PATH},null,null,null)){require(c!=null&&c.moveToFirst(),"missing media row");require(c.getLong(0)==sample.length(),"file not complete");require(c.getInt(1)==0,"file still pending");require(c.getString(2).equals("Movies/星落/"),"wrong location");}
                MediaExtractor ex=new MediaExtractor();try{ex.setDataSource(target,uri,null);require(ex.getTrackCount()==2,"saved video lost sound");}finally{ex.release();}
            });
            check("cancellation removes unpublished gallery row",()->{
                int before=countOwnMedia();AtomicInteger calls=new AtomicInteger();try{MediaFiles.publish(target,sample,"不应发布",()->calls.incrementAndGet()>=3);throw new AssertionError("cancellation ignored");}catch(CancellationException expected){}require(countOwnMedia()==before,"cancel left gallery residue");
            });
            check("task state survives repository recreation and updates without duplicates",()->{
                TaskStore s=new TaskStore(target);TaskStore.Task t=new TaskStore.Task();t.input="https://example.com/test";t.title="持久化验证";s.save(t);t.status="failed";s.save(t);TaskStore other=new TaskStore(target);require(other.get(t.id).status.equals("failed"),"state lost");require(other.all().stream().filter(x->x.id.equals(t.id)).count()==1,"duplicate task");
            });
            check("interrupted downloads become retryable failures",()->{
                TaskStore s=new TaskStore(target);TaskStore.Task t=new TaskStore.Task();t.status="downloading";s.save(t);s.recover();require(s.get(t.id).status.equals("failed"),"stale download still active");
            });
            check("home launches and input is accessible",()->{
                Activity a=startActivitySync(new Intent(target,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();require(a!=null,"no activity");
            });
            check("sharing a video saves automatically and recreation does not duplicate",()->{
                Uri fixture=Uri.parse("content://app.xingluo.video.test.fixture/sample.mp4");getContext().grantUriPermission(target.getPackageName(),fixture,Intent.FLAG_GRANT_READ_URI_PERMISSION);
                TaskStore s=new TaskStore(target);int before=s.all().size();Intent share=new Intent(Intent.ACTION_SEND).setClass(target,MainActivity.class).setType("video/mp4").putExtra(Intent.EXTRA_STREAM,fixture).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK|Intent.FLAG_GRANT_READ_URI_PERMISSION);share.setClipData(ClipData.newRawUri("video",fixture));
                Activity a=startActivitySync(share);waitForIdleSync();long deadline=System.currentTimeMillis()+20000;TaskStore.Task received=null;
                while(System.currentTimeMillis()<deadline){for(TaskStore.Task t:s.all())if(t.direct.equals(fixture.toString()))received=t;if(received!=null&&!received.active())break;Thread.sleep(150);}
                require(received!=null,"share did not submit");require(received.status.equals("done"),"automatic save failed: "+received.detail);cleanup.add(Uri.parse(received.uri));int after=s.all().size();require(after==before+1,"unexpected task count");
                require(received.detail.contains("实际 160×160"),"collection omits the saved dimensions");
                require(!received.detail.contains("低于首选"),"import was incorrectly compared with the download preference");
                if(a!=null)runOnMainSync(a::recreate);waitForIdleSync();Thread.sleep(500);require(s.all().size()==after,"recreation downloaded twice");
            });
            check("text share begins without a second confirmation",()->{
                TaskStore s=new TaskStore(target);String text="【测试】 https://example.invalid/video.mp4 。";
                startActivitySync(new Intent(Intent.ACTION_SEND).setClass(target,MainActivity.class).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();long deadline=System.currentTimeMillis()+10000;TaskStore.Task task=null;
                while(System.currentTimeMillis()<deadline){for(TaskStore.Task t:s.all())if(t.input.equals("https://example.invalid/video.mp4"))task=t;if(task!=null&&!task.active())break;Thread.sleep(100);}
                require(task!=null,"text not automatically submitted");require(!"done".equals(task.status),"invalid address shown as success");
            });
            Bundle out=new Bundle();out.putString("stream","\n"+log+"\nOK ("+passed+" checks)\n");finish(Activity.RESULT_OK,out);
        }catch(Throwable e){Bundle out=new Bundle();out.putString("stream","\n"+log+"\nFAIL: "+e+"\n"+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,out);}
        finally{for(Uri u:cleanup)target.getContentResolver().delete(u,null,null);sample.delete();}
    }
    private int countOwnMedia(){try(Cursor c=getTargetContext().getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,new String[]{"_id"},"relative_path=?",new String[]{"Movies/星落/"},null)){return c==null?0:c.getCount();}}
}
