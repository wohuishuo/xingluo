package app.xingluo.video;

import app.xingluo.video.engine.HttpClient;
import app.xingluo.video.engine.torrent.TorrentMeta;
import app.xingluo.video.model.MediaBundle;
import app.xingluo.video.platform.cloud.BaiduNetdiskExtractor;
import app.xingluo.video.platform.cloud.LanzouExtractor;
import app.xingluo.video.platform.cloud.QuarkExtractor;
import app.xingluo.video.platform.cn.BilibiliExtractor;
import app.xingluo.video.platform.cn.DouyinExtractor;
import app.xingluo.video.platform.cn.KuaishouExtractor;
import app.xingluo.video.platform.cn.XiaohongshuExtractor;
import app.xingluo.video.platform.global.AiMediaExtractor;
import app.xingluo.video.platform.global.GlobalMediaExtractor;
import app.xingluo.video.platform.global.PixivExtractor;
import org.json.*;
import java.util.*;
import java.util.regex.*;

public final class Resolver {

    /** Unified v2.0 extraction router returning rich MediaBundle across all platforms and formats. */
    public static MediaBundle resolveBundle(String rawInput, Preferences prefs, Net.Cancellation cancel) throws Exception {
        String extracted = LinkInput.extract(rawInput);
        LinkInput.Platform platform = LinkInput.platform(extracted);
        int quality = prefs == null ? 1080 : prefs.quality();
        boolean preferHevc = prefs == null || prefs.preferHevc();
        boolean audioOnly = prefs != null && prefs.audioOnly();

        return switch (platform) {
            case TORRENT -> resolveTorrentInput(extracted, cancel);
            case LIVE -> resolveLiveInput(extracted, quality, preferHevc, audioOnly, cancel);
            case BILIBILI -> BilibiliExtractor.extract(extracted, quality, preferHevc, audioOnly, cancel);
            case DOUYIN -> DouyinExtractor.extract(extracted, quality, preferHevc, audioOnly, cancel);
            case KUAISHOU -> KuaishouExtractor.extract(extracted, quality, preferHevc, audioOnly, cancel);
            case XIAOHONGSHU -> XiaohongshuExtractor.extract(extracted, quality, preferHevc, audioOnly, cancel);
            case PIXIV -> PixivExtractor.extract(extracted, prefs, cancel);
            case QUARK -> QuarkExtractor.extract(rawInput, cancel);
            case BAIDU_PAN -> BaiduNetdiskExtractor.extract(rawInput, cancel);
            case LANZOU -> LanzouExtractor.extract(rawInput, cancel);
            case AI_MEDIA -> AiMediaExtractor.extract(extracted, prefs, cancel);
            case PEERTUBE, YOUTUBE, TWITTER, TIKTOK, REDDIT, VIMEO, WEIBO, ACFUN ->
                    GlobalMediaExtractor.extract(extracted, quality, audioOnly, preferHevc, cancel);
            case CHANNELS -> resolveChannelsOrGeneric(extracted, quality, audioOnly, preferHevc, cancel);
            default -> {
                // Follow redirects on unknown short links before routing
                if (extracted.toLowerCase(Locale.ROOT).matches(".*\\.(mp4|m4a|mp3|mkv|webm)(?:[?#].*)?")) {
                    yield MediaBundle.builder(MediaBundle.Type.VIDEO)
                            .title("直接媒体文件")
                            .platform("直链")
                            .videoUrl(extracted)
                            .referer(extracted)
                            .build();
                }
                if (extracted.toLowerCase(Locale.ROOT).matches(".*\\.(m3u8|flv)(?:[?#].*)?")) {
                    yield resolveLiveInput(extracted, quality, preferHevc, audioOnly, cancel);
                }
                String resolved = HttpClient.resolveRedirectUrl(extracted, Map.of(), cancel);
                LinkInput.Platform after = LinkInput.platform(resolved);
                if (after != LinkInput.Platform.WEB) {
                    yield resolveBundle(resolved, prefs, cancel);
                }
                yield GlobalMediaExtractor.extract(resolved, quality, audioOnly, preferHevc, cancel);
            }
        };
    }

    private static MediaBundle resolveTorrentInput(String uri, Net.Cancellation cancel) throws Exception {
        if (LinkInput.isMagnet(uri)) {
            TorrentMeta meta = TorrentMeta.fromMagnet(uri);
            String title = meta.name != null && !meta.name.isBlank() ? meta.name : "磁力任务 " + meta.infoHashHex.substring(0, 8);
            return MediaBundle.builder(MediaBundle.Type.TORRENT)
                    .title(title)
                    .platform("磁力/种子")
                    .torrentUriOrPath(uri)
                    .estimatedBytes(meta.totalSize())
                    .build();
        }
        return MediaBundle.builder(MediaBundle.Type.TORRENT)
                .title("种子任务")
                .platform("磁力/种子")
                .torrentUriOrPath(uri)
                .build();
    }

