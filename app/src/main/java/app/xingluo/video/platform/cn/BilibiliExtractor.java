package app.xingluo.video.platform.cn;

import app.xingluo.video.LinkInput;
import app.xingluo.video.Net;
import app.xingluo.video.Preferences;
import app.xingluo.video.VideoQuality;
import app.xingluo.video.engine.HttpClient;
import app.xingluo.video.model.MediaBundle;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Bilibili extractor supporting WBI-signed DASH 4K/HEVC/AVC/audio-only streams,
 * b23.tv short links, BV/av, multi-P (?p=N), and live.bilibili.com rooms.
 */
public final class BilibiliExtractor {
    public static final String PLATFORM = "哔哩哔哩";
    private static final String DEFAULT_REFERER = "https://www.bilibili.com/";
    private static final String LIVE_REFERER = "https://live.bilibili.com/";
    private static final long WBI_CACHE_TTL_MS = 15L * 60L * 1000L;

    static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };

    private static final Pattern BV_PATTERN = Pattern.compile("(?i)BV[1-9A-HJ-NP-Za-km-z]{10}");
    private static final Pattern AV_PATTERN = Pattern.compile("(?i)(?:/av|[?&]aid=)(\\d+)");
    private static final Pattern LIVE_ROOM_PATTERN = Pattern.compile("(?i)live\\.bilibili\\.com/(?:h5/)?(\\d+)");

    private static volatile WbiKeys cachedWbiKeys;

    public static final class WbiKeys {
        public final String imgKey;
        public final String subKey;
        public final long fetchedAtMs;

        public WbiKeys(String imgKey, String subKey, long fetchedAtMs) {
            this.imgKey = imgKey;
            this.subKey = subKey;
            this.fetchedAtMs = fetchedAtMs;
        }
    }

    public static final class ViewMetadata {
        public final String bvid;
        public final long aid;
        public final long cid;
        public final String title;
        public final String author;
        public final List<String> tags;

        public ViewMetadata(String bvid, long aid, long cid, String title, String author, List<String> tags) {
            this.bvid = bvid;
            this.aid = aid;
            this.cid = cid;
            this.title = title;
            this.author = author;
            this.tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    private BilibiliExtractor() {}

    public static boolean supports(String url) {
        if (url == null || url.isBlank()) return false;
        if (BV_PATTERN.matcher(url.trim()).matches()) return true;
        if (!LinkInput.isWebUrl(url)) return false;
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            String h = host.toLowerCase(Locale.ROOT);
            return LinkInput.hostIs(h, "bilibili.com")
                    || LinkInput.hostIs(h, "b23.tv")
                    || LinkInput.hostIs(h, "live.bilibili.com");
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
        String resolved = normalized;
        if (needsRedirectResolution(resolved)) {
            resolved = HttpClient.resolveRedirectUrl(resolved, headers(DEFAULT_REFERER), cancel);
        }
        if (isLiveUrl(resolved)) {
            return extractLive(resolved, preferHevc, cancel);
        }

        int page = LinkInput.page(resolved);
        if (page == 1) page = LinkInput.page(normalized);

        String viewQuery = buildVideoIdQuery(resolved);
        if (viewQuery == null) viewQuery = buildVideoIdQuery(normalized);
        if (viewQuery == null) {
            throw new IllegalArgumentException("请分享 B 站单个视频的 BV / av 链接");
        }

        HttpClient.HttpResult viewResp = HttpClient.get(
                "https://api.bilibili.com/x/web-interface/view?" + viewQuery,
                headers(DEFAULT_REFERER),
                cancel
        );
        ViewMetadata meta = parseViewMetadata(new JSONObject(viewResp.body), page);
        List<String> tags = new ArrayList<>(meta.tags);
        fetchArchiveTags(meta.bvid, tags, cancel);

        String watchReferer = !meta.bvid.isBlank()
                ? "https://www.bilibili.com/video/" + meta.bvid + "/"
                : DEFAULT_REFERER;
        int qn = qualityToQn(maxQuality);

        Exception lastError = null;
        MediaBundle bestBundle = null;
        // 1. WBI signed DASH playurl (with try_look=1 to unlock 720p/1080p when available without login)
        try {
            WbiKeys keys = getWbiKeys(cancel);
            Map<String, String> params = new LinkedHashMap<>();
            if (!meta.bvid.isBlank()) params.put("bvid", meta.bvid);
            else params.put("avid", String.valueOf(meta.aid));
            params.put("cid", String.valueOf(meta.cid));
            params.put("qn", String.valueOf(qn));
            params.put("fnval", "4048");
            params.put("fnver", "0");
            params.put("fourk", "1");
            params.put("try_look", "1");
            String signedUrl = signWbiUrl(
                    "https://api.bilibili.com/x/player/wbi/playurl",
                    params,
                    keys.imgKey,
                    keys.subKey,
                    System.currentTimeMillis() / 1000L
            );
            HttpClient.HttpResult playResp = HttpClient.get(signedUrl, headers(watchReferer), cancel);
            bestBundle = parseVideoPlayUrl(new JSONObject(playResp.body), meta.title, meta.author, tags,
                    watchReferer, maxQuality, preferHevc, audioOnly);
            int maxGot = bestBundle.qualityOptions.isEmpty() ? 0 : Collections.max(bestBundle.qualityOptions);
            if (maxGot >= Math.min(maxQuality, 720)) return bestBundle;
        } catch (Exception e) {
            Net.check(cancel);
            lastError = e;
        }

        // 2. Fallback: legacy playurl DASH (with try_look=1), then MP4 (fnval=0) preserving quality qn
        String idParam = !meta.bvid.isBlank() ? "bvid=" + meta.bvid : "avid=" + meta.aid;
        String[] fallbacks = {
                "https://api.bilibili.com/x/player/playurl?" + idParam + "&cid=" + meta.cid + "&qn=" + qn + "&fnval=4048&fnver=0&fourk=1&try_look=1",
                "https://api.bilibili.com/x/player/playurl?" + idParam + "&cid=" + meta.cid + "&qn=" + qn + "&fnval=0&high_quality=1",
                "https://api.bilibili.com/x/player/playurl?" + idParam + "&cid=" + meta.cid + "&qn=" + qn + "&fnval=0&high_quality=1&platform=html5"
        };
        for (String fallbackUrl : fallbacks) {
            Net.check(cancel);
            try {
                HttpClient.HttpResult playResp = HttpClient.get(fallbackUrl, headers(watchReferer), cancel);
                MediaBundle candidate = parseVideoPlayUrl(new JSONObject(playResp.body), meta.title, meta.author, tags,
                        watchReferer, maxQuality, preferHevc, audioOnly);
                if (bestBundle == null) bestBundle = candidate;
                else {
                    int cMax = candidate.qualityOptions.isEmpty() ? 0 : Collections.max(candidate.qualityOptions);
                    int bMax = bestBundle.qualityOptions.isEmpty() ? 0 : Collections.max(bestBundle.qualityOptions);
                    if (cMax > bMax) bestBundle = candidate;
                }
                int bMax = bestBundle.qualityOptions.isEmpty() ? 0 : Collections.max(bestBundle.qualityOptions);
                if (bMax >= Math.min(maxQuality, 720)) return bestBundle;
            } catch (Exception e) {
                Net.check(cancel);
                lastError = e;
            }
        }
        if (bestBundle != null) return bestBundle;
        throw lastError != null ? lastError : new IllegalArgumentException("没有获取到可用的 B 站视频流");
    }

    private static MediaBundle extractLive(String url, boolean preferHevc, Net.Cancellation cancel) throws Exception {
        Matcher m = LIVE_ROOM_PATTERN.matcher(url);
        if (!m.find()) throw new IllegalArgumentException("未识别到 B 站直播间房间号");
        String rawRoomId = m.group(1);
        String realRoomId = rawRoomId;
        String title = "B 站直播间 " + rawRoomId;
        String author = "";
        List<String> tags = new ArrayList<>();
        long uid = 0L;

        try {
            HttpClient.HttpResult infoResp = HttpClient.get(
                    "https://api.live.bilibili.com/xlive/web-room/v1/room/get_info?room_id=" + rawRoomId,
                    headers(LIVE_REFERER),
                    cancel
            );
            JSONObject infoJson = new JSONObject(infoResp.body);
            JSONObject data = infoJson.optJSONObject("data");
            if (infoJson.optInt("code", -1) == 0 && data != null) {
                long rid = data.optLong("room_id", 0L);
                if (rid > 0) realRoomId = String.valueOf(rid);
                String t = data.optString("title", "").trim();
                if (!t.isEmpty()) title = t;
                String area = data.optString("area_name", "").trim();
                if (!area.isEmpty()) tags.add(area);
                uid = data.optLong("uid", 0L);
            }
        } catch (Exception ignored) {
            Net.check(cancel);
        }

        if (uid > 0) {
            try {
                HttpClient.HttpResult anchorResp = HttpClient.get(
                        "https://api.live.bilibili.com/xlive/web-room/v1/room/get_status_info_by_uids?uids[]=" + uid,
                        headers(LIVE_REFERER),
                        cancel
                );
                JSONObject anchorJson = new JSONObject(anchorResp.body);
                JSONObject data = anchorJson.optJSONObject("data");
                if (data != null) {
                    JSONObject userObj = data.optJSONObject(String.valueOf(uid));
                    if (userObj != null) author = userObj.optString("uname", "").trim();
                }
            } catch (Exception ignored) {
                Net.check(cancel);
            }
        }

        String playUrl = "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?room_id="
                + realRoomId + "&protocol=0,1&format=0,1,2&codec=0,1&qn=10000";
        HttpClient.HttpResult playResp = HttpClient.get(playUrl, headers(LIVE_REFERER), cancel);
        return parseLivePlayInfo(new JSONObject(playResp.body), title, author, tags, url, preferHevc);
    }

    /** Extracts WBI key token (filename without extension) from a Bilibili nav img_url or sub_url. */
    public static String extractKeyFromUrl(String url) {
        if (url == null || url.isBlank()) return "";
        String clean = url;
        int q = clean.indexOf('?');
        if (q >= 0) clean = clean.substring(0, q);
        int slash = clean.lastIndexOf('/');
        String name = slash >= 0 ? clean.substring(slash + 1) : clean;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Derives the 32-character WBI mixin key from imgKey + subKey using MIXIN_KEY_ENC_TAB. */
    public static String getMixinKey(String imgKey, String subKey) {
        String raw = (imgKey == null ? "" : imgKey) + (subKey == null ? "" : subKey);
        if (raw.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32 && i < MIXIN_KEY_ENC_TAB.length; i++) {
            int idx = MIXIN_KEY_ENC_TAB[i];
            if (idx < raw.length()) {
                sb.append(raw.charAt(idx));
            }
        }
        return sb.toString();
    }

    /** Signs query parameters with WBI wts + sorted URL-encoded query + MD5 w_rid. */
    public static String signWbiParams(Map<String, String> params, String imgKey, String subKey, long wtsSeconds) {
        String mixinKey = getMixinKey(imgKey, subKey);
        TreeMap<String, String> sorted = new TreeMap<>();
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    sorted.put(e.getKey(), e.getValue());
                }
            }
        }
        sorted.put("wts", String.valueOf(wtsSeconds));

        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (query.length() > 0) query.append('&');
            String cleanValue = e.getValue().replaceAll("[!'()*]", "");
            query.append(encodeWbiComponent(e.getKey()))
                    .append('=')
                    .append(encodeWbiComponent(cleanValue));
        }
        String queryStr = query.toString();
        String wRid = md5Hex(queryStr + mixinKey);
        return queryStr + "&w_rid=" + wRid;
    }

    public static String signWbiUrl(String baseUrl, Map<String, String> params,
                                    String imgKey, String subKey, long wtsSeconds) {
        String signedQuery = signWbiParams(params, imgKey, subKey, wtsSeconds);
        String sep = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + sep + signedQuery;
    }

    static WbiKeys getWbiKeys(Net.Cancellation cancel) throws IOException {
        WbiKeys current = cachedWbiKeys;
        long now = System.currentTimeMillis();
        if (current != null && (now - current.fetchedAtMs) < WBI_CACHE_TTL_MS) {
            return current;
        }
        synchronized (BilibiliExtractor.class) {
            current = cachedWbiKeys;
            now = System.currentTimeMillis();
            if (current != null && (now - current.fetchedAtMs) < WBI_CACHE_TTL_MS) {
                return current;
            }
            HttpClient.HttpResult resp = HttpClient.get(
                    "https://api.bilibili.com/x/web-interface/nav",
                    headers(DEFAULT_REFERER),
                    cancel
            );
            JSONObject root;
            try { root = new JSONObject(resp.body); }
            catch (org.json.JSONException e) { throw new IOException("B 站密钥返回格式异常", e); }
            JSONObject data = root.optJSONObject("data");
            JSONObject wbiImg = data != null ? data.optJSONObject("wbi_img") : null;
            if (wbiImg == null) {
                throw new IOException("无法获取 B 站 WBI 签名密钥");
            }
            String imgKey = extractKeyFromUrl(wbiImg.optString("img_url", ""));
            String subKey = extractKeyFromUrl(wbiImg.optString("sub_url", ""));
            if (imgKey.isEmpty() || subKey.isEmpty()) {
                throw new IOException("B 站 WBI 签名密钥为空");
            }
            WbiKeys fresh = new WbiKeys(imgKey, subKey, System.currentTimeMillis());
            cachedWbiKeys = fresh;
            return fresh;
        }
    }

    static void clearWbiCacheForTest() {
        cachedWbiKeys = null;
    }

    public static ViewMetadata parseViewMetadata(JSONObject root, int requestedPage) {
        if (root.optInt("code", -1) != 0 || root.optJSONObject("data") == null) {
            throw new IllegalArgumentException("B 站没有返回视频信息：" + root.optString("message", "请在网页打开后重试"));
        }
        JSONObject data = root.optJSONObject("data");
        JSONArray pages = data != null ? data.optJSONArray("pages") : null;
        int pageIdx = Math.max(1, requestedPage) - 1;
        if (pages == null || pageIdx >= pages.length()) {
            throw new IllegalArgumentException("这个分 P 不存在");
        }
        JSONObject pageObj = pages.optJSONObject(pageIdx);
        long cid = pageObj != null ? pageObj.optLong("cid", 0L) : 0L;
        if (cid <= 0) throw new IllegalArgumentException("未获取到 B 站分 P 的 cid");
        String baseTitle = data.optString("title", "B 站视频").trim();
        if (baseTitle.isEmpty()) baseTitle = "B 站视频";
        String partName = pageObj.optString("part", "").trim();
        String finalTitle = pages.length() > 1
                ? baseTitle + " · P" + (pageIdx + 1) + (!partName.isEmpty() && !partName.equals(baseTitle) ? " " + partName : "")
                : baseTitle;

        JSONObject owner = data.optJSONObject("owner");
        String author = owner != null ? owner.optString("name", "").trim() : "";

        List<String> tags = new ArrayList<>();
        String tname = data.optString("tname", "").trim();
        if (!tname.isEmpty()) tags.add(tname);

        return new ViewMetadata(
                data.optString("bvid", ""),
                data.optLong("aid", 0L),
                cid,
                finalTitle,
                author,
                tags
        );
    }

    public static MediaBundle parseVideoPlayUrl(JSONObject root, String title, String author,
                                                List<String> tags, String referer,
                                                int maxQuality, boolean preferHevc, boolean audioOnly) {
        if (root.optInt("code", 0) != 0) {
            throw new IllegalArgumentException("B 站暂时拒绝访问：" + root.optString("message", "请在网页打开并重试"));
        }
        JSONObject data = root.optJSONObject("data");
        if (data == null) data = root.optJSONObject("result");
        if (data == null) data = root;
        if (data.optInt("is_preview", 0) == 1 || data.optBoolean("is_preview", false)) {
            throw new IllegalArgumentException("平台只返回试看内容，无法保存完整视频");
        }

        List<Integer> qualities = extractQualityOptions(data.optJSONArray("accept_quality"));
        Map<String, String> reqHeaders = headers(referer);

        JSONObject dash = data.optJSONObject("dash");
        if (dash != null) {
            JSONObject audioTrack = selectBestAudioTrack(dash);
            List<String> audioCandidates = audioTrack != null ? extractTrackUrls(audioTrack) : List.of();

            if (audioOnly) {
                if (audioCandidates.isEmpty()) {
                    throw new IllegalArgumentException("未找到可用的 B 站音频流");
                }
                return MediaBundle.builder(MediaBundle.Type.AUDIO)
                        .title(title)
                        .author(author)
                        .platform(PLATFORM)
                        .tags(tags)
                        .referer(referer)
                        .headers(reqHeaders)
                        .watermarkFree(true)
                        .qualityOptions(qualities)
                        .audioUrls(audioCandidates)
                        .build();
            }

            JSONObject videoTrack = selectBestVideoTrack(dash.optJSONArray("video"), maxQuality, preferHevc);
            if (videoTrack != null) {
                List<String> videoCandidates = extractTrackUrls(videoTrack);
                if (!videoCandidates.isEmpty()) {
                    return MediaBundle.builder(MediaBundle.Type.VIDEO)
                            .title(title)
                            .author(author)
                            .platform(PLATFORM)
                            .tags(tags)
                            .referer(referer)
                            .headers(reqHeaders)
                            .watermarkFree(true)
                            .qualityOptions(qualities)
                            .videoUrls(videoCandidates)
                            .audioUrls(audioCandidates)
                            .build();
                }
            }
        }

        JSONArray durl = data.optJSONArray("durl");
        if (durl != null && durl.length() > 0) {
            JSONObject d = durl.optJSONObject(0);
            if (d != null) {
                List<String> candidates = extractTrackUrls(d);
                candidates.removeIf(u -> u.toLowerCase(Locale.ROOT).contains(".flv"));
                if (!candidates.isEmpty()) {
                    MediaBundle.Builder b = MediaBundle.builder(audioOnly ? MediaBundle.Type.AUDIO : MediaBundle.Type.VIDEO)
                            .title(title)
                            .author(author)
                            .platform(PLATFORM)
                            .tags(tags)
                            .referer(referer)
                            .headers(reqHeaders)
                            .watermarkFree(true)
                            .qualityOptions(qualities);
                    if (audioOnly) b.audioUrls(candidates);
                    else b.videoUrls(candidates);
                    return b.build();
                }
            }
        }
        throw new IllegalArgumentException("没有获得可保存的完整 B 站视频流，请在网页打开后重试");
    }

    public static MediaBundle parseLivePlayInfo(JSONObject root, String title, String author,
                                                List<String> tags, String referer, boolean preferHevc) {
        if (root.optInt("code", -1) != 0) {
            throw new IllegalArgumentException("B 站直播信息获取失败：" + root.optString("message", "请稍后重试"));
        }
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            throw new IllegalArgumentException("B 站直播间未返回数据");
        }
        int liveStatus = data.optInt("live_status", 1);
        JSONObject playurlInfo = data.optJSONObject("playurl_info");
        JSONObject playurl = playurlInfo != null ? playurlInfo.optJSONObject("playurl") : null;
        JSONArray streams = playurl != null ? playurl.optJSONArray("stream") : null;

        List<String> hlsPreferred = new ArrayList<>();
        List<String> hlsFallback = new ArrayList<>();
        List<String> flvPreferred = new ArrayList<>();
        List<String> flvFallback = new ArrayList<>();

        if (streams != null) {
            for (int i = 0; i < streams.length(); i++) {
                JSONObject stream = streams.optJSONObject(i);
                if (stream == null) continue;
                String protocolName = stream.optString("protocol_name", "");
                JSONArray formats = stream.optJSONArray("format");
                if (formats == null) continue;
                for (int j = 0; j < formats.length(); j++) {
                    JSONObject fmt = formats.optJSONObject(j);
                    if (fmt == null) continue;
                    String formatName = fmt.optString("format_name", "");
                    JSONArray codecs = fmt.optJSONArray("codec");
                    if (codecs == null) continue;
                    for (int k = 0; k < codecs.length(); k++) {
                        JSONObject codec = codecs.optJSONObject(k);
                        if (codec == null) continue;
                        String codecName = codec.optString("codec_name", "").toLowerCase(Locale.ROOT);
                        String baseUrl = codec.optString("base_url", "");
                        JSONArray urlInfos = codec.optJSONArray("url_info");
                        if (baseUrl.isEmpty() || urlInfos == null) continue;

                        boolean isHevc = codecName.contains("hevc") || codecName.contains("h265");
                        boolean matchesPref = preferHevc ? isHevc : !isHevc;
                        boolean isHls = "http_hls".equalsIgnoreCase(protocolName)
                                || "ts".equalsIgnoreCase(formatName)
                                || "fmp4".equalsIgnoreCase(formatName)
                                || baseUrl.contains(".m3u8");

                        for (int u = 0; u < urlInfos.length(); u++) {
                            JSONObject info = urlInfos.optJSONObject(u);
                            if (info == null) continue;
                            String fullUrl = info.optString("host", "") + baseUrl + info.optString("extra", "");
                            if (!LinkInput.isWebUrl(fullUrl)) continue;
                            if (isHls) {
                                if (matchesPref) hlsPreferred.add(fullUrl);
                                else hlsFallback.add(fullUrl);
                            } else {
                                if (matchesPref) flvPreferred.add(fullUrl);
                                else flvFallback.add(fullUrl);
                            }
                        }
                    }
                }
            }
        }

        LinkedHashSet<String> allUrls = new LinkedHashSet<>();
        allUrls.addAll(hlsPreferred);
        allUrls.addAll(hlsFallback);
        allUrls.addAll(flvPreferred);
        allUrls.addAll(flvFallback);

        if (allUrls.isEmpty()) {
            if (liveStatus == 0) {
                throw new IllegalArgumentException("该 B 站直播间当前未开播");
            }
            throw new IllegalArgumentException("未获取到 B 站直播流地址");
        }

        String hlsUrl = !hlsPreferred.isEmpty() ? hlsPreferred.get(0)
                : (!hlsFallback.isEmpty() ? hlsFallback.get(0) : "");
        String primaryLive = !hlsUrl.isEmpty() ? hlsUrl : allUrls.iterator().next();

        return MediaBundle.builder(MediaBundle.Type.LIVE)
                .title(title)
                .author(author)
                .platform(PLATFORM)
                .tags(tags)
                .referer(referer)
                .headers(headers(LIVE_REFERER))
                .watermarkFree(true)
                .hlsUrl(hlsUrl)
                .liveUrl(primaryLive)
                .videoUrls(new ArrayList<>(allUrls))
                .build();
    }

    private static JSONObject selectBestVideoTrack(JSONArray videos, int maxQuality, boolean preferHevc) {
        if (videos == null) return null;
        JSONObject bestHevc = null;
        int hevcSize = 0;
        long hevcBw = -1;

        JSONObject bestAvc = null;
        int avcSize = 0;
        long avcBw = -1;

        JSONObject bestAny = null;
        int anySize = 0;
        long anyBw = -1;

        for (int i = 0; i < videos.length(); i++) {
            JSONObject o = videos.optJSONObject(i);
            if (o == null || extractTrackUrls(o).isEmpty()) continue;
            String codecs = o.optString("codecs", "").toLowerCase(Locale.ROOT);
            int size = VideoQuality.shortSide(o.optInt("width"), o.optInt("height"));
            long bw = o.optLong("bandwidth", 0L);

            if (bestAny == null || VideoQuality.better(size, bw, anySize, anyBw, maxQuality)) {
                bestAny = o;
                anySize = size;
                anyBw = bw;
            }
            if (codecs.startsWith("hev1") || codecs.startsWith("hvc1")) {
                if (bestHevc == null || VideoQuality.better(size, bw, hevcSize, hevcBw, maxQuality)) {
                    bestHevc = o;
                    hevcSize = size;
                    hevcBw = bw;
                }
            } else if (codecs.startsWith("avc")) {
                if (bestAvc == null || VideoQuality.better(size, bw, avcSize, avcBw, maxQuality)) {
                    bestAvc = o;
                    avcSize = size;
                    avcBw = bw;
                }
            }
        }

        if (!preferHevc) {
            if (bestAvc != null) return bestAvc;
            return bestHevc != null ? bestHevc : bestAny;
        }
        if (bestHevc != null) {
            if (bestAvc == null || avcSize == hevcSize || !VideoQuality.better(avcSize, 0L, hevcSize, 0L, maxQuality)) {
                return bestHevc;
            }
            return bestAvc;
        }
        return bestAvc != null ? bestAvc : bestAny;
    }

    private static JSONObject selectBestAudioTrack(JSONObject dash) {
        JSONObject best = pickAudioFromArray(dash.optJSONArray("audio"));
        if (best != null) return best;
        JSONObject dolby = dash.optJSONObject("dolby");
        if (dolby != null) {
            best = pickAudioFromArray(dolby.optJSONArray("audio"));
            if (best != null) return best;
        }
        JSONObject flac = dash.optJSONObject("flac");
        if (flac != null) {
            JSONObject flacAudio = flac.optJSONObject("audio");
            if (flacAudio != null && !extractTrackUrls(flacAudio).isEmpty()) return flacAudio;
        }
        return null;
    }

    private static JSONObject pickAudioFromArray(JSONArray list) {
        if (list == null) return null;
        JSONObject bestMp4a = null;
        long mp4aBw = -1;
        JSONObject bestAny = null;
        long anyBw = -1;
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null || extractTrackUrls(o).isEmpty()) continue;
            String codecs = o.optString("codecs", "").toLowerCase(Locale.ROOT);
            long bw = o.optLong("bandwidth", 0L);
            if (bestAny == null || bw > anyBw) {
                bestAny = o;
                anyBw = bw;
            }
            if (codecs.isEmpty() || codecs.startsWith("mp4a")) {
                if (bestMp4a == null || bw > mp4aBw) {
                    bestMp4a = o;
                    mp4aBw = bw;
                }
            }
        }
        return bestMp4a != null ? bestMp4a : bestAny;
    }

    private static List<String> extractTrackUrls(JSONObject track) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (String key : new String[]{"baseUrl", "base_url", "url"}) {
            String u = track.optString(key, "");
            if (LinkInput.isWebUrl(u)) urls.add(u);
        }
        for (String key : new String[]{"backupUrl", "backup_url"}) {
            JSONArray arr = track.optJSONArray(key);
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String u = arr.optString(i, "");
                    if (LinkInput.isWebUrl(u)) urls.add(u);
                }
            }
        }
        return new ArrayList<>(urls);
    }

    private static List<Integer> extractQualityOptions(JSONArray arr) {
        if (arr == null) return List.of();
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            int qn = arr.optInt(i, 0);
            if (qn > 0) list.add(qn);
        }
        return list;
    }

    private static void fetchArchiveTags(String bvid, List<String> tags, Net.Cancellation cancel) {
        if (bvid == null || bvid.isBlank()) return;
        try {
            HttpClient.HttpResult resp = HttpClient.get(
                    "https://api.bilibili.com/x/tag/archive/tags?bvid=" + bvid,
                    headers(DEFAULT_REFERER),
                    cancel
            );
            JSONObject root;
            try { root = new JSONObject(resp.body); }
            catch (org.json.JSONException e) { throw new IOException("B 站密钥返回格式异常", e); }
            JSONArray data = root.optJSONArray("data");
            if (root.optInt("code", -1) == 0 && data != null) {
                for (int i = 0; i < data.length(); i++) {
                    JSONObject item = data.optJSONObject(i);
                    if (item == null) continue;
                    String name = item.optString("tag_name", "").trim();
                    if (!name.isEmpty() && !tags.contains(name)) tags.add(name);
                }
            }
        } catch (Exception ignored) {
            Net.check(cancel);
        }
    }

    private static int qualityToQn(int maxQuality) {
        if (maxQuality >= 2160) return 120;
        if (maxQuality >= 1080) return 80;
        if (maxQuality >= 720) return 64;
        if (maxQuality >= 480) return 32;
        return 16;
    }

    private static boolean isLiveUrl(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null && LinkInput.hostIs(host.toLowerCase(Locale.ROOT), "live.bilibili.com");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean needsRedirectResolution(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host != null && LinkInput.hostIs(host.toLowerCase(Locale.ROOT), "b23.tv")) return true;
        } catch (Exception ignored) {}
        return buildVideoIdQuery(url) == null;
    }

    private static String buildVideoIdQuery(String url) {
        Matcher bv = BV_PATTERN.matcher(url);
        if (bv.find()) return "bvid=" + bv.group();
        Matcher av = AV_PATTERN.matcher(url);
        if (av.find()) return "aid=" + av.group(1);
        return null;
    }

    private static Map<String, String> headers(String referer) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("User-Agent", HttpClient.DESKTOP_UA);
        map.put("Referer", referer != null && !referer.isBlank() ? referer : DEFAULT_REFERER);
        return map;
    }

    private static String encodeWbiComponent(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }
}
