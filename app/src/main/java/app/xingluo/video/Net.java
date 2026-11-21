package app.xingluo.video;

import android.webkit.CookieManager;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.zip.GZIPInputStream;

public final class Net {
    public static final String UA="Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    public interface Cancellation { boolean cancelled(); }
    public interface Progress { void update(long done,long total); }
    public record Page(String url,String body,String type) {}
    public static void check(Cancellation c) { if(Thread.currentThread().isInterrupted() || c.cancelled()) throw new CancellationException(); }
    public static HttpURLConnection open(String address,String referer,Cancellation cancel) throws IOException {
        return open(address,referer,cancel,false);
    }
    static String biliWatchLocation(String address,Cancellation cancel) throws IOException {
        HttpURLConnection connection=open(address,"https://www.bilibili.com/",cancel,true);
        try { return connection.getURL().toString(); }
        finally { connection.disconnect(); }
    }
    private static HttpURLConnection open(String address,String referer,Cancellation cancel,boolean stopAtBiliWatch) throws IOException {
        String current=address.startsWith("http://")?"https://"+address.substring(7):address;
        for(int i=0;i<9;i++) {
            check(cancel);
            if(!LinkInput.isWebUrl(current)) throw new IOException("跳转到了不支持的地址");
            HttpURLConnection conn=(HttpURLConnection)new URL(current).openConnection();
            if(stopAtBiliWatch && LinkInput.isBiliWatchUrl(current)) return conn;
            conn.setInstanceFollowRedirects(false); conn.setConnectTimeout(15000); conn.setReadTimeout(15000);
            conn.setUseCaches(false);conn.setRequestProperty("Cache-Control","no-cache");
            conn.setRequestProperty("User-Agent",UA); conn.setRequestProperty("Accept","*/*"); conn.setRequestProperty("Accept-Encoding","identity");
            if(referer!=null && LinkInput.isWebUrl(referer)) conn.setRequestProperty("Referer",referer);
            // Look cookies up for EACH destination; never forward a cookie across redirects.
            String cookies=CookieManager.getInstance().getCookie(current);
            if(cookies!=null && !cookies.isEmpty()) conn.setRequestProperty("Cookie",cookies);
            int code;
            try { code=conn.getResponseCode(); } catch(IOException e) { conn.disconnect(); throw e; }
            for(Map.Entry<String,List<String>> entry:conn.getHeaderFields().entrySet()) {
                if("Set-Cookie".equalsIgnoreCase(entry.getKey())) for(String value:entry.getValue()) CookieManager.getInstance().setCookie(current,value);
            }
            if(code==301||code==302||code==303||code==307||code==308) {
                String location=conn.getHeaderField("Location"); conn.disconnect();
                if(location==null) throw new IOException("平台重定向缺少目标地址");
                current=URI.create(current).resolve(location).toString(); continue;
            }
            if(code<200 || code>=300) { conn.disconnect(); throw new IOException((code==412||code==403||code==429?"平台访问受限，请点网页识别后重试":"网络请求未成功")+"（"+code+"）"); }
            return conn;
        }
        throw new IOException("链接跳转次数过多");
    }
    public static Page page(String url,String referer,Cancellation cancel) throws IOException {
        HttpURLConnection conn=open(url,referer,cancel);
        try {
            String type=Objects.toString(conn.getContentType(),"");
            if(type.startsWith("video/") || type.startsWith("audio/") || type.contains("octet-stream")) return new Page(conn.getURL().toString(),"",type);
            InputStream raw=conn.getInputStream();
            if("gzip".equalsIgnoreCase(conn.getContentEncoding())) raw=new GZIPInputStream(raw);
            try(InputStream in=raw; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[32768]; int n;
                while((n=in.read(buffer))!=-1) { check(cancel); if(out.size()+n>5*1024*1024) throw new IOException("页面内容过大，请尝试直接分享视频链接"); out.write(buffer,0,n); }
                return new Page(conn.getURL().toString(),new String(out.toByteArray(),StandardCharsets.UTF_8),type);
            }
        } finally { conn.disconnect(); }
    }
    public static void download(String url,String referer,File file,Cancellation cancel,Progress progress) throws IOException {
        HttpURLConnection conn=open(url,referer,cancel);
        try {
            String type=Objects.toString(conn.getContentType(),"").toLowerCase(Locale.ROOT);
            if(type.contains("text/")||type.contains("json")||type.contains("mpegurl")) throw new IOException("平台返回了网页或播放列表，没有返回视频文件");
            long total=conn.getContentLengthLong(), done=0;
            if(total>4L*1024*1024*1024) throw new IOException("视频超过 4 GB，请尝试较短的视频");
            try(InputStream in=conn.getInputStream(); FileOutputStream out=new FileOutputStream(file)) {
                byte[] b=new byte[128*1024]; int n; long last=0;
                while((n=in.read(b))!=-1) {
                    check(cancel); done+=n;
                    if(done>4L*1024*1024*1024) throw new IOException("视频超过 4 GB");
                    out.write(b,0,n);
                    if(System.currentTimeMillis()-last>350) { progress.update(done,total); last=System.currentTimeMillis(); }
                }
                out.getFD().sync();
            }
            check(cancel);
            if(total>=0 && done!=total) throw new IOException("下载中断，文件不完整，请重试");
            if(done<64) throw new IOException("视频文件为空或无效");
            progress.update(done,total);
        } finally { conn.disconnect(); }
    }
}
