package app.xingluo.video.platform.global;

import app.xingluo.video.LinkInput;
import app.xingluo.video.MediaParser;
import app.xingluo.video.MediaPlan;
import app.xingluo.video.Net;
import app.xingluo.video.Preferences;
import app.xingluo.video.VideoQuality;
import app.xingluo.video.engine.HttpClient;
import app.xingluo.video.model.MediaBundle;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
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

/** Extracts video, audio, HLS, and gallery bundles across YouTube, X/Twitter, TikTok, Reddit, Vimeo, Weibo, AcFun, PeerTube, and generic web pages. */
public final class GlobalMediaExtractor {
    private static final String YT_VR_UA = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip";
    private static final String REDDIT_UA = "android:app.xingluo.video:v2.0.0 (by /u/xingluo_app)";

    private static final Pattern YT_ID_PATH = Pattern.compile("^/(?:shorts|embed|live|v)/([A-Za-z0-9_-]{11})(?:[/?#].*)?$");
    private static final Pattern YT_ID_STRICT = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern TWEET_ID = Pattern.compile("/(?:status|statuses)/(\\d+)");
    private static final Pattern VIMEO_PATH = Pattern.compile("^/(?:video/)?(\\d+)(?:/([A-Za-z0-9]+))?/?$");
    private static final Pattern WEIBO_ID = Pattern.compile("(?:detail|status|statuses)/([A-Za-z0-9]+)|weibo\\.com/\\d+/([A-Za-z0-9]+)");
    private static final Pattern ACFUN_ID = Pattern.compile("/v/ac(\\d+)");
    private static final Pattern M3U8_URL = Pattern.compile("https?://[^\\s\"'<>\\\\]+\\.m3u8(?:\\?[^\\s\"'<>\\\\]*)?", Pattern.CASE_INSENSITIVE);

    private GlobalMediaExtractor() {}

    public static MediaBundle extract(String input, Preferences prefs, Net.Cancellation cancel) throws IOException {
        int quality = prefs == null ? 1080 : prefs.quality();
        boolean audioOnly = prefs != null && prefs.audioOnly();
        boolean preferHevc = prefs == null || prefs.preferHevc();
        return extract(input, quality, audioOnly, preferHevc, cancel);
    }

    public static MediaBundle extract(String input, int preferredQuality, boolean audioOnly,
                                      boolean preferHevc, Net.Cancellation cancel) throws IOException {
        String url = LinkInput.extract(input);
        LinkInput.Platform platform = LinkInput.platform(url);
        if (platform == LinkInput.Platform.WEB && peerTubeApiUrl(url) != null) {
            platform = LinkInput.Platform.PEERTUBE;
        }
        return switch (platform) {
            case YOUTUBE -> extractYouTube(url, preferredQuality, audioOnly, preferHevc, cancel);
            case TWITTER -> extractTwitter(url, preferredQuality, cancel);
            case TIKTOK -> extractTikTok(url, preferredQuality, cancel);
            case REDDIT -> extractReddit(url, cancel);
            case VIMEO -> extractVimeo(url, preferredQuality, cancel);
            case WEIBO -> extractWeibo(url, cancel);
            case ACFUN -> extractAcFun(url, preferredQuality, cancel);
            case PEERTUBE -> extractPeerTube(url, preferredQuality, cancel);
            default -> extractGeneric(url, preferredQuality, cancel);
        };
    }

    // =========================================================================
    // 1. YouTube (Innertube ANDROID_VR + TVHTML5_SIMPLY_EMBEDDED_PLAYER)
    // =========================================================================

