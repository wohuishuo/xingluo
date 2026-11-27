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
 * Kuaishou extractor supporting v.kuaishou.com, /f/, /short-video/, /fw/photo/,
 * cookie-preserving redirects, __APOLLO_STATE__ ($ref resolution), INIT_STATE / __INITIAL_STATE__,
 * GraphQL fallback, watermark-free MP4/HEVC/manifest streams, and atlas photo albums.
 */
public final class KuaishouExtractor {
    public static final String PLATFORM = "快手";
    private static final String DEFAULT_REFERER = "https://www.kuaishou.com/";
    private static final String GRAPHQL_ENDPOINT = "https://www.kuaishou.com/graphql";

    private static final Pattern PHOTO_ID_PATTERN = Pattern.compile(
            "(?:/short-video/|/fw/photo/|[?&]photoId=)([A-Za-z0-9_-]+)");
    private static final Pattern HASHTAG_PATTERN = Pattern.compile("#([^\\s#]{1,32})");

    private KuaishouExtractor() {}

    public static boolean supports(String url) {
        if (!LinkInput.isWebUrl(url)) return false;
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            String h = host.toLowerCase(Locale.ROOT);
            return LinkInput.hostIs(h, "kuaishou.com")
                    || LinkInput.hostIs(h, "chenzhongtech.com");
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
        String resolved = HttpClient.resolveRedirectUrl(normalized, mobileHeaders(DEFAULT_REFERER), cancel);
        String photoId = extractPhotoId(resolved);
        if (photoId == null) photoId = extractPhotoId(normalized);

        // 1. Mobile H5 SSR (INIT_STATE / __INITIAL_STATE__)
        try {
            HttpClient.HttpResult h5Resp = HttpClient.get(resolved, mobileHeaders(resolved), cancel);
            return parseHtml(h5Resp.body, h5Resp.finalUrl, maxQuality, preferHevc, audioOnly);
        } catch (Exception ignored) {
            Net.check(cancel);
        }

        // 2. Desktop SSR (__APOLLO_STATE__)
        if (photoId != null) {
            String desktopUrl = "https://www.kuaishou.com/short-video/" + photoId;
            try {
                HttpClient.HttpResult deskResp = HttpClient.get(desktopUrl, desktopHeaders(DEFAULT_REFERER), cancel);
                return parseHtml(deskResp.body, deskResp.finalUrl, maxQuality, preferHevc, audioOnly);
            } catch (Exception ignored) {
                Net.check(cancel);
            }

            // 3. GraphQL fallback
            try {
                JSONObject vars = new JSONObject()
                        .put("photoId", photoId)
                        .put("page", "detail");
                JSONObject payload = new JSONObject()
                        .put("operationName", "visionVideoDetail")
                        .put("variables", vars)
                        .put("query", "query visionVideoDetail($photoId: String, $page: String) {"
                                + " visionVideoDetail(photoId: $photoId, page: $page) {"
                                + " status type author { id name }"
                                + " photo { id caption coverUrl photoUrl photoH265Url"
                                + " manifest { adaptationSet { representation { id url backupUrl width height bitrate codecs } } } }"
                                + " } }");
                HttpClient.HttpResult gqlResp = HttpClient.postJson(
                        GRAPHQL_ENDPOINT,
                        payload.toString(),
                        desktopHeaders(desktopUrl),
                        cancel
                );
                return parseGraphql(new JSONObject(gqlResp.body), desktopUrl, maxQuality, preferHevc, audioOnly);
            } catch (Exception ignored) {
                Net.check(cancel);
            }
        }

        throw new IllegalArgumentException("快手暂未返回作品数据。请点网页识别，完成验证后重试。");
    }