    private static MediaBundle resolveLiveInput(String url, int quality, boolean preferHevc, boolean audioOnly, Net.Cancellation cancel) throws Exception {
        String low = url.toLowerCase(Locale.ROOT);
        if (low.contains("live.bilibili.com")) return BilibiliExtractor.extract(url, quality, preferHevc, audioOnly, cancel);
        if (low.contains("live.douyin.com")) return DouyinExtractor.extract(url, quality, preferHevc, audioOnly, cancel);
        if (low.contains(".m3u8")) {
            // Live vs VOD HLS: LiveRecorder handles rolling, HlsDownloader handles VOD; mark LIVE if "live" in URL else VIDEO HLS
            return MediaBundle.builder(low.contains("live") ? MediaBundle.Type.LIVE : MediaBundle.Type.VIDEO)
                    .title("流媒体视频")
                    .platform("流媒体")
                    .hlsUrl(url)
                    .liveUrl(url)
                    .referer(url)
                    .build();
        }
        return MediaBundle.builder(MediaBundle.Type.LIVE)
                .title("直播录制")
                .platform("直播流")
                .liveUrl(url)
                .referer(url)
                .build();
    }

    private static MediaBundle resolveChannelsOrGeneric(String url, int quality, boolean audioOnly, boolean preferHevc, Net.Cancellation cancel) throws Exception {
        try {
            return GlobalMediaExtractor.extract(url, quality, audioOnly, preferHevc, cancel);
        } catch (Exception e) {
            throw new IllegalArgumentException("视频号可能只有微信内卡片或加密媒体。请使用可访问网页链接，或在微信保存后分享视频文件。");
        }
    }

    // =========================================================================
    // Legacy v1.x public methods preserved for backward test & BrowserActivity compatibility
    // =========================================================================

    public static MediaPlan resolve(String input, Net.Cancellation cancel) throws Exception {
        return resolve(input, cancel, 720);
    }

    public static MediaPlan resolve(String input, Net.Cancellation cancel, int quality) throws Exception {
        String url = LinkInput.extract(input);
        if (url.matches("(?i).*\\.mp4(?:[?#].*)?")) return MediaParser.checkedPlan("星落视频", url, url);
        Exception biliFailure = null;
        if (LinkInput.platform(url) == LinkInput.Platform.BILIBILI) {
            try {
                String watch = Net.biliWatchLocation(url, cancel);
                if (LinkInput.isBiliWatchUrl(watch)) return bili(watch, cancel, false, quality);
            } catch (Exception e) {
                Net.check(cancel);
                biliFailure = e;
            }
        }
        String hostRef = LinkInput.platform(url) == LinkInput.Platform.BILIBILI ? "https://www.bilibili.com/" : url;
        Net.Page page = Net.page(url, hostRef, cancel);
        if (page.type().startsWith("video/") || page.type().contains("octet-stream")) {
            return MediaParser.checkedPlan("星落视频", page.url(), url);
        }
        LinkInput.Platform platform = LinkInput.platform(page.url());
        if (platform == LinkInput.Platform.BILIBILI) {
            if (biliFailure == null && LinkInput.isBiliWatchUrl(page.url())) {
                try { return bili(page.url(), cancel, false, quality); }
                catch (Exception e) { Net.check(cancel); biliFailure = e; }
            }
            try { return MediaParser.webpage(page.body(), page.url(), quality); }
            catch (IllegalArgumentException e) { if (biliFailure != null) throw biliFailure; throw e; }
        }
        if (platform == LinkInput.Platform.DOUYIN) {
            try { return MediaParser.webpage(page.body(), page.url(), quality); } catch (IllegalArgumentException ignored) {}
            Matcher m = Pattern.compile("(?:video/|modal_id=|aweme_id=)(\\d{10,})").matcher(page.url());
            if (m.find()) {
                String id = m.group(1);
                try {
                    Net.Page detail = Net.page("https://www.douyin.com/aweme/v1/web/aweme/detail/?aweme_id=" + id, "https://www.douyin.com/", cancel);
                    return MediaParser.douyin(new JSONObject(detail.body()), page.url(), quality);
                } catch (Exception ignored) { Net.check(cancel); }
                try {
                    Net.Page share = Net.page("https://www.iesdouyin.com/share/video/" + id + "/", page.url(), cancel);
                    return MediaParser.webpage(share.body(), share.url(), quality);
                } catch (Exception ignored) { Net.check(cancel); }
            }
            throw new IllegalArgumentException("抖音暂未返回视频。请点网页识别，完成验证或播放后再试。");
        }
        String peerApi = PeerTube.apiUrl(page.url());
        if (peerApi != null) {
            JSONObject data = null;
            try { data = new JSONObject(Net.page(peerApi, page.url(), cancel).body()); }
            catch (Exception ignored) { Net.check(cancel); }
            if (data != null && PeerTube.describesVideo(data)) return PeerTube.select(data, page.url(), quality);
        }
        return MediaParser.webpage(page.body(), page.url(), quality);
    }

