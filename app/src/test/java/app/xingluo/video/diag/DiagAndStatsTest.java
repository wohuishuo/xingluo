package app.xingluo.video.diag;

import org.junit.Test;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import app.xingluo.video.stats.ActivityStats;
import static org.junit.Assert.*;

public class DiagAndStatsTest {

    @Test
    public void updateCheckerSemanticVersionComparison() {
        assertTrue(UpdateChecker.isNewer("v2.1.0", "2.0.0"));
        assertTrue(UpdateChecker.isNewer("2.10.0", "2.2.0"));
        assertTrue(UpdateChecker.isNewer("v2.1", "2.0.9"));
        assertTrue(UpdateChecker.isNewer("2.1.0", "2.1.0-beta.1"));
        assertTrue(UpdateChecker.isNewer("2.1.1-alpha", "2.1.0"));

        assertFalse(UpdateChecker.isNewer("v2.0.0", "2.0.0"));
        assertFalse(UpdateChecker.isNewer("2.0.0", "2.0.1"));
        assertFalse(UpdateChecker.isNewer("v2.1.0-beta.1", "2.1.0"));
        assertFalse(UpdateChecker.isNewer("", "2.0.0"));
        assertFalse(UpdateChecker.isNewer(null, "2.0.0"));
    }

    @Test
    public void updateCheckerParsesApiAndRedirectFallback() throws Exception {
        String json = """
                {
                  "tag_name": "v2.2.0",
                  "name": "星落 v2.2.0",
                  "body": "支持网络诊断与足迹热力图",
                  "html_url": "https://github.com/wohuishuo/xingluo/releases/tag/v2.2.0",
                  "assets": [
                    {
                      "name": "xingluo-v2.2.0-release.apk",
                      "size": 12345678,
                      "browser_download_url": "https://github.com/wohuishuo/xingluo/releases/download/v2.2.0/xingluo-v2.2.0-release.apk"
                    }
                  ]
                }
                """;
        UpdateChecker.UpdateResult direct = UpdateChecker.parseReleaseJson(json, "2.0.0", "GitHub 官方 API", false, "");
        assertTrue(direct.success);
        assertTrue(direct.updateAvailable);
        assertEquals("2.2.0", direct.latestVersion);
        assertEquals("v2.2.0", direct.tagName);
        assertEquals(12345678L, direct.assetSizeBytes);
        assertFalse(direct.fromCommunityMirror);
        assertTrue(direct.downloadUrl.endsWith(".apk"));

        UpdateChecker.UpdateResult mirror = UpdateChecker.parseReleaseJson(
                json, "2.0.0", "社区镜像备用通道（gh-proxy.com，非官方源）", true, "https://gh-proxy.com/");
        assertTrue(mirror.fromCommunityMirror);
        assertTrue(mirror.downloadUrl.startsWith("https://gh-proxy.com/https://github.com/"));

        assertEquals("v2.1.5",
                UpdateChecker.parseRedirectTag("https://github.com/wohuishuo/xingluo/releases/tag/v2.1.5?ref=latest"));
    }

