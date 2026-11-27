package app.xingluo.video.stats;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Local-only download activity recorder and analytics aggregator stored in SharedPreferences("xingluo_stats").
 */
public final class ActivityStats {
    public static final String PREFS_NAME = "xingluo_stats";
    public static final int HEATMAP_DAYS = 112;
    private static final String KEY_EVENTS = "events";
    private static final Object LOCK = new Object();

    public static final class DownloadEvent {
        public final long timestampMs;
        public final String platform;
        public final String mediaType;
        public final String author;
        public final List<String> tags;
        public final long bytes;
        public final String resolution;
        public final int itemCount;

        public DownloadEvent(
                long timestampMs,
                String platform,
                String mediaType,
                String author,
                List<String> tags,
                long bytes,
                String resolution,
                int itemCount) {
            this.timestampMs = timestampMs;
            this.platform = platform == null ? "" : platform.trim();
            this.mediaType = mediaType == null ? "" : mediaType.trim();
            this.author = author == null ? "" : author.trim();
            this.tags = cleanTags(tags);
            this.bytes = Math.max(0L, bytes);
            this.resolution = resolution == null ? "" : resolution.trim();
            this.itemCount = Math.max(1, itemCount);
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("timestampMs", timestampMs);
                o.put("platform", platform);
                o.put("mediaType", mediaType);
                o.put("author", author);
                o.put("bytes", bytes);
                o.put("resolution", resolution);
                o.put("itemCount", itemCount);
                JSONArray arr = new JSONArray();
                for (String t : tags) arr.put(t);
                o.put("tags", arr);
            } catch (Exception ignored) {}
            return o;
        }

