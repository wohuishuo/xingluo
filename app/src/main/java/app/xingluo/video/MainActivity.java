package app.xingluo.video;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.provider.DocumentsContract;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.*;
import static app.xingluo.video.Ui.*;

public final class MainActivity extends Activity {
    private LinearLayout root,body,bottom,taskList;private EditText input;private TaskStore store;private int tab=0;private String bagFilter="全部";private String lastSubmitted="",draft="";
    private final Handler handler=new Handler(Looper.getMainLooper());private TextView badgeCount;
    private final BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){refreshTasks();}};
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag") // Pre-33 registration is protected by our signature permission.
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);store=new TaskStore(this);
        if(saved!=null){tab=saved.getInt("tab");lastSubmitted=saved.getString("last","");draft=saved.getString("draft","");}
        if(Build.VERSION.SDK_INT>=33)registerReceiver(receiver,new IntentFilter(TaskStore.CHANGED),Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver,new IntentFilter(TaskStore.CHANGED),"app.xingluo.video.INTERNAL",null);
        build(); if(saved==null) consume(getIntent());
    }
    @Override public void onNewIntent(Intent intent) { super.onNewIntent(intent);setIntent(intent);lastSubmitted="";consume(intent); }
    @Override protected void onResume(){super.onResume();refreshTasks();}
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("tab",tab);out.putString("last",lastSubmitted);if(input!=null)out.putString("draft",input.getText().toString());super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){unregisterReceiver(receiver);handler.removeCallbacksAndMessages(null);super.onDestroy();}
    private void consume(Intent intent) {
        if(intent==null)return;
        Uri stream=Build.VERSION.SDK_INT>=33?intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri.class):intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if(Intent.ACTION_SEND.equals(intent.getAction()) && stream!=null && "content".equals(stream.getScheme()) && intent.getType()!=null && intent.getType().startsWith("video/")) {
            if(!lastSubmitted.equals(stream.toString())) { lastSubmitted=stream.toString();DownloadService.submit(this,"分享的视频",stream.toString(),"",displayName(stream));showTasksAfterSubmit();}return;
        }
        String text=intent.getStringExtra(Intent.EXTRA_TEXT);
        if(text==null && Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) { ArrayList<String> list=intent.getStringArrayListExtra(Intent.EXTRA_TEXT);if(list!=null)text=String.join("\n",list); }
        if(text==null && intent.getData()!=null && "xingluo".equals(intent.getData().getScheme())) text=intent.getData().getQueryParameter("url");
        if(text!=null && !text.isBlank() && !text.equals(lastSubmitted)) {lastSubmitted=text;submit(text);}
    }
    private String displayName(Uri uri) {
        try(android.database.Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0).replaceAll("(?i)\\.mp4$","");}catch(Exception ignored){}return "分享的视频";
    }
    private void build() {
        if(input!=null){draft=input.getText().toString();input=null;}
        root=column(this);root.setBackgroundColor(BG);setContentView(root);
        LinearLayout.LayoutParams fill=new LinearLayout.LayoutParams(-1,0,1);ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
        body=column(this);pad(body,22,12,22,24);scroll.addView(body,new ScrollView.LayoutParams(-1,-2));root.addView(scroll,fill);
        bottom=row(this);pad(bottom,18,8,18,10);bottom.setBackground(border(0xfffdfcfa,LINE,0,this));root.addView(bottom,new LinearLayout.LayoutParams(-1,dp(this,76)));
        for(int i=0;i<3;i++){final int index=i;LinearLayout item=column(this);item.setGravity(Gravity.CENTER);Icon icon=new Icon(this,new String[]{"home","bag","settings"}[i],tab==i?PURPLE:MUTED);item.addView(icon,new LinearLayout.LayoutParams(dp(this,23),dp(this,23)));gap(item,5);TextView label=text(this,new String[]{"接住心动","我的收藏","小小偏好"}[i],10,tab==i?PURPLE:MUTED,tab==i);label.setGravity(Gravity.CENTER);item.addView(label);item.setBackground(rounded(tab==i?LILAC:Color.TRANSPARENT,18,this));click(item,()->{tab=index;build();});bottom.addView(item,new LinearLayout.LayoutParams(0,-1,1));}
        if(tab==0)home();else if(tab==1)bag();else settings();enter(body);
    }
    private void heading(String title,String subtitle) {
        LinearLayout head=row(this);head.setPadding(0,dp(this,6),0,dp(this,22));
        LinearLayout words=column(this);words.addView(text(this,title,25,INK,true));gap(words,6);TextView sub=text(this,subtitle,10,MUTED,false);sub.setLetterSpacing(.08f);words.addView(sub);head.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        Icon star=new Icon(this,"star",PURPLE);star.setBackground(rounded(LILAC,16,this));pad(star,11,11,11,11);head.addView(star,new LinearLayout.LayoutParams(dp(this,46),dp(this,46)));body.addView(head);
    }
    private void home() {
        LinearLayout head=row(this);Icon logo=new Icon(this,"star",PURPLE);logo.setBackground(rounded(LILAC,15,this));pad(logo,10,10,10,10);head.addView(logo,new LinearLayout.LayoutParams(dp(this,42),dp(this,42)));
        LinearLayout brand=column(this);pad(brand,11,0,0,0);brand.addView(text(this,"星落",23,INK,true));gap(brand,3);TextView en=text(this,"A LITTLE VIDEO POCKET",8,MUTED,false);en.setLetterSpacing(.12f);brand.addView(en);head.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView info=text(this,"使用小贴士",11,MUTED,false);pad(info,10,12,0,12);click(info,this::help);head.addView(info);body.addView(head);gap(body,24);
        FrameLayout hero=new FrameLayout(this);hero.setBackground(gradient(0xffece6f7,0xfff4eff6,28,this));hero.setClipToOutline(true);body.addView(hero,new LinearLayout.LayoutParams(-1,dp(this,230)));
        TextView tag=text(this,"✦  把喜欢，留在身边",10,PURPLE,true);tag.setBackground(rounded(0x99ffffff,20,this));pad(tag,10,6,10,6);frame(hero,tag,-2,-2,18,18,Gravity.TOP|Gravity.LEFT);
        TextView title=text(this,"每一份心动\n都有处可藏。",23,INK,true);title.setLineSpacing(dp(this,6),1f);frame(hero,title,185,-2,20,65,Gravity.TOP|Gravity.LEFT);
        TextView desc=text(this,"链接给我，\n剩下的交给小星。",11,0xff8b819c,false);desc.setLineSpacing(dp(this,5),1);frame(hero,desc,150,-2,21,142,Gravity.TOP|Gravity.LEFT);
        ImageView mascot=new ImageView(this);mascot.setImageResource(R.drawable.mascot);mascot.setScaleType(ImageView.ScaleType.FIT_CENTER);mascot.setContentDescription("小星抱着视频抱枕坐在星星上");FrameLayout.LayoutParams mp=new FrameLayout.LayoutParams(dp(this,181),dp(this,195),Gravity.RIGHT|Gravity.BOTTOM);mp.rightMargin=dp(this,-4);mp.bottomMargin=dp(this,10);hero.addView(mascot,mp);
        mascot.setOnClickListener(v->{mascot.animate().rotation(-5).translationY(-dp(this,6)).setDuration(150).withEndAction(()->mascot.animate().rotation(0).translationY(0).setDuration(220).start()).start();toast("今天也要把喜欢的瞬间收藏好呀 ✦");});
        gap(body,21);LinearLayout section=row(this);section.addView(text(this,"接住一个视频",16,INK,true),new LinearLayout.LayoutParams(0,-2,1));section.addView(text(this,"PASTE & KEEP",8,MUTED,false));body.addView(section);gap(body,11);
        LinearLayout card=column(this);card.setBackground(border(Color.WHITE,LINE,22,this));pad(card,15,14,15,15);body.addView(card);
        input=new EditText(this);input.setText(draft);input.setTextSize(13);input.setTextColor(INK);input.setHintTextColor(0xffaaa3b7);input.setHint("粘贴链接，或整段分享文案…");input.setBackgroundColor(Color.TRANSPARENT);input.setMinLines(2);input.setMaxLines(4);input.setGravity(Gravity.TOP);input.setPadding(dp(this,2),dp(this,3),dp(this,2),dp(this,6));input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);input.setContentDescription("视频链接或分享文案");card.addView(input,new LinearLayout.LayoutParams(-1,dp(this,66)));
        View line=new View(this);line.setBackgroundColor(LINE);card.addView(line,new LinearLayout.LayoutParams(-1,dp(this,1)));gap(card,13);
        LinearLayout action=row(this);action.setGravity(Gravity.CENTER);action.setBackground(gradient(0xff9480cd,0xff7b65b8,16,this));Icon paste=new Icon(this,"paste",Color.WHITE);action.addView(paste,new LinearLayout.LayoutParams(dp(this,19),dp(this,19)));TextView actionText=text(this,"粘贴并下载",14,Color.WHITE,true);pad(actionText,9,0,0,0);action.addView(actionText);card.addView(action,new LinearLayout.LayoutParams(-1,dp(this,50)));action.setContentDescription("粘贴并下载");click(action,this::pasteOrSubmit);
        gap(body,12);LinearLayout chips=row(this);chips.setGravity(Gravity.CENTER);chip(chips,"哔哩哔哩",0xffe2eff8,0xff6797b5);chip(chips,"抖音",0xffeeeaf6,0xff8b79ab);chip(chips,"视频号",0xffe9f2e9,0xff73967a);body.addView(chips);
        gap(body,17);LinearLayout hint=row(this);Icon share=new Icon(this,"share",MUTED);hint.addView(share,new LinearLayout.LayoutParams(dp(this,14),dp(this,14)));TextView hintText=text(this,"其他 App 分享给「星落」，自动下载保存",10,MUTED,false);pad(hintText,7,0,0,0);hint.addView(hintText);hint.setGravity(Gravity.CENTER);body.addView(hint);
        gap(body,27);LinearLayout recent=row(this);recent.addView(text(this,"最近的心动",16,INK,true),new LinearLayout.LayoutParams(0,-2,1));TextView all=text(this,"收藏袋  ›",11,PURPLE,false);click(all,()->{tab=1;build();});recent.addView(all);body.addView(recent);gap(body,12);taskList=column(this);body.addView(taskList);refreshTasks();
    }
    private void frame(FrameLayout parent,View v,int width,int height,int x,int y,int gravity){FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(width<0?width:dp(this,width),height<0?height:dp(this,height),gravity);p.leftMargin=dp(this,x);p.topMargin=dp(this,y);parent.addView(v,p);}
    private void chip(LinearLayout group,String label,int bg,int fg){TextView t=text(this,"•  "+label,10,fg,true);pad(t,10,6,10,6);t.setBackground(rounded(bg,20,this));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.setMargins(dp(this,4),0,dp(this,4),0);group.addView(t,p);}
    private void bag(){
        heading("我的收藏袋","YOUR LITTLE COLLECTION");
        LinearLayout summary=row(this);summary.setBackground(gradient(0xffece6f7,0xfff3edf7,24,this));pad(summary,19,18,14,18);
        LinearLayout numbers=column(this);badgeCount=text(this,"0",35,PURPLE,true);numbers.addView(badgeCount);gap(numbers,4);numbers.addView(text(this,"个心动瞬间，已好好收藏",11,0xff9588a9,false));summary.addView(numbers,new LinearLayout.LayoutParams(0,-2,1));ImageView image=new ImageView(this);image.setImageResource(R.drawable.mascot);summary.addView(image,new LinearLayout.LayoutParams(dp(this,100),dp(this,100)));body.addView(summary);gap(body,23);
        LinearLayout filters=row(this);for(String value:new String[]{"全部","下载中","已收藏"}){TextView f=text(this,value,12,bagFilter.equals(value)?PURPLE:MUTED,bagFilter.equals(value));pad(f,14,9,14,9);f.setBackground(rounded(bagFilter.equals(value)?LILAC:Color.TRANSPARENT,16,this));click(f,()->{bagFilter=value;build();});filters.addView(f);}
        body.addView(filters);gap(body,15);taskList=column(this);body.addView(taskList);refreshTasks();
    }
    private void refreshTasks(){
        if(taskList==null || tab==2)return;List<TaskStore.Task> tasks=store.all();if(badgeCount!=null)badgeCount.setText(String.valueOf(tasks.stream().filter(t->"done".equals(t.status)).count()));taskList.removeAllViews();int shown=0;
        for(TaskStore.Task t:tasks){if(tab==0 && shown>=3)break;if(tab==1&&"下载中".equals(bagFilter)&&!t.active())continue;if(tab==1&&"已收藏".equals(bagFilter)&&!t.status.equals("done"))continue;taskList.addView(taskCard(t));gap(taskList,10);shown++;}
        if(shown==0){LinearLayout empty=column(this);empty.setGravity(Gravity.CENTER);pad(empty,14,18,14,20);empty.setBackground(border(0xfffefdfb,LINE,22,this));Icon icon=new Icon(this,"bag",0xffbdb2d2);empty.addView(icon,new LinearLayout.LayoutParams(dp(this,32),dp(this,32)));gap(empty,10);empty.addView(text(this,tab==0?"还没有收藏，先接住一个喜欢的瞬间吧":"这里会装满你喜欢的瞬间",11,MUTED,false));taskList.addView(empty);}
    }
    private View taskCard(TaskStore.Task task){
        LinearLayout card=column(this);pad(card,14,14,14,14);card.setBackground(border(Color.WHITE,LINE,20,this));LinearLayout top=row(this);
        String icon=task.status.equals("done")?"check":task.status.equals("failed")?"globe":"download";int tone=task.status.equals("done")?0xff719781:task.status.equals("failed")?0xffb58b72:PURPLE;
        Icon symbol=new Icon(this,icon,tone);symbol.setBackground(rounded(task.status.equals("done")?0xffedf4ed:LILAC,13,this));pad(symbol,10,10,10,10);top.addView(symbol,new LinearLayout.LayoutParams(dp(this,43),dp(this,43)));
        LinearLayout words=column(this);pad(words,11,0,0,0);TextView title=text(this,task.title,12,INK,true);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);words.addView(title);gap(words,6);String subtitle=task.input.equals("分享的视频")?"分享导入":LinkInput.platformName(task.input);words.addView(text(this,subtitle+"  ·  "+new java.text.SimpleDateFormat("MM/dd HH:mm",Locale.CHINA).format(new Date(task.created)),9,MUTED,false));top.addView(words,new LinearLayout.LayoutParams(0,-2,1));card.addView(top);gap(card,11);
        TextView detail=text(this,task.detail,11,task.status.equals("failed")?0xffae8067:MUTED,false);detail.setLineSpacing(dp(this,3),1);card.addView(detail);
        if(task.active()){gap(card,10);ProgressBar p=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);p.setProgressTintList(android.content.res.ColorStateList.valueOf(PURPLE));p.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(LILAC));p.setIndeterminate(task.percent<0);p.setProgress(task.percent);card.addView(p,new LinearLayout.LayoutParams(-1,dp(this,5)));}
        gap(card,10);LinearLayout actions=row(this);actions.setGravity(Gravity.RIGHT);
        if(task.active())smallAction(actions,"取消",()->DownloadService.cancel(this,task.id));
        else if(task.status.equals("done")){smallAction(actions,"分享",()->shareVideo(task));smallAction(actions,"播放  ↗",()->openVideo(task));}
        else{smallAction(actions,"重试",()->retry(task));if(!task.input.equals("分享的视频")){smallAction(actions,"备用解析",()->openBrowser(task.input,true));smallAction(actions,"网页识别",()->openBrowser(task.input,false));}}
        card.addView(actions);return card;
    }
    private void smallAction(LinearLayout row,String label,Runnable action){TextView t=text(this,label,11,PURPLE,true);pad(t,11,8,11,8);t.setBackground(rounded(0xfff4f1f9,11,this));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.leftMargin=dp(this,7);row.addView(t,p);click(t,action);}
    private void pasteOrSubmit(){
        String value=input.getText().toString().strip();if(value.isEmpty()){ClipboardManager clip=getSystemService(ClipboardManager.class);if(clip.hasPrimaryClip()&&clip.getPrimaryClip()!=null&&clip.getPrimaryClip().getItemCount()>0){CharSequence content=clip.getPrimaryClip().getItemAt(0).coerceToText(this);if(content!=null)value=content.toString();}}
        if(value.isEmpty()){toast("剪贴板里还没有链接，先去复制一段分享文案吧");return;}input.setText(value);submit(value);
    }
    private void submit(String raw){
        try{String url=LinkInput.extract(raw);requestNotifications();DownloadService.submit(this,url);getSystemService(InputMethodManager.class).hideSoftInputFromWindow(root.getWindowToken(),0);if(input!=null)input.clearFocus();showTasksAfterSubmit();}
        catch(Exception e){toast(e.getMessage()==null?"暂时无法开始下载":e.getMessage());}
    }
    private void requestNotifications(){if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED && !getPreferences(0).getBoolean("notifAsked",false)){getPreferences(0).edit().putBoolean("notifAsked",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},10);}}
    private void showTasksAfterSubmit(){tab=1;build();toast("收到，交给小星啦 ✦");handler.postDelayed(this::refreshTasks,400);}
    private void retry(TaskStore.Task t){if(!t.direct.isEmpty())DownloadService.submit(this,t.input,t.direct,t.referer,t.title);else submit(t.input);}
    private void openBrowser(String raw,boolean peanut){try{String url=LinkInput.extract(raw);startActivity(new Intent(this,BrowserActivity.class).putExtra("url",url).putExtra("peanut",peanut));}catch(Exception e){toast(e.getMessage());}}
    private void openVideo(TaskStore.Task task){try{startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(task.uri),"video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}catch(Exception e){toast("没有找到视频，文件可能已被移动或删除");}}
    private void shareVideo(TaskStore.Task task){try{Intent i=new Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM,Uri.parse(task.uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(i,"分享这个心动瞬间"));}catch(Exception e){toast("视频可能已被移动或删除");}}
    private void settings(){taskList=null;heading("小小偏好","KEEP IT SIMPLE, KEEP IT YOURS");
        Preferences prefs=new Preferences(this);
        preference("保存位置",prefs.locationLabel(),"bag",this::chooseDestination);
        preference("分享后自动下载","把链接分享给星落，就交给小星","share",this::help);
        preference("画质首选",prefs.quality()+"p · 点击选择，实际画质以平台可用源为准","play",this::chooseQuality);
        preference("自动重新解析","失败时重新获取下载地址，最多尝试 3 次","link",()->new AlertDialog.Builder(this).setTitle("小星会再试试").setMessage("解析失败、下载节点失效或视频校验失败时，会重新解析原分享链接，再尝试下载。每个任务最多尝试 3 次，取消后立即停止。登录验证或加密限制仍可能需要你处理。").setPositiveButton("知道啦",null).show());
        preference("GitHub 开源与更新","点 Star 支持 · Releases 下载新版 APK","star",()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/wohuishuo/xingluo"))));
        preference("平台网页与登录","登录信息只保存在本机 WebView","globe",()->new AlertDialog.Builder(this).setTitle("打开平台网页").setItems(new String[]{"哔哩哔哩","抖音","视频号","PeanutDL 备用解析"},(d,w)->{String[] u={"https://www.bilibili.com/","https://www.douyin.com/","https://channels.weixin.qq.com/","https://peanutdl.com/zh/bilibili"};startActivity(new Intent(this,BrowserActivity.class).putExtra("url",u[w]));}).show());
        preference("清除网页登录","退出本机内置浏览器的所有平台","close",()->new AlertDialog.Builder(this).setTitle("清除网页登录？").setMessage("会清除内置网页的 Cookie 和缓存，下次需重新登录。").setNegativeButton("保留",null).setPositiveButton("清除",(d,w)->{android.webkit.CookieManager.getInstance().removeAllCookies(v->toast("已清除网页登录"));android.webkit.WebStorage.getInstance().deleteAllData();new android.webkit.WebView(this).clearCache(true);}).show());
        preference("清空任务记录","保留已经下载到相册的视频","bag",()->new AlertDialog.Builder(this).setTitle("清空已结束的任务？").setMessage("已下载的视频仍留在系统相册，进行中的任务继续保留。").setNegativeButton("算了",null).setPositiveButton("清空",(d,w)->{store.clearFinished();toast("收藏袋里的记录已整理好");}).show());
        gap(body,20);TextView note=text(this,"星落 1.1.0  /  MADE FOR YOUR LITTLE MOMENTS\n\n仅在你粘贴或分享时读取内容。下载和登录信息保存在手机。使用备用解析时，链接会交给 PeanutDL 网页处理。\n\n视频号微信卡片、登录验证或加密媒体可能无法解析。请保存自己有权使用的内容，尊重创作者。",10,MUTED,false);note.setLineSpacing(dp(this,5),1);pad(note,4,0,4,0);body.addView(note);
    }
    private void chooseQuality(){
        Preferences prefs=new Preferences(this);int[] values={1080,720,480,360};String[] labels={"1080p · 高清优先","720p · 推荐","480p · 均衡","360p · 省流量"};int checked=1;for(int i=0;i<values.length;i++)if(values[i]==prefs.quality())checked=i;
        new AlertDialog.Builder(this).setTitle("选择默认画质首选").setSingleChoiceItems(labels,checked,(dialog,which)->{prefs.quality(values[which]);dialog.dismiss();build();toast("已设为 "+values[which]+"p，后续任务生效");}).setNegativeButton("取消",null).setNeutralButton("画质说明",(d,w)->new AlertDialog.Builder(this).setTitle("画质由平台提供").setMessage("B 站会请求所选档位，并选择不高于首选的可用音画源。抖音提供多档源时同样选择对应来源；只有单一格式时使用平台返回的视频。不会突破登录或会员限制，也不会把低清视频伪装成高清。").setPositiveButton("知道啦",null).show()).show();
    }
    private void chooseDestination(){
        Preferences prefs=new Preferences(this);String[] labels={"系统相册 · Movies/星落","下载目录 · Download/星落","自选文件夹…"};int checked=prefs.destination().equals("downloads")?1:prefs.destination().equals("custom")?2:0;
        new AlertDialog.Builder(this).setTitle("默认保存位置").setSingleChoiceItems(labels,checked,(dialog,which)->{dialog.dismiss();if(which==2){Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(picker,910);}else{prefs.destination(which==0?"gallery":"downloads");build();toast("保存位置已更新，后续任务生效");}}).setNegativeButton("取消",null).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=910||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);String label=DocumentsContract.getTreeDocumentId(uri);if(label.contains(":"))label=label.substring(label.indexOf(':')+1);new Preferences(this).folder(uri,label.isEmpty()?"已选择文件夹":label);build();toast("自选文件夹已保存");}catch(Exception e){toast("没有获得文件夹写入权限，请重新选择");}}
    private void preference(String title,String desc,String symbol,Runnable action){LinearLayout row=row(this);pad(row,14,17,11,17);row.setBackground(border(Color.WHITE,LINE,19,this));Icon icon=new Icon(this,symbol,PURPLE);row.addView(icon,new LinearLayout.LayoutParams(dp(this,23),dp(this,23)));LinearLayout words=column(this);pad(words,12,0,0,0);words.addView(text(this,title,13,INK,true));gap(words,6);words.addView(text(this,desc,10,MUTED,false));row.addView(words,new LinearLayout.LayoutParams(0,-2,1));row.addView(new Icon(this,"chevron",0xffb3a9c2),new LinearLayout.LayoutParams(dp(this,16),dp(this,16)));click(row,action);body.addView(row);gap(body,10);}
    private void help(){new AlertDialog.Builder(this).setTitle("把喜欢的瞬间交给小星").setMessage("① 在 B 站、抖音等 App 点分享，选择星落，即开始下载。\n\n② 或复制完整分享文案，打开星落点“粘贴并下载”。\n\n③ 遇到平台验证，点任务里的“网页识别”，正常登录或播放后识别。也可试试“备用解析”。\n\n视频号若只有微信卡片没有链接，可先在微信保存视频，再把视频文件分享给星落。\n\n成功后视频在系统相册 Movies/星落。无需相册读取或全部文件权限。").setPositiveButton("知道啦",null).show();}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
}