    public static MediaPlan alternative(String input, Net.Cancellation cancel) throws Exception {
        return alternative(input, cancel, 720);
    }

    public static MediaPlan alternative(String input, Net.Cancellation cancel, int quality) throws Exception {
        return biliAlternatives(input, cancel, quality).get(0);
    }

    static List<MediaPlan> biliAlternatives(String input, Net.Cancellation cancel, int quality) throws Exception {
        String url = LinkInput.extract(input);
        if (LinkInput.platform(url) != LinkInput.Platform.BILIBILI) throw new IllegalArgumentException("没有其他视频格式");
        if (!LinkInput.isBiliWatchUrl(url)) url = Net.biliWatchLocation(url, cancel);
        return biliPlans(url, cancel, true, quality);
    }

    private static MediaPlan bili(String url, Net.Cancellation cancel, boolean mp4Only, int quality) throws Exception {
        return biliPlans(url, cancel, mp4Only, quality).get(0);
    }

    private static List<MediaPlan> biliPlans(String url, Net.Cancellation cancel, boolean mp4Only, int quality) throws Exception {
        Matcher bv = Pattern.compile("(?i)BV[1-9A-HJ-NP-Za-km-z]{10}").matcher(url);
        Matcher av = Pattern.compile("/av(\\d+)").matcher(url);
        String query;
        if (bv.find()) query = "bvid=" + bv.group();
        else if (av.find()) query = "aid=" + av.group(1);
        else throw new IllegalArgumentException("请分享 B 站单个视频的 BV / av 链接");
        JSONObject root = new JSONObject(Net.page("https://api.bilibili.com/x/web-interface/view?" + query, "https://www.bilibili.com/", cancel).body());
        if (root.optInt("code") != 0 || root.optJSONObject("data") == null) {
            throw new IllegalArgumentException("B 站没有返回视频信息，请在网页打开后重试");
        }
        JSONObject data = root.getJSONObject("data");
        JSONArray pages = data.optJSONArray("pages");
        int index = LinkInput.page(url) - 1;
        if (pages == null || index >= pages.length()) throw new IllegalArgumentException("这个分 P 不存在");
        long cid = pages.getJSONObject(index).getLong("cid");
        String title = data.optString("title", "B 站视频") + (pages.length() > 1 ? " · P" + (index + 1) : "");
        Exception failure = null;
        ArrayList<MediaPlan> plans = new ArrayList<>();
        HashSet<String> urls = new HashSet<>();
        int qn = quality >= 1080 ? 80 : quality >= 720 ? 64 : quality >= 480 ? 32 : 16;
        for (String format : biliFormats(qn, mp4Only)) {
            Net.check(cancel);
            try {
                JSONObject play = new JSONObject(Net.page("https://api.bilibili.com/x/player/playurl?" + query + "&cid=" + cid + "&" + format, "https://www.bilibili.com/", cancel).body());
                MediaPlan plan = MediaParser.bilibili(play, title, "https://www.bilibili.com/video/" + data.optString("bvid") + "/", quality);
                if (!mp4Only) return List.of(plan);
                if (urls.add(plan.videoUrl)) plans.add(plan);
            } catch (Exception e) { failure = e; }
        }
        if (plans.isEmpty()) throw failure;
        return plans;
    }

    static String[] biliFormats(int qn, boolean mp4Only) {
        String mp4 = "fnval=0&qn=" + qn + "&high_quality=1";
        String mobileMp4 = mp4 + "&platform=html5";
        if (mp4Only) return new String[]{mp4, mobileMp4};
        return new String[]{"fnval=4048&qn=" + qn + "&fnver=0&fourk=0", mp4, mobileMp4};
    }
}
