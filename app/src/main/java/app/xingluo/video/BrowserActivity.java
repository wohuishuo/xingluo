package app.xingluo.video;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import static app.xingluo.video.Ui.*;

public final class BrowserActivity extends Activity {
    private WebView web;private TextView status;private ProgressBar progress;private String source="",original="";private boolean peanut=false,filled=false,submitted=false,closed=false;
    private final Handler handler=new Handler(Looper.getMainLooper());private final ExecutorService worker=Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle saved){super.onCreate(saved);
        original=getIntent().getStringExtra("url");if(!LinkInput.isWebUrl(original)){finish();return;}peanut=getIntent().getBooleanExtra("peanut",false);source=original;
        LinearLayout root=column(this);root.setBackgroundColor(BG);setContentView(root);
        LinearLayout bar=row(this);pad(bar,15,9,15,9);Icon back=new Icon(this,"close",PURPLE);pad(back,10,10,10,10);back.setContentDescription("关闭网页识别");click(back,this::finish);bar.addView(back,new LinearLayout.LayoutParams(dp(this,42),dp(this,42)));
        LinearLayout labels=column(this);pad(labels,7,0,0,0);labels.addView(text(this,peanut?"PeanutDL 备用解析":"网页识别",14,INK,true));status=text(this,peanut?"链接仅交给本页处理，识别后自动保存":"正常登录或播放，识别后自动保存",10,MUTED,false);status.setMaxLines(2);gap(labels,4);labels.addView(status);bar.addView(labels,new LinearLayout.LayoutParams(0,-2,1));root.addView(bar);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setProgressTintList(android.content.res.ColorStateList.valueOf(PURPLE));root.addView(progress,new LinearLayout.LayoutParams(-1,dp(this,3)));
        web=new WebView(this);root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setUserAgentString(Net.UA);s.setMediaPlaybackRequiresUserGesture(false);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setSupportMultipleWindows(false);CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebChromeClient(new WebChromeClient(){@Override public void onProgressChanged(WebView v,int p){progress.setProgress(p);progress.setVisibility(p<100?View.VISIBLE:View.INVISIBLE);}});
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return !LinkInput.isWebUrl(r.getUrl().toString());}
            @Override public void onPageFinished(WebView v,String url){
                if(!peanut)source=url;CookieManager.getInstance().flush();
                if(peanut && !filled && LinkInput.hostIs(Uri.parse(url).getHost(),"peanutdl.com")){filled=true;fillReferenceForm();}
                inspect();
            }
            @Override public void onReceivedError(WebView v,WebResourceRequest request,WebResourceError error){if(request.isForMainFrame())status.setText("网页暂时打不开，可返回后重试");}
        });
        web.setDownloadListener((url,ua,disposition,mime,length)->{if(mime!=null&&mime.startsWith("video/") || url.contains(".mp4"))found(url,web.getTitle());});
        LinearLayout footer=row(this);pad(footer,16,10,16,13);TextView retry=button(this,"重新解析原链接",false,this::retry);footer.addView(retry,new LinearLayout.LayoutParams(0,-2,1));TextView refresh=button(this,"刷新网页",false,()->web.reload());LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,-2,1);rp.leftMargin=dp(this,9);footer.addView(refresh,rp);root.addView(footer);
        web.loadUrl(peanut?"https://peanutdl.com/zh/"+(LinkInput.platform(original)==LinkInput.Platform.DOUYIN?"douyin":"bilibili"):original);
        handler.postDelayed(poll,1500);
    }
    private void fillReferenceForm(){
        String js="(()=>{const e=[...document.querySelectorAll('input,textarea')].find(x=>(x.placeholder||'').includes('链接'));if(!e)return;const set=Object.getOwnPropertyDescriptor(e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype,'value').set;set.call(e,"+JSONObject.quote(original)+");e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));setTimeout(()=>{const b=[...document.querySelectorAll('button')].find(x=>/^(获取|获取视频|解析|开始解析|获取链接)$/.test(x.innerText.trim()));if(b)b.click();},700);})()";
        web.evaluateJavascript(js,null);
    }
    private final Runnable poll=new Runnable(){public void run(){if(closed||submitted)return;inspect();handler.postDelayed(this,1600);}};
    private void inspect(){
        if(web==null||submitted||closed)return;
        String js="(()=>{let a=[...document.querySelectorAll('video')].sort((a,b)=>(b.paused?0:1)-(a.paused?0:1));let u='';for(const v of a){let x=v.currentSrc||v.src||(v.querySelector('source')||{}).src;if(x&&/^https?:/.test(x)){u=x;break;}}if(!u){const a=[...document.querySelectorAll('a[download]')].find(a=>/^https?:/.test(a.href)&&/\\.mp4(?:\\?|$)/i.test(a.href));if(a)u=a.href;}return JSON.stringify({url:u,title:document.title});})()";
        web.evaluateJavascript(js,result->{if(result==null||result.equals("null"))return;try{Object decoded=new JSONTokener(result).nextValue();if(decoded instanceof String text){JSONObject o=new JSONObject(text);String u=o.optString("url");if(LinkInput.isWebUrl(u))found(u,o.optString("title"));}}catch(Exception ignored){}});
    }
    private void found(String url,String title){
        if(submitted||closed)return;
        try{MediaParser.checkedPlan(title,url,source);}catch(Exception e){status.setText(e.getMessage());return;}
        submitted=true;CookieManager.getInstance().flush();status.setText("已识别视频，正在交给小星保存…");
        String ref=LinkInput.platform(original)==LinkInput.Platform.BILIBILI?"https://www.bilibili.com/":web.getUrl();
        DownloadService.submit(this,original,url,ref,title==null?"网页视频":title);Toast.makeText(this,"已识别，自动开始保存 ✦",Toast.LENGTH_LONG).show();finish();
    }
    private void retry(){if(submitted)return;status.setText("正在使用本机登录状态重试…");int quality=new Preferences(this).quality();worker.execute(()->{try{Resolver.resolve(original,()->closed,quality);runOnUiThread(()->{if(closed)return;submitted=true;DownloadService.submit(this,original);finish();});}catch(Exception e){runOnUiThread(()->{if(!closed)status.setText(e.getMessage()==null?"暂时没有识别到视频，请播放后再试":e.getMessage());});}});}
    @Override public void onBackPressed(){if(web.canGoBack())web.goBack();else super.onBackPressed();}
    @Override protected void onDestroy(){closed=true;handler.removeCallbacksAndMessages(null);worker.shutdownNow();if(web!=null){web.stopLoading();web.destroy();}super.onDestroy();}
}
