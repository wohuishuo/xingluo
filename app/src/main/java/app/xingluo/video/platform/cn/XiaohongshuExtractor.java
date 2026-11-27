package app.xingluo.video.platform.cn;

import app.xingluo.video.LinkInput;
import app.xingluo.video.MediaParser;
import app.xingluo.video.Net;
import app.xingluo.video.Preferences;
import app.xingluo.video.VideoQuality;
import app.xingluo.video.engine.HttpClient;
import app.xingluo.video.model.MediaBundle;
import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Xiaohongshu extractor supporting xhslink.com short links (preserving xsec_token & xsec_source),
 * window.__INITIAL_STATE__ (handling JS undefined literals), video notes (masterUrl / h264 / h265),
 * and lossless watermark-free imageList galleries.
 */
public final class XiaohongshuExtractor {
    public static final String PLATFORM = "小红书";
    private static final String DEFAULT_REFERER = "https://www.xiaohongshu.com/";
    private static final String LOSS_LESS_IMAGE_HOST = "https://sns-img-bd.xhscdn.com/";
    private static final String VIDEO_CDN_HOST = "https://sns-video-bd.xhscdn.com/";

    private static final Pattern NOTE_ID_PATTERN = Pattern.compile(
            "(?:/explore/|/discovery/item/|/item/)([0-9a-fA-F]{16,32})");
    private static final Pattern HASHTAG_PATTERN = Pattern.compile("#([^\\s#\\[]+)(?:\\[话题\\])?");

    private XiaohongshuExtractor() {}

    public static boolean supports(String url) {
        if (!LinkInput.isWebUrl(url)) return false;
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            String h = host.toLowerCase(Locale.ROOT);
            return LinkInput.hostIs(h, "xiaohongshu.com")
                    || LinkInput.hostIs(h, "xhslink.com");
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
        String resolved = HttpClient.resolveRedirectUrl(normalized, desktopHeaders(DEFAULT_REFERER), cancel);
        resolved = preserveXsecParams(normalized, resolved);

        HttpClient.HttpResult resp = HttpClient.get(resolved, desktopHeaders(DEFAULT_REFERER), cancel);
        return parseHtml(resp.body, resp.finalUrl, maxQuality, preferHevc, audioOnly);
    }

    static String preserveXsecParams(String originalUrl, String resolvedUrl) {
        try {
            URI orig = URI.create(originalUrl);
            URI res = URI.create(resolvedUrl);
            String origQuery = orig.getRawQuery();
            if (origQuery == null || origQuery.isEmpty()) return resolvedUrl;
            String resQuery = res.getRawQuery() == null ? "" : res.getRawQuery();
            StringBuilder append = new StringBuilder();
            for (String param : origQuery.split("&")) {
                if ((param.startsWith("xsec_token=") || param.startsWith("xsec_source="))
                        && !resQuery.contains(param.substring(0, param.indexOf('=') + 1))) {
                    if (append.length() > 0) append.append('&');
                    append.append(param);
                }
            }
            if (append.length() == 0) return resolvedUrl;
            String sep = resQuery.isEmpty() ? "?" : "&";
            return resolvedUrl + sep + append;
        } catch (Exception e) {
            return resolvedUrl;
        }
    }

    public static MediaBundle parseHtml(String html, String pageUrl, int maxQuality,
                                        boolean preferHevc, boolean audioOnly) {
        if (html == null || html.isBlank()) {
            throw new IllegalArgumentException("小红书页面内容为空");
        }
        int pos = html.indexOf("window.__INITIAL_STATE__");
        if (pos < 0) {
            throw new IllegalArgumentException("小红书页面未包含 __INITIAL_STATE__，可能需要登录或验证");
        }
        int start = html.indexOf('{', pos);
        String rawJson = MediaParser.balancedJson(html, start);
        if (rawJson == null) {
            throw new IllegalArgumentException("小红书页面状态解析失败");
        }
        String sanitized = sanitizeJsStateJson(rawJson);
        JSONObject state;
        try { state = new JSONObject(sanitized); }
        catch (org.json.JSONException e) { throw new IllegalArgumentException("小红书页面状态 JSON 解析失败", e); }
        return parseInitialState(state, pageUrl, maxQuality, preferHevc);
    }

