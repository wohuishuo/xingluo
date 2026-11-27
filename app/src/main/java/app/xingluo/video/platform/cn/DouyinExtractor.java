package app.xingluo.video.platform.cn;

import app.xingluo.video.LinkInput;
import app.xingluo.video.MediaParser;
import app.xingluo.video.Net;
import app.xingluo.video.Preferences;
import app.xingluo.video.VideoQuality;
import app.xingluo.video.engine.HttpClient;
import app.xingluo.video.model.MediaBundle;
import java.net.URI;
import java.net.URLDecoder;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Douyin extractor supporting v.douyin.com short links, video/note/slides/modal_id,
 * iesdouyin share pages, photo albums (图文 -> GALLERY), watermark-free video (playwm -> play),
 * and live.douyin.com streams.
 */
public final class DouyinExtractor {
    public static final String PLATFORM = "抖音";
    private static final String DEFAULT_REFERER = "https://www.douyin.com/";
    private static final String LIVE_REFERER = "https://live.douyin.com/";

    private static final Pattern ITEM_ID_PATTERN = Pattern.compile(
            "(?:video/|note/|slides/|modal_id=|aweme_id=)(\\d{10,})");
    private static final Pattern LIVE_RID_PATTERN = Pattern.compile(
            "(?i)live\\.douyin\\.com/([^/?#]+)");
    private static final Pattern RENDER_SCRIPT_PATTERN = Pattern.compile(
            "(?is)<script[^>]*id\\s*=\\s*['\"](?:RENDER_DATA|SIGI_STATE|__NEXT_DATA__)['\"][^>]*>(.*?)</script>");

    private DouyinExtractor() {}

