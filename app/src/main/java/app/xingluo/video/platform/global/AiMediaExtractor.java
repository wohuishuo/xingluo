package app.xingluo.video.platform.global;

import app.xingluo.video.LinkInput;
import app.xingluo.video.MediaParser;
import app.xingluo.video.Net;
import app.xingluo.video.Preferences;
import app.xingluo.video.engine.HttpClient;
import app.xingluo.video.model.MediaBundle;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Extracts AI-generated images and videos from Civitai, Runway, Luma, Jimeng, Kling, and Hailuo share pages. */
public final class AiMediaExtractor {
    private static final Pattern CIVITAI_POST = Pattern.compile("^/posts/(\\d+)(?:[/?#].*)?$");
    private static final Pattern CIVITAI_MODEL = Pattern.compile("^/models/(\\d+)(?:[/?#].*)?$");
    private static final Pattern CIVITAI_IMAGE = Pattern.compile("^/images/(\\d+)(?:[/?#].*)?$");
    private static final Pattern MP4_URL = Pattern.compile("https?://[^\\s\"'<>\\\\]+\\.mp4(?:\\?[^\\s\"'<>\\\\]*)?", Pattern.CASE_INSENSITIVE);
    private static final Set<String> WATERMARK_QUERY_KEYS = Set.of(
            "watermark", "wm", "mark", "logo", "has_watermark", "is_watermark", "need_watermark"
    );

    private AiMediaExtractor() {}

    public static MediaBundle extract(String input, Net.Cancellation cancel) throws IOException {
        return extract(input, null, cancel);
    }

    public static MediaBundle extract(String input, Preferences prefs, Net.Cancellation cancel) throws IOException {
        String url = LinkInput.extract(input);
        URI uri = URI.create(url);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        ImageFilter.Config filterConfig = prefs == null
                ? new ImageFilter.Config(0, true, false, false)
                : ImageFilter.Config.from(prefs);

        if (LinkInput.hostIs(host, "civitai.com")) {
            return extractCivitai(url, uri, filterConfig, cancel);
        }
        if (LinkInput.hostIs(host, "runwayml.com")) {
            return fetchAndParseSsr(url, "Runway", cancel);
        }
        if (LinkInput.hostIs(host, "lumalabs.ai")) {
            return fetchAndParseSsr(url, "Luma", cancel);
        }
        if (LinkInput.hostIs(host, "jimeng.jianying.com")) {
            return fetchAndParseCnAi(url, "即梦 AI", cancel);
        }
        if (LinkInput.hostIs(host, "klingai.kuaishou.com")) {
            return fetchAndParseCnAi(url, "可灵 AI", cancel);
        }
        if (LinkInput.hostIs(host, "hailuoai.com")) {
            return fetchAndParseCnAi(url, "海螺 AI", cancel);
        }
        return fetchAndParseSsr(url, "AI 创作", cancel);
    }

    // =========================================================================
    // 1. Civitai (/api/v1/images & /api/v1/models)
    // =========================================================================

    private static MediaBundle extractCivitai(String url, URI uri, ImageFilter.Config filterConfig,
                                              Net.Cancellation cancel) throws IOException {
        String path = uri.getPath() == null ? "/" : uri.getPath();
        Map<String, String> headers = Map.of(
                "User-Agent", HttpClient.DESKTOP_UA,
                "Accept", "application/json",
                "Referer", "https://civitai.com/"
        );

        Matcher postMatcher = CIVITAI_POST.matcher(path);
        if (postMatcher.matches()) {
            String api = "https://civitai.com/api/v1/images?postId=" + postMatcher.group(1);
            HttpClient.HttpResult res = HttpClient.get(api, headers, cancel);
            try { return parseCivitaiImages(new JSONObject(res.body), url, filterConfig); }
            catch (org.json.JSONException e) { throw new IOException("Civitai 作品数据解析失败", e); }
        }

        Matcher modelMatcher = CIVITAI_MODEL.matcher(path);
        if (modelMatcher.matches()) {
            String api = "https://civitai.com/api/v1/models/" + modelMatcher.group(1);
            HttpClient.HttpResult res = HttpClient.get(api, headers, cancel);
            try { return parseCivitaiModel(new JSONObject(res.body), url, filterConfig); }
            catch (org.json.JSONException e) { throw new IOException("Civitai 模型数据解析失败", e); }
        }

        Matcher imageMatcher = CIVITAI_IMAGE.matcher(path);
        if (imageMatcher.matches()) {
            try {
                String api = "https://civitai.com/api/v1/images?imageId=" + imageMatcher.group(1);
                HttpClient.HttpResult res = HttpClient.get(api, headers, cancel);
                JSONObject json = new JSONObject(res.body);
                JSONArray items = json.optJSONArray("items");
                if (items != null && items.length() > 0) {
                    return parseCivitaiImages(json, url, filterConfig);
                }
            } catch (Exception ignored) {
                Net.check(cancel);
            }
        }
        return fetchAndParseSsr(url, "Civitai", cancel);
    }

