package app.xingluo.video;

import android.content.*;
import android.media.*;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.DocumentsContract;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

public final class MediaFiles {
    public record VideoInfo(int width,int height) {
        public String resolutionLabel() {
            return width+"×"+height+" · "+VideoQuality.shortSide(width,height)+"p";
        }

        public boolean belowPreference(int preferred) {
            return preferred>0 && VideoQuality.shortSide(width,height)<preferred;
        }
    }

    public static void validate(File file) throws IOException {
        inspect(file);
    }

    public static VideoInfo inspect(File file) throws IOException {
        MediaExtractor ex=new MediaExtractor();
        try {
            ex.setDataSource(file.getAbsolutePath());
            if(ex.getDrmInitData()!=null) throw new IOException("视频包含加密媒体，无法直接保存");
            for(int i=0;i<ex.getTrackCount();i++) {
                MediaFormat f=ex.getTrackFormat(i);
                if(!Objects.toString(f.getString(MediaFormat.KEY_MIME),"").startsWith("video/")
                        || !f.containsKey(MediaFormat.KEY_DURATION) || f.getLong(MediaFormat.KEY_DURATION)<=0) continue;
                int width=f.getInteger(MediaFormat.KEY_WIDTH,0);
                int height=f.getInteger(MediaFormat.KEY_HEIGHT,0);
                if(width<=0 || height<=0) continue;
                int rotation=f.getInteger(MediaFormat.KEY_ROTATION,0);
                return Math.floorMod(rotation,180)==90 ? new VideoInfo(height,width) : new VideoInfo(width,height);
            }
            throw new IOException("文件不是完整可播放的视频，已清理临时内容");
        } catch(RuntimeException e) { throw new IOException("视频格式不受支持或文件已损坏",e); } finally { ex.release(); }
    }
    public static void mux(File video,File audio,File output,Net.Cancellation cancel) throws IOException {
        MediaExtractor v=new MediaExtractor(), a=new MediaExtractor(); MediaMuxer mux=null;
        try {
            v.setDataSource(video.getAbsolutePath()); a.setDataSource(audio.getAbsolutePath());
            int vi=find(v,"video/"),ai=find(a,"audio/");
            if(vi<0 || ai<0) throw new IOException("缺少视频或音轨，无法合并");
            v.selectTrack(vi); a.selectTrack(ai);
            mux=new MediaMuxer(output.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vt=mux.addTrack(v.getTrackFormat(vi)),at=mux.addTrack(a.getTrackFormat(ai)); mux.start();
            copyTrack(v,mux,vt,cancel); copyTrack(a,mux,at,cancel); mux.stop();
        } catch(RuntimeException e) { throw new IOException("系统无法合并这个视频格式",e); }
        finally { v.release(); a.release(); if(mux!=null) try { mux.release(); } catch(Exception ignored) {} }
    }
    private static int find(MediaExtractor ex,String prefix) { for(int i=0;i<ex.getTrackCount();i++) if(ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith(prefix)) return i; return -1; }
    private static void copyTrack(MediaExtractor ex,MediaMuxer mux,int track,Net.Cancellation cancel) throws IOException {
        MediaFormat format=ex.getTrackFormat(ex.getSampleTrackIndex());
        int capacity=format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)?Math.max(format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE),2*1024*1024):4*1024*1024;
        if(capacity>32*1024*1024) throw new IOException("视频单帧过大");
        ByteBuffer buffer=ByteBuffer.allocateDirect(capacity); MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
        while(true) { Net.check(cancel); buffer.clear(); int size=ex.readSampleData(buffer,0); if(size<0) break;
            int sampleFlags=ex.getSampleFlags();if((sampleFlags&MediaExtractor.SAMPLE_FLAG_ENCRYPTED)!=0)throw new IOException("音视频轨道包含加密数据");
            int outputFlags=(sampleFlags&MediaExtractor.SAMPLE_FLAG_SYNC)!=0?MediaCodec.BUFFER_FLAG_KEY_FRAME:0;
            info.set(0,size,ex.getSampleTime(),outputFlags); mux.writeSampleData(track,buffer,info); ex.advance(); }
    }
    public static Uri publish(Context context,File file,String title,Net.Cancellation cancel) throws IOException {
        validate(file); Net.check(cancel);
        Preferences prefs=new Preferences(context);
        if(prefs.destination().equals("custom"))return publishDocument(context,file,title,cancel,prefs.folder());
        ContentResolver resolver=context.getContentResolver(); ContentValues values=new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME,LinkInput.safeName(title)+"_"+System.currentTimeMillis()+".mp4");
        boolean downloads=prefs.destination().equals("downloads");
        values.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4"); values.put(MediaStore.Video.Media.RELATIVE_PATH,(downloads?Environment.DIRECTORY_DOWNLOADS:Environment.DIRECTORY_MOVIES)+"/星落"); values.put(MediaStore.Video.Media.IS_PENDING,1);
        Uri uri=resolver.insert(downloads?MediaStore.Downloads.EXTERNAL_CONTENT_URI:MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values);
        if(uri==null) throw new IOException("无法创建相册文件，请检查剩余空间");
        try {
            try(InputStream in=new FileInputStream(file); OutputStream out=resolver.openOutputStream(uri,"w")) {
                if(out==null) throw new IOException("无法写入相册"); byte[] bytes=new byte[128*1024]; int n;
                while((n=in.read(bytes))!=-1) { Net.check(cancel); out.write(bytes,0,n); }
            }
            Net.check(cancel); values.clear(); values.put(MediaStore.Video.Media.IS_PENDING,0);
            if(resolver.update(uri,values,null,null)!=1) throw new IOException("相册保存未完成");
            return uri;
        } catch(Exception e) { resolver.delete(uri,null,null); if(e instanceof RuntimeException r) throw r; if(e instanceof IOException io) throw io; throw new IOException(e); }
    }
    private static Uri publishDocument(Context context,File file,String title,Net.Cancellation cancel,String folder) throws IOException {
        if(folder.isEmpty())throw new IOException("请重新选择自定义保存文件夹");
        ContentResolver resolver=context.getContentResolver();Uri tree=Uri.parse(folder),uri=null;
        SharedPreferences state=context.getSharedPreferences("publication",Context.MODE_PRIVATE);
        try{
            Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
            uri=DocumentsContract.createDocument(resolver,parent,"video/mp4",LinkInput.safeName(title)+"_"+System.currentTimeMillis()+".mp4");
            if(uri==null)throw new IOException("所选文件夹无法创建视频文件");
            state.edit().putString("pending",uri.toString()).commit();
            try(InputStream in=new FileInputStream(file);OutputStream out=resolver.openOutputStream(uri,"w")){
                if(out==null)throw new IOException("无法写入所选文件夹");byte[] bytes=new byte[131072];int n;
                while((n=in.read(bytes))!=-1){Net.check(cancel);out.write(bytes,0,n);}
            }
            Net.check(cancel);state.edit().remove("pending").commit();return uri;
        }catch(Exception e){if(uri!=null)try{DocumentsContract.deleteDocument(resolver,uri);}catch(Exception ignored){}state.edit().remove("pending").commit();if(e instanceof java.util.concurrent.CancellationException c)throw c;if(e instanceof IOException io)throw io;throw new IOException("文件夹授权失效，请在偏好里重新选择保存位置",e);}
    }
    public static void cleanupPending(Context context){
        SharedPreferences p=context.getSharedPreferences("publication",Context.MODE_PRIVATE);String pending=p.getString("pending","");
        if(!pending.isEmpty()){try{DocumentsContract.deleteDocument(context.getContentResolver(),Uri.parse(pending));}catch(Exception ignored){}p.edit().remove("pending").commit();}
    }
}