    private static MediaBundle extractYouTube(String url, int preferredQuality, boolean audioOnly,
                                              boolean preferHevc, Net.Cancellation cancel) throws IOException {
        String videoId = extractYouTubeId(url);
        if (videoId == null) {
            throw new IllegalArgumentException("无法从链接中识别 YouTube 视频 ID");
        }
        Exception primaryFailure = null;
        try {
            JSONObject vrPayload = buildYouTubeClientBody(videoId, "ANDROID_VR", "1.65.10", true);
            Map<String, String> vrHeaders = Map.of(
                    "User-Agent", YT_VR_UA,
                    "X-YouTube-Client-Name", "28",
                    "X-YouTube-Client-Version", "1.65.10"
            );
            HttpClient.HttpResult vrRes = HttpClient.postJson(
                    "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                    vrPayload.toString(), vrHeaders, cancel);
            return parseYouTubePlayer(new JSONObject(vrRes.body), videoId, preferredQuality, audioOnly, preferHevc);
        } catch (Exception e) {
            Net.check(cancel);
            primaryFailure = e;
        }

        try {
            JSONObject tvPayload = buildYouTubeClientBody(videoId, "TVHTML5_SIMPLY_EMBEDDED_PLAYER", "2.0", false);
            Map<String, String> tvHeaders = Map.of(
                    "User-Agent", HttpClient.DESKTOP_UA,
                    "Referer", "https://www.youtube.com/"
            );
            HttpClient.HttpResult tvRes = HttpClient.postJson(
                    "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                    tvPayload.toString(), tvHeaders, cancel);
            return parseYouTubePlayer(new JSONObject(tvRes.body), videoId, preferredQuality, audioOnly, preferHevc);
        } catch (Exception fallbackEx) {
            Net.check(cancel);
            if (primaryFailure instanceof IOException io) throw io;
            if (fallbackEx instanceof IOException io) throw io;
            String msg = fallbackEx.getMessage() != null ? fallbackEx.getMessage() : primaryFailure.getMessage();
            throw new IllegalArgumentException(msg != null ? msg : "YouTube 解析失败，请点「网页识别」后重试");
        }
    }

    static JSONObject buildYouTubeClientBody(String videoId, String clientName, String clientVersion, boolean vrDevice) {
        try {
            JSONObject client = new JSONObject();
            client.put("clientName", clientName);
            client.put("clientVersion", clientVersion);
            client.put("hl", "en");
            client.put("gl", "US");
            if (vrDevice) {
                client.put("deviceMake", "Oculus");
                client.put("deviceModel", "Quest 3");
                client.put("androidSdkVersion", 32);
                client.put("osName", "Android");
                client.put("osVersion", "12L");
            } else {
                client.put("clientScreen", "EMBED");
            }
            JSONObject context = new JSONObject().put("client", client);
            if (!vrDevice) {
                context.put("thirdParty", new JSONObject().put("embedUrl", "https://www.youtube.com/"));
            }
            return new JSONObject()
                    .put("context", context)
                    .put("videoId", videoId);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String extractYouTubeId(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = URI.create(url.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (LinkInput.hostIs(host, "youtu.be")) {
                String seg = path.startsWith("/") ? path.substring(1) : path;
                int slash = seg.indexOf('/');
                if (slash > 0) seg = seg.substring(0, slash);
                return YT_ID_STRICT.matcher(seg).matches() ? seg : null;
            }
            if (LinkInput.hostIs(host, "youtube.com") || LinkInput.hostIs(host, "youtube-nocookie.com")) {
                String v = queryParam(uri.getRawQuery(), "v");
                if (v != null && YT_ID_STRICT.matcher(v).matches()) return v;
                Matcher m = YT_ID_PATH.matcher(path);
                if (m.matches()) return m.group(1);
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static MediaBundle parseYouTubePlayer(JSONObject root, String videoId, int preferredQuality,
                                                 boolean audioOnly, boolean preferHevc) {
        if (root == null) throw new IllegalArgumentException("YouTube 未返回播放器数据");
        JSONObject playability = root.optJSONObject("playabilityStatus");
        if (playability != null) {
            String status = playability.optString("status", "OK");
            if (!"OK".equalsIgnoreCase(status)) {
                String reason = playability.optString("reason", "");
                String low = (status + " " + reason).toLowerCase(Locale.ROOT);
                if (low.contains("login") || low.contains("sign in") || low.contains("bot")
                        || low.contains("po") || low.contains("token") || low.contains("age")
                        || low.contains("confirm")) {
                    throw new IllegalArgumentException(
                            "YouTube 需要登录验证或 PO Token（" + status
                                    + (reason.isEmpty() ? "" : ": " + reason)
                                    + "），请点「网页识别」登录后重试");
                }
                throw new IllegalArgumentException("YouTube 视频不可播放（" + status
                        + (reason.isEmpty() ? "" : ": " + reason) + "）");
            }
        }

        JSONObject details = root.optJSONObject("videoDetails");
        String title = details == null ? "YouTube 视频" : details.optString("title", "YouTube 视频");
        String author = details == null ? "" : details.optString("author", "");
        boolean isLive = details != null && details.optBoolean("isLiveContent", false);

        JSONObject streaming = root.optJSONObject("streamingData");
        if (streaming == null) {
            throw new IllegalArgumentException("YouTube 未返回流媒体地址（可能需要 PO Token 或登录）");
        }

        boolean sawCipherOnly = false;
        List<JSONObject> progressive = new ArrayList<>();
        List<JSONObject> adaptiveVideo = new ArrayList<>();
        List<JSONObject> adaptiveAudio = new ArrayList<>();
        Set<Integer> heights = new LinkedHashSet<>();

        JSONArray formats = streaming.optJSONArray("formats");
        if (formats != null) {
            for (int i = 0; i < formats.length(); i++) {
                JSONObject fmt = formats.optJSONObject(i);
                if (fmt == null) continue;
                String streamUrl = fmt.optString("url", "");
                if (!LinkInput.isWebUrl(streamUrl)) {
                    if (fmt.has("signatureCipher") || fmt.has("cipher")) sawCipherOnly = true;
                    continue;
                }
                progressive.add(fmt);
                int h = fmt.optInt("height", 0);
                if (h > 0) heights.add(h);
            }
        }

        JSONArray adaptive = streaming.optJSONArray("adaptiveFormats");
        if (adaptive != null) {
            for (int i = 0; i < adaptive.length(); i++) {
                JSONObject fmt = adaptive.optJSONObject(i);
                if (fmt == null) continue;
                String streamUrl = fmt.optString("url", "");
                if (!LinkInput.isWebUrl(streamUrl)) {
                    if (fmt.has("signatureCipher") || fmt.has("cipher")) sawCipherOnly = true;
                    continue;
                }
                String mime = fmt.optString("mimeType", "").toLowerCase(Locale.ROOT);
                if (mime.startsWith("audio/")) {
                    adaptiveAudio.add(fmt);
                } else if (mime.startsWith("video/")) {
                    adaptiveVideo.add(fmt);
                    int h = fmt.optInt("height", 0);
                    if (h > 0) heights.add(h);
                }
            }
        }

        JSONObject bestAudio = selectBestYouTubeAudio(adaptiveAudio);
        if (audioOnly) {
            if (bestAudio != null) {
                return MediaBundle.builder(MediaBundle.Type.AUDIO)
                        .title(title)
                        .author(author)
                        .platform("YouTube")
                        .referer("https://www.youtube.com/")
                        .audioUrl(bestAudio.optString("url"))
                        .estimatedBytes(parseContentLength(bestAudio))
                        .watermarkFree(true)
                        .build();
            }
            JSONObject progFallback = selectBestYouTubeVideo(progressive, preferredQuality, preferHevc);
            if (progFallback != null) {
                return MediaBundle.builder(MediaBundle.Type.AUDIO)
                        .title(title)
                        .author(author)
                        .platform("YouTube")
                        .referer("https://www.youtube.com/")
                        .audioUrl(progFallback.optString("url"))
                        .estimatedBytes(parseContentLength(progFallback))
                        .watermarkFree(true)
                        .build();
            }
        }

        JSONObject bestProg = selectBestYouTubeVideo(progressive, preferredQuality, preferHevc);
        JSONObject bestAdaptiveVid = selectBestYouTubeVideo(adaptiveVideo, preferredQuality, preferHevc);
        List<Integer> qualityOptions = new ArrayList<>(heights);
        qualityOptions.sort(Collections.reverseOrder());

        if (bestAdaptiveVid != null && bestAudio != null) {
            boolean useAdaptive = bestProg == null || isStrictlyBetterFormat(bestAdaptiveVid, bestProg, preferredQuality);
            if (useAdaptive) {
                long est = sumKnownBytes(parseContentLength(bestAdaptiveVid), parseContentLength(bestAudio));
                return MediaBundle.builder(MediaBundle.Type.VIDEO)
                        .title(title)
                        .author(author)
                        .platform("YouTube")
                        .referer("https://www.youtube.com/")
                        .qualityOptions(qualityOptions)
                        .videoUrl(bestAdaptiveVid.optString("url"))
                        .audioUrl(bestAudio.optString("url"))
                        .estimatedBytes(est)
                        .watermarkFree(true)
                        .build();
            }
        }

        if (bestProg != null) {
            return MediaBundle.builder(MediaBundle.Type.VIDEO)
                    .title(title)
                    .author(author)
                    .platform("YouTube")
                    .referer("https://www.youtube.com/")
                    .qualityOptions(qualityOptions)
                    .videoUrl(bestProg.optString("url"))
                    .estimatedBytes(parseContentLength(bestProg))
                    .watermarkFree(true)
                    .build();
        }

        String hls = streaming.optString("hlsManifestUrl", "");
        if (LinkInput.isWebUrl(hls)) {
            return MediaBundle.builder(isLive ? MediaBundle.Type.LIVE : MediaBundle.Type.VIDEO)
                    .title(title)
                    .author(author)
                    .platform("YouTube")
                    .referer("https://www.youtube.com/")
                    .hlsUrl(hls)
                    .liveUrl(isLive ? hls : "")
                    .watermarkFree(true)
                    .build();
        }

        if (sawCipherOnly) {
            throw new IllegalArgumentException("YouTube 返回了签名加密流（需 PO Token 或 JS 解密），请点「网页识别」后重试");
        }
        throw new IllegalArgumentException("YouTube 未返回可用视频流（" + (videoId == null ? "" : videoId) + "）");
    }

    private static JSONObject selectBestYouTubeVideo(List<JSONObject> formats, int preferredQuality, boolean preferHevc) {
        JSONObject best = null;
        int bestSize = 0;
        long bestBitrate = -1;
        boolean bestIsMp4 = false;
        for (JSONObject fmt : formats) {
            String mime = fmt.optString("mimeType", "").toLowerCase(Locale.ROOT);
            if (!preferHevc && (mime.contains("hev1") || mime.contains("hvc1"))) continue;
            int size = VideoQuality.shortSide(fmt.optInt("width", 0), fmt.optInt("height", 0));
            if (size <= 0) size = parseQualityLabelPx(fmt.optString("qualityLabel", ""));
            long bitrate = fmt.optLong("bitrate", fmt.optLong("averageBitrate", 0));
            boolean isMp4 = mime.startsWith("video/mp4");
            if (best == null) {
                best = fmt;
                bestSize = size;
                bestBitrate = bitrate;
                bestIsMp4 = isMp4;
                continue;
            }
            if (size == bestSize && isMp4 != bestIsMp4) {
                if (isMp4) {
                    best = fmt;
                    bestSize = size;
                    bestBitrate = bitrate;
                    bestIsMp4 = true;
                }
                continue;
            }
            if (VideoQuality.better(size, bitrate, bestSize, bestBitrate, preferredQuality)) {
                best = fmt;
                bestSize = size;
                bestBitrate = bitrate;
                bestIsMp4 = isMp4;
            }
        }
        return best;
    }

    private static JSONObject selectBestYouTubeAudio(List<JSONObject> formats) {
        JSONObject best = null;
        long bestBitrate = -1;
        boolean bestMp4 = false;
        for (JSONObject fmt : formats) {
            String mime = fmt.optString("mimeType", "").toLowerCase(Locale.ROOT);
            boolean isMp4 = mime.startsWith("audio/mp4");
            long bitrate = fmt.optLong("bitrate", fmt.optLong("averageBitrate", 0));
            if (best == null || (isMp4 && !bestMp4) || (isMp4 == bestMp4 && bitrate > bestBitrate)) {
                best = fmt;
                bestBitrate = bitrate;
                bestMp4 = isMp4;
            }
        }
        return best;
    }

    private static boolean isStrictlyBetterFormat(JSONObject candidate, JSONObject baseline, int preferred) {
        int cSize = VideoQuality.shortSide(candidate.optInt("width", 0), candidate.optInt("height", 0));
        int bSize = VideoQuality.shortSide(baseline.optInt("width", 0), baseline.optInt("height", 0));
        if (cSize == bSize) return false;
        long cBitrate = candidate.optLong("bitrate", 0);
        long bBitrate = baseline.optLong("bitrate", 0);
        return VideoQuality.better(cSize, cBitrate, bSize, bBitrate, preferred);
    }

    // =========================================================================
    // 2. X / Twitter (fxtwitter primary + syndication fallback)
    // =========================================================================

    private static MediaBundle extractTwitter(String url, int preferredQuality, Net.Cancellation cancel) throws IOException {
        String tweetId = extractTweetId(url);
        if (tweetId == null) {
            throw new IllegalArgumentException("无法从 X/Twitter 链接识别推文 ID");
        }
        Exception fxFailure = null;
        try {
            HttpClient.HttpResult fx = HttpClient.get(
                    "https://api.fxtwitter.com/status/" + tweetId,
                    Map.of("User-Agent", HttpClient.DESKTOP_UA, "Accept", "application/json"),
                    cancel);
            return parseFxTwitter(new JSONObject(fx.body), url, preferredQuality);
        } catch (Exception e) {
            Net.check(cancel);
            fxFailure = e;
        }

        try {
            String token = twitterSyndicationToken(tweetId);
            HttpClient.HttpResult syn = HttpClient.get(
                    "https://cdn.syndication.twimg.com/tweet-result?id=" + tweetId + "&token=" + token,
                    Map.of("User-Agent", HttpClient.DESKTOP_UA, "Referer", "https://platform.twitter.com/"),
                    cancel);
            return parseTwitterSyndication(new JSONObject(syn.body), url, preferredQuality);
        } catch (Exception synEx) {
            Net.check(cancel);
            if (fxFailure instanceof IOException io) throw io;
            if (synEx instanceof IOException io) throw io;
            throw new IllegalArgumentException("未能提取 X/Twitter 媒体，请检查推文是否公开或点「网页识别」");
        }
    }

    public static String extractTweetId(String url) {
        if (url == null) return null;
        Matcher m = TWEET_ID.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /** Pure Java port of Twitter syndication token: ((Number(id) / 1e15) * Math.PI).toString(36).replace(/(0+|\.)/g, ''). */
    public static String twitterSyndicationToken(String tweetId) {
        double val = (Double.parseDouble(tweetId) / 1e15d) * Math.PI;
        return doubleToBase36(val).replaceAll("(0+|\\.)", "");
    }

    private static String doubleToBase36(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "";
        StringBuilder sb = new StringBuilder();
        if (value < 0) {
            sb.append('-');
            value = -value;
        }
        long intPart = (long) Math.floor(value);
        sb.append(Long.toString(intPart, 36));
        double frac = value - intPart;
        if (frac > 0d) {
            sb.append('.');
            for (int i = 0; i < 12 && frac > 1e-12d; i++) {
                frac *= 36d;
                int digit = (int) Math.floor(frac);
                sb.append(Character.forDigit(digit, 36));
                frac -= digit;
            }
        }
        return sb.toString();
    }

    public static MediaBundle parseFxTwitter(JSONObject root, String pageUrl, int preferredQuality) {
        if (root == null || root.optInt("code", 200) != 200) {
            throw new IllegalArgumentException("FxTwitter 未返回有效推文内容");
        }
        JSONObject tweet = root.optJSONObject("tweet");
        if (tweet == null) throw new IllegalArgumentException("推文数据为空");

        JSONObject authorObj = tweet.optJSONObject("author");
        String author = authorObj == null ? "" : authorObj.optString("name", authorObj.optString("screen_name", ""));
        String text = tweet.optString("text", "X / Twitter 媒体");
        JSONObject media = tweet.optJSONObject("media");
        if (media == null) throw new IllegalArgumentException("这条推文没有包含视频或图片");

        JSONArray videos = media.optJSONArray("videos");
        if (videos != null && videos.length() > 0) {
            JSONObject firstVideo = videos.optJSONObject(0);
            List<String> sortedUrls = sortTwitterVariants(firstVideo == null ? null : firstVideo.optJSONArray("variants"), preferredQuality);
            if (!sortedUrls.isEmpty()) {
                return MediaBundle.builder(MediaBundle.Type.VIDEO)
                        .title(text)
                        .author(author)
                        .platform("X / Twitter")
                        .referer("https://x.com/")
                        .videoUrls(sortedUrls)
                        .watermarkFree(true)
                        .build();
            }
            String direct = firstVideo == null ? "" : firstVideo.optString("url", "");
            if (LinkInput.isWebUrl(direct)) {
                return MediaBundle.builder(MediaBundle.Type.VIDEO)
                        .title(text)
                        .author(author)
                        .platform("X / Twitter")
                        .referer("https://x.com/")
                        .videoUrl(direct)
                        .watermarkFree(true)
                        .build();
            }
        }

        JSONArray photos = media.optJSONArray("photos");
        if (photos != null && photos.length() > 0) {
            List<MediaBundle.ImageItem> images = new ArrayList<>();
            for (int i = 0; i < photos.length(); i++) {
                JSONObject p = photos.optJSONObject(i);
                if (p == null) continue;
                String origUrl = normalizeTwitterPhotoUrl(p.optString("url", ""));
                if (!LinkInput.isWebUrl(origUrl)) continue;
                int w = p.optInt("width", 0);
                int h = p.optInt("height", 0);
                String name = "x_" + LinkInput.safeName(author.isBlank() ? "photo" : author) + "_" + (i + 1) + guessImgExt(origUrl);
                images.add(new MediaBundle.ImageItem(origUrl, name, w, h));
            }
            if (!images.isEmpty()) {
                return MediaBundle.builder(MediaBundle.Type.GALLERY)
                        .title(text)
                        .author(author)
                        .platform("X / Twitter")
                        .referer("https://x.com/")
                        .subfolder("X/" + LinkInput.safeName(author.isBlank() ? "Twitter" : author))
                        .images(images)
                        .watermarkFree(true)
                        .build();
            }
        }
        throw new IllegalArgumentException("推文中没有可下载的视频或图片");
    }

    public static MediaBundle parseTwitterSyndication(JSONObject root, String pageUrl, int preferredQuality) {
        if (root == null) throw new IllegalArgumentException("Twitter Syndication 未返回数据");
        JSONObject user = root.optJSONObject("user");
        String author = user == null ? "" : user.optString("name", user.optString("screen_name", ""));
        String text = root.optString("text", "X / Twitter 媒体");
        JSONArray details = root.optJSONArray("mediaDetails");
        if (details == null || details.length() == 0) {
            throw new IllegalArgumentException("推文未包含媒体详情");
        }

        List<MediaBundle.ImageItem> photos = new ArrayList<>();
        for (int i = 0; i < details.length(); i++) {
            JSONObject item = details.optJSONObject(i);
            if (item == null) continue;
            String type = item.optString("type", "");
            if ("video".equals(type) || "animated_gif".equals(type)) {
                JSONObject info = item.optJSONObject("video_info");
                List<String> urls = sortTwitterVariants(info == null ? null : info.optJSONArray("variants"), preferredQuality);
                if (!urls.isEmpty()) {
                    return MediaBundle.builder(MediaBundle.Type.VIDEO)
                            .title(text)
                            .author(author)
                            .platform("X / Twitter")
                            .referer("https://x.com/")
                            .videoUrls(urls)
                            .watermarkFree(true)
                            .build();
                }
            } else if ("photo".equals(type)) {
                String u = normalizeTwitterPhotoUrl(item.optString("media_url_https", item.optString("media_url", "")));
                if (LinkInput.isWebUrl(u)) {
                    JSONObject origInfo = item.optJSONObject("original_info");
                    int w = origInfo == null ? 0 : origInfo.optInt("width", 0);
                    int h = origInfo == null ? 0 : origInfo.optInt("height", 0);
                    String name = "x_" + (i + 1) + guessImgExt(u);
                    photos.add(new MediaBundle.ImageItem(u, name, w, h));
                }
            }
        }
        if (!photos.isEmpty()) {
            return MediaBundle.builder(MediaBundle.Type.GALLERY)
                    .title(text)
                    .author(author)
                    .platform("X / Twitter")
                    .referer("https://x.com/")
                    .subfolder("X/" + LinkInput.safeName(author.isBlank() ? "Twitter" : author))
                    .images(photos)
                    .watermarkFree(true)
                    .build();
        }
        throw new IllegalArgumentException("未能从推文解析出有效视频或图片");
    }

    private static List<String> sortTwitterVariants(JSONArray variants, int preferredQuality) {
        if (variants == null || variants.length() == 0) return List.of();
        List<JSONObject> mp4s = new ArrayList<>();
        for (int i = 0; i < variants.length(); i++) {
            JSONObject v = variants.optJSONObject(i);
            if (v == null) continue;
            String ct = v.optString("content_type", "");
            String u = v.optString("url", "");
            if (!LinkInput.isWebUrl(u)) continue;
            if ("video/mp4".equalsIgnoreCase(ct) || u.toLowerCase(Locale.ROOT).contains(".mp4")) {
                mp4s.add(v);
            }
        }
        if (mp4s.isEmpty()) return List.of();
        JSONObject best = null;
        int bestSize = 0;
        long bestBitrate = -1;
        for (JSONObject v : mp4s) {
            int size = VideoQuality.shortSide(v.optInt("width", 0), v.optInt("height", 0));
            if (size <= 0) size = inferTwitterVariantHeight(v.optString("url", ""));
            long br = v.optLong("bitrate", 0);
            if (best == null || VideoQuality.better(size, br, bestSize, bestBitrate, preferredQuality)) {
                best = v;
                bestSize = size;
                bestBitrate = br;
            }
        }
        mp4s.sort((a, b) -> Long.compare(b.optLong("bitrate", 0), a.optLong("bitrate", 0)));
        Set<String> ordered = new LinkedHashSet<>();
        if (best != null) ordered.add(best.optString("url"));
        for (JSONObject v : mp4s) ordered.add(v.optString("url"));
        return new ArrayList<>(ordered);
    }

    private static int inferTwitterVariantHeight(String url) {
        Matcher m = Pattern.compile("/(\\d+)x(\\d+)/").matcher(url);
        if (m.find()) {
            try {
                return VideoQuality.shortSide(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            } catch (Exception ignored) {}
        }
        return 0;
    }

    public static String normalizeTwitterPhotoUrl(String url) {
        if (url == null || url.isBlank()) return "";
        if (url.contains(":orig") || url.contains("name=orig")) return url;
        if (url.contains("?")) return url + "&name=orig";
        return url + ":orig";
    }

    // =========================================================================
    // 3. TikTok (__UNIVERSAL_DATA_FOR_REHYDRATION__)
    // =========================================================================

    private static MediaBundle extractTikTok(String url, int preferredQuality, Net.Cancellation cancel) throws IOException {
        HttpClient.HttpResult res = HttpClient.get(
                url,
                Map.of("User-Agent", HttpClient.DESKTOP_UA, "Referer", "https://www.tiktok.com/"),
                cancel);
        return parseTikTokHtml(res.body, res.finalUrl, preferredQuality);
    }

    public static MediaBundle parseTikTokHtml(String html, String pageUrl, int preferredQuality) {
        if (html == null || html.isBlank()) throw new IllegalArgumentException("TikTok 页面为空");
        Matcher m = Pattern.compile("(?is)<script[^>]*id\\s*=\\s*['\"]__UNIVERSAL_DATA_FOR_REHYDRATION__['\"][^>]*>(.*?)</script>")
                .matcher(html);
        if (m.find()) {
            try {
                JSONObject root = new JSONObject(m.group(1).trim());
                JSONObject scope = root.optJSONObject("__DEFAULT_SCOPE__");
                if (scope != null) {
                    JSONObject detail = scope.optJSONObject("webapp.video-detail");
                    if (detail == null) detail = scope.optJSONObject("webapp.reflow.video.detail");
                    if (detail != null) {
                        JSONObject itemInfo = detail.optJSONObject("itemInfo");
                        JSONObject item = itemInfo == null ? null : itemInfo.optJSONObject("itemStruct");
                        if (item != null) {
                            return parseTikTokItemStruct(item, preferredQuality);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        MediaPlan fallback = MediaParser.webpage(html, pageUrl, preferredQuality);
        return wrapMediaPlan(fallback, "TikTok");
    }

    private static MediaBundle parseTikTokItemStruct(JSONObject item, int preferredQuality) {
        String title = item.optString("desc", "TikTok 视频");
        JSONObject authorObj = item.optJSONObject("author");
        String author = authorObj == null ? "" : authorObj.optString("nickname", authorObj.optString("uniqueId", ""));
        JSONObject video = item.optJSONObject("video");
        if (video == null) throw new IllegalArgumentException("TikTok 作品缺少 video 字段");

        Set<String> urls = new LinkedHashSet<>();
        String downloadAddr = firstTikTokUrl(video.opt("downloadAddr"));
        boolean watermarkFree = LinkInput.isWebUrl(downloadAddr);
        if (watermarkFree) urls.add(downloadAddr);

        JSONArray bitrateInfo = video.optJSONArray("bitrateInfo");
        String bestBitrateUrl = null;
        int bestSize = 0;
        long bestBr = -1;
        if (bitrateInfo != null) {
            for (int i = 0; i < bitrateInfo.length(); i++) {
                JSONObject entry = bitrateInfo.optJSONObject(i);
                if (entry == null) continue;
                JSONObject playAddrObj = entry.optJSONObject("PlayAddr");
                String candidate = firstTikTokUrl(playAddrObj);
                if (!LinkInput.isWebUrl(candidate)) continue;
                int w = playAddrObj == null ? 0 : playAddrObj.optInt("Width", 0);
                int h = playAddrObj == null ? 0 : playAddrObj.optInt("Height", 0);
                int size = VideoQuality.shortSide(w, h);
                long br = entry.optLong("Bitrate", 0);
                if (bestBitrateUrl == null || VideoQuality.better(size, br, bestSize, bestBr, preferredQuality)) {
                    bestBitrateUrl = candidate;
                    bestSize = size;
                    bestBr = br;
                }
            }
        }
        if (LinkInput.isWebUrl(bestBitrateUrl)) urls.add(bestBitrateUrl);

        String playAddr = firstTikTokUrl(video.opt("playAddr"));
        if (LinkInput.isWebUrl(playAddr)) urls.add(playAddr);

        if (urls.isEmpty()) {
            throw new IllegalArgumentException("TikTok 未返回可播放的视频地址");
        }
        return MediaBundle.builder(MediaBundle.Type.VIDEO)
                .title(title)
                .author(author)
                .platform("TikTok")
                .referer("https://www.tiktok.com/")
                .header("Referer", "https://www.tiktok.com/")
                .videoUrls(urls)
                .watermarkFree(watermarkFree)
                .build();
    }

    private static String firstTikTokUrl(Object raw) {
        if (raw instanceof String s && LinkInput.isWebUrl(s)) return s;
        if (raw instanceof JSONObject obj) {
            for (String key : new String[]{"UrlList", "urlList", "url_list", "url"}) {
                String u = firstTikTokUrl(obj.opt(key));
                if (u != null) return u;
            }
        }
        if (raw instanceof JSONArray arr) {
            for (int i = 0; i < arr.length(); i++) {
                String u = firstTikTokUrl(arr.opt(i));
                if (u != null) return u;
            }
        }
        return null;
    }

    // =========================================================================
    // 4. Reddit (<url>.json)
    // =========================================================================

    private static MediaBundle extractReddit(String url, Net.Cancellation cancel) throws IOException {
        Map<String, String> headers = Map.of("User-Agent", REDDIT_UA);
        String resolved = HttpClient.resolveRedirectUrl(url, headers, cancel);
        String jsonUrl = toRedditJsonUrl(resolved);
        HttpClient.HttpResult res = HttpClient.get(jsonUrl, headers, cancel);
        return parseRedditJson(res.body, resolved);
    }

    public static String toRedditJsonUrl(String resolvedUrl) {
        URI uri = URI.create(resolvedUrl);
        String path = uri.getRawPath() == null ? "/" : uri.getRawPath();
        if (!path.endsWith(".json")) {
            path = path.endsWith("/") ? path + ".json" : path + "/.json";
        }
        return "https://" + uri.getRawAuthority() + path + "?raw_json=1";
    }

    public static MediaBundle parseRedditJson(String jsonText, String pageUrl) {
        if (jsonText == null || jsonText.isBlank()) throw new IllegalArgumentException("Reddit 返回为空");
        JSONObject postData = extractRedditPostData(jsonText);
        if (postData == null) throw new IllegalArgumentException("未找到 Reddit 帖子内容");

        String title = MediaParser.entities(postData.optString("title", "Reddit 媒体"));
        String author = postData.optString("author", "");
        String subreddit = postData.optString("subreddit_name_prefixed", "");

        JSONObject redditVideo = findRedditVideo(postData);
        if (redditVideo != null) {
            String fallbackUrl = MediaParser.entities(redditVideo.optString("fallback_url", ""));
            String hlsUrl = MediaParser.entities(redditVideo.optString("hls_url", ""));
            boolean hasAudio = !redditVideo.optBoolean("is_gif", false)
                    && redditVideo.optBoolean("has_audio", true);
            List<String> audioUrls = hasAudio ? deriveRedditAudioUrls(fallbackUrl) : List.of();
            if (LinkInput.isWebUrl(fallbackUrl) || LinkInput.isWebUrl(hlsUrl)) {
                MediaBundle.Builder b = MediaBundle.builder(MediaBundle.Type.VIDEO)
                        .title(title)
                        .author(author)
                        .platform("Reddit")
                        .referer("https://www.reddit.com/")
                        .hlsUrl(LinkInput.isWebUrl(hlsUrl) ? hlsUrl : "")
                        .extraInfo(subreddit)
                        .watermarkFree(true);
                if (LinkInput.isWebUrl(fallbackUrl)) b.videoUrl(fallbackUrl);
                if (!audioUrls.isEmpty()) b.audioUrls(audioUrls);
                return b.build();
            }
        }

        JSONObject galleryData = postData.optJSONObject("gallery_data");
        JSONObject metadata = postData.optJSONObject("media_metadata");
        if (galleryData != null && metadata != null) {
            JSONArray items = galleryData.optJSONArray("items");
            List<MediaBundle.ImageItem> images = new ArrayList<>();
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    String mediaId = item.optString("media_id", "");
                    JSONObject meta = metadata.optJSONObject(mediaId);
                    if (meta == null) continue;
                    JSONObject source = meta.optJSONObject("s");
                    if (source == null) continue;
                    String imgUrl = MediaParser.entities(source.optString("u", source.optString("gif", "")));
                    if (!LinkInput.isWebUrl(imgUrl)) continue;
                    int w = source.optInt("x", 0);
                    int h = source.optInt("y", 0);
                    String filename = "reddit_" + mediaId + guessImgExt(imgUrl);
                    images.add(new MediaBundle.ImageItem(imgUrl, filename, w, h));
                }
            }
            if (!images.isEmpty()) {
                return MediaBundle.builder(MediaBundle.Type.GALLERY)
                        .title(title)
                        .author(author)
                        .platform("Reddit")
                        .referer("https://www.reddit.com/")
                        .subfolder("Reddit/" + LinkInput.safeName(title))
                        .images(images)
                        .watermarkFree(true)
                        .build();
            }
        }

        String direct = MediaParser.entities(postData.optString("url_overridden_by_dest", postData.optString("url", "")));
        if (LinkInput.isWebUrl(direct) && direct.toLowerCase(Locale.ROOT).matches(".*\\.(?:jpg|jpeg|png|webp|gif)(?:\\?.*)?$")) {
            return MediaBundle.builder(MediaBundle.Type.GALLERY)
                    .title(title)
                    .author(author)
                    .platform("Reddit")
                    .referer("https://www.reddit.com/")
                    .addImage(new MediaBundle.ImageItem(direct, "reddit_image" + guessImgExt(direct), 0, 0))
                    .watermarkFree(true)
                    .build();
        }
        throw new IllegalArgumentException("该 Reddit 帖子没有可提取的视频或图片");
    }

    private static JSONObject extractRedditPostData(String jsonText) {
        String trimmed = jsonText.trim();
        try {
            if (trimmed.startsWith("[")) {
                JSONArray arr = new JSONArray(trimmed);
                JSONObject first = arr.optJSONObject(0);
                JSONObject data = first == null ? null : first.optJSONObject("data");
                JSONArray children = data == null ? null : data.optJSONArray("children");
                JSONObject child = children == null ? null : children.optJSONObject(0);
                return child == null ? null : child.optJSONObject("data");
            }
            JSONObject obj = new JSONObject(trimmed);
            if (obj.has("data")) {
                JSONObject data = obj.optJSONObject("data");
                if (data != null && data.has("children")) {
                    JSONArray children = data.optJSONArray("children");
                    JSONObject child = children == null ? null : children.optJSONObject(0);
                    return child == null ? null : child.optJSONObject("data");
                }
            }
            return obj;
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject findRedditVideo(JSONObject postData) {
        for (String key : new String[]{"secure_media", "media"}) {
            JSONObject media = postData.optJSONObject(key);
            if (media != null && media.optJSONObject("reddit_video") != null) {
                return media.optJSONObject("reddit_video");
            }
        }
        JSONArray crossposts = postData.optJSONArray("crosspost_parent_list");
        if (crossposts != null && crossposts.length() > 0) {
            JSONObject first = crossposts.optJSONObject(0);
            if (first != null) return findRedditVideo(first);
        }
        return null;
    }

    private static List<String> deriveRedditAudioUrls(String fallbackUrl) {
        if (!LinkInput.isWebUrl(fallbackUrl)) return List.of();
        int idx = fallbackUrl.indexOf("/DASH_");
        if (idx < 0) return List.of();
        String base = fallbackUrl.substring(0, idx + 1);
        return List.of(
                base + "DASH_AUDIO_128.mp4",
                base + "DASH_AUDIO_64.mp4",
                base + "DASH_audio.mp4"
        );
    }

    // =========================================================================
    // 5. Vimeo (player.vimeo.com/video/<id>/config)
    // =========================================================================

    private static MediaBundle extractVimeo(String url, int preferredQuality, Net.Cancellation cancel) throws IOException {
        URI uri = URI.create(url);
        Matcher m = VIMEO_PATH.matcher(uri.getPath() == null ? "" : uri.getPath());
        if (!m.find()) throw new IllegalArgumentException("无法识别 Vimeo 视频 ID");
        String id = m.group(1);
        String hash = m.group(2);
        if (hash == null) hash = queryParam(uri.getRawQuery(), "h");
        String configUrl = "https://player.vimeo.com/video/" + id + "/config"
                + (hash != null && !hash.isBlank() ? "?h=" + hash : "");
        HttpClient.HttpResult res = HttpClient.get(
                configUrl,
                Map.of("Referer", "https://vimeo.com/", "User-Agent", HttpClient.DESKTOP_UA),
                cancel);
        try { return parseVimeoConfig(new JSONObject(res.body), url, preferredQuality); }
        catch (org.json.JSONException e) { throw new IOException("Vimeo 配置解析失败", e); }
    }

    public static MediaBundle parseVimeoConfig(JSONObject config, String pageUrl, int preferredQuality) {
        if (config == null) throw new IllegalArgumentException("Vimeo 配置为空");
        JSONObject video = config.optJSONObject("video");
        String title = video == null ? "Vimeo 视频" : video.optString("title", "Vimeo 视频");
        JSONObject owner = video == null ? null : video.optJSONObject("owner");
        String author = owner == null ? "" : owner.optString("name", "");

        JSONObject request = config.optJSONObject("request");
        JSONObject files = request == null ? null : request.optJSONObject("files");
        if (files == null) throw new IllegalArgumentException("Vimeo 未返回媒体流信息");

        JSONArray progressive = files.optJSONArray("progressive");
        if (progressive != null && progressive.length() > 0) {
            JSONObject best = null;
            int bestSize = 0;
            long bestBr = -1;
            List<JSONObject> allProg = new ArrayList<>();
            for (int i = 0; i < progressive.length(); i++) {
                JSONObject item = progressive.optJSONObject(i);
                if (item == null || !LinkInput.isWebUrl(item.optString("url", ""))) continue;
                allProg.add(item);
                int size = VideoQuality.shortSide(item.optInt("width", 0), item.optInt("height", 0));
                long br = item.optLong("bitrate", 0);
                if (best == null || VideoQuality.better(size, br, bestSize, bestBr, preferredQuality)) {
                    best = item;
                    bestSize = size;
                    bestBr = br;
                }
            }
            if (best != null) {
                Set<String> urls = new LinkedHashSet<>();
                urls.add(best.optString("url"));
                for (JSONObject item : allProg) urls.add(item.optString("url"));
                return MediaBundle.builder(MediaBundle.Type.VIDEO)
                        .title(title)
                        .author(author)
                        .platform("Vimeo")
                        .referer("https://vimeo.com/")
                        .videoUrls(urls)
                        .watermarkFree(true)
                        .build();
            }
        }

        JSONObject hls = files.optJSONObject("hls");
        String hlsUrl = extractVimeoCdnUrl(hls);
        if (LinkInput.isWebUrl(hlsUrl)) {
            return MediaBundle.builder(MediaBundle.Type.VIDEO)
                    .title(title)
                    .author(author)
                    .platform("Vimeo")
                    .referer("https://vimeo.com/")
                    .hlsUrl(hlsUrl)
                    .watermarkFree(true)
                    .build();
        }
        throw new IllegalArgumentException("Vimeo 未返回可下载的 MP4 或 HLS 流");
    }

    private static String extractVimeoCdnUrl(JSONObject adaptive) {
        if (adaptive == null) return "";
        JSONObject cdns = adaptive.optJSONObject("cdns");
        if (cdns == null) return "";
        String def = adaptive.optString("default_cdn", "");
        JSONObject cdnObj = def.isEmpty() ? null : cdns.optJSONObject(def);
        if (cdnObj == null) {
            var keys = cdns.keys();
            if (keys.hasNext()) cdnObj = cdns.optJSONObject(keys.next());
        }
        return cdnObj == null ? "" : cdnObj.optString("url", "");
    }

    // =========================================================================
    // 6. Weibo (m.weibo.cn/statuses/show?id=<id>)
    // =========================================================================

    private static MediaBundle extractWeibo(String url, Net.Cancellation cancel) throws IOException {
        Matcher m = WEIBO_ID.matcher(url);
        if (!m.find()) throw new IllegalArgumentException("无法从微博链接提取微博 ID");
        String id = m.group(1) != null ? m.group(1) : m.group(2);
        HttpClient.HttpResult res = HttpClient.get(
                "https://m.weibo.cn/statuses/show?id=" + id,
                Map.of(
                        "User-Agent", HttpClient.MOBILE_UA,
                        "Referer", "https://m.weibo.cn/detail/" + id,
                        "Accept", "application/json"
                ),
                cancel);
        try { return parseWeiboStatus(new JSONObject(res.body), url); }
        catch (org.json.JSONException e) { throw new IOException("微博数据解析失败", e); }
    }

    public static MediaBundle parseWeiboStatus(JSONObject root, String pageUrl) {
        if (root == null) throw new IllegalArgumentException("微博接口返回为空");
        JSONObject data = root.optJSONObject("data");
        if (data == null) throw new IllegalArgumentException("微博数据无效（可能需要登录态）");
        JSONObject user = data.optJSONObject("user");
        String author = user == null ? "" : user.optString("screen_name", "");
        String rawTitle = data.optString("status_title", data.optString("text", "微博媒体"));
        String title = rawTitle.replaceAll("<[^>]+>", "").trim();
        if (title.isEmpty()) title = "微博媒体";

        JSONObject pageInfo = data.optJSONObject("page_info");
        if (pageInfo != null) {
            List<String> candidates = new ArrayList<>();
            collectWeiboUrls(pageInfo.optJSONObject("media_info"), candidates);
            collectWeiboUrls(pageInfo.optJSONObject("urls"), candidates);
            if (!candidates.isEmpty()) {
                return MediaBundle.builder(MediaBundle.Type.VIDEO)
                        .title(title)
                        .author(author)
                        .platform("微博")
                        .referer("https://m.weibo.cn/")
                        .header("Referer", "https://m.weibo.cn/")
                        .videoUrls(candidates)
                        .watermarkFree(true)
                        .build();
            }
        }

        JSONArray pics = data.optJSONArray("pics");
        if (pics != null && pics.length() > 0) {
            List<MediaBundle.ImageItem> images = new ArrayList<>();
            for (int i = 0; i < pics.length(); i++) {
                JSONObject pic = pics.optJSONObject(i);
                if (pic == null) continue;
                JSONObject large = pic.optJSONObject("large");
                String picUrl = large == null ? pic.optString("url", "") : large.optString("url", pic.optString("url", ""));
                if (!LinkInput.isWebUrl(picUrl)) continue;
                JSONObject geo = large == null ? pic.optJSONObject("geo") : large.optJSONObject("geo");
                int w = geo == null ? 0 : geo.optInt("width", 0);
                int h = geo == null ? 0 : geo.optInt("height", 0);
                images.add(new MediaBundle.ImageItem(picUrl, "weibo_" + (i + 1) + guessImgExt(picUrl), w, h));
            }
            if (!images.isEmpty()) {
                return MediaBundle.builder(MediaBundle.Type.GALLERY)
                        .title(title)
                        .author(author)
                        .platform("微博")
                        .referer("https://m.weibo.cn/")
                        .subfolder("Weibo/" + LinkInput.safeName(author.isBlank() ? "微博" : author))
                        .images(images)
                        .watermarkFree(true)
                        .build();
            }
        }
        throw new IllegalArgumentException("未在微博动态中发现公开视频或图片");
    }

    private static void collectWeiboUrls(JSONObject obj, List<String> out) {
        if (obj == null) return;
        for (String key : new String[]{"mp4_720p_mp4", "mp4_hd_mp4", "mp4_ld_mp4", "stream_url_hd", "stream_url"}) {
            String u = obj.optString(key, "");
            if (LinkInput.isWebUrl(u) && !out.contains(u)) out.add(u);
        }
    }

    // =========================================================================
    // 7. AcFun (window.videoInfo / currentVideoInfo.ksPlayJson)
    // =========================================================================

    private static MediaBundle extractAcFun(String url, int preferredQuality, Net.Cancellation cancel) throws IOException {
        String targetUrl = url;
        Matcher m = ACFUN_ID.matcher(url);
        if (m.find()) {
            targetUrl = "https://www.acfun.cn/v/ac" + m.group(1);
        }
        HttpClient.HttpResult res = HttpClient.get(
                targetUrl,
                Map.of("User-Agent", HttpClient.DESKTOP_UA, "Referer", "https://www.acfun.cn/"),
                cancel);
        return parseAcFunHtml(res.body, res.finalUrl, preferredQuality);
    }

    public static MediaBundle parseAcFunHtml(String html, String pageUrl, int preferredQuality) {
        if (html == null || html.isBlank()) throw new IllegalArgumentException("AcFun 页面为空");
        for (String marker : new String[]{"window.videoInfo", "window.pageInfo"}) {
            int pos = html.indexOf(marker);
            if (pos < 0) continue;
            int start = html.indexOf('{', pos);
            String rawJson = MediaParser.balancedJson(html, start);
            if (rawJson != null) {
                try {
                    return parseAcFunVideoInfo(new JSONObject(rawJson), pageUrl, preferredQuality);
                } catch (IllegalArgumentException e) {
                    throw e;
                } catch (Exception ignored) {}
            }
        }
        throw new IllegalArgumentException("未能从 AcFun 页面提取视频播放信息");
    }

    public static MediaBundle parseAcFunVideoInfo(JSONObject videoInfo, String pageUrl, int preferredQuality) {
        if (videoInfo == null) throw new IllegalArgumentException("AcFun videoInfo 为空");
        String title = videoInfo.optString("title", "AcFun 视频");
        JSONObject user = videoInfo.optJSONObject("user");
        String author = user == null ? "" : user.optString("name", "");
        JSONObject currentVideo = videoInfo.optJSONObject("currentVideoInfo");
        if (currentVideo == null) throw new IllegalArgumentException("AcFun 缺少 currentVideoInfo");

        String ksPlayJsonStr = currentVideo.optString("ksPlayJson", "");
        if (ksPlayJsonStr.isBlank()) throw new IllegalArgumentException("AcFun 缺少 ksPlayJson 播放配置");
        JSONObject ksPlay;
        try {
            ksPlay = new JSONObject(ksPlayJsonStr);
        } catch (Exception e) {
            throw new IllegalArgumentException("AcFun ksPlayJson 解析失败", e);
        }

        JSONArray adaptationSet = ksPlay.optJSONArray("adaptationSet");
        JSONObject firstSet = adaptationSet == null ? null : adaptationSet.optJSONObject(0);
        JSONArray reps = firstSet == null ? null : firstSet.optJSONArray("representation");
        if (reps == null || reps.length() == 0) {
            throw new IllegalArgumentException("AcFun 未返回可用的视频清晰度列表");
        }

        JSONObject best = null;
        int bestSize = 0;
        long bestBitrate = -1;
        for (int i = 0; i < reps.length(); i++) {
            JSONObject rep = reps.optJSONObject(i);
            if (rep == null || !LinkInput.isWebUrl(rep.optString("url", ""))) continue;
            int size = VideoQuality.shortSide(rep.optInt("width", 0), rep.optInt("height", 0));
            long br = rep.optLong("avgBitrate", rep.optLong("maxBitrate", 0));
            if (best == null || VideoQuality.better(size, br, bestSize, bestBitrate, preferredQuality)) {
                best = rep;
                bestSize = size;
                bestBitrate = br;
            }
        }
        if (best == null) throw new IllegalArgumentException("AcFun 未找到有效的视频地址");

        String mainUrl = best.optString("url", "");
        List<String> backups = new ArrayList<>();
        backups.add(mainUrl);
        JSONArray backupArr = best.optJSONArray("backupUrl");
        if (backupArr != null) {
            for (int i = 0; i < backupArr.length(); i++) {
                String u = backupArr.optString(i, "");
                if (LinkInput.isWebUrl(u) && !backups.contains(u)) backups.add(u);
            }
        }

        MediaBundle.Builder b = MediaBundle.builder(MediaBundle.Type.VIDEO)
                .title(title)
                .author(author)
                .platform("AcFun")
                .referer("https://www.acfun.cn/")
                .watermarkFree(true);
        if (mainUrl.toLowerCase(Locale.ROOT).contains(".m3u8")) {
            b.hlsUrl(mainUrl);
        } else {
            b.videoUrls(backups);
        }
        return b.build();
    }

    // =========================================================================
    // 8. PeerTube (via PeerTube.java + HLS streamingPlaylists fallback)
    // =========================================================================

    private static MediaBundle extractPeerTube(String url, int preferredQuality, Net.Cancellation cancel) throws IOException {
        String apiUrl = peerTubeApiUrl(url);
        if (apiUrl == null) throw new IllegalArgumentException("无效的 PeerTube 视频链接");
        HttpClient.HttpResult res = HttpClient.get(apiUrl, Map.of("Referer", url), cancel);
        JSONObject data;
        try { data = new JSONObject(res.body); }
        catch (org.json.JSONException e) { throw new IOException("PeerTube 接口返回数据格式异常", e); }
        return parsePeerTube(data, url, preferredQuality);
    }

    public static MediaBundle parsePeerTube(JSONObject data, String pageUrl, int preferredQuality) {
        if (data == null || !peerTubeDescribesVideo(data)) {
            throw new IllegalArgumentException("PeerTube 接口未返回有效的视频描述");
        }
        if (data.optBoolean("isLive")) {
            throw new IllegalArgumentException("PeerTube 直播暂不支持保存");
        }
        try {
            MediaPlan plan = peerTubeSelect(data, pageUrl, preferredQuality);
            return wrapMediaPlan(plan, "PeerTube");
        } catch (IllegalArgumentException primaryError) {
            // HLS streamingPlaylists fallback when direct MP4 files are unavailable or downloadEnabled=false
            JSONArray playlists = data.optJSONArray("streamingPlaylists");
            if (playlists != null && playlists.length() > 0) {
                String hlsUrl = "";
                List<String> fragmentedFiles = new ArrayList<>();
                for (int i = 0; i < playlists.length(); i++) {
                    JSONObject sp = playlists.optJSONObject(i);
                    if (sp == null) continue;
                    String pl = sp.optString("playlistUrl", "");
                    if (hlsUrl.isEmpty() && LinkInput.isWebUrl(pl)) hlsUrl = pl;
                    JSONArray files = sp.optJSONArray("files");
                    if (files != null) {
                        for (int j = 0; j < files.length(); j++) {
                            JSONObject f = files.optJSONObject(j);
                            if (f != null && LinkInput.isWebUrl(f.optString("fileUrl", ""))) {
                                fragmentedFiles.add(f.optString("fileUrl"));
                            }
                        }
                    }
                }
                if (!hlsUrl.isEmpty() || !fragmentedFiles.isEmpty()) {
                    MediaBundle.Builder b = MediaBundle.builder(MediaBundle.Type.VIDEO)
                            .title(data.optString("name", "PeerTube 视频"))
                            .platform("PeerTube")
                            .referer(pageUrl)
                            .watermarkFree(true);
                    if (!hlsUrl.isEmpty()) b.hlsUrl(hlsUrl);
                    if (!fragmentedFiles.isEmpty()) b.videoUrls(fragmentedFiles);
                    return b.build();
                }
            }
            throw primaryError;
        }
    }

    // =========================================================================
    // 9. Generic HTML / M3U8 / MP4 Fallback
    // =========================================================================

    private static MediaBundle extractGeneric(String url, int preferredQuality, Net.Cancellation cancel) throws IOException {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\.mp4(?:[?#].*)?$")) {
            return MediaBundle.builder(MediaBundle.Type.VIDEO)
                    .title("星落视频")
                    .platform("网页视频")
                    .referer(url)
                    .videoUrl(url)
                    .build();
        }
        if (lower.matches(".*\\.m3u8(?:[?#].*)?$")) {
            return MediaBundle.builder(MediaBundle.Type.LIVE)
                    .title("流媒体视频")
                    .platform("直播 / 流媒体")
                    .referer(url)
                    .hlsUrl(url)
                    .liveUrl(url)
                    .build();
        }
        HttpClient.HttpResult res = HttpClient.get(url, Map.of("Referer", url), cancel);
        String ct = res.contentType == null ? "" : res.contentType.toLowerCase(Locale.ROOT);
        if (ct.startsWith("video/") || ct.contains("octet-stream")) {
            return MediaBundle.builder(MediaBundle.Type.VIDEO)
                    .title("星落视频")
                    .platform("网页视频")
                    .referer(url)
                    .videoUrl(res.finalUrl)
                    .build();
        }
        if (ct.contains("mpegurl")) {
            return MediaBundle.builder(MediaBundle.Type.LIVE)
                    .title("流媒体视频")
                    .platform("直播 / 流媒体")
                    .referer(url)
                    .hlsUrl(res.finalUrl)
                    .liveUrl(res.finalUrl)
                    .build();
        }
        return parseGenericHtml(res.body, res.finalUrl, preferredQuality);
    }

    public static MediaBundle parseGenericHtml(String html, String pageUrl, int preferredQuality) {
        try {
            MediaPlan plan = MediaParser.webpage(html, pageUrl, preferredQuality);
            return wrapMediaPlan(plan, LinkInput.platformName(pageUrl));
        } catch (IllegalArgumentException originalError) {
            String m3u8 = discoverM3u8Url(html, pageUrl);
            if (m3u8 != null) {
                return MediaBundle.builder(MediaBundle.Type.VIDEO)
                        .title(extractPageTitle(html))
                        .platform(LinkInput.platformName(pageUrl))
                        .referer(pageUrl)
                        .hlsUrl(m3u8)
                        .build();
            }
            throw originalError;
        }
    }

    public static String discoverM3u8Url(String html, String baseUrl) {
        if (html == null || html.isBlank()) return null;
        String normalized = html.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&");
        Matcher m = M3U8_URL.matcher(normalized);
        if (m.find() && LinkInput.isWebUrl(m.group())) return m.group();
        Matcher rel = Pattern.compile("(?is)(?:src|content)\\s*=\\s*['\"]([^'\"]+\\.m3u8(?:\\?[^'\"]*)?)['\"]").matcher(normalized);
        if (rel.find()) {
            try {
                String resolved = URI.create(baseUrl).resolve(rel.group(1)).toString();
                if (LinkInput.isWebUrl(resolved)) return resolved;
            } catch (Exception ignored) {}
        }
        return null;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static MediaBundle wrapMediaPlan(MediaPlan plan, String platform) {
        return MediaBundle.builder(MediaBundle.Type.VIDEO)
                .title(plan.title)
                .platform(platform)
                .referer(plan.referer)
                .videoUrls(plan.videoUrls)
                .audioUrls(plan.audioUrls)
                .watermarkFree(true)
                .build();
    }

    private static String extractPageTitle(String html) {
        Matcher m = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
        return m.find() ? MediaParser.entities(m.group(1)).replaceAll("\\s+", " ").trim() : "网页视频";
    }

    private static long parseContentLength(JSONObject fmt) {
        String s = fmt.optString("contentLength", "");
        if (s.isEmpty()) return -1L;
        try { return Long.parseLong(s); } catch (Exception e) { return -1L; }
    }

    private static long sumKnownBytes(long a, long b) {
        if (a > 0 && b > 0) return a + b;
        return Math.max(a, b);
    }

    private static int parseQualityLabelPx(String label) {
        if (label == null || label.isEmpty()) return 0;
        Matcher m = Pattern.compile("(\\d{3,4})p").matcher(label);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
        }
        return 0;
    }

    private static String guessImgExt(String url) {
        String low = url.toLowerCase(Locale.ROOT);
        if (low.contains("format=png") || low.endsWith(".png")) return ".png";
        if (low.contains("format=webp") || low.endsWith(".webp")) return ".webp";
        if (low.contains("format=gif") || low.endsWith(".gif")) return ".gif";
        return ".jpg";
    }

    private static String queryParam(String rawQuery, String key) {
        if (rawQuery == null || rawQuery.isEmpty()) return null;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            try {
                String k = URLDecoder.decode(eq >= 0 ? pair.substring(0, eq) : pair, "UTF-8");
                if (key.equals(k)) {
                    return eq >= 0 ? URLDecoder.decode(pair.substring(eq + 1), "UTF-8") : "";
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    // PeerTube in app.xingluo.video is package-private; reflection works whether it remains package-private or becomes public.
    private static String peerTubeApiUrl(String page) {
        try {
            Class<?> cls = Class.forName("app.xingluo.video.PeerTube");
            Method m = cls.getDeclaredMethod("apiUrl", String.class);
            m.setAccessible(true);
            return (String) m.invoke(null, page);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean peerTubeDescribesVideo(JSONObject data) {
        try {
            Class<?> cls = Class.forName("app.xingluo.video.PeerTube");
            Method m = cls.getDeclaredMethod("describesVideo", JSONObject.class);
            m.setAccessible(true);
            return Boolean.TRUE.equals(m.invoke(null, data));
        } catch (Exception e) {
            return false;
        }
    }

    private static MediaPlan peerTubeSelect(JSONObject data, String page, int preferred) {
        try {
            Class<?> cls = Class.forName("app.xingluo.video.PeerTube");
            Method m = cls.getDeclaredMethod("select", JSONObject.class, String.class, int.class);
            m.setAccessible(true);
            return (MediaPlan) m.invoke(null, data, page, preferred);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new IllegalArgumentException("PeerTube 视频解析失败", e.getCause());
        } catch (Exception e) {
            throw new IllegalArgumentException("PeerTube 组件不可用", e);
        }
    }
}