    @Test
    public void networkDoctorClassifiesNeedVpn() {
        NetworkDoctor.DiagnosisReport report = NetworkDoctor.classify(
                true, false, 3, 0, List.of(),
                "SocketTimeoutException: connect timed out", "", "https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertEquals(NetworkDoctor.Cause.NEED_VPN, report.cause);
        assertEquals(3, report.cnReachableCount);
        assertEquals(0, report.globalReachableCount);
    }

    @Test
    public void networkDoctorClassifiesVpnBlockingCn() {
        NetworkDoctor.DiagnosisReport report = NetworkDoctor.classify(
                true, true, 0, 4, List.of(),
                "ConnectException: failed to connect", "", "https://www.bilibili.com/video/BV1xx411c7mD");
        assertEquals(NetworkDoctor.Cause.VPN_BLOCKING_CN, report.cause);
        assertTrue(report.vpnActive);
        assertEquals(0, report.cnReachableCount);
        assertEquals(4, report.globalReachableCount);
    }

    @Test
    public void networkDoctorClassifiesPlatformLoginAndOtherCauses() {
        NetworkDoctor.DiagnosisReport login = NetworkDoctor.classify(
                true, false, 3, 4, List.of(),
                "平台访问受限，请点网页识别或登录后重试（412）", "412", "https://www.bilibili.com/video/BV1xx411c7mD");
        assertEquals(NetworkDoctor.Cause.PLATFORM_LOGIN_REQUIRED, login.cause);

        NetworkDoctor.DiagnosisReport offline = NetworkDoctor.classify(
                false, false, 0, 0, List.of(), "", "", "");
        assertEquals(NetworkDoctor.Cause.NO_INTERNET, offline.cause);

        NetworkDoctor.DiagnosisReport rate = NetworkDoctor.classify(
                true, false, 3, 4, List.of(), "HTTP 429 Too Many Requests", "429", "https://www.pixiv.net/artworks/1");
        assertEquals(NetworkDoctor.Cause.RATE_LIMITED, rate.cause);

        NetworkDoctor.DiagnosisReport expired = NetworkDoctor.classify(
                true, false, 3, 4, List.of(), "分享链接已过期或失效", "410", "https://v.douyin.com/abc/");
        assertEquals(NetworkDoctor.Cause.LINK_EXPIRED, expired.cause);

        NetworkDoctor.DiagnosisReport ok = NetworkDoctor.classify(
                true, false, 3, 4, List.of(), "", "", "");
        assertEquals(NetworkDoctor.Cause.OK, ok.cause);
    }

    @Test
    public void activityStatsStartsEmptyAndRoundTripsJson() {
        ActivityStats.StatsStore empty = new ActivityStats.StatsStore();
        assertTrue(empty.all().isEmpty());
        ActivityStats.Summary s = empty.summarize();
        assertEquals(0, s.totalDownloads);
        assertEquals(0L, s.totalBytes);
        assertEquals(0, s.currentStreakDays);
        assertEquals(0, s.bestStreakDays);
        assertEquals(112, s.heatmap112Days.length);
        assertEquals(24, s.hourlyBuckets.length);
        assertTrue(s.topPlatforms.isEmpty());
        assertTrue(s.topAuthors.isEmpty());
        assertTrue(s.topKeywords.isEmpty());

        empty.record(1700000000000L, "哔哩哔哩", "VIDEO", "UP主A", List.of("MMD", "4K"), 1024L, "1080p", 1);
        ActivityStats.StatsStore restored = ActivityStats.StatsStore.fromJson(empty.toJson());
        assertEquals(1, restored.all().size());
        assertEquals("UP主A", restored.all().get(0).author);
        assertEquals(List.of("MMD", "4K"), restored.all().get(0).tags);
    }

    @Test
    public void activityStatsHeatmapStreaksPersonaAndTops() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        ZonedDateTime now = ZonedDateTime.of(2026, 11, 28, 23, 30, 0, 0, zone);
        long nowMs = now.toInstant().toEpochMilli();

        ActivityStats.StatsStore store = new ActivityStats.StatsStore();
        // Current 3-day streak ending today (days ago: 0, 1, 2) at 23:xx
        store.record(now.toInstant().toEpochMilli(), "哔哩哔哩", "VIDEO", "画师A", List.of("动画", "日常"), 1000L, "1080p", 1);
        store.record(now.minusHours(1).toInstant().toEpochMilli(), "哔哩哔哩", "VIDEO", "画师A", List.of("动画"), 2000L, "1080p", 1);
        store.record(now.minusDays(1).toInstant().toEpochMilli(), "Pixiv", "GALLERY", "画师B", List.of("插画", "动画"), 3000L, "原图", 5);
        store.record(now.minusDays(2).toInstant().toEpochMilli(), "YouTube", "VIDEO", "画师C", List.of("音乐"), 4000L, "4K", 1);

        // Older 5-day streak 10..14 days ago (bestStreakDays should be 5)
        for (int d = 10; d <= 14; d++) {
            store.record(now.minusDays(d).withHour(23).toInstant().toEpochMilli(),
                    "哔哩哔哩", "VIDEO", "画师" + d, List.of("tag" + d), 500L, "720p", 1);
        }

        // Oldest visible cell (111 days ago -> index 0) and out-of-window (112 days ago -> excluded from heatmap)
        store.record(now.minusDays(111).toInstant().toEpochMilli(), "抖音", "VIDEO", "画师D", List.of("舞见"), 100L, "1080p", 1);
        store.record(now.minusDays(112).toInstant().toEpochMilli(), "抖音", "VIDEO", "画师E", List.of("旧档"), 200L, "1080p", 1);

        ActivityStats.Summary summary = store.summarize(nowMs, zone);
        assertEquals(11, summary.totalDownloads);
        assertEquals(1000 + 2000 + 3000 + 4000 + 5 * 500 + 100 + 200L, summary.totalBytes);
        assertEquals(2, summary.heatmap112Days[111]); // today
        assertEquals(1, summary.heatmap112Days[110]); // yesterday
        assertEquals(1, summary.heatmap112Days[109]); // 2 days ago
        assertEquals(1, summary.heatmap112Days[0]);   // 111 days ago
        assertEquals(3, summary.currentStreakDays);
        assertEquals(5, summary.bestStreakDays);
        assertEquals(23, summary.peakHour);
        assertEquals("23:00 - 23:59", summary.peakHourLabel);
        assertEquals("深夜收藏家 ✦", summary.personaTag);

        assertEquals("哔哩哔哩", summary.topPlatforms.get(0).name);
        assertEquals(7, summary.topPlatforms.get(0).count);
        assertEquals(7.0 / 11.0, summary.topPlatforms.get(0).ratio, 1e-6);

        // Top authors capped at 5, sorted by count descending
        assertEquals(5, summary.topAuthors.size());
        assertEquals("画师A", summary.topAuthors.get(0).name);
        assertEquals(2, summary.topAuthors.get(0).count);

        // Top keywords capped at 12
        ActivityStats.StatsStore tagStore = new ActivityStats.StatsStore();
        List<String> manyTags = new ArrayList<>();
        for (int i = 1; i <= 15; i++) manyTags.add("kw" + i);
        tagStore.record(nowMs, "Pixiv", "GALLERY", "Artist", manyTags, 100L, "原图", 1);
        tagStore.record(nowMs, "Pixiv", "GALLERY", "Artist", List.of("kw15", "kw14"), 100L, "原图", 1);
        ActivityStats.Summary tagSummary = tagStore.summarize(nowMs, zone);
        assertEquals(12, tagSummary.topKeywords.size());
        assertEquals("kw14", tagSummary.topKeywords.get(0).name);
        assertEquals(2, tagSummary.topKeywords.get(0).count);
        assertEquals("kw15", tagSummary.topKeywords.get(1).name);

        // Midday persona and 7-day full-attendance persona
        ActivityStats.StatsStore midday = new ActivityStats.StatsStore();
        midday.record(now.withHour(12).toInstant().toEpochMilli(), "抖音", "VIDEO", "A", List.of(), 100L, "", 1);
        assertEquals("午间摸鱼党", midday.summarize(nowMs, zone).personaTag);

        ActivityStats.StatsStore streak7 = new ActivityStats.StatsStore();
        for (int d = 0; d < 7; d++) {
            streak7.record(now.minusDays(d).withHour(23).toInstant().toEpochMilli(), "哔哩哔哩", "VIDEO", "A", List.of(), 100L, "", 1);
        }
        assertEquals("全勤追更人", streak7.summarize(nowMs, zone).personaTag);
    }
}