    public static MediaBundle parseInitialState(JSONObject state, String pageUrl, int maxQuality, boolean preferHevc) {
        JSONObject noteSection = state.optJSONObject("note");
        JSONObject noteObj = null;
        String targetId = extractNoteId(pageUrl);

        if (noteSection != null) {
            JSONObject detailMap = noteSection.optJSONObject("noteDetailMap");
            if (detailMap != null) {
                if (targetId != null && detailMap.optJSONObject(targetId) != null) {
                    noteObj = detailMap.optJSONObject(targetId).optJSONObject("note");
                }
                if (noteObj == null) {
                    Iterator<String> keys = detailMap.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        if ("null".equals(key) || "undefined".equals(key) || key.isBlank()) continue;
                        JSONObject entry = detailMap.optJSONObject(key);
                        if (entry != null && entry.optJSONObject("note") != null) {
                            noteObj = entry.optJSONObject("note");
                            break;
                        }
                    }
                }
            }
            if (noteObj == null) {
                noteObj = noteSection.optJSONObject("note");
            }
        }

        if (noteObj == null) {
            throw new IllegalArgumentException("小红书状态中未找到笔记详情");
        }

        String title = cleanTitle(noteObj.optString("title", ""));
        if (title.isEmpty()) {
            title = cleanTitle(noteObj.optString("desc", "小红书笔记"));
            if (title.length() > 60) title = title.substring(0, 60);
        }
        if (title.isEmpty()) title = "小红书笔记";

        JSONObject user = noteObj.optJSONObject("user");
        String author = user != null ? user.optString("nickname", "").trim() : "";
        List<String> tags = extractTags(noteObj);
        Map<String, String> headers = desktopHeaders(pageUrl);

        String type = noteObj.optString("type", "").toLowerCase(Locale.ROOT);
        JSONObject video = noteObj.optJSONObject("video");
        if ("video".equals(type) || (video != null && video.has("media"))) {
            List<String> videoUrls = extractVideoUrls(video, maxQuality, preferHevc);
            if (!videoUrls.isEmpty()) {
                return MediaBundle.builder(MediaBundle.Type.VIDEO)
                        .title(title)
                        .author(author)
                        .platform(PLATFORM)
                        .tags(tags)
                        .referer(pageUrl)
                        .headers(headers)
                        .watermarkFree(true)
                        .videoUrls(videoUrls)
                        .build();
            }
        }

        JSONArray imageList = noteObj.optJSONArray("imageList");
        if (imageList != null && imageList.length() > 0) {
            List<MediaBundle.ImageItem> images = parseImageList(imageList, title, tags);
            if (!images.isEmpty()) {
                return MediaBundle.builder(MediaBundle.Type.GALLERY)
                        .title(title)
                        .author(author)
                        .platform(PLATFORM)
                        .tags(tags)
                        .referer(pageUrl)
                        .headers(headers)
                        .watermarkFree(true)
                        .images(images)
                        .build();
            }
        }