    public static String extractPhotoId(String url) {
        if (url == null) return null;
        Matcher m = PHOTO_ID_PATTERN.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    public static MediaBundle parseHtml(String html, String pageUrl, int maxQuality,
                                        boolean preferHevc, boolean audioOnly) {
        if (html == null || html.isBlank()) {
            throw new IllegalArgumentException("快手页面内容为空");
        }

        // 1. Desktop Apollo normalized state
        int apolloPos = html.indexOf("window.__APOLLO_STATE__");
        if (apolloPos >= 0) {
            int start = html.indexOf('{', apolloPos);
            String json = MediaParser.balancedJson(html, start);
            if (json != null) {
                try {
                    return parseApolloState(new JSONObject(json), pageUrl, maxQuality, preferHevc);
                } catch (Exception ignored) {}
            }
        }

        // 2. Mobile / H5 INIT_STATE or __INITIAL_STATE__
        for (String marker : new String[]{"window.INIT_STATE", "window.__INITIAL_STATE__"}) {
            int pos = html.indexOf(marker);
            if (pos < 0) continue;
            int start = html.indexOf('{', pos);
            String json = MediaParser.balancedJson(html, start);
            if (json != null) {
                try {
                    return parseInitState(new JSONObject(json), pageUrl, maxQuality, preferHevc);
                } catch (Exception ignored) {}
            }
        }

        throw new IllegalArgumentException("快手页面未包含可解析的视频或图集状态");
    }

    public static MediaBundle parseGraphql(JSONObject root, String pageUrl, int maxQuality,
                                           boolean preferHevc, boolean audioOnly) {
        JSONObject data = root.optJSONObject("data");
        JSONObject detail = data != null ? data.optJSONObject("visionVideoDetail") : root.optJSONObject("visionVideoDetail");
        if (detail == null) {
            throw new IllegalArgumentException("快手 GraphQL 未返回 visionVideoDetail");
        }
        JSONObject photo = detail.optJSONObject("photo");
        if (photo == null) {
            throw new IllegalArgumentException("快手 GraphQL 未返回作品详情");
        }
        String author = "";
        JSONObject authorObj = detail.optJSONObject("author");
        if (authorObj != null) author = authorObj.optString("name", "").trim();
        return parsePhotoObject(photo, author, pageUrl, maxQuality, preferHevc);
    }

    private static MediaBundle parseApolloState(JSONObject root, String pageUrl, int maxQuality, boolean preferHevc) {
        JSONObject client = root.optJSONObject("defaultClient");
        if (client == null) client = root;

        JSONObject photo = null;
        Iterator<String> keys = client.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.startsWith("VisionVideoDetailPhoto:") || key.contains("Photo:")) {
                JSONObject candidate = client.optJSONObject(key);
                if (candidate != null && (candidate.has("photoUrl") || candidate.has("photoH265Url")
                        || candidate.has("manifest") || candidate.has("atlas"))) {
                    photo = candidate;
                    break;
                }
            }
        }
        if (photo == null) {
            throw new IllegalArgumentException("快手 Apollo 状态中未找到作品");
        }

