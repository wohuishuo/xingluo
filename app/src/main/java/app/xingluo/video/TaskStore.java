package app.xingluo.video;

import android.content.*;
import org.json.*;
import java.util.*;

public final class TaskStore {
    public static final String CHANGED = "app.xingluo.video.CHANGED";
    private static final Object LOCK = new Object();
    private final Context context;

    public TaskStore(Context c) { context = c.getApplicationContext(); }

    public static final class Task {
        public String id = UUID.randomUUID().toString();
        public String input = "";
        public String title = "正在寻找视频…";
        public String status = "queued";
        public String detail = "等待开始";
        public String uri = "";
        public String direct = "";
        public String referer = "";
        public String platform = "";
        public String mediaType = "VIDEO";
        public String author = "";
        public List<String> tags = new ArrayList<>();
        public long created = System.currentTimeMillis();
        public long bytes = 0;
        public long totalBytes = -1;
        public long speedBps = 0;
        public int percent = -1;
        public String resolution = "";
        public int itemCount = 1;
        public int savedCount = 0;
        public String errorCode = "";
        public String errorCause = "";
        public boolean resumable = false;
        public String localPath = "";
        public boolean watermarkFree = false;
        public int threadCount = 1;

        public boolean active() {
            return Set.of("queued", "resolving", "downloading", "merging", "saving", "recording").contains(status);
        }

        public boolean paused() { return "paused".equals(status); }

        JSONObject json() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id)
                        .put("input", input)
                        .put("title", title)
                        .put("status", status)
                        .put("detail", detail)
                        .put("uri", uri)
                        .put("created", created)
                        .put("bytes", bytes)
                        .put("totalBytes", totalBytes)
                        .put("speedBps", speedBps)
                        .put("percent", percent)
                        .put("direct", direct)
                        .put("referer", referer)
                        .put("platform", platform)
                        .put("mediaType", mediaType)
                        .put("author", author)
                        .put("resolution", resolution)
                        .put("itemCount", itemCount)
                        .put("savedCount", savedCount)
                        .put("errorCode", errorCode)
                        .put("errorCause", errorCause)
                        .put("resumable", resumable)
                        .put("localPath", localPath)
                        .put("watermarkFree", watermarkFree)
                        .put("threadCount", threadCount);
                JSONArray arr = new JSONArray();
                for (String t : tags) arr.put(t);
                o.put("tags", arr);
            } catch (Exception ignored) {}
            return o;
        }

        static Task from(JSONObject o) {
            Task t = new Task();
            t.id = o.optString("id", t.id);
            t.input = o.optString("input");
            t.title = o.optString("title", t.title);
            t.status = o.optString("status", t.status);
            t.detail = o.optString("detail");
            t.uri = o.optString("uri");
            t.created = o.optLong("created");
            t.bytes = o.optLong("bytes");
            t.totalBytes = o.optLong("totalBytes", -1);
            t.speedBps = o.optLong("speedBps", 0);
            t.percent = o.optInt("percent", -1);
            t.direct = o.optString("direct");
            t.referer = o.optString("referer");
            t.platform = o.optString("platform", "");
            t.mediaType = o.optString("mediaType", "VIDEO");
            t.author = o.optString("author", "");
            t.resolution = o.optString("resolution", "");
            t.itemCount = o.optInt("itemCount", 1);
            t.savedCount = o.optInt("savedCount", 0);
            t.errorCode = o.optString("errorCode", "");
            t.errorCause = o.optString("errorCause", "");
            t.resumable = o.optBoolean("resumable", false);
            t.localPath = o.optString("localPath", "");
            t.watermarkFree = o.optBoolean("watermarkFree", false);
            t.threadCount = o.optInt("threadCount", 1);
            JSONArray arr = o.optJSONArray("tags");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String s = arr.optString(i);
                    if (!s.isBlank()) t.tags.add(s);
                }
            }
            return t;
        }
    }

    public List<Task> all() {
        synchronized (LOCK) {
            List<Task> list = new ArrayList<>();
            try {
                JSONArray a = new JSONArray(prefs().getString("tasks", "[]"));
                for (int i = 0; i < a.length(); i++) list.add(Task.from(a.getJSONObject(i)));
            } catch (Exception ignored) {}
            return list;
        }
    }

    private SharedPreferences prefs() { return context.getSharedPreferences("tasks", Context.MODE_PRIVATE); }

    public void save(Task task) {
        synchronized (LOCK) {
            List<Task> list = all();
            list.removeIf(t -> t.id.equals(task.id));
            list.add(0, task);
            list.sort((a, b) -> Long.compare(b.created, a.created));
            JSONArray a = new JSONArray();
            int terminal = 0;
            for (Task t : list) {
                if (t.active() || t.paused() || terminal++ < 200) a.put(t.json());
            }
            prefs().edit().putString("tasks", a.toString()).commit();
        }
        notifyChanged();
    }

    public Task get(String id) {
        return all().stream().filter(t -> t.id.equals(id)).findFirst().orElse(null);
    }

    public boolean delete(String id) {
        boolean removed;
        synchronized (LOCK) {
            List<Task> list = all();
            removed = list.removeIf(t -> t.id.equals(id));
            if (removed) {
                JSONArray a = new JSONArray();
                for (Task t : list) a.put(t.json());
                prefs().edit().putString("tasks", a.toString()).commit();
            }
        }
        if (removed) notifyChanged();
        return removed;
    }

    public int clearFailed() {
        int count = 0;
        synchronized (LOCK) {
            List<Task> list = all();
            List<Task> kept = new ArrayList<>();
            for (Task t : list) {
                if ("failed".equals(t.status) || "cancelled".equals(t.status)) count++;
                else kept.add(t);
            }
            if (count > 0) {
                JSONArray a = new JSONArray();
                for (Task t : kept) a.put(t.json());
                prefs().edit().putString("tasks", a.toString()).commit();
            }
        }
        if (count > 0) notifyChanged();
        return count;
    }

    public List<Task> search(String query, String filter) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Task> out = new ArrayList<>();
        for (Task t : all()) {
            if ("下载中".equals(filter) && !t.active() && !t.paused()) continue;
            if ("已收藏".equals(filter) && !"done".equals(t.status)) continue;
            if ("图集".equals(filter) && !"GALLERY".equals(t.mediaType)) continue;
            if ("失败".equals(filter) && !"failed".equals(t.status)) continue;
            if (!q.isEmpty()) {
                boolean match = t.title.toLowerCase(Locale.ROOT).contains(q)
                        || t.author.toLowerCase(Locale.ROOT).contains(q)
                        || t.platform.toLowerCase(Locale.ROOT).contains(q)
                        || t.input.toLowerCase(Locale.ROOT).contains(q)
                        || t.tags.stream().anyMatch(tag -> tag.toLowerCase(Locale.ROOT).contains(q));
                if (!match) continue;
            }
            out.add(t);
        }
        return out;
    }

    public void clearFinished() {
        synchronized (LOCK) {
            JSONArray a = new JSONArray();
            for (Task t : all()) if (t.active() || t.paused()) a.put(t.json());
            prefs().edit().putString("tasks", a.toString()).commit();
        }
        notifyChanged();
    }

    public void recover() {
        for (Task t : all()) {
            if (t.active()) {
                t.status = "failed";
                t.detail = t.resumable ? "上次下载中断，可断点续传（点重试）" : "上次任务被系统中断，点重试继续";
                t.errorCode = "INTERRUPTED";
                save(t);
            }
        }
    }

    private void notifyChanged() {
        context.sendBroadcast(new Intent(CHANGED).setPackage(context.getPackageName()));
    }
}