    public static MediaBundle parseCivitaiImages(JSONObject root, String pageUrl, ImageFilter.Config filterConfig) {
        if (root == null) throw new IllegalArgumentException("Civitai 接口返回为空");
        JSONArray items = root.optJSONArray("items");
        if (items == null || items.length() == 0) {
            throw new IllegalArgumentException("Civitai 未返回图片或视频条目");
        }

        List<String> videoUrls = new ArrayList<>();
        List<MediaBundle.ImageItem> rawImages = new ArrayList<>();
        Set<String> tags = new LinkedHashSet<>();
        String author = "";
        StringBuilder metaSummary = new StringBuilder();

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String mediaUrl = item.optString("url", "");
            if (!LinkInput.isWebUrl(mediaUrl)) continue;
            if (author.isEmpty()) author = item.optString("username", "");

            JSONObject meta = item.optJSONObject("meta");
            if (meta != null && metaSummary.length() == 0) {
                String prompt = meta.optString("prompt", "");
                String model = meta.optString("Model", "");
                if (!model.isBlank()) {
                    tags.add(model.trim());
                    metaSummary.append("Model: ").append(model.trim());
                }
                if (!prompt.isBlank()) {
                    if (metaSummary.length() > 0) metaSummary.append(" | ");
                    metaSummary.append("Prompt: ").append(prompt.length() > 140 ? prompt.substring(0, 140) + "..." : prompt);
                }
            }

            String type = item.optString("type", "").toLowerCase(Locale.ROOT);
            String lowerUrl = mediaUrl.toLowerCase(Locale.ROOT);
            boolean isVideo = "video".equals(type) || lowerUrl.endsWith(".mp4") || lowerUrl.endsWith(".webm");
            if (isVideo) {
                videoUrls.add(mediaUrl);
            } else {
                int w = item.optInt("width", 0);
                int h = item.optInt("height", 0);
                boolean r18 = item.optBoolean("nsfw", false) || item.optInt("nsfwLevel", 1) > 2;
                long id = item.optLong("id", i + 1);
                String filename = "civitai_" + id + guessImageExtension(mediaUrl);
                rawImages.add(new MediaBundle.ImageItem(
                        mediaUrl, List.of(), filename, w, h, true, r18, new ArrayList<>(tags)));
            }
        }

        if (!videoUrls.isEmpty() && rawImages.isEmpty()) {
            return MediaBundle.builder(MediaBundle.Type.VIDEO)
                    .title("Civitai AI 视频")
                    .author(author)
                    .platform("Civitai")
                    .tags(tags)
                    .referer("https://civitai.com/")
                    .videoUrls(videoUrls)
                    .extraInfo(metaSummary.toString())
                    .watermarkFree(true)
                    .build();
        }