        String author = resolveApolloAuthor(photo, client, root);
        return parsePhotoObject(photo, author, pageUrl, maxQuality, preferHevc);
    }

    private static String resolveApolloAuthor(JSONObject photo, JSONObject client, JSONObject root) {
        Object authorVal = photo.opt("author");
        if (authorVal instanceof JSONObject aObj) {
            String ref = aObj.optString("$ref", aObj.optString("id", ""));
            if (!ref.isEmpty()) {
                JSONObject target = client.optJSONObject(ref);
                if (target == null) target = root.optJSONObject(ref);
                if (target != null) {
                    String name = target.optString("name", target.optString("userName", "")).trim();
                    if (!name.isEmpty()) return name;
                }
            }
            String direct = aObj.optString("name", aObj.optString("userName", "")).trim();
            if (!direct.isEmpty()) return direct;
        }
        Iterator<String> keys = client.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (k.startsWith("VisionVideoDetailAuthor:") || k.startsWith("User:")) {
                JSONObject u = client.optJSONObject(k);
                if (u != null) {
                    String name = u.optString("name", u.optString("userName", "")).trim();
                    if (!name.isEmpty()) return name;
                }
            }
        }
        return "";
    }

    private static MediaBundle parseInitState(JSONObject root, String pageUrl, int maxQuality, boolean preferHevc) {
        JSONObject photo = findPhotoObject(root, 0);
        if (photo == null) {
            throw new IllegalArgumentException("快手 INIT_STATE 中未找到作品");
        }
        String author = extractAuthorFromNode(photo, root);
        return parsePhotoObject(photo, author, pageUrl, maxQuality, preferHevc);
    }

    private static MediaBundle parsePhotoObject(JSONObject photo, String fallbackAuthor, String pageUrl,
                                                int maxQuality, boolean preferHevc) {
        String title = cleanTitle(photo.optString("caption", photo.optString("title", "快手作品")));
        if (title.isEmpty()) title = "快手作品";

        String author = fallbackAuthor == null ? "" : fallbackAuthor.trim();
        if (author.isEmpty()) {
            JSONObject aObj = photo.optJSONObject("author");
            if (aObj != null) author = aObj.optString("name", "").trim();
            if (author.isEmpty()) author = photo.optString("userName", "").trim();
        }
        List<String> tags = extractHashtags(title);
        Map<String, String> headers = mobileHeaders(pageUrl);

        // 1. Check atlas / photo album first
        JSONObject atlas = photo.optJSONObject("atlas");
        if (atlas == null) {
            JSONObject ext = photo.optJSONObject("ext_params");
            if (ext != null) atlas = ext.optJSONObject("atlas");
        }
        if (atlas != null) {
            List<MediaBundle.ImageItem> images = parseAtlasImages(atlas, title, tags);
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

        // 2. Watermark-free video selection
        List<String> videoUrls = selectVideoUrls(photo, maxQuality, preferHevc);
        if (videoUrls.isEmpty()) {
            throw new IllegalArgumentException("快手作品未包含可用的无水印视频流");
        }
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

    private static List<MediaBundle.ImageItem> parseAtlasImages(JSONObject atlas, String title, List<String> tags) {
        List<String> cdns = new ArrayList<>();
        String primaryCdn = atlas.optString("cdn", "").trim();
        if (!primaryCdn.isEmpty()) cdns.add(normalizeHost(primaryCdn));
        JSONArray cdnList = atlas.optJSONArray("cdnList");
        if (cdnList != null) {
            for (int i = 0; i < cdnList.length(); i++) {
                Object entry = cdnList.opt(i);
                String host = entry instanceof JSONObject o ? o.optString("cdn", o.optString("host", "")) : String.valueOf(entry);
                String norm = normalizeHost(host);
                if (!norm.isEmpty() && !cdns.contains(norm)) cdns.add(norm);
            }
        }

        JSONArray list = atlas.optJSONArray("list");
        if (list == null || list.length() == 0) return List.of();

        List<MediaBundle.ImageItem> items = new ArrayList<>();
        String baseName = LinkInput.safeName(title);
        for (int i = 0; i < list.length(); i++) {
            Object entry = list.opt(i);
            String uri = entry instanceof JSONObject o
                    ? o.optString("url", o.optString("uri", ""))
                    : (entry != null ? entry.toString() : "");
            uri = uri.trim();
            if (uri.isEmpty()) continue;

            List<String> urls = new ArrayList<>();
            if (LinkInput.isWebUrl(uri)) {
                urls.add(uri);
            } else if (!cdns.isEmpty()) {
                String path = uri.startsWith("/") ? uri : "/" + uri;
                for (String host : cdns) {
                    String assembled = "https://" + host + path;
                    if (LinkInput.isWebUrl(assembled) && !urls.contains(assembled)) {
                        urls.add(assembled);
                    }
                }
            }
            if (urls.isEmpty()) continue;
            String primary = urls.get(0);
            List<String> backups = urls.size() > 1 ? urls.subList(1, urls.size()) : List.of();
            String ext = primary.toLowerCase(Locale.ROOT).contains(".webp") ? ".webp" : ".jpg";
            items.add(new MediaBundle.ImageItem(
                    primary,
                    backups,
                    baseName + "_" + (i + 1) + ext,
                    0,
                    0,
                    false,
                    false,
                    tags
            ));
        }
        return items;
    }

    private static List<String> selectVideoUrls(JSONObject photo, int maxQuality, boolean preferHevc) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();

        for (String key : new String[]{"srcNoWatermark", "srcNoMark"}) {
            String u = photo.optString(key, "").trim();
            if (LinkInput.isWebUrl(u)) urls.add(u);
        }

        // Manifest adaptationSet representation selection
        JSONObject manifest = photo.optJSONObject("manifest");
        if (manifest != null) {
            List<String> bestManifestUrls = selectBestManifestUrls(manifest.optJSONArray("adaptationSet"), maxQuality, preferHevc);
            urls.addAll(bestManifestUrls);
        }

        String h264 = photo.optString("photoUrl", "").trim();
        String h265 = photo.optString("photoH265Url", "").trim();
        if (preferHevc) {
            if (LinkInput.isWebUrl(h265)) urls.add(h265);
            if (LinkInput.isWebUrl(h264)) urls.add(h264);
        } else {
            if (LinkInput.isWebUrl(h264)) urls.add(h264);
            if (LinkInput.isWebUrl(h265)) urls.add(h265);
        }

        JSONArray mainMvUrls = photo.optJSONArray("mainMvUrls");
        if (mainMvUrls != null) {
            for (int i = 0; i < mainMvUrls.length(); i++) {
                JSONObject item = mainMvUrls.optJSONObject(i);
                if (item == null) continue;
                String u = item.optString("url", "").trim();
                if (LinkInput.isWebUrl(u)) urls.add(u);
            }
        }
        return new ArrayList<>(urls);
    }

    private static List<String> selectBestManifestUrls(JSONArray adaptationSets, int maxQuality, boolean preferHevc) {
        if (adaptationSets == null) return List.of();
        JSONObject bestRep = null;
        int bestSize = 0;
        long bestBitrate = -1;
        boolean bestHevc = false;

        for (int i = 0; i < adaptationSets.length(); i++) {
            JSONObject set = adaptationSets.optJSONObject(i);
            if (set == null) continue;
            JSONArray reps = set.optJSONArray("representation");
            if (reps == null) continue;
            for (int j = 0; j < reps.length(); j++) {
                JSONObject rep = reps.optJSONObject(j);
                if (rep == null) continue;
                String u = rep.optString("url", "").trim();
                if (!LinkInput.isWebUrl(u)) continue;

                int size = VideoQuality.shortSide(rep.optInt("width", 0), rep.optInt("height", 0));
                long bitrate = rep.optLong("bitrate", rep.optLong("maxBitrate", 0L));
                String codecs = rep.optString("codecs", "").toLowerCase(Locale.ROOT);
                boolean isHevc = codecs.contains("hevc") || codecs.contains("h265") || codecs.contains("hev1") || codecs.contains("hvc1");

                if (bestRep == null) {
                    bestRep = rep;
                    bestSize = size;
                    bestBitrate = bitrate;
                    bestHevc = isHevc;
                    continue;
                }
                if (size == bestSize) {
                    if (isHevc != bestHevc) {
                        if ((preferHevc && isHevc) || (!preferHevc && !isHevc)) {
                            bestRep = rep;
                            bestSize = size;
                            bestBitrate = bitrate;
                            bestHevc = isHevc;
                        }
                    } else if (bitrate > bestBitrate) {
                        bestRep = rep;
                        bestBitrate = bitrate;
                    }
                } else if (VideoQuality.better(size, bitrate, bestSize, bestBitrate, maxQuality)) {
                    bestRep = rep;
                    bestSize = size;
                    bestBitrate = bitrate;
                    bestHevc = isHevc;
                }
            }
        }

        if (bestRep == null) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String main = bestRep.optString("url", "").trim();
        if (LinkInput.isWebUrl(main)) out.add(main);
        JSONArray backups = bestRep.optJSONArray("backupUrl");
        if (backups != null) {
            for (int i = 0; i < backups.length(); i++) {
                String b = backups.optString(i, "").trim();
                if (LinkInput.isWebUrl(b)) out.add(b);
            }
        }
        return new ArrayList<>(out);
    }

    private static JSONObject findPhotoObject(Object node, int depth) {
        if (depth > 20 || node == null) return null;
        if (node instanceof JSONObject obj) {
            if (obj.has("photo") && obj.optJSONObject("photo") != null) {
                return obj.optJSONObject("photo");
            }
            if (obj.has("srcNoWatermark") || obj.has("srcNoMark")
                    || obj.has("photoUrl") || obj.has("mainMvUrls")
                    || obj.has("atlas") || obj.has("manifest")) {
                return obj;
            }
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                JSONObject found = findPhotoObject(obj.opt(keys.next()), depth + 1);
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray arr) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject found = findPhotoObject(arr.opt(i), depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String extractAuthorFromNode(JSONObject photo, JSONObject root) {
        JSONObject author = photo.optJSONObject("author");
        if (author != null) {
            String n = author.optString("name", author.optString("userName", "")).trim();
            if (!n.isEmpty()) return n;
        }
        String uName = photo.optString("userName", "").trim();
        if (!uName.isEmpty()) return uName;
        JSONObject rootAuthor = root.optJSONObject("author");
        if (rootAuthor != null) return rootAuthor.optString("name", "").trim();
        return "";
    }

    private static List<String> extractHashtags(String caption) {
        if (caption == null || caption.isEmpty()) return List.of();
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        Matcher m = HASHTAG_PATTERN.matcher(caption);
        while (m.find()) {
            String t = m.group(1).trim();
            if (!t.isEmpty()) tags.add(t);
        }
        return new ArrayList<>(tags);
    }

    private static String normalizeHost(String host) {
        if (host == null) return "";
        String h = host.trim();
        if (h.startsWith("https://")) h = h.substring(8);
        else if (h.startsWith("http://")) h = h.substring(7);
        while (h.endsWith("/")) h = h.substring(0, h.length() - 1);
        return h;
    }

    private static String cleanTitle(String s) {
        return s == null ? "" : s.replaceAll("<[^>]+>", "").strip().replaceAll("\\s+", " ");
    }

    private static Map<String, String> mobileHeaders(String referer) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("User-Agent", HttpClient.MOBILE_UA);
        map.put("Referer", referer != null && !referer.isBlank() ? referer : DEFAULT_REFERER);
        map.put("Accept-Language", "zh-CN,zh;q=0.9");
        return map;
    }

    private static Map<String, String> desktopHeaders(String referer) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("User-Agent", HttpClient.DESKTOP_UA);
        map.put("Referer", referer != null && !referer.isBlank() ? referer : DEFAULT_REFERER);
        map.put("Accept-Language", "zh-CN,zh;q=0.9");
        return map;
    }
}
