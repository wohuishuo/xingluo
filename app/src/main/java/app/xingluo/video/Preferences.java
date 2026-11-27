package app.xingluo.video;

import android.content.*;
import android.net.Uri;

public final class Preferences {
    private final SharedPreferences prefs;

    public Preferences(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences("preferences", Context.MODE_PRIVATE);
    }

    public int quality() { return prefs.getInt("quality", 1080); }

    public void quality(int height) {
        if (height != 2160 && height != 1080 && height != 720 && height != 480 && height != 360) {
            throw new IllegalArgumentException("画质无效");
        }
        prefs.edit().putInt("quality", height).apply();
    }

    public String destination() { return prefs.getString("destination", "gallery"); }
    public String folder() { return prefs.getString("folder", ""); }

    public void destination(String value) {
        if (!value.equals("gallery") && !value.equals("downloads") && !value.equals("custom")) {
            throw new IllegalArgumentException("保存位置无效");
        }
        prefs.edit().putString("destination", value).apply();
    }

    public void folder(Uri uri, String label) {
        prefs.edit()
                .putString("folder", uri.toString())
                .putString("folderLabel", label)
                .putString("destination", "custom")
                .apply();
    }

    public String locationLabel() {
        return switch (destination()) {
            case "downloads" -> "下载文件夹 · Download / 星落";
            case "custom" -> "自选文件夹 · " + prefs.getString("folderLabel", "已授权文件夹");
            default -> "系统相册 · Movies / 星落";
        };
    }

    public int maxConcurrentTasks() { return Math.max(1, Math.min(5, prefs.getInt("maxConcurrent", 3))); }
    public void maxConcurrentTasks(int v) { prefs.edit().putInt("maxConcurrent", Math.max(1, Math.min(5, v))).apply(); }

    public int threadsPerTask() {
        int v = prefs.getInt("threadsPerTask", 4);
        return (v == 1 || v == 2 || v == 4 || v == 8) ? v : 4;
    }
    public void threadsPerTask(int v) {
        int clamped = v >= 8 ? 8 : v >= 4 ? 4 : v >= 2 ? 2 : 1;
        prefs.edit().putInt("threadsPerTask", clamped).apply();
    }

    public boolean preferHevc() { return prefs.getBoolean("preferHevc", true); }
    public void preferHevc(boolean v) { prefs.edit().putBoolean("preferHevc", v).apply(); }

    public boolean audioOnly() { return prefs.getBoolean("audioOnly", false); }
    public void audioOnly(boolean v) { prefs.edit().putBoolean("audioOnly", v).apply(); }

    public boolean autoClipboard() { return prefs.getBoolean("autoClipboard", true); }
    public void autoClipboard(boolean v) { prefs.edit().putBoolean("autoClipboard", v).apply(); }

    public boolean autoResume() { return prefs.getBoolean("autoResume", true); }
    public void autoResume(boolean v) { prefs.edit().putBoolean("autoResume", v).apply(); }

    public int pixivMinPx() { return Math.max(0, prefs.getInt("pixivMinPx", 400)); }
    public void pixivMinPx(int v) { prefs.edit().putInt("pixivMinPx", Math.max(0, v)).apply(); }

    public boolean pixivSkipBg() { return prefs.getBoolean("pixivSkipBg", true); }
    public void pixivSkipBg(boolean v) { prefs.edit().putBoolean("pixivSkipBg", v).apply(); }

    public boolean pixivSkipAi() { return prefs.getBoolean("pixivSkipAi", false); }
    public void pixivSkipAi(boolean v) { prefs.edit().putBoolean("pixivSkipAi", v).apply(); }

    public int pixivMaxBatch() { return Math.max(5, Math.min(100, prefs.getInt("pixivMaxBatch", 30))); }
    public void pixivMaxBatch(int v) { prefs.edit().putInt("pixivMaxBatch", Math.max(5, Math.min(100, v))).apply(); }

    public String lastClipboardHash() { return prefs.getString("lastClipboardHash", ""); }
    public void lastClipboardHash(String hash) { prefs.edit().putString("lastClipboardHash", hash == null ? "" : hash).apply(); }

    public long lastUpdateCheck() { return prefs.getLong("lastUpdateCheck", 0L); }
    public void lastUpdateCheck(long ts) { prefs.edit().putLong("lastUpdateCheck", ts).apply(); }

    public int mascotAffection() { return prefs.getInt("mascotAffection", 0); }
    public int bumpMascotAffection() {
        int next = mascotAffection() + 1;
        prefs.edit().putInt("mascotAffection", next).apply();
        return next;
    }

    public String preferredLanguage() { return prefs.getString("lang", "auto"); }
    public void preferredLanguage(String v) { prefs.edit().putString("lang", v == null ? "auto" : v).apply(); }
}