        static DownloadEvent fromJson(JSONObject o) {
            List<String> tagList = new ArrayList<>();
            JSONArray arr = o.optJSONArray("tags");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String s = arr.optString(i, "");
                    if (!s.isBlank()) tagList.add(s);
                }
            }
            return new DownloadEvent(
                    o.optLong("timestampMs", 0L),
                    o.optString("platform", ""),
                    o.optString("mediaType", ""),
                    o.optString("author", ""),
                    tagList,
                    o.optLong("bytes", 0L),
                    o.optString("resolution", ""),
                    o.optInt("itemCount", 1)
            );
        }

        private static List<String> cleanTags(List<String> raw) {
            if (raw == null || raw.isEmpty()) return List.of();
            Set<String> dedup = new LinkedHashSet<>();
            for (String s : raw) {
                if (s != null) {
                    String t = s.trim();
                    if (!t.isEmpty()) dedup.add(t);
                }
            }
            return List.copyOf(dedup);
        }
    }

    public static final class PlatformStat {
        public final String name;
        public final int count;
        public final double ratio;

        public PlatformStat(String name, int count, double ratio) {
            this.name = name;
            this.count = count;
            this.ratio = ratio;
        }
    }

    public static final class NamedCount {
        public final String name;
        public final int count;

        public NamedCount(String name, int count) {
            this.name = name;
            this.count = count;
        }
    }

    public static final class Summary {
        public final int[] heatmap112Days;
        public final int totalDownloads;
        public final long totalBytes;
        public final int currentStreakDays;
        public final int bestStreakDays;
        public final int[] hourlyBuckets;
        public final int peakHour;
        public final String peakHourLabel;
        public final String personaTag;
        public final List<PlatformStat> topPlatforms;
        public final List<NamedCount> topAuthors;
        public final List<NamedCount> topKeywords;

        public Summary(
                int[] heatmap112Days,
                int totalDownloads,
                long totalBytes,
                int currentStreakDays,
                int bestStreakDays,
                int[] hourlyBuckets,
                int peakHour,
                String peakHourLabel,
                String personaTag,
                List<PlatformStat> topPlatforms,
                List<NamedCount> topAuthors,
                List<NamedCount> topKeywords) {
            this.heatmap112Days = heatmap112Days;
            this.totalDownloads = totalDownloads;
            this.totalBytes = totalBytes;
            this.currentStreakDays = currentStreakDays;
            this.bestStreakDays = bestStreakDays;
            this.hourlyBuckets = hourlyBuckets;
            this.peakHour = peakHour;
            this.peakHourLabel = peakHourLabel;
            this.personaTag = personaTag;
            this.topPlatforms = Collections.unmodifiableList(topPlatforms);
            this.topAuthors = Collections.unmodifiableList(topAuthors);
            this.topKeywords = Collections.unmodifiableList(topKeywords);
        }
    }

    /** Pure-Java in-memory store with JSON serialization, usable directly in JVM unit tests. */
    public static final class StatsStore {
        private final List<DownloadEvent> events = new ArrayList<>();

        public StatsStore() {}

        public static StatsStore fromJson(String json) {
            StatsStore store = new StatsStore();
            if (json == null || json.isBlank()) return store;
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    if (obj != null) store.events.add(DownloadEvent.fromJson(obj));
                }
            } catch (Exception ignored) {}
            return store;
        }

        public String toJson() {
            JSONArray arr = new JSONArray();
            for (DownloadEvent e : events) arr.put(e.toJson());
            return arr.toString();
        }

        public void record(DownloadEvent event) {
            if (event != null) events.add(event);
        }

        public void record(
                long timestampMs,
                String platform,
                String mediaType,
                String author,
                List<String> tags,
                long bytes,
                String resolution,
                int itemCount) {
            record(new DownloadEvent(timestampMs, platform, mediaType, author, tags, bytes, resolution, itemCount));
        }

        public List<DownloadEvent> all() {
            return Collections.unmodifiableList(new ArrayList<>(events));
        }

        public void clear() {
            events.clear();
        }

        public Summary summarize() {
            return summarize(System.currentTimeMillis(), ZoneId.systemDefault());
        }

        public Summary summarize(long nowMs, ZoneId zone) {
            ZoneId effectiveZone = zone == null ? ZoneId.systemDefault() : zone;
            LocalDate today = Instant.ofEpochMilli(nowMs).atZone(effectiveZone).toLocalDate();
            LocalDate gridStart = today.minusDays(HEATMAP_DAYS - 1L);

            int[] heatmap = new int[HEATMAP_DAYS];
            int[] hourly = new int[24];
            long totalBytes = 0L;
            TreeSet<LocalDate> activeDays = new TreeSet<>();
            Map<String, Integer> platformCounts = new HashMap<>();
            Map<String, Integer> authorCounts = new HashMap<>();
            Map<String, Integer> keywordCounts = new HashMap<>();

            for (DownloadEvent e : events) {
                totalBytes += Math.max(0L, e.bytes);
                ZonedDateTime zdt = Instant.ofEpochMilli(e.timestampMs).atZone(effectiveZone);
                LocalDate day = zdt.toLocalDate();
                activeDays.add(day);

                int hour = zdt.getHour();
                if (hour >= 0 && hour < 24) hourly[hour]++;

                long offset = day.toEpochDay() - gridStart.toEpochDay();
                if (offset >= 0 && offset < HEATMAP_DAYS) {
                    heatmap[(int) offset]++;
                }

                String plat = e.platform.isEmpty() ? "网页媒体" : e.platform;
                platformCounts.merge(plat, 1, Integer::sum);

                if (!e.author.isEmpty()) {
                    authorCounts.merge(e.author, 1, Integer::sum);
                }

                for (String tag : e.tags) {
                    if (!tag.isEmpty()) keywordCounts.merge(tag, 1, Integer::sum);
                }
            }

            int total = events.size();
            int currentStreak = computeCurrentStreak(activeDays, today);
            int bestStreak = computeBestStreak(activeDays);
            int peakHour = computePeakHour(hourly, total);
            String peakHourLabel = peakHour < 0
                    ? "暂无记录"
                    : String.format(Locale.CHINA, "%02d:00 - %02d:59", peakHour, peakHour);
            String persona = computePersona(total, currentStreak, peakHour);

            List<PlatformStat> topPlatforms = buildPlatformStats(platformCounts, total);
            List<NamedCount> topAuthors = topNamedCounts(authorCounts, 5);
            List<NamedCount> topKeywords = topNamedCounts(keywordCounts, 12);

            return new Summary(
                    heatmap,
                    total,
                    totalBytes,
                    currentStreak,
                    bestStreak,
                    hourly,
                    peakHour,
                    peakHourLabel,
                    persona,
                    topPlatforms,
                    topAuthors,
                    topKeywords
            );
        }
    }

    private final SharedPreferences prefs;

    public ActivityStats(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void record(
            long timestampMs,
            String platform,
            String mediaType,
            String author,
            List<String> tags,
            long bytes,
            String resolution,
            int itemCount) {
        record(new DownloadEvent(timestampMs, platform, mediaType, author, tags, bytes, resolution, itemCount));
    }

    public void record(DownloadEvent event) {
        if (event == null) return;
        synchronized (LOCK) {
            StatsStore store = loadStoreLocked();
            store.record(event);
            prefs.edit().putString(KEY_EVENTS, store.toJson()).apply();
        }
    }

    public List<DownloadEvent> all() {
        synchronized (LOCK) {
            return loadStoreLocked().all();
        }
    }

    public void clear() {
        synchronized (LOCK) {
            prefs.edit().remove(KEY_EVENTS).apply();
        }
    }

    public Summary summarize() {
        synchronized (LOCK) {
            return loadStoreLocked().summarize();
        }
    }

    public Summary summarize(long nowMs, ZoneId zone) {
        synchronized (LOCK) {
            return loadStoreLocked().summarize(nowMs, zone);
        }
    }

    private StatsStore loadStoreLocked() {
        return StatsStore.fromJson(prefs.getString(KEY_EVENTS, "[]"));
    }

    private static int computeCurrentStreak(TreeSet<LocalDate> activeDays, LocalDate today) {
        if (activeDays.isEmpty()) return 0;
        LocalDate cursor = activeDays.contains(today)
                ? today
                : (activeDays.contains(today.minusDays(1)) ? today.minusDays(1) : null);
        if (cursor == null) return 0;
        int streak = 0;
        while (activeDays.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    private static int computeBestStreak(TreeSet<LocalDate> activeDays) {
        if (activeDays.isEmpty()) return 0;
        int best = 0;
        int run = 0;
        LocalDate prev = null;
        for (LocalDate d : activeDays) {
            if (prev != null && d.equals(prev.plusDays(1))) {
                run++;
            } else {
                run = 1;
            }
            if (run > best) best = run;
            prev = d;
        }
        return best;
    }

    private static int computePeakHour(int[] hourly, int totalDownloads) {
        if (totalDownloads <= 0) return -1;
        int bestHour = 0;
        int bestCount = -1;
        for (int h = 0; h < 24; h++) {
            if (hourly[h] > bestCount) {
                bestCount = hourly[h];
                bestHour = h;
            }
        }
        return bestHour;
    }

    private static String computePersona(int totalDownloads, int currentStreak, int peakHour) {
        if (totalDownloads <= 0) return "星落初遇 · 暂无收藏";
        if (currentStreak >= 7) return "全勤追更人";
        if (peakHour >= 22 || peakHour <= 4) return "深夜收藏家 ✦";
        if (peakHour >= 11 && peakHour <= 14) return "午间摸鱼党";
        if (peakHour >= 5 && peakHour <= 10) return "清晨拾光者";
        if (peakHour >= 15 && peakHour <= 18) return "日落补档员";
        return "晚风追番人";
    }

    private static List<PlatformStat> buildPlatformStats(Map<String, Integer> counts, int total) {
        if (counts.isEmpty() || total <= 0) return List.of();
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey));
        List<PlatformStat> out = new ArrayList<>(entries.size());
        for (Map.Entry<String, Integer> e : entries) {
            out.add(new PlatformStat(e.getKey(), e.getValue(), (double) e.getValue() / (double) total));
        }
        return out;
    }

    private static List<NamedCount> topNamedCounts(Map<String, Integer> counts, int limit) {
        if (counts.isEmpty()) return List.of();
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey));
        int cap = Math.min(limit, entries.size());
        List<NamedCount> out = new ArrayList<>(cap);
        for (int i = 0; i < cap; i++) {
            Map.Entry<String, Integer> e = entries.get(i);
            out.add(new NamedCount(e.getKey(), e.getValue()));
        }
        return out;
    }
}
