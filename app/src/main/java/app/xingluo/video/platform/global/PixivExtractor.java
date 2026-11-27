package app.xingluo.video.platform.global;

import app.xingluo.video.LinkInput;
import app.xingluo.video.Net;
import app.xingluo.video.Preferences;
import app.xingluo.video.engine.HttpClient;
import app.xingluo.video.model.MediaBundle;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Extracts single artworks, multi-page sets, and artist portfolios from Pixiv AJAX endpoints. */
public final class PixivExtractor {
    public static final String PIXIV_REFERER = "https://www.pixiv.net/";
    private static final int PROFILE_ILLUST_BATCH_SIZE = 30;

    private static final Pattern ARTWORK_PATH = Pattern.compile("^/(?:[a-z]{2}(?:-[a-z]{2})?/)?artworks/(\\d+)/?$");
    private static final Pattern USER_ILLUSTS_PATH = Pattern.compile("^/(?:[a-z]{2}(?:-[a-z]{2})?/)?users/(\\d+)/illustrations/?$");
    private static final Pattern USER_MANGA_PATH = Pattern.compile("^/(?:[a-z]{2}(?:-[a-z]{2})?/)?users/(\\d+)/manga/?$");
    private static final Pattern USER_ALL_PATH = Pattern.compile("^/(?:[a-z]{2}(?:-[a-z]{2})?/)?users/(\\d+)(?:/artworks)?/?$");
    private static final Pattern LEGACY_ILLUST_PATH = Pattern.compile("^/(?:[a-z]{2}(?:-[a-z]{2})?/)?member_illust\\.php$");

    public enum TargetType { ARTWORK, USER_ALL, USER_ILLUSTS, USER_MANGA }

    public record Target(TargetType type, String id) {}

    private PixivExtractor() {}

    public static Target parseUrl(String url) {
        if (url == null || url.isBlank() || !LinkInput.isWebUrl(url)) {
            throw new IllegalArgumentException("请提供有效的 Pixiv 链接");
        }
        URI uri = URI.create(url.trim());
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!LinkInput.hostIs(host, "pixiv.net")) {
            throw new IllegalArgumentException("不是 Pixiv 链接: " + url);
        }
        String path = uri.getPath() == null ? "/" : uri.getPath();
        Matcher m = ARTWORK_PATH.matcher(path);
        if (m.matches()) return new Target(TargetType.ARTWORK, m.group(1));

        if (LEGACY_ILLUST_PATH.matcher(path).matches()) {
            String illustId = queryParam(uri.getRawQuery(), "illust_id");
            if (illustId != null && illustId.matches("\\d+")) {
                return new Target(TargetType.ARTWORK, illustId);
            }
        }
        m = USER_ILLUSTS_PATH.matcher(path);
        if (m.matches()) return new Target(TargetType.USER_ILLUSTS, m.group(1));

        m = USER_MANGA_PATH.matcher(path);
        if (m.matches()) return new Target(TargetType.USER_MANGA, m.group(1));

        m = USER_ALL_PATH.matcher(path);
        if (m.matches()) return new Target(TargetType.USER_ALL, m.group(1));

