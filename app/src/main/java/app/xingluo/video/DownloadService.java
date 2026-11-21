package app.xingluo.video;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class DownloadService extends Service {
    public static final String ENQUEUE="enqueue", CANCEL="cancel";
    private final ExecutorService queue=Executors.newSingleThreadExecutor();
    private final Set<String> cancelled=ConcurrentHashMap.newKeySet();
    private final AtomicInteger count=new AtomicInteger();
    private record Downloaded(File file, MediaFiles.VideoInfo info) {}
    private TaskStore store; private int latestStart;
    public static void submit(Context c,String input) { submit(c,input,null,null,null); }
    public static void submit(Context c,String input,String media,String referer,String title) {
        Intent i=new Intent(c,DownloadService.class).setAction(ENQUEUE).putExtra("input",input).putExtra("direct",media).putExtra("referer",referer).putExtra("title",title);
        c.startForegroundService(i);
    }
    public static void cancel(Context c,String id) { c.startService(new Intent(c,DownloadService.class).setAction(CANCEL).putExtra("id",id)); }
    @Override public void onCreate() {
        super.onCreate(); store=new TaskStore(this); store.recover();
        MediaFiles.cleanupPending(this);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("downloads","视频下载",NotificationManager.IMPORTANCE_LOW));
        // Pending rows and caches can remain after an OS kill. Only our own unpublished media is removed.
        getContentResolver().delete(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI,"is_pending=1 AND relative_path=?",new String[]{"Movies/星落/"});
        getContentResolver().delete(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,"is_pending=1 AND relative_path=?",new String[]{"Download/星落/"});
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        latestStart=startId;
        if(intent==null) { stopSelf(); return START_NOT_STICKY; }
        if(CANCEL.equals(intent.getAction())) {
            String id=intent.getStringExtra("id"); if(id!=null) { cancelled.add(id); TaskStore.Task t=store.get(id); if(t!=null && t.active()) { t.status="cancelled"; t.detail="已取消"; store.save(t); } }
            if(count.get()==0) stopSelf(); return START_NOT_STICKY;
        }
        String input=intent.getStringExtra("input"),direct=intent.getStringExtra("direct");
        for(TaskStore.Task existing:store.all()) if(existing.active() && existing.input.equals(input==null?"":input) && existing.direct.equals(direct==null?"":direct)) { foreground(existing); return START_NOT_STICKY; }
        TaskStore.Task task=new TaskStore.Task(); task.input=input==null?"":input; task.direct=direct==null?"":direct; task.referer=Objects.toString(intent.getStringExtra("referer"),"");
        String title=intent.getStringExtra("title"); if(title!=null&&!title.isBlank()) task.title=title;
        store.save(task); foreground(task); count.incrementAndGet();
        queue.execute(()->{
            runTask(task);
            if(count.decrementAndGet()==0) new Handler(getMainLooper()).post(()->{ if(count.get()==0) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(latestStart); } });
        });
        return START_NOT_STICKY;
    }
    private void runTask(TaskStore.Task task) {
        File dir=new File(getCacheDir(),"job-"+task.id); dir.mkdirs();
        Net.Cancellation cancel=()->cancelled.contains(task.id);
        PowerManager.WakeLock wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"xingluo:download");
        Uri published=null;
        try {
            wake.acquire(30*60*1000L); Net.check(cancel);
            Downloaded downloaded;
            boolean imported=task.direct.startsWith("content://");
            int quality=imported?0:new Preferences(this).quality();
            if(imported) {
                File result=new File(dir,"video.mp4");
                update(task,"downloading","正在把分享的视频放进收藏袋",-1);
                try(InputStream in=getContentResolver().openInputStream(Uri.parse(task.direct));OutputStream out=new FileOutputStream(result)) {
                    if(in==null) throw new IOException("无法读取分享的视频"); byte[] b=new byte[131072];int n;long size=0;
                    while((n=in.read(b))!=-1) { Net.check(cancel); size+=n; if(size>4L*1024*1024*1024) throw new IOException("视频超过 4 GB"); out.write(b,0,n); }
                }
                if(task.title.equals("正在寻找视频…")) task.title="分享的视频";
                downloaded=new Downloaded(result,MediaFiles.inspect(result));
            } else {
                downloaded=RetryPolicy.run(3,attempt->{
                    Net.check(cancel);update(task,"resolving",attempt==1?"正在识别链接，寻找视频…":"第 "+attempt+" / 3 次尝试 · 正在重新解析原链接",-1);
                    MediaPlan plan=(attempt==1&&!task.direct.isEmpty())?MediaParser.checkedPlan(task.title,task.direct,task.referer):Resolver.resolve(task.input,cancel,quality);
                    task.title=plan.title==null||plan.title.isBlank()?"星落视频":plan.title;
                    try {
                        File result=fetchPlan(plan,dir,task,cancel);
                        return new Downloaded(result,MediaFiles.inspect(result));
                    } catch(IOException firstFailure) {
                        if(LinkInput.platform(task.input)!=LinkInput.Platform.BILIBILI)throw firstFailure;
                        Net.check(cancel);
                        update(task,"resolving","正在尝试 B 站提供的其他视频格式…",-1);
                        IOException lastFailure=firstFailure;
                        for(MediaPlan alternative:Resolver.biliAlternatives(task.input,cancel,quality)) {
                            if(alternative.videoUrls.equals(plan.videoUrls) && alternative.audioUrls.equals(plan.audioUrls)) continue;
                            try {
                                File result=fetchPlan(alternative,dir,task,cancel);
                                return new Downloaded(result,MediaFiles.inspect(result));
                            } catch(IOException e) { lastFailure=e; }
                        }
                        throw lastFailure;
                    }
                },cancel::cancelled,(next,error)->update(task,"resolving","暂时没接住，马上重新获取链接（"+next+" / 3）",-1),millis->{long end=System.currentTimeMillis()+millis;while(System.currentTimeMillis()<end){Net.check(cancel);Thread.sleep(100);}});
            }
            update(task,"saving","正在写入保存位置",97); task.bytes=downloaded.file().length();
            published=MediaFiles.publish(this,downloaded.file(),task.title,cancel);
            Net.check(cancel);
            task.uri=published.toString();
            String detail="已保存 · "+new Preferences(this).locationLabel()
                    +"\n实际 "+downloaded.info().resolutionLabel();
            if(downloaded.info().belowPreference(quality)) detail+=" · 低于首选 "+quality+"p";
            update(task,"done",detail,100);
            finishNotification(task,true);
        } catch(CancellationException e) {
            if(published!=null) getContentResolver().delete(published,null,null);
            update(task,"cancelled","已取消，临时文件已清理",-1);
        } catch(Exception e) {
            if(published!=null) getContentResolver().delete(published,null,null);
            String message=e.getMessage(); if(message==null||message.isBlank()) message="暂时没接住这个视频，请重试";
            if(e instanceof java.net.UnknownHostException) message="网络暂时不可用，连接网络后重试";
            if(e instanceof java.net.SocketTimeoutException) message="网络响应超时，请检查网络或稍后重试";
            update(task,"failed",message,-1); finishNotification(task,false);
        } finally {
            if(wake.isHeld()) wake.release(); File[] files=dir.listFiles(); if(files!=null) for(File f:files) f.delete(); dir.delete(); cancelled.remove(task.id);
        }
    }
    private File fetchPlan(MediaPlan plan,File dir,TaskStore.Task task,Net.Cancellation cancel) throws IOException {
        File video=new File(dir,"video.mp4");update(task,"downloading",plan.audioUrl==null?"正在接住你喜欢的片段":"正在下载画面与声音",0);
        downloadCandidates(plan.videoUrls,plan.referer,video,cancel,(done,total)->progress(task,done,total,0,plan.audioUrl==null?94:70));
        if(plan.audioUrl==null)return video;
        File audio=new File(dir,"audio.m4a"),merged=new File(dir,"merged.mp4");
        downloadCandidates(plan.audioUrls,plan.referer,audio,cancel,(done,total)->progress(task,done,total,70,23));
        update(task,"merging","画面与声音，正在相遇",94);MediaFiles.mux(video,audio,merged,cancel);return merged;
    }
    private void downloadCandidates(List<String> urls,String ref,File file,Net.Cancellation cancel,Net.Progress progress) throws IOException {
        IOException failure=null;
        for(String url:urls){Net.check(cancel);try{Net.download(url,ref,file,cancel,progress);return;}catch(IOException e){failure=e;file.delete();}}
        throw failure==null?new IOException("没有可用的视频下载节点"):failure;
    }
    private void progress(TaskStore.Task task,long done,long total,int start,int span) {
        task.bytes=done; int percent=total>0?start+(int)(done*span/total):-1;
        update(task,"downloading",bytes(done)+(total>0?" / "+bytes(total):"")+" · 正在下载",percent);
    }
    public static String bytes(long n) { if(n<1024*1024) return String.format(Locale.CHINA,"%.0f KB",n/1024d); return String.format(Locale.CHINA,"%.1f MB",n/1048576d); }
    private void update(TaskStore.Task task,String state,String detail,int percent) {
        task.status=state;task.detail=detail;task.percent=percent;store.save(task);
        if(task.active()) getSystemService(NotificationManager.class).notify(1,notification(task,true));
    }
    private Notification notification(TaskStore.Task task,boolean ongoing) {
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent content=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=new Notification.Builder(this,"downloads").setSmallIcon(R.drawable.ic_notification).setContentTitle(task.title).setContentText(task.detail).setStyle(new Notification.BigTextStyle().bigText(task.detail)).setContentIntent(content).setOngoing(ongoing).setOnlyAlertOnce(true).setAutoCancel(!ongoing);
        if(ongoing) {
            b.setProgress(100,Math.max(0,task.percent),task.percent<0);
            Intent cancel=new Intent(this,DownloadService.class).setAction(CANCEL).putExtra("id",task.id);
            b.addAction(new Notification.Action.Builder(null,"取消",PendingIntent.getService(this,task.id.hashCode(),cancel,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT)).build());
        }
        return b.build();
    }
    private void foreground(TaskStore.Task task) { startForeground(1,notification(task,true),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC); }
    private void finishNotification(TaskStore.Task task,boolean success) { getSystemService(NotificationManager.class).notify(task.id.hashCode(),notification(task,false)); }
    @Override public void onTimeout(int startId,int fgsType) { for(TaskStore.Task t:store.all()) if(t.active()) cancelled.add(t.id); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
    @Override public void onDestroy() { for(TaskStore.Task t:store.all()) if(t.active()) cancelled.add(t.id); queue.shutdownNow(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