        throw new IllegalArgumentException("小红书笔记未包含可保存的视频或图片");
    }

    /** Converts JS object literal `undefined` values (outside JSON strings) into `null`. */
    public static String sanitizeJsStateJson(String raw) {
        if (raw == null) return "{}";
        StringBuilder sb = new StringBuilder(raw.length());
        boolean inString = false;
        boolean escape = false;
        int n = raw.length();
        for (int i = 0; i < n; i++) {
            char c = raw.charAt(i);
            if (inString) {
                sb.append(c);
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                sb.append(c);
                continue;
            }
            if (c == 'u' && i + 9 <= n && raw.startsWith("undefined", i)) {
                char prev = i > 0 ? raw.charAt(i - 1) : ' ';
                char next = i + 9 < n ? raw.charAt(i + 9) : ' ';
                if (!Character.isJavaIdentifierPart(prev) && !Character.isJavaIdentifierPart(next)) {
                    sb.append("null");
                    i += 8;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Strips watermark/resize query params & !suffixes and optionally builds lossless sns-img URL. */
    public static String toLosslessImageUrl(String url, String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            String cleanTrace = traceId.trim();
            if (cleanTrace.startsWith("/")) cleanTrace = cleanTrace.substring(1);
            if (!cleanTrace.isEmpty() && (url == null || !url.contains("sns-img"))) {
                return LOSS_LESS_IMAGE_HOST + cleanTrace;
            }
        }
        if (url == null || url.isBlank()) return "";
        String cleaned = stripWatermarkSuffix(url);
        return LinkInput.isWebUrl(cleaned) ? cleaned : url;
    }

    static String stripWatermarkSuffix(String url) {
        if (url == null) return "";
        String result = url.trim();
        int q = result.indexOf('?');
        if (q >= 0) {
            String query = result.substring(q + 1);
            if (query.startsWith("imageView2") || query.contains("imageView2/") || query.contains("x-oss-process=")) {
                result = result.substring(0, q);
            }
        }
        int bang = result.indexOf('!');
        if (bang > 0) {
            result = result.substring(0, bang);
        }
        return result;
    }

    private static List<MediaBundle.ImageItem> parseImageList(JSONArray imageList, String title, List<String> tags) {
        List<MediaBundle.ImageItem> items = new ArrayList<>();
        String baseName = LinkInput.safeName(title);

        for (int i = 0; i < imageList.length(); i++) {
            JSONObject img = imageList.optJSONObject(i);
            if (img == null) continue;
            String traceId = img.optString("traceId", "").trim();

            LinkedHashSet<String> rawCandidates = new LinkedHashSet<>();
            for (String key : new String[]{"urlDefault", "urlPre", "url"}) {
                String u = img.optString(key, "").trim();
                if (LinkInput.isWebUrl(u)) rawCandidates.add(u);
            }
            JSONArray infoList = img.optJSONArray("infoList");
            if (infoList != null) {
                for (int j = 0; j < infoList.length(); j++) {
                    JSONObject info = infoList.optJSONObject(j);
                    if (info == null) continue;
                    String u = info.optString("url", "").trim();
                    if (LinkInput.isWebUrl(u)) rawCandidates.add(u);
                }
            }

            LinkedHashSet<String> cleanUrls = new LinkedHashSet<>();
            if (!traceId.isEmpty()) {
                String snsUrl = LOSS_LESS_IMAGE_HOST + (traceId.startsWith("/") ? traceId.substring(1) : traceId);
                cleanUrls.add(snsUrl);
            }
            for (String raw : rawCandidates) {
                String stripped = stripWatermarkSuffix(raw);
                if (LinkInput.isWebUrl(stripped)) cleanUrls.add(stripped);
            }
            if (cleanUrls.isEmpty()) continue;

            // If an already-clean sns-img URL exists in rawCandidates, prefer it first
            List<String> ordered = new ArrayList<>(cleanUrls);
            String primary = ordered.get(0);
            for (String u : ordered) {
                if (u.contains("sns-img")) {
                    primary = u;
                    break;
                }
            }
            List<String> backups = new ArrayList<>();
            for (String u : ordered) {
                if (!u.equals(primary)) backups.add(u);
            }

            items.add(new MediaBundle.ImageItem(
                    primary,
                    backups,
                    baseName + "_" + (i + 1) + ".png",
                    img.optInt("width", 0),
                    img.optInt("height", 0),
                    false,
                    false,
                    tags
            ));
        }
        return items;
    }

    private static List<String> extractVideoUrls(JSONObject video, int maxQuality, boolean preferHevc) {
        if (video == null) return List.of();
        LinkedHashSet<String> urls = new LinkedHashSet<>();

        JSONObject media = video.optJSONObject("media");
        JSONObject stream = media != null ? media.optJSONObject("stream") : null;
        if (stream != null) {
            JSONObject bestH264 = selectBestStream(stream.optJSONArray("h264"), maxQuality);
            JSONObject bestH265 = selectBestStream(stream.optJSONArray("h265"), maxQuality);

            JSONObject first = preferHevc ? (bestH265 != null ? bestH265 : bestH264) : (bestH264 != null ? bestH264 : bestH265);
            JSONObject second = preferHevc ? bestH264 : bestH265;
            if (first != null) addStreamUrls(first, urls);
            if (second != null && second != first) addStreamUrls(second, urls);
        }

        JSONObject consumer = video.optJSONObject("consumer");
        if (consumer != null) {
            String originKey = consumer.optString("originVideoKey", "").trim();
            if (!originKey.isEmpty()) {
                String originUrl = VIDEO_CDN_HOST + (originKey.startsWith("/") ? originKey.substring(1) : originKey);
                if (LinkInput.isWebUrl(originUrl)) urls.add(originUrl);
            }
        }
        return new ArrayList<>(urls);
    }

    private static JSONObject selectBestStream(JSONArray streams, int maxQuality) {
        if (streams == null) return null;
        JSONObject best = null;
        int bestSize = 0;
        long bestBitrate = -1;
        for (int i = 0; i < streams.length(); i++) {
            JSONObject s = streams.optJSONObject(i);
            if (s == null) continue;
            String master = s.optString("masterUrl", "").trim();
            if (!LinkInput.isWebUrl(master)) continue;
            int size = VideoQuality.shortSide(s.optInt("width", 0), s.optInt("height", 0));
            long bitrate = s.optLong("videoBitrate", s.optLong("avgBitrate", 0L));
            if (best == null || VideoQuality.better(size, bitrate, bestSize, bestBitrate, maxQuality)) {
                best = s;
                bestSize = size;
                bestBitrate = bitrate;
            }
        }
        return best;
    }

    private static void addStreamUrls(JSONObject stream, Set<String> out) {
        String master = stream.optString("masterUrl", "").trim();
        if (LinkInput.isWebUrl(master)) out.add(master);
        JSONArray backups = stream.optJSONArray("backupUrls");
        if (backups != null) {
            for (int i = 0; i < backups.length(); i++) {
                String u = backups.optString(i, "").trim();
                if (LinkInput.isWebUrl(u)) out.add(u);
            }
        }
    }

    private static List<String> extractTags(JSONObject note) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        JSONArray tagList = note.optJSONArray("tagList");
        if (tagList != null) {
            for (int i = 0; i < tagList.length(); i++) {
                JSONObject t = tagList.optJSONObject(i);
                if (t == null) continue;
                String name = t.optString("name", "").trim();
                if (!name.isEmpty()) tags.add(name);
            }
        }
        if (tags.isEmpty()) {
            String desc = note.optString("desc", "");
            Matcher m = HASHTAG_PATTERN.matcher(desc);
            while (m.find()) {
                String t = m.group(1).trim();
                if (!t.isEmpty()) tags.add(t);
            }
        }
        return new ArrayList<>(tags);
    }

    private static String extractNoteId(String url) {
        if (url == null) return null;
        Matcher m = NOTE_ID_PATTERN.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private static String cleanTitle(String s) {
        return s == null ? "" : s.replaceAll("<[^>]+>", "").strip().replaceAll("\\s+", " ");
    }

    private static Map<String, String> desktopHeaders(String referer) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("User-Agent", HttpClient.DESKTOP_UA);
        map.put("Referer", referer != null && !referer.isBlank() ? referer : DEFAULT_REFERER);
        return map;
    }
}