    public static boolean supports(String url) {
        if (!LinkInput.isWebUrl(url)) return false;
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            String h = host.toLowerCase(Locale.ROOT);
            return LinkInput.hostIs(h, "douyin.com")
                    || LinkInput.hostIs(h, "iesdouyin.com")
                    || LinkInput.hostIs(h, "live.douyin.com");
        } catch (Exception e) {
            return false;
        }
    }

    public static MediaBundle extract(String url, Net.Cancellation cancel) throws Exception {
        return extract(url, 1080, true, false, cancel);
    }

    public static MediaBundle extract(String url, Preferences prefs, Net.Cancellation cancel) throws Exception {
        int q = prefs != null ? prefs.quality() : 1080;
        boolean hevc = prefs == null || prefs.preferHevc();
        boolean audio = prefs != null && prefs.audioOnly();
        return extract(url, q, hevc, audio, cancel);
    }

    public static MediaBundle extract(String url, int maxQuality, boolean preferHevc, boolean audioOnly,
                                      Net.Cancellation cancel) throws Exception {
        String normalized = LinkInput.extract(url);
        if (isLiveUrl(normalized)) {
            return extractLive(normalized, preferHevc, cancel);
        }

        String resolved = HttpClient.resolveRedirectUrl(normalized, mobileHeaders(DEFAULT_REFERER), cancel);
        if (isLiveUrl(resolved)) {
            return extractLive(resolved, preferHevc, cancel);
        }

        String itemId = extractItemId(resolved);
        if (itemId == null) itemId = extractItemId(normalized);
        boolean isNoteHint = resolved.contains("/note/") || resolved.contains("/slides/")
                || normalized.contains("/note/") || normalized.contains("/slides/");

        // 1. Fetch resolved page HTML (mobile share pages often expose _ROUTER_DATA / RENDER_DATA directly)
        try {
            HttpClient.HttpResult pageResp = HttpClient.get(resolved, mobileHeaders(DEFAULT_REFERER), cancel);
            return parseHtml(pageResp.body, pageResp.finalUrl, maxQuality, preferHevc, audioOnly);
        } catch (Exception ignored) {
            Net.check(cancel);
        }

        if (itemId != null) {
            // 2. Try iesdouyin mobile share page
            String shareType = isNoteHint ? "note" : "video";
            String shareUrl = "https://www.iesdouyin.com/share/" + shareType + "/" + itemId + "/";
            try {
                HttpClient.HttpResult shareResp = HttpClient.get(shareUrl, mobileHeaders(resolved), cancel);
                return parseHtml(shareResp.body, shareResp.finalUrl, maxQuality, preferHevc, audioOnly);
            } catch (Exception ignored) {
                Net.check(cancel);
            }

            // 3. Fallback: public web detail API
            try {
                HttpClient.HttpResult detailResp = HttpClient.get(
                        "https://www.douyin.com/aweme/v1/web/aweme/detail/?aweme_id=" + itemId,
                        desktopHeaders(DEFAULT_REFERER),
                        cancel
                );
                return parseAwemeJson(new JSONObject(detailResp.body), resolved, maxQuality, preferHevc, audioOnly);
            } catch (Exception ignored) {
                Net.check(cancel);
            }
        }

        throw new IllegalArgumentException("抖音暂未返回媒体数据。请点网页识别，完成验证或播放后再试。");
    }

    private static MediaBundle extractLive(String url, boolean preferHevc, Net.Cancellation cancel) throws Exception {
        Matcher m = LIVE_RID_PATTERN.matcher(url);
        if (!m.find()) throw new IllegalArgumentException("未识别到抖音直播间地址");
        String rid = m.group(1);

        try {
            HttpClient.HttpResult apiResp = HttpClient.get(
                    "https://live.douyin.com/webcast/room/web/enter/?aid=6383&app_name=douyin_web&live_id=1&device_platform=web&enter_from=web_live&web_rid=" + rid,
                    desktopHeaders(LIVE_REFERER),
                    cancel
            );
            return parseLiveContent(apiResp.body, url, preferHevc);
        } catch (Exception ignored) {
            Net.check(cancel);
        }

        HttpClient.HttpResult pageResp = HttpClient.get(url, desktopHeaders(LIVE_REFERER), cancel);
        return parseLiveContent(pageResp.body, pageResp.finalUrl, preferHevc);
    }

    public static String extractItemId(String url) {
        if (url == null) return null;
        Matcher m = ITEM_ID_PATTERN.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    public static String toNoWatermarkUrl(String url) {
        if (url == null || url.isBlank()) return url;
        String updated = url.replace("/playwm/", "/play/")
                .replace("/playwm?", "/play?");
        if (updated.contains("watermark=1")) {
            updated = updated.replaceAll("([?&])watermark=1(&?)", "$1")
                    .replaceAll("[?&]$", "");
        }
        return updated;
    }

    public static MediaBundle parseHtml(String html, String pageUrl, int maxQuality,
                                        boolean preferHevc, boolean audioOnly) {
        if (html == null || html.isBlank()) {
            throw new IllegalArgumentException("抖音页面内容为空");
        }
        for (String marker : new String[]{"window._ROUTER_DATA", "window.__INITIAL_STATE__"}) {
            int pos = html.indexOf(marker);
            if (pos < 0) continue;
            int start = html.indexOf('{', pos);
            String json = MediaParser.balancedJson(html, start);
            if (json != null) {
                try {
                    return parseAwemeJson(new JSONObject(json), pageUrl, maxQuality, preferHevc, audioOnly);
                } catch (Exception ignored) {}
            }
        }
        Matcher scripts = RENDER_SCRIPT_PATTERN.matcher(html);
        while (scripts.find()) {
            String raw = scripts.group(1).trim();
            if (raw.isEmpty()) continue;
            try {
                if (raw.startsWith("%")) raw = URLDecoder.decode(raw, "UTF-8");
                return parseAwemeJson(new JSONObject(raw), pageUrl, maxQuality, preferHevc, audioOnly);
            } catch (Exception ignored) {}
        }
        throw new IllegalArgumentException("抖音未返回视频或图文数据，请在网页中打开后重试");
    }

    public static MediaBundle parseAwemeJson(JSONObject root, String pageUrl, int maxQuality,
                                             boolean preferHevc, boolean audioOnly) {
        JSONObject item = findAwemeItem(root, 0);
        if (item == null) {
            throw new IllegalArgumentException("抖音未返回有效作品数据");
        }

        String title = cleanTitle(item.optString("desc", item.optString("title", "抖音作品")));
        if (title.isEmpty()) title = "抖音作品";
        String author = extractAuthor(item);
        List<String> tags = extractTags(item);
        List<String> musicUrls = extractMusicUrls(item);
        Map<String, String> headers = mobileHeaders(pageUrl);

        JSONArray images = item.optJSONArray("images");
        int awemeType = item.optInt("aweme_type", -1);
        boolean isGallery = (images != null && images.length() > 0)
                && (awemeType == 68 || awemeType == 2 || awemeType == -1 || !item.has("video") || item.optJSONObject("video") == null
                || isPhotoOnlyVideo(item.optJSONObject("video")));
        // Note: on Douyin slides/notes (aweme_type == 68 or 2), images[] is authoritative even when a synthetic video object exists.
        if (images != null && images.length() > 0 && (awemeType == 68 || awemeType == 2 || isGallery)) {
            List<MediaBundle.ImageItem> gallery = parseGalleryImages(images, title, tags);
            if (!gallery.isEmpty()) {
                MediaBundle.Builder builder = MediaBundle.builder(MediaBundle.Type.GALLERY)
                        .title(title)
                        .author(author)
                        .platform(PLATFORM)
                        .tags(tags)
                        .referer(pageUrl)
                        .headers(headers)
                        .watermarkFree(true)
                        .images(gallery);
                if (!musicUrls.isEmpty()) builder.audioUrls(musicUrls);
                return builder.build();
            }
        }

        if (audioOnly && !musicUrls.isEmpty()) {
            return MediaBundle.builder(MediaBundle.Type.AUDIO)
                    .title(title)
                    .author(author)
                    .platform(PLATFORM)
                    .tags(tags)
                    .referer(pageUrl)
                    .headers(headers)
                    .watermarkFree(true)
                    .audioUrls(musicUrls)
                    .build();
        }

        JSONObject video = item.optJSONObject("video");
        if (video != null) {
            List<String> videoUrls = selectVideoUrls(video, maxQuality, preferHevc);
            if (!videoUrls.isEmpty()) {
                MediaBundle.Builder builder = MediaBundle.builder(MediaBundle.Type.VIDEO)
                        .title(title)
                        .author(author)
                        .platform(PLATFORM)
                        .tags(tags)
                        .referer(pageUrl)
                        .headers(headers)
                        .watermarkFree(true)
                        .videoUrls(videoUrls);
                if (!musicUrls.isEmpty()) builder.audioUrls(musicUrls);
                return builder.build();
            }
        }

        // Fallback if images exist regardless of aweme_type
        if (images != null && images.length() > 0) {
            List<MediaBundle.ImageItem> gallery = parseGalleryImages(images, title, tags);
            if (!gallery.isEmpty()) {
                MediaBundle.Builder builder = MediaBundle.builder(MediaBundle.Type.GALLERY)
                        .title(title)
                        .author(author)
                        .platform(PLATFORM)
                        .tags(tags)
                        .referer(pageUrl)
                        .headers(headers)
                        .watermarkFree(true)
                        .images(gallery);
                if (!musicUrls.isEmpty()) builder.audioUrls(musicUrls);
                return builder.build();
            }
        }

        throw new IllegalArgumentException("抖音未返回可保存的视频或图文地址");
    }

    public static MediaBundle parseLiveContent(String body, String pageUrl, boolean preferHevc) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("抖音直播间页面为空");
        }
        List<JSONObject> candidateRoots = new ArrayList<>();
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            try { candidateRoots.add(new JSONObject(trimmed)); } catch (Exception ignored) {}
        }
        Matcher scripts = RENDER_SCRIPT_PATTERN.matcher(body);
        while (scripts.find()) {
            String raw = scripts.group(1).trim();
            if (raw.isEmpty()) continue;
            try {
                if (raw.startsWith("%")) raw = URLDecoder.decode(raw, "UTF-8");
                candidateRoots.add(new JSONObject(raw));
            } catch (Exception ignored) {}
        }
        // Scan embedded JSON blobs in Next.js / pace_f payloads
        int idx = 0;
        while ((idx = body.indexOf("stream_url", idx)) >= 0) {
            int brace = body.lastIndexOf('{', idx);
            if (brace >= 0) {
                String unescaped = body.substring(brace, Math.min(body.length(), brace + 65536))
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\");
                String json = MediaParser.balancedJson(unescaped, 0);
                if (json != null) {
                    try { candidateRoots.add(new JSONObject(json)); } catch (Exception ignored) {}
                }
            }
            idx += 10;
        }

        for (JSONObject root : candidateRoots) {
            JSONObject room = findLiveRoom(root, 0);
            if (room == null) continue;
            JSONObject streamUrl = room.optJSONObject("stream_url");
            if (streamUrl == null) continue;

            LinkedHashSet<String> hlsUrls = new LinkedHashSet<>();
            LinkedHashSet<String> flvUrls = new LinkedHashSet<>();
            collectLiveUrls(streamUrl, hlsUrls, flvUrls, preferHevc);

            LinkedHashSet<String> all = new LinkedHashSet<>();
            all.addAll(hlsUrls);
            all.addAll(flvUrls);
            if (all.isEmpty()) continue;

            String title = cleanTitle(room.optString("title", "抖音直播"));
            if (title.isEmpty()) title = "抖音直播";
            JSONObject owner = room.optJSONObject("owner");
            String author = owner != null ? owner.optString("nickname", "").trim() : "";
            String hls = hlsUrls.isEmpty() ? "" : hlsUrls.iterator().next();
            String live = !hls.isEmpty() ? hls : all.iterator().next();

            return MediaBundle.builder(MediaBundle.Type.LIVE)
                    .title(title)
                    .author(author)
                    .platform(PLATFORM)
                    .referer(pageUrl)
                    .headers(desktopHeaders(LIVE_REFERER))
                    .watermarkFree(true)
                    .hlsUrl(hls)
                    .liveUrl(live)
                    .videoUrls(new ArrayList<>(all))
                    .build();
        }
        throw new IllegalArgumentException("未获取到公开抖音直播流，可能已下播或需要验证");
    }

    private static void collectLiveUrls(JSONObject streamUrl, Set<String> hlsUrls, Set<String> flvUrls, boolean preferHevc) {
        JSONObject liveCore = streamUrl.optJSONObject("live_core_sdk_data");
        JSONObject pullData = liveCore != null ? liveCore.optJSONObject("pull_data") : null;
        if (pullData != null) {
            String streamDataRaw = pullData.optString("stream_data", "");
            if (!streamDataRaw.isEmpty() && streamDataRaw.trim().startsWith("{")) {
                try {
                    JSONObject sd = new JSONObject(streamDataRaw);
                    JSONObject data = sd.optJSONObject("data");
                    if (data != null) {
                        for (String qKey : new String[]{"origin", "uhd", "hd", "sd"}) {
                            JSONObject qObj = data.optJSONObject(qKey);
                            JSONObject main = qObj != null ? qObj.optJSONObject("main") : null;
                            if (main != null) {
                                String hls = main.optString("hls", "");
                                String flv = main.optString("flv", "");
                                if (LinkInput.isWebUrl(hls)) hlsUrls.add(hls);
                                if (LinkInput.isWebUrl(flv)) flvUrls.add(flv);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }

        for (String mapKey : new String[]{"hls_pull_url_map", "flv_pull_url"}) {
            JSONObject map = streamUrl.optJSONObject(mapKey);
            if (map == null) continue;
            for (String qKey : new String[]{"FULL_HD1", "ORIGION", "ORIGIN", "HD1", "SD1", "SD2"}) {
                String u = map.optString(qKey, "");
                if (LinkInput.isWebUrl(u)) {
                    if (mapKey.startsWith("hls") || u.contains(".m3u8")) hlsUrls.add(u);
                    else flvUrls.add(u);
                }
            }
            Iterator<String> keys = map.keys();
            while (keys.hasNext()) {
                String u = map.optString(keys.next(), "");
                if (LinkInput.isWebUrl(u)) {
                    if (mapKey.startsWith("hls") || u.contains(".m3u8")) hlsUrls.add(u);
                    else flvUrls.add(u);
                }
            }
        }

        String hlsSingle = streamUrl.optString("hls_pull_url", "");
        if (LinkInput.isWebUrl(hlsSingle)) hlsUrls.add(hlsSingle);
        JSONObject flvMap = streamUrl.optJSONObject("flv_pull_url");
        if (flvMap == null) {
            String flvSingle = streamUrl.optString("flv_pull_url", "");
            if (LinkInput.isWebUrl(flvSingle)) flvUrls.add(flvSingle);
        }
    }

    private static List<MediaBundle.ImageItem> parseGalleryImages(JSONArray images, String title, List<String> tags) {
        List<MediaBundle.ImageItem> result = new ArrayList<>();
        String baseName = LinkInput.safeName(title);
        for (int i = 0; i < images.length(); i++) {
            JSONObject img = images.optJSONObject(i);
            if (img == null) continue;
            List<String> candidates = new ArrayList<>();
            collectImageUrls(img.optJSONArray("download_url_list"), candidates);
            collectImageUrls(img.optJSONArray("url_list"), candidates);
            if (candidates.isEmpty()) continue;

            // Prefer non-webp high-res image first while keeping all candidates in backupUrls
            String best = candidates.get(0);
            for (String u : candidates) {
                String low = u.toLowerCase(Locale.ROOT);
                if (!low.contains(".webp") && !low.contains("format=webp")) {
                    best = u;
                    break;
                }
            }
            List<String> backups = new ArrayList<>();
            for (String u : candidates) {
                if (!u.equals(best) && !backups.contains(u)) backups.add(u);
            }
            String ext = guessImageExt(best);
            String filename = baseName + "_" + (i + 1) + ext;
            result.add(new MediaBundle.ImageItem(
                    best,
                    backups,
                    filename,
                    img.optInt("width", 0),
                    img.optInt("height", 0),
                    false,
                    false,
                    tags
            ));
        }
        return result;
    }

    private static void collectImageUrls(JSONArray arr, List<String> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            String u = arr.optString(i, "");
            if (LinkInput.isWebUrl(u) && !out.contains(u)) {
                out.add(u);
            }
        }
    }

    private static List<String> selectVideoUrls(JSONObject video, int preferred, boolean preferHevc) {
        List<String> bestUrls = List.of();
        int bestSize = 0;
        long bestBitrate = -1;
        boolean bestIsHevc = false;

        JSONArray rates = video.optJSONArray("bit_rate");
        if (rates != null) {
            for (int i = 0; i < rates.length(); i++) {
                JSONObject rate = rates.optJSONObject(i);
                if (rate == null) continue;
                JSONObject addr = rate.optJSONObject("play_addr");
                if (addr == null) addr = rate.optJSONObject("playAddr");
                if (addr == null) continue;
                List<String> urls = extractAddrUrls(addr);
                if (urls.isEmpty()) continue;

                int size = VideoQuality.shortSide(addr.optInt("width", rate.optInt("width")),
                        addr.optInt("height", rate.optInt("height")));
                long bitrate = rate.optLong("bit_rate", 0L);
                boolean isHevc = rate.optInt("is_h265", rate.optInt("is_bytevc1", 0)) == 1;

                if (bestUrls.isEmpty() || isBetterStream(size, bitrate, isHevc, bestSize, bestBitrate, bestIsHevc, preferred, preferHevc)) {
                    bestUrls = urls;
                    bestSize = size;
                    bestBitrate = bitrate;
                    bestIsHevc = isHevc;
                }
            }
        }

        for (String key : new String[]{"play_addr", "playAddr", "download_addr", "downloadAddr"}) {
            JSONObject addr = video.optJSONObject(key);
            if (addr == null) continue;
            List<String> urls = extractAddrUrls(addr);
            if (urls.isEmpty()) continue;
            int w = addr.optInt("width", 0);
            int h = addr.optInt("height", 0);
            if (w <= 0 || h <= 0) {
                w = video.optInt("width", 0);
                h = video.optInt("height", 0);
            }
            int size = VideoQuality.shortSide(w, h);
            if (bestUrls.isEmpty() || (size > 0 && VideoQuality.better(size, 0L, bestSize, bestBitrate, preferred))) {
                bestUrls = urls;
                bestSize = size;
                bestBitrate = 0L;
                bestIsHevc = false;
            }
        }
        return bestUrls;
    }

    private static boolean isBetterStream(int size, long bitrate, boolean isHevc,
                                          int bestSize, long bestBitrate, boolean bestIsHevc,
                                          int preferred, boolean preferHevc) {
        if (size == bestSize) {
            if (preferHevc && isHevc != bestIsHevc) return isHevc;
            if (!preferHevc && isHevc != bestIsHevc) return !isHevc;
            return bitrate > bestBitrate;
        }
        return VideoQuality.better(size, bitrate, bestSize, bestBitrate, preferred);
    }

    private static List<String> extractAddrUrls(JSONObject addr) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (String key : new String[]{"url_list", "urlList"}) {
            JSONArray arr = addr.optJSONArray(key);
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String u = toNoWatermarkUrl(arr.optString(i, ""));
                    if (LinkInput.isWebUrl(u)) urls.add(u);
                }
            }
        }
        for (String key : new String[]{"src", "url"}) {
            String u = toNoWatermarkUrl(addr.optString(key, ""));
            if (LinkInput.isWebUrl(u)) urls.add(u);
        }
        return new ArrayList<>(urls);
    }

    private static JSONObject findAwemeItem(Object node, int depth) {
        if (depth > 28 || node == null) return null;
        if (node instanceof JSONObject obj) {
            if (obj.has("images") && obj.optJSONArray("images") != null && obj.optJSONArray("images").length() > 0) {
                return obj;
            }
            if (obj.has("video") && obj.optJSONObject("video") != null) {
                JSONObject v = obj.optJSONObject("video");
                if (v.has("play_addr") || v.has("playAddr") || v.has("bit_rate") || v.has("download_addr")) {
                    return obj;
                }
            }
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (Set.of("music", "author", "cover", "avatar", "related", "recommend").contains(k)) continue;
                JSONObject found = findAwemeItem(obj.opt(k), depth + 1);
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray arr) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject found = findAwemeItem(arr.opt(i), depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JSONObject findLiveRoom(Object node, int depth) {
        if (depth > 24 || node == null) return null;
        if (node instanceof JSONObject obj) {
            if (obj.has("stream_url") && obj.optJSONObject("stream_url") != null) {
                return obj;
            }
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                JSONObject found = findLiveRoom(obj.opt(keys.next()), depth + 1);
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray arr) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject found = findLiveRoom(arr.opt(i), depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean isPhotoOnlyVideo(JSONObject video) {
        return video == null || (!video.has("play_addr") && !video.has("playAddr") && !video.has("bit_rate"));
    }

    private static String extractAuthor(JSONObject item) {
        JSONObject author = item.optJSONObject("author");
        if (author == null) return "";
        String nick = author.optString("nickname", "").trim();
        return !nick.isEmpty() ? nick : author.optString("unique_id", "").trim();
    }

    private static List<String> extractTags(JSONObject item) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        JSONArray textExtra = item.optJSONArray("text_extra");
        if (textExtra != null) {
            for (int i = 0; i < textExtra.length(); i++) {
                JSONObject te = textExtra.optJSONObject(i);
                if (te == null) continue;
                String name = te.optString("hashtag_name", "").trim();
                if (!name.isEmpty()) tags.add(name);
            }
        }
        return new ArrayList<>(tags);
    }

    private static List<String> extractMusicUrls(JSONObject item) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        JSONObject music = item.optJSONObject("music");
        if (music == null) return List.of();
        JSONObject playUrl = music.optJSONObject("play_url");
        if (playUrl != null) {
            JSONArray urlList = playUrl.optJSONArray("url_list");
            if (urlList != null) {
                for (int i = 0; i < urlList.length(); i++) {
                    String u = urlList.optString(i, "");
                    if (LinkInput.isWebUrl(u)) urls.add(u);
                }
            }
            String uri = playUrl.optString("uri", "");
            if (LinkInput.isWebUrl(uri)) urls.add(uri);
        }
        return new ArrayList<>(urls);
    }

    private static String guessImageExt(String url) {
        String low = url.toLowerCase(Locale.ROOT);
        if (low.contains(".png")) return ".png";
        if (low.contains(".webp")) return ".webp";
        return ".jpg";
    }

    private static String cleanTitle(String s) {
        return s == null ? "" : s.replaceAll("<[^>]+>", "").strip().replaceAll("\\s+", " ");
    }

    private static boolean isLiveUrl(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null && LinkInput.hostIs(host.toLowerCase(Locale.ROOT), "live.douyin.com");
        } catch (Exception e) {
            return false;
        }
    }

    private static Map<String, String> mobileHeaders(String referer) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("User-Agent", HttpClient.MOBILE_UA);
        map.put("Referer", referer != null && !referer.isBlank() ? referer : DEFAULT_REFERER);
        return map;
    }

    private static Map<String, String> desktopHeaders(String referer) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("User-Agent", HttpClient.DESKTOP_UA);
        map.put("Referer", referer != null && !referer.isBlank() ? referer : DEFAULT_REFERER);
        return map;
    }
}
