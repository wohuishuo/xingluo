package app.xingluo.video;

import android.content.*;
import org.json.*;
import java.util.*;

public final class TaskStore {
    public static final String CHANGED="app.xingluo.video.CHANGED";
    private static final Object LOCK=new Object();
    private final Context context;
    public TaskStore(Context c) { context=c.getApplicationContext(); }
    public static final class Task {
        public String id=UUID.randomUUID().toString(), input="", title="正在寻找视频…", status="queued", detail="等待开始", uri="", direct="", referer="";
        public long created=System.currentTimeMillis(), bytes=0; public int percent=-1;
        public boolean active() { return Set.of("queued","resolving","downloading","merging","saving").contains(status); }
        JSONObject json() {
            JSONObject o=new JSONObject();
            try { o.put("id",id).put("input",input).put("title",title).put("status",status).put("detail",detail).put("uri",uri).put("created",created).put("bytes",bytes).put("percent",percent).put("direct",direct).put("referer",referer); } catch(Exception ignored) {}
            return o;
        }
        static Task from(JSONObject o) {
            Task t=new Task(); t.id=o.optString("id",t.id); t.input=o.optString("input"); t.title=o.optString("title"); t.status=o.optString("status"); t.detail=o.optString("detail"); t.uri=o.optString("uri"); t.created=o.optLong("created"); t.bytes=o.optLong("bytes"); t.percent=o.optInt("percent",-1); t.direct=o.optString("direct"); t.referer=o.optString("referer"); return t;
        }
    }
    public List<Task> all() {
        synchronized(LOCK) {
            List<Task> list=new ArrayList<>();
            try { JSONArray a=new JSONArray(prefs().getString("tasks","[]")); for(int i=0;i<a.length();i++) list.add(Task.from(a.getJSONObject(i))); } catch(Exception ignored) {}
            return list;
        }
    }
    private SharedPreferences prefs() { return context.getSharedPreferences("tasks",Context.MODE_PRIVATE); }
    public void save(Task task) {
        synchronized(LOCK) {
            List<Task> list=all(); list.removeIf(t->t.id.equals(task.id)); list.add(0,task); list.sort((a,b)->Long.compare(b.created,a.created));
            JSONArray a=new JSONArray(); int terminal=0;
            for(Task t:list) { if(t.active() || terminal++<100) a.put(t.json()); }
            prefs().edit().putString("tasks",a.toString()).commit();
        }
        context.sendBroadcast(new Intent(CHANGED).setPackage(context.getPackageName()));
    }
    public Task get(String id) { return all().stream().filter(t->t.id.equals(id)).findFirst().orElse(null); }
    public void clearFinished() {
        synchronized(LOCK) { JSONArray a=new JSONArray(); for(Task t:all()) if(t.active()) a.put(t.json()); prefs().edit().putString("tasks",a.toString()).commit(); }
        context.sendBroadcast(new Intent(CHANGED).setPackage(context.getPackageName()));
    }
    public void recover() {
        for(Task t:all()) if(t.active()) { t.status="failed"; t.detail="上次任务被系统中断，点重试继续"; save(t); }
    }
}