        ImageFilter.Config cfg = filterConfig == null ? new ImageFilter.Config(0, true, false, false) : filterConfig;
        List<MediaBundle.ImageItem> filtered = ImageFilter.filter(rawImages, cfg);
        if (filtered.isEmpty() && !videoUrls.isEmpty()) {
            return MediaBundle.builder(MediaBundle.Type.VIDEO)
                    .title("Civitai AI 视频")
                    .author(author)
                    .platform("Civitai")
                    .tags(tags)
                    .referer("https://civitai.com/")
                    .videoUrls(videoUrls)
                    .extraInfo(metaSummary.toString())
                    .watermarkFree(true)
                    .build();
        }
        if (filtered.isEmpty()) {
            throw new IllegalArgumentException("Civitai 媒体均被过滤规则跳过");
        }
        return MediaBundle.builder(MediaBundle.Type.GALLERY)
                .title("Civitai AI 图集")
                .author(author)
                .platform("Civitai")
                .tags(tags)
                .referer("https://civitai.com/")
                .subfolder("Civitai/" + LinkInput.safeName(author.isBlank() ? "Gallery" : author))
                .images(filtered)
                .extraInfo(metaSummary.toString())
                .watermarkFree(true)
                .build();
    }

    public static MediaBundle parseCivitaiModel(JSONObject root, String pageUrl, ImageFilter.Config filterConfig) {
        if (root == null) throw new IllegalArgumentException("Civitai 模型接口返回为空");
        String modelName = root.optString("name", "Civitai 模型展示");
        JSONObject creator = root.optJSONObject("creator");
        String author = creator == null ? "" : creator.optString("username", "");

        List<String> tags = new ArrayList<>();
        JSONArray tagsArr = root.optJSONArray("tags");
        if (tagsArr != null) {
            for (int i = 0; i < tagsArr.length(); i++) {
                String t = tagsArr.optString(i, "").trim();
                if (!t.isEmpty()) tags.add(t);
            }
        }

        JSONArray versions = root.optJSONArray("modelVersions");
        JSONObject firstVersion = versions != null && versions.length() > 0 ? versions.optJSONObject(0) : null;
        JSONArray imagesArr = firstVersion == null ? null : firstVersion.optJSONArray("images");
        if (imagesArr == null || imagesArr.length() == 0) {
            throw new IllegalArgumentException("该 Civitai 模型没有公开示例图片或视频");
        }

        List<MediaBundle.ImageItem> rawImages = new ArrayList<>();
        List<String> videoUrls = new ArrayList<>();
        for (int i = 0; i < imagesArr.length(); i++) {
            JSONObject img = imagesArr.optJSONObject(i);
            if (img == null) continue;
            String u = img.optString("url", "");
            if (!LinkInput.isWebUrl(u)) continue;
            String type = img.optString("type", "").toLowerCase(Locale.ROOT);
            if ("video".equals(type) || u.toLowerCase(Locale.ROOT).endsWith(".mp4")) {
                videoUrls.add(u);
                continue;
            }
            int w = img.optInt("width", 0);
            int h = img.optInt("height", 0);
            boolean r18 = img.optInt("nsfwLevel", 1) > 2;
            rawImages.add(new MediaBundle.ImageItem(
                    u, List.of(), "civitai_model_" + (i + 1) + guessImageExtension(u), w, h, true, r18, tags));
        }

        if (rawImages.isEmpty() && !videoUrls.isEmpty()) {
            return MediaBundle.builder(MediaBundle.Type.VIDEO)
                    .title(modelName)
                    .author(author)
                    .platform("Civitai")
                    .tags(tags)
                    .referer("https://civitai.com/")
                    .videoUrls(videoUrls)
                    .watermarkFree(true)
                    .build();
        }
        ImageFilter.Config cfg = filterConfig == null ? new ImageFilter.Config(0, true, false, false) : filterConfig;
        List<MediaBundle.ImageItem> filtered = ImageFilter.filter(rawImages, cfg);
        if (filtered.isEmpty()) {
            throw new IllegalArgumentException("Civitai 模型展示图均被过滤规则跳过");
        }
        return MediaBundle.builder(MediaBundle.Type.GALLERY)
                .title(modelName)
                .author(author)
                .platform("Civitai")
                .tags(tags)
                .referer("https://civitai.com/")
                .subfolder("Civitai/" + LinkInput.safeName(modelName))
                .images(filtered)
                .watermarkFree(true)
                .build();
    }

    // =========================================================================
    // 2. Runway & Luma SSR Share Pages (og:video / __NEXT_DATA__)
    // =========================================================================

    private static MediaBundle fetchAndParseSsr(String url, String platformName, Net.Cancellation cancel) throws IOException {
        HttpClient.HttpResult res = HttpClient.get(
                url,
                Map.of("User-Agent", HttpClient.DESKTOP_UA, "Referer", url),
                cancel);
        return parseSsrSharePage(res.body, res.finalUrl, platformName);
    }

    public static MediaBundle parseSsrSharePage(String html, String pageUrl, String platformName) {
        if (html == null || html.isBlank()) {
            throw new IllegalArgumentException(platformName + " 页面内容为空");
        }
        String title = extractPageTitle(html, platformName + " AI 视频");
        List<String> candidates = new ArrayList<>();
        collectMetaVideoUrls(html, pageUrl, candidates);
        collectNextDataVideoUrls(html, candidates);
        collectMediaTagUrls(html, pageUrl, candidates);
        collectMp4ScanUrls(html, candidates);

        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("未在 " + platformName + " 分享页找到公开视频流，请点「网页识别」播放后重试");
        }
        return MediaBundle.builder(MediaBundle.Type.VIDEO)
                .title(title)
                .platform(platformName)
                .referer(pageUrl)
                .videoUrls(dedup(candidates))
                .watermarkFree(true)
                .build();
    }

    // =========================================================================
    // 3. Jimeng (即梦) / Kling (可灵) / Hailuo (海螺)
    // =========================================================================

    private static MediaBundle fetchAndParseCnAi(String url, String platformName, Net.Cancellation cancel) throws IOException {
        HttpClient.HttpResult res;
        try {
            res = HttpClient.get(
                    url,
                    Map.of("User-Agent", HttpClient.DESKTOP_UA, "Referer", url),
                    cancel);
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("401") || msg.contains("403") || msg.contains("412") || msg.contains("429")) {
                throw new IOException("需要登录 " + platformName + " 账号或会话权限才能读取无水印原片，请点「网页识别」登录后重试", e);
            }
            throw e;
        }
        return parseCnAiShareHtml(res.body, res.finalUrl, platformName);
    }

    public static MediaBundle parseCnAiShareHtml(String html, String pageUrl, String platformName) {
        if (html == null || html.isBlank()) {
            throw new IllegalArgumentException(platformName + " 页面内容为空");
        }
        String title = extractPageTitle(html, platformName + " 作品");
        List<String> candidates = new ArrayList<>();

        collectWindowStateVideoUrls(html, candidates);
        collectNextDataVideoUrls(html, candidates);
        collectMetaVideoUrls(html, pageUrl, candidates);
        collectMediaTagUrls(html, pageUrl, candidates);
        collectMp4ScanUrls(html, candidates);

        List<String> cleaned = new ArrayList<>();
        for (String u : dedup(candidates)) {
            String clean = stripWatermarkParams(u);
            if (LinkInput.isWebUrl(clean)) cleaned.add(clean);
        }
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException(
                    "未能在 " + platformName + " 公开分享页提取到无水印直链。该平台部分分享页需要登录会话或签名权限，请点「网页识别」登录后播放并保存。");
        }
        return MediaBundle.builder(MediaBundle.Type.VIDEO)
                .title(title)
                .platform(platformName)
                .referer(pageUrl)
                .header("Referer", pageUrl)
                .videoUrls(cleaned)
                .watermarkFree(true)
                .build();
    }

    /** Strips common watermark query params (e.g. watermark, wm, logo, OSS watermark op) while keeping auth signatures intact. */
    public static String stripWatermarkParams(String url) {
        if (url == null || url.isBlank()) return url;
        try {
            URI uri = URI.create(url);
            String rawQuery = uri.getRawQuery();
            if (rawQuery == null || rawQuery.isEmpty()) return url;
            List<String> kept = new ArrayList<>();
            boolean changed = false;
            for (String pair : rawQuery.split("&")) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                String key = (eq >= 0 ? pair.substring(0, eq) : pair).toLowerCase(Locale.ROOT);
                String val = eq >= 0 ? pair.substring(eq + 1) : "";
                if (WATERMARK_QUERY_KEYS.contains(key)) {
                    changed = true;
                    continue;
                }
                if ("x-oss-process".equals(key) && val.toLowerCase(Locale.ROOT).contains("watermark")) {
                    changed = true;
                    continue;
                }
                kept.add(pair);
            }
            if (!changed) return url;
            String base = url.substring(0, url.indexOf('?'));
            return kept.isEmpty() ? base : base + "?" + String.join("&", kept);
        } catch (Exception ignored) {
            return url;
        }
    }

    // =========================================================================
    // Shared HTML / JSON Video Discovery Helpers
    // =========================================================================

    private static void collectWindowStateVideoUrls(String html, List<String> out) {
        for (String marker : new String[]{"window.__INITIAL_STATE__", "window._ROUTER_DATA"}) {
            int pos = html.indexOf(marker);
            if (pos < 0) continue;
            int start = html.indexOf('{', pos);
            String raw = MediaParser.balancedJson(html, start);
            if (raw == null) continue;
            try {
                scanJsonForVideos(new JSONObject(raw), 0, out);
            } catch (Exception ignored) {}
        }
    }

    private static void collectNextDataVideoUrls(String html, List<String> out) {
        Matcher m = Pattern.compile("(?is)<script[^>]*id\\s*=\\s*['\"](?:__NEXT_DATA__|RENDER_DATA|SIGI_STATE)['\"][^>]*>(.*?)</script>")
                .matcher(html);
        while (m.find()) {
            try {
                scanJsonForVideos(new JSONObject(m.group(1).trim()), 0, out);
            } catch (Exception ignored) {}
        }
    }

    private static void scanJsonForVideos(Object node, int depth, List<String> out) {
        if (depth > 20 || node == null) return;
        if (node instanceof JSONObject obj) {
            for (String key : new String[]{"no_watermark_url", "downloadUrl", "download_url", "videoUrl", "video_url", "mp4Url", "playUrl", "play_url", "url", "src"}) {
                Object val = obj.opt(key);
                if (val instanceof String s && isLikelyVideoUrl(s)) out.add(s);
            }
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                String low = k.toLowerCase(Locale.ROOT);
                if (low.contains("avatar") || low.contains("thumb") || low.contains("cover") || low.contains("poster")) continue;
                scanJsonForVideos(obj.opt(k), depth + 1, out);
            }
        } else if (node instanceof JSONArray arr) {
            for (int i = 0; i < arr.length(); i++) {
                scanJsonForVideos(arr.opt(i), depth + 1, out);
            }
        }
    }

    private static void collectMetaVideoUrls(String html, String baseUrl, List<String> out) {
        Matcher tags = Pattern.compile("(?is)<meta\\b[^>]*>").matcher(html);
        while (tags.find()) {
            String tag = tags.group();
            String prop = extractAttr(tag, "property");
            if (prop == null) prop = extractAttr(tag, "name");
            if (prop == null) continue;
            String key = prop.toLowerCase(Locale.ROOT);
            if (Set.of("og:video", "og:video:url", "og:video:secure_url", "twitter:player:stream").contains(key)) {
                String content = extractAttr(tag, "content");
                addResolvedIfVideo(content, baseUrl, out);
            }
        }
    }

    private static void collectMediaTagUrls(String html, String baseUrl, List<String> out) {
        Matcher tags = Pattern.compile("(?is)<(?:video|source)\\b[^>]*>").matcher(html);
        while (tags.find()) {
            String src = extractAttr(tags.group(), "src");
            addResolvedIfVideo(src, baseUrl, out);
        }
    }

    private static void collectMp4ScanUrls(String html, List<String> out) {
        String normalized = html.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&");
        Matcher m = MP4_URL.matcher(normalized);
        while (m.find()) {
            String u = m.group();
            if (LinkInput.isWebUrl(u) && !ImageFilter.isJunkUrl(u)) out.add(u);
        }
    }

    private static void addResolvedIfVideo(String raw, String baseUrl, List<String> out) {
        if (raw == null || raw.isBlank() || raw.startsWith("blob:")) return;
        String unescaped = MediaParser.entities(raw.trim());
        try {
            unescaped = URI.create(baseUrl).resolve(unescaped).toString();
        } catch (Exception ignored) {}
        if (LinkInput.isWebUrl(unescaped) && !ImageFilter.isJunkUrl(unescaped)) {
            out.add(unescaped);
        }
    }

    private static boolean isLikelyVideoUrl(String s) {
        if (!LinkInput.isWebUrl(s) || ImageFilter.isJunkUrl(s)) return false;
        String low = s.toLowerCase(Locale.ROOT);
        return low.contains(".mp4") || low.contains(".webm") || low.contains("video");
    }

    private static String extractAttr(String tag, String attrName) {
        Matcher m = Pattern.compile("(?is)\\b" + Pattern.quote(attrName) + "\\s*=\\s*(['\"])(.*?)\\1").matcher(tag);
        return m.find() ? MediaParser.entities(m.group(2)) : null;
    }

    private static String extractPageTitle(String html, String fallback) {
        Matcher m = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
        if (!m.find()) return fallback;
        String clean = MediaParser.entities(m.group(1)).replaceAll("\\s+", " ").trim();
        return clean.isEmpty() ? fallback : clean;
    }

    private static List<String> dedup(List<String> input) {
        return new ArrayList<>(new LinkedHashSet<>(input));
    }

    private static String guessImageExtension(String url) {
        String low = url.toLowerCase(Locale.ROOT);
        int q = low.indexOf('?');
        if (q >= 0) low = low.substring(0, q);
        if (low.endsWith(".png")) return ".png";
        if (low.endsWith(".webp")) return ".webp";
        if (low.endsWith(".gif")) return ".gif";
        return ".jpg";
    }
}