        throw new IllegalArgumentException("暂不支持该 Pixiv 链接格式，请分享作品 (/artworks/ID) 或画师主页 (/users/ID)");
    }

    public static MediaBundle extract(String url, Preferences prefs, Net.Cancellation cancel) throws IOException {
        ImageFilter.Config filterConfig = ImageFilter.Config.from(prefs);
        int maxBatch = prefs == null ? 30 : prefs.pixivMaxBatch();
        return extract(url, filterConfig, maxBatch, cancel);
    }

    public static MediaBundle extract(String url, ImageFilter.Config filterConfig, int maxBatch,
                                      Net.Cancellation cancel) throws IOException {
        Target target = parseUrl(url);
        ImageFilter.Config cfg = filterConfig == null ? ImageFilter.Config.defaults() : filterConfig;
        int limit = Math.max(1, Math.min(100, maxBatch));
        if (target.type() == TargetType.ARTWORK) {
            return extractArtwork(target.id(), cfg, cancel);
        }
        return extractArtist(target, cfg, limit, cancel);
    }

    private static MediaBundle extractArtwork(String illustId, ImageFilter.Config cfg,
                                              Net.Cancellation cancel) throws IOException {
        JSONObject illustJson = getPixivJson("https://www.pixiv.net/ajax/illust/" + illustId, cancel);
        JSONObject body = requireBodyObject(illustJson, "获取 Pixiv 作品信息失败");
        int pageCount = body.optInt("pageCount", 1);
        JSONObject pagesJson = null;
        if (pageCount > 1) {
            pagesJson = getPixivJson("https://www.pixiv.net/ajax/illust/" + illustId + "/pages", cancel);
        }
        return parseSingleArtwork(illustJson, pagesJson, cfg);
    }

    private static MediaBundle extractArtist(Target target, ImageFilter.Config cfg, int maxBatch,
                                             Net.Cancellation cancel) throws IOException {
        String uid = target.id();
        JSONObject allJson = getPixivJson("https://www.pixiv.net/ajax/user/" + uid + "/profile/all", cancel);
        JSONObject topJson = null;
        try {
            topJson = getPixivJson("https://www.pixiv.net/ajax/user/" + uid + "/profile/top", cancel);
        } catch (Exception ignored) {
            Net.check(cancel);
        }

        List<String> targetIds = collectArtistIds(allJson, target.type(), maxBatch);
        if (targetIds.isEmpty()) {
            throw new IllegalArgumentException("该 Pixiv 画师主页没有可提取的公开插画或漫画作品");
        }

        List<JSONObject> batchJsons = new ArrayList<>();
        for (int i = 0; i < targetIds.size(); i += PROFILE_ILLUST_BATCH_SIZE) {
            Net.check(cancel);
            List<String> chunk = targetIds.subList(i, Math.min(i + PROFILE_ILLUST_BATCH_SIZE, targetIds.size()));
            String batchUrl = buildBatchIllustsUrl(uid, target.type(), chunk);
            try {
                batchJsons.add(getPixivJson(batchUrl, cancel));
            } catch (Exception ignored) {
                Net.check(cancel);
            }
        }

        Map<String, JSONObject> summaries = indexArtistSummaries(topJson, batchJsons);
        List<MediaBundle.ImageItem> rawItems = new ArrayList<>();
        Set<String> allTags = new LinkedHashSet<>();
        String authorName = extractAuthorFromSummaryOrTop(uid, topJson, summaries);

        for (String id : targetIds) {
            Net.check(cancel);
            JSONObject summary = summaries.get(id);
            if (summary != null) {
                if (summary.optInt("illustType", 0) == 2) continue;
                boolean ai = summary.optInt("aiType", 0) == 2;
                boolean r18 = summary.optInt("xRestrict", 0) > 0;
                if ((cfg.skipAi && ai) || (cfg.skipR18 && r18)) continue;
                int w = summary.optInt("width", 0);
                int h = summary.optInt("height", 0);
                if ((w > 0 && w < cfg.minPx) || (h > 0 && h < cfg.minPx)) continue;
            }
            try {
                JSONObject illustJson = getPixivJson("https://www.pixiv.net/ajax/illust/" + id, cancel);
                JSONObject body = illustJson.optJSONObject("body");
                if (body == null || illustJson.optBoolean("error", true) || body.optInt("illustType", 0) == 2) continue;
                if (authorName.isBlank()) authorName = body.optString("userName", "");
                int pageCount = body.optInt("pageCount", 1);
                JSONObject pagesJson = pageCount > 1
                        ? getPixivJson("https://www.pixiv.net/ajax/illust/" + id + "/pages", cancel)
                        : null;
                List<MediaBundle.ImageItem> items = extractArtworkItems(body, pagesJson);
                for (MediaBundle.ImageItem item : items) {
                    rawItems.add(item);
                    allTags.addAll(item.tags);
                }
            } catch (Exception ignored) {
                Net.check(cancel);
                if (summary != null) {
                    List<MediaBundle.ImageItem> fallbackItems = extractSummaryItems(id, authorName, summary);
                    for (MediaBundle.ImageItem item : fallbackItems) {
                        rawItems.add(item);
                        allTags.addAll(item.tags);
                    }
                }
            }
        }

        List<MediaBundle.ImageItem> filtered = ImageFilter.filter(rawItems, cfg);
        if (filtered.isEmpty()) {
            throw new IllegalArgumentException("没有符合过滤条件的 Pixiv 画师作品（已跳过动图、头像或低分辨率图片）");
        }
        String finalAuthor = authorName.isBlank() ? "user_" + uid : authorName;
        return buildPixivGalleryBundle(finalAuthor + " 的 Pixiv 作品", finalAuthor, uid, allTags, filtered);
    }

    public static MediaBundle parseSingleArtwork(JSONObject illustJson, JSONObject pagesJson,
                                                 ImageFilter.Config filterConfig) {
        JSONObject body = requireBodyObject(illustJson, "Pixiv 作品 JSON 缺少 body");
        if (body.optInt("illustType", 0) == 2) {
            throw new IllegalArgumentException("暂不支持 Pixiv 动图 (Ugoira) 导出，请选择插画或漫画");
        }
        String userName = body.optString("userName", "");
        String userId = body.optString("userId", "");
        String title = body.optString("illustTitle", "Pixiv 作品");
        List<String> tags = extractTags(body);
        List<MediaBundle.ImageItem> rawItems = extractArtworkItems(body, pagesJson);
        ImageFilter.Config cfg = filterConfig == null ? ImageFilter.Config.defaults() : filterConfig;
        List<MediaBundle.ImageItem> filtered = ImageFilter.filter(rawItems, cfg);
        if (filtered.isEmpty()) {
            throw new IllegalArgumentException("该 Pixiv 作品已被过滤（可能为 AI 生成、R-18 或分辨率低于阈值）");
        }
        return buildPixivGalleryBundle(title, userName, userId, tags, filtered);
    }

    public static MediaBundle parseArtistCatalog(String uid, TargetType type, JSONObject allJson, JSONObject topJson,
                                                 List<JSONObject> batchJsons, ImageFilter.Config filterConfig,
                                                 int maxBatch) {
        List<String> ids = collectArtistIds(allJson, type, Math.max(1, maxBatch));
        Map<String, JSONObject> summaries = indexArtistSummaries(topJson, batchJsons);
        String authorName = extractAuthorFromSummaryOrTop(uid, topJson, summaries);
        List<MediaBundle.ImageItem> rawItems = new ArrayList<>();
        Set<String> allTags = new LinkedHashSet<>();

        for (String id : ids) {
            JSONObject summary = summaries.get(id);
            if (summary == null || summary.optInt("illustType", 0) == 2) continue;
            String itemAuthor = summary.optString("userName", authorName);
            if (authorName.isBlank() && !itemAuthor.isBlank()) authorName = itemAuthor;
            List<MediaBundle.ImageItem> items = extractSummaryItems(id, itemAuthor, summary);
            for (MediaBundle.ImageItem item : items) {
                rawItems.add(item);
                allTags.addAll(item.tags);
            }
        }
        ImageFilter.Config cfg = filterConfig == null ? ImageFilter.Config.defaults() : filterConfig;
        List<MediaBundle.ImageItem> filtered = ImageFilter.filter(rawItems, cfg);
        if (filtered.isEmpty()) {
            throw new IllegalArgumentException("没有符合过滤条件的 Pixiv 画师作品");
        }
        String finalAuthor = authorName.isBlank() ? "user_" + uid : authorName;
        return buildPixivGalleryBundle(finalAuthor + " 的 Pixiv 作品", finalAuthor, uid, allTags, filtered);
    }

    public static List<String> collectArtistIds(JSONObject allJson, TargetType type, int maxBatch) {
        JSONObject body = requireBodyObject(allJson, "Pixiv 画师作品目录无效");
        Set<String> ids = new LinkedHashSet<>();
        if (type != TargetType.USER_MANGA) {
            addNumericKeys(body.optJSONObject("illusts"), ids);
        }
        if (type != TargetType.USER_ILLUSTS) {
            addNumericKeys(body.optJSONObject("manga"), ids);
        }
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(Comparator.comparingLong(PixivExtractor::parseLongSafe).reversed());
        if (maxBatch > 0 && sorted.size() > maxBatch) {
            return new ArrayList<>(sorted.subList(0, maxBatch));
        }
        return sorted;
    }

    public static String buildSubfolder(String userName, String userId) {
        String safeAuthor = (userName == null || userName.isBlank())
                ? "user_" + (userId == null ? "0" : userId)
                : LinkInput.safeName(userName);
        String safeUid = LinkInput.safeName(userId == null || userId.isBlank() ? "0" : userId);
        return "Pixiv/" + safeAuthor + "_" + safeUid;
    }

    public static String formatFilename(String userName, String illustId, List<String> tags,
                                        int pageIndex, String imageUrl) {
        String author = LinkInput.safeName((userName == null || userName.isBlank()) ? "Pixiv" : userName);
        if (author.length() > 24) author = author.substring(0, 24);
        String id = LinkInput.safeName((illustId == null || illustId.isBlank()) ? "0" : illustId);

        List<String> cleanTags = new ArrayList<>();
        if (tags != null) {
            for (String tag : tags) {
                if (tag == null || tag.isBlank()) continue;
                String safeTag = LinkInput.safeName(tag.trim()).replaceAll("\\s+", "");
                if (!safeTag.isBlank()) {
                    if (safeTag.length() > 16) safeTag = safeTag.substring(0, 16);
                    cleanTags.add(safeTag);
                }
                if (cleanTags.size() >= 2) break;
            }
        }
        String tagBlock = cleanTags.isEmpty() ? "" : "[" + String.join("_", cleanTags) + "]";
        String ext = extensionFromUrl(imageUrl);
        return "[" + author + "][" + id + "]" + tagBlock + "_p" + Math.max(0, pageIndex) + ext;
    }

    private static MediaBundle buildPixivGalleryBundle(String title, String author, String userId,
                                                       Iterable<String> tags, List<MediaBundle.ImageItem> images) {
        List<String> tagList = new ArrayList<>();
        if (tags != null) {
            Set<String> dedup = new LinkedHashSet<>();
            for (String t : tags) {
                if (t != null && !t.isBlank()) dedup.add(t.trim());
            }
            tagList.addAll(dedup);
        }
        return MediaBundle.builder(MediaBundle.Type.GALLERY)
                .title(title)
                .author(author)
                .platform("Pixiv")
                .tags(tagList)
                .referer(PIXIV_REFERER)
                .header("Referer", PIXIV_REFERER)
                .header("User-Agent", HttpClient.DESKTOP_UA)
                .watermarkFree(true)
                .subfolder(buildSubfolder(author, userId))
                .images(images)
                .build();
    }

    private static List<MediaBundle.ImageItem> extractArtworkItems(JSONObject body, JSONObject pagesJson) {
        String illustId = body.optString("illustId", body.optString("id", ""));
        String userName = body.optString("userName", "");
        boolean aiGenerated = body.optInt("aiType", 0) == 2;
        boolean r18 = body.optInt("xRestrict", 0) > 0;
        List<String> tags = extractTags(body);
        List<MediaBundle.ImageItem> items = new ArrayList<>();

        JSONArray pages = pagesJson == null ? null : pagesJson.optJSONArray("body");
        if (pages != null && pages.length() > 0) {
            for (int i = 0; i < pages.length(); i++) {
                JSONObject page = pages.optJSONObject(i);
                if (page == null) continue;
                JSONObject urls = page.optJSONObject("urls");
                String original = urls == null ? "" : urls.optString("original", urls.optString("regular", ""));
                if (!LinkInput.isWebUrl(original)) continue;
                int w = page.optInt("width", body.optInt("width", 0));
                int h = page.optInt("height", body.optInt("height", 0));
                String filename = formatFilename(userName, illustId, tags, i, original);
                items.add(new MediaBundle.ImageItem(original, List.of(), filename, w, h, aiGenerated, r18, tags));
            }
            return items;
        }

        JSONObject urls = body.optJSONObject("urls");
        String original = urls == null ? "" : urls.optString("original", urls.optString("regular", ""));
        if (LinkInput.isWebUrl(original)) {
            int w = body.optInt("width", 0);
            int h = body.optInt("height", 0);
            String filename = formatFilename(userName, illustId, tags, 0, original);
            items.add(new MediaBundle.ImageItem(original, List.of(), filename, w, h, aiGenerated, r18, tags));
        }
        return items;
    }

    private static List<MediaBundle.ImageItem> extractSummaryItems(String id, String fallbackAuthor, JSONObject summary) {
        String userName = summary.optString("userName", fallbackAuthor);
        boolean aiGenerated = summary.optInt("aiType", 0) == 2;
        boolean r18 = summary.optInt("xRestrict", 0) > 0;
        List<String> tags = extractTags(summary);
        int w = summary.optInt("width", 0);
        int h = summary.optInt("height", 0);

        String url = "";
        JSONObject urls = summary.optJSONObject("urls");
        if (urls != null) {
            url = urls.optString("original", urls.optString("regular", ""));
        }
        if (url.isBlank()) {
            url = summary.optString("url", "");
        }
        if (!LinkInput.isWebUrl(url) || ImageFilter.isJunkUrl(url)) return List.of();
        String filename = formatFilename(userName, id, tags, 0, url);
        return List.of(new MediaBundle.ImageItem(url, List.of(), filename, w, h, aiGenerated, r18, tags));
    }

    private static Map<String, JSONObject> indexArtistSummaries(JSONObject topJson, List<JSONObject> batchJsons) {
        Map<String, JSONObject> map = new LinkedHashMap<>();
        if (topJson != null && !topJson.optBoolean("error", false)) {
            JSONObject body = topJson.optJSONObject("body");
            if (body != null) {
                putWorkEntries(body.optJSONObject("illusts"), map);
                putWorkEntries(body.optJSONObject("manga"), map);
            }
        }
        if (batchJsons != null) {
            for (JSONObject batch : batchJsons) {
                if (batch == null || batch.optBoolean("error", false)) continue;
                JSONObject body = batch.optJSONObject("body");
                if (body == null) continue;
                JSONObject works = body.optJSONObject("works");
                putWorkEntries(works != null ? works : body, map);
            }
        }
        return map;
    }

    private static void putWorkEntries(JSONObject source, Map<String, JSONObject> target) {
        if (source == null) return;
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject obj = source.optJSONObject(key);
            if (obj != null) target.put(key, obj);
        }
    }

    private static String extractAuthorFromSummaryOrTop(String uid, JSONObject topJson, Map<String, JSONObject> summaries) {
        for (JSONObject obj : summaries.values()) {
            String name = obj.optString("userName", "");
            if (!name.isBlank()) return name;
        }
        if (topJson != null) {
            JSONObject body = topJson.optJSONObject("body");
            JSONObject extra = body == null ? null : body.optJSONObject("extraData");
            JSONObject meta = extra == null ? null : extra.optJSONObject("meta");
            if (meta != null) {
                String title = meta.optString("title", "");
                if (!title.isBlank()) return title.replace(" - pixiv", "").trim();
            }
        }
        return "user_" + uid;
    }

    private static List<String> extractTags(JSONObject obj) {
        List<String> result = new ArrayList<>();
        Object rawTags = obj.opt("tags");
        if (rawTags instanceof JSONObject tagsObj) {
            JSONArray arr = tagsObj.optJSONArray("tags");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.optJSONObject(i);
                    if (item != null) {
                        String tag = item.optString("tag", "").trim();
                        if (!tag.isEmpty()) result.add(tag);
                    }
                }
            }
        } else if (rawTags instanceof JSONArray arr) {
            for (int i = 0; i < arr.length(); i++) {
                String tag = arr.optString(i, "").trim();
                if (!tag.isEmpty()) result.add(tag);
            }
        }
        return result;
    }

    private static JSONObject getPixivJson(String url, Net.Cancellation cancel) throws IOException {
        Map<String, String> headers = Map.of(
                "Referer", PIXIV_REFERER,
                "User-Agent", HttpClient.DESKTOP_UA,
                "Accept", "application/json"
        );
        HttpClient.HttpResult res = HttpClient.get(url, headers, cancel);
        try {
            return new JSONObject(res.body);
        } catch (Exception e) {
            throw new IOException("Pixiv 返回内容不是有效的 JSON（可能需要登录 Cookie）", e);
        }
    }

    private static JSONObject requireBodyObject(JSONObject root, String fallbackError) {
        if (root == null) throw new IllegalArgumentException(fallbackError);
        if (root.optBoolean("error", false)) {
            String msg = root.optString("message", "");
            throw new IllegalArgumentException(msg.isBlank() ? fallbackError : "Pixiv 访问失败: " + msg);
        }
        JSONObject body = root.optJSONObject("body");
        if (body == null) throw new IllegalArgumentException(fallbackError);
        return body;
    }

    private static String buildBatchIllustsUrl(String uid, TargetType type, List<String> ids) {
        StringBuilder sb = new StringBuilder("https://www.pixiv.net/ajax/user/")
                .append(uid)
                .append("/profile/illusts?");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append('&');
            sb.append("ids[]=").append(ids.get(i));
        }
        String category = type == TargetType.USER_MANGA ? "manga" : "illust";
        sb.append("&work_category=").append(category).append("&is_first_page=1");
        return sb.toString();
    }

    private static void addNumericKeys(JSONObject obj, Set<String> out) {
        if (obj == null) return;
        Iterator<String> keys = obj.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key != null && key.matches("\\d+")) out.add(key);
        }
    }

    private static long parseLongSafe(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return 0L; }
    }

    private static String extensionFromUrl(String url) {
        if (url == null) return ".jpg";
        String lower = url.toLowerCase(Locale.ROOT);
        int q = lower.indexOf('?');
        if (q >= 0) lower = lower.substring(0, q);
        if (lower.endsWith(".png")) return ".png";
        if (lower.endsWith(".jpeg")) return ".jpeg";
        if (lower.endsWith(".webp")) return ".webp";
        if (lower.endsWith(".gif")) return ".gif";
        return ".jpg";
    }

    private static String queryParam(String rawQuery, String key) {
        if (rawQuery == null || rawQuery.isEmpty()) return null;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            try {
                String decodedKey = URLDecoder.decode(k, "UTF-8");
                if (key.equals(decodedKey)) {
                    return eq >= 0 ? URLDecoder.decode(pair.substring(eq + 1), "UTF-8") : "";
                }
            } catch (Exception ignored) {}
        }
        return null;
    }
}
