package app.xingluo.video.platform.cloud;

import app.xingluo.video.LinkInput;
import app.xingluo.video.Net;
import app.xingluo.video.engine.HttpClient;
import app.xingluo.video.model.MediaBundle;
import okhttp3.Headers;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Baidu Netdisk (pan.baidu.com) share extractor using user WebView cookies.
 */
public final class BaiduNetdiskExtractor {
    static final String BAIDU_DOWNLOAD_UA = "netdisk;7.0.3.2;PC;PC-Windows;10.0.19045;WindowsBaiduYunGuanJia";
    static final String NON_SVIP_NOTE = "非 SVIP 账号受百度服务器单账号限速（约 100–300 KB/s），建议保持 1–3 线程下载以避免 403 限流";
    private static final String PAN_HOST = "https://pan.baidu.com";
    private static final String AUTH_REQUIRED_MSG = "请点网页识别登录百度网盘账号后再试";
    private static final Pattern SURL_PATH_PATTERN = Pattern.compile("/s/([A-Za-z0-9_-]+)");
    private static final Pattern SURL_QUERY_PATTERN = Pattern.compile("[?&]surl=([A-Za-z0-9_-]+)");
    private static final int MAX_DIRS = 20;
    private static final int MAX_FILES = 100;

    private BaiduNetdiskExtractor() {}

    static final class ShareMeta {
        final String uk;
        final String shareId;
        final String sign;
        final String timestamp;
        final String title;

        ShareMeta(String uk, String shareId, String sign, String timestamp, String title) {
            this.uk = uk;
            this.shareId = shareId;
            this.sign = sign;
            this.timestamp = timestamp;
            this.title = title;
        }

        boolean isComplete() {
            return !uk.isEmpty() && !shareId.isEmpty() && !sign.isEmpty() && !timestamp.isEmpty();
        }

        ShareMeta merge(ShareMeta other) {
            if (other == null) return this;
            return new ShareMeta(
                    !uk.isEmpty() ? uk : other.uk,
                    !shareId.isEmpty() ? shareId : other.shareId,
                    !sign.isEmpty() ? sign : other.sign,
                    !timestamp.isEmpty() ? timestamp : other.timestamp,
                    !title.isEmpty() ? title : other.title
            );
        }
    }

    static final class BaiduFile {
        final long fsId;
        final String name;
        final long size;
        final boolean dir;
        final String path;

        BaiduFile(long fsId, String name, long size, boolean dir, String path) {
            this.fsId = fsId;
            this.name = name;
            this.size = size;
            this.dir = dir;
            this.path = path;
        }
    }

    static final class BaiduDownload {
        final long fsId;
        final String dlink;

        BaiduDownload(long fsId, String dlink) {
            this.fsId = fsId;
            this.dlink = dlink;
        }
    }

    public static MediaBundle extract(String rawInput, Net.Cancellation cancel) throws Exception {
        String url = LinkInput.extract(rawInput);
        String passcode = LinkInput.extractPasscode(rawInput);
        String surl = extractSurl(url);
        String surlWithout1 = stripLeadingOne(surl);
        String shareUrl = PAN_HOST + "/s/" + surl;

        HttpClient.HttpResult initPage = HttpClient.get(shareUrl, webHeaders(PAN_HOST + "/"), cancel);
        ShareMeta meta = parseShareHtmlMeta(initPage.body);

        String randsk = "";
        if (!passcode.isEmpty()) {
            randsk = verifyPasscode(surl, surlWithout1, passcode, shareUrl, cancel);
            if (!meta.isComplete()) {
                HttpClient.HttpResult unlockedPage = HttpClient.get(shareUrl, webHeaders(shareUrl), cancel);
                meta = meta.merge(parseShareHtmlMeta(unlockedPage.body));
            }
        } else {
            randsk = extractCookieValue(mergedBaiduCookies(), "BDCLND");
        }

        List<BaiduFile> files = new ArrayList<>();
        meta = collectShareFiles(surl, shareUrl, meta, files, cancel);
        if (files.isEmpty()) {
            throw new IllegalArgumentException("该百度网盘分享中没有可下载的文件");
        }
        if (!meta.isComplete()) {
            throw new IllegalStateException("未能解析百度网盘分享签名参数（uk/shareid/sign），请点网页识别打开分享页后重试");
        }

        if (randsk.isEmpty()) {
            randsk = extractCookieValue(mergedBaiduCookies(), "BDCLND");
        }

        Map<Long, String> dlinks = resolveDownloadLinks(meta, files, randsk, shareUrl, cancel);
        String cookieHeader = mergedBaiduCookies();
        Map<String, String> dlHeaders = downloadHeaders(cookieHeader, shareUrl);

        List<MediaBundle.CloudFileItem> items = new ArrayList<>();
        long totalBytes = 0;
        for (BaiduFile f : files) {
            String dlink = dlinks.get(f.fsId);
            if (dlink == null || dlink.isBlank()) continue;
            if (f.size > 0) totalBytes += f.size;
            items.add(new MediaBundle.CloudFileItem(String.valueOf(f.fsId), f.name, f.size, dlink, dlHeaders));
        }
        if (items.isEmpty()) {
            throw new IllegalStateException("未能获取百度网盘文件下载地址，请检查登录状态后重试");
        }

        String bundleTitle = !meta.title.isBlank() ? meta.title : items.get(0).name;
        return MediaBundle.builder(MediaBundle.Type.CLOUD_FILE)
                .title(bundleTitle)
                .platform("百度网盘")
                .referer(shareUrl)
                .headers(dlHeaders)
                .cloudFiles(items)
                .estimatedBytes(totalBytes > 0 ? totalBytes : -1)
                .extraInfo(NON_SVIP_NOTE)
                .build();
    }

    static String extractSurl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("百度网盘分享链接无效");
        }
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if (path != null) {
                Matcher pm = SURL_PATH_PATTERN.matcher(path);
                if (pm.find()) return pm.group(1);
            }
        } catch (Exception ignored) {}
        Matcher pm = SURL_PATH_PATTERN.matcher(url);
        if (pm.find()) return pm.group(1);
        Matcher qm = SURL_QUERY_PATTERN.matcher(url);
        if (qm.find()) {
            String q = qm.group(1);
            return q.startsWith("1") ? q : "1" + q;
        }
        throw new IllegalArgumentException("未能识别百度网盘分享标识 surl");
    }

    static String stripLeadingOne(String surl) {
        if (surl == null) return "";
        return surl.length() > 1 && surl.startsWith("1") ? surl.substring(1) : surl;
    }

    private static String verifyPasscode(
            String surl, String surlWithout1, String passcode, String shareUrl, Net.Cancellation cancel) throws IOException {
        String verifyUrl = PAN_HOST + "/share/verify?surl=" + encode(surlWithout1)
                + "&t=" + System.currentTimeMillis()
                + "&channel=chunlei&clienttype=0&web=1";
        Map<String, String> form = new LinkedHashMap<>();
        form.put("pwd", passcode);
        HttpClient.HttpResult res = HttpClient.postForm(verifyUrl, form, webHeaders(shareUrl), cancel);
        String randsk = parseVerifyRandsk(res.body, res.headers);
        if (!randsk.isEmpty()) {
            Headers injected = new Headers.Builder()
                    .add("Set-Cookie", "BDCLND=" + randsk + "; Domain=.baidu.com; Path=/")
                    .build();
            HttpClient.saveResponseCookies(PAN_HOST + "/", injected);
            HttpClient.saveResponseCookies(shareUrl, injected);
        }
        return randsk;
    }

    private static JSONObject parseJsonObj(String jsonText) {
        try { return new JSONObject(jsonText); }
        catch (org.json.JSONException e) { throw new IllegalArgumentException("百度网盘返回数据格式异常", e); }
    }

    static String parseVerifyRandsk(String jsonText, Headers headers) {
        JSONObject root = parseJsonObj(jsonText);
        int errno = root.optInt("errno", 0);
        if (errno != 0) {
            if (errno == -9 || errno == -12) {
                throw new IllegalArgumentException("百度网盘提取码错误，请核对后重试");
            }
            if (errno == -62) {
                throw new IllegalArgumentException("百度网盘验证次数过多，请点网页识别完成验证");
            }
            throw new IllegalArgumentException("百度网盘提取码校验失败（errno=" + errno + "）");
        }
        String randsk = root.optString("randsk", "").trim();
        if (randsk.isEmpty() && headers != null) {
            for (String sc : headers.values("Set-Cookie")) {
                Matcher m = Pattern.compile("(?i)(?:^|;\\s*)BDCLND=([^;\\s]+)").matcher(sc);
                if (m.find()) {
                    randsk = m.group(1).trim();
                    break;
                }
            }
        }
        if (randsk.isEmpty()) {
            throw new IllegalStateException("百度网盘未返回访问凭证 randsk");
        }
        return randsk;
    }

    static ShareMeta parseShareHtmlMeta(String html) {
        if (html == null || html.isEmpty()) return new ShareMeta("", "", "", "", "");
        String uk = firstGroup(html,
                "\"uk\"\\s*:\\s*\"?(\\d+)\"?",
                "\"share_uk\"\\s*:\\s*\"?(\\d+)\"?",
                "yunData\\.SHARE_UK\\s*=\\s*['\"]?(\\d+)['\"]?");
        String shareId = firstGroup(html,
                "\"shareid\"\\s*:\\s*\"?(\\d+)\"?",
                "\"share_id\"\\s*:\\s*\"?(\\d+)\"?",
                "yunData\\.SHARE_ID\\s*=\\s*['\"]?(\\d+)['\"]?");
        String sign = firstGroup(html,
                "\"sign\"\\s*:\\s*\"([A-Za-z0-9+/=_-]+)\"",
                "yunData\\.SIGN\\s*=\\s*['\"]([^'\"]+)['\"]");
        String timestamp = firstGroup(html,
                "\"timestamp\"\\s*:\\s*\"?(\\d+)\"?",
                "yunData\\.TIMESTAMP\\s*=\\s*['\"]?(\\d+)['\"]?");
        String title = firstGroup(html,
                "<title[^>]*>([^<]+)</title>");
        if (title.endsWith("_免费高速下载|百度网盘-分享无限制")) {
            title = title.substring(0, title.length() - "_免费高速下载|百度网盘-分享无限制".length()).trim();
        }
        return new ShareMeta(uk, shareId, sign, timestamp, title);
    }

    private static ShareMeta collectShareFiles(
            String surl, String shareUrl, ShareMeta meta, List<BaiduFile> out, Net.Cancellation cancel) throws Exception {
        ArrayDeque<String> dirQueue = new ArrayDeque<>();
        dirQueue.add("");
        Set<String> visitedDirs = new HashSet<>();

        while (!dirQueue.isEmpty() && visitedDirs.size() < MAX_DIRS && out.size() < MAX_FILES) {
            String dir = dirQueue.poll();
            if (!visitedDirs.add(dir)) continue;
            int page = 1;
            while (out.size() < MAX_FILES) {
                Net.check(cancel);
                boolean isRoot = dir.isEmpty();
                String listUrl = PAN_HOST + "/share/list?app_id=250528&web=1&channel=chunlei&clienttype=0"
                        + "&shorturl=" + encode(surl)
                        + "&dir=" + encode(dir)
                        + "&root=" + (isRoot ? "1" : "0")
                        + "&page=" + page
                        + "&num=100";
                String body = HttpClient.get(listUrl, webHeaders(shareUrl), cancel).body;
                JSONObject root = new JSONObject(body);
                meta = meta.merge(parseMetaFromJson(root));
                List<BaiduFile> pageFiles = parseShareList(body);
                if (pageFiles.isEmpty()) break;
                for (BaiduFile item : pageFiles) {
                    if (item.dir) {
                        if (!item.path.isEmpty() && visitedDirs.size() + dirQueue.size() < MAX_DIRS) {
                            dirQueue.add(item.path);
                        }
                    } else {
                        out.add(item);
                        if (out.size() >= MAX_FILES) break;
                    }
                }
                if (pageFiles.size() < 100) break;
                page++;
            }
        }
        return meta;
    }

    private static ShareMeta parseMetaFromJson(JSONObject root) {
        String uk = root.has("uk") ? String.valueOf(root.optLong("uk", 0)) : "";
        if ("0".equals(uk)) uk = "";
        String shareId = root.has("share_id") ? String.valueOf(root.optLong("share_id", 0))
                : root.has("shareid") ? String.valueOf(root.optLong("shareid", 0)) : "";
        if ("0".equals(shareId)) shareId = "";
        String sign = root.optString("sign", "").trim();
        String ts = root.has("timestamp") ? String.valueOf(root.optLong("timestamp", 0)) : "";
        if ("0".equals(ts)) ts = "";
        String title = root.optString("title", "").trim();
        return new ShareMeta(uk, shareId, sign, ts, title);
    }

    static List<BaiduFile> parseShareList(String jsonText) {
        JSONObject root = parseJsonObj(jsonText);
        int errno = root.optInt("errno", 0);
        if (errno != 0) {
            if (errno == -9 || errno == -130) {
                throw new IllegalArgumentException("百度网盘分享需要提取码或提取码已失效（errno=" + errno + "）");
            }
            if (errno == -7 || errno == -1) {
                throw new IllegalArgumentException("百度网盘分享链接已失效或不存在（errno=" + errno + "）");
            }
            throw new IllegalArgumentException("获取百度网盘文件列表失败（errno=" + errno + "）");
        }
        JSONArray list = root.optJSONArray("list");
        if (list == null) return List.of();
        List<BaiduFile> out = new ArrayList<>(list.length());
        for (int i = 0; i < list.length(); i++) {
            JSONObject obj = list.optJSONObject(i);
            if (obj == null) continue;
            long fsId = obj.optLong("fs_id", 0L);
            String name = obj.optString("server_filename", "").trim();
            long size = obj.optLong("size", -1L);
            boolean dir = obj.optInt("isdir", 0) == 1 || obj.optBoolean("isdir", false);
            String path = obj.optString("path", "").trim();
            if (fsId != 0L) {
                out.add(new BaiduFile(fsId, name, size, dir, path));
            }
        }
        return out;
    }

    private static Map<Long, String> resolveDownloadLinks(
            ShareMeta meta, List<BaiduFile> files, String randsk, String shareUrl, Net.Cancellation cancel) throws Exception {
        Map<Long, String> result = new LinkedHashMap<>();
        String apiUrl = PAN_HOST + "/api/sharedownload?sign=" + encode(meta.sign)
                + "&timestamp=" + encode(meta.timestamp)
                + "&channel=chunlei&clienttype=0&web=1&app_id=250528";
        String sekey = decodeUrlSafe(randsk);

        for (int start = 0; start < files.size(); start += 50) {
            Net.check(cancel);
            int end = Math.min(files.size(), start + 50);
            JSONArray fidList = new JSONArray();
            for (int i = start; i < end; i++) fidList.put(files.get(i).fsId);

            Map<String, String> form = new LinkedHashMap<>();
            form.put("encrypt", "0");
            form.put("product", "share");
            form.put("uk", meta.uk);
            form.put("primaryid", meta.shareId);
            form.put("fid_list", fidList.toString());
            if (!sekey.isEmpty()) {
                JSONObject extra = new JSONObject();
                extra.put("sekey", sekey);
                form.put("extra", extra.toString());
            }

            try {
                HttpClient.HttpResult res = HttpClient.postForm(apiUrl, form, webHeaders(shareUrl), cancel);
                for (BaiduDownload dl : parseDownloadLinks(res.body)) {
                    result.put(dl.fsId, dl.dlink);
                }
            } catch (IOException e) {
                String msg = e.getMessage();
                if (msg != null && (msg.contains("401") || msg.contains("403") || msg.contains("访问受限"))) {
                    throw new IllegalStateException(AUTH_REQUIRED_MSG + "（" + msg + "）", e);
                }
                throw e;
            }
        }
        return result;
    }

    static List<BaiduDownload> parseDownloadLinks(String jsonText) {
        JSONObject root = parseJsonObj(jsonText);
        int errno = root.optInt("errno", 0);
        if (errno != 0) {
            if (errno == -6 || errno == 110) {
                throw new IllegalStateException(AUTH_REQUIRED_MSG + "（errno=" + errno + "）");
            }
            if (errno == 112 || errno == 113) {
                throw new IllegalStateException("百度网盘签名已过期，请重新解析（errno=" + errno + "）");
            }
            if (errno == -20) {
                throw new IllegalStateException("百度网盘触发验证码校验，请点网页识别登录后重试");
            }
            throw new IllegalArgumentException("获取百度网盘下载直链失败（errno=" + errno + "）");
        }
        JSONArray list = root.optJSONArray("list");
        if (list == null) return List.of();
        List<BaiduDownload> out = new ArrayList<>(list.length());
        for (int i = 0; i < list.length(); i++) {
            JSONObject obj = list.optJSONObject(i);
            if (obj == null) continue;
            long fsId = obj.optLong("fs_id", 0L);
            String dlink = obj.optString("dlink", "").trim();
            if (fsId != 0L && !dlink.isEmpty()) {
                out.add(new BaiduDownload(fsId, dlink));
            }
        }
        return out;
    }

    private static Map<String, String> webHeaders(String referer) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", HttpClient.DESKTOP_UA);
        h.put("Referer", referer);
        h.put("Origin", PAN_HOST);
        String cookie = mergedBaiduCookies();
        if (!cookie.isBlank()) {
            h.put("Cookie", cookie);
        }
        return h;
    }

    private static Map<String, String> downloadHeaders(String cookie, String referer) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", BAIDU_DOWNLOAD_UA);
        h.put("Referer", referer);
        if (cookie != null && !cookie.isBlank()) {
            h.put("Cookie", cookie);
        }
        return h;
    }

    static String mergedBaiduCookies() {
        Map<String, String> jar = new LinkedHashMap<>();
        parseCookiePairs(HttpClient.getCookie("https://pan.baidu.com/"), jar);
        parseCookiePairs(HttpClient.getCookie("https://d.pcs.baidu.com/"), jar);
        if (jar.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : jar.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static void parseCookiePairs(String raw, Map<String, String> out) {
        if (raw == null || raw.isBlank()) return;
        for (String part : raw.split(";")) {
            String trimmed = part.trim();
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                String name = trimmed.substring(0, eq).trim();
                String val = trimmed.substring(eq + 1).trim();
                if (!name.isEmpty()) out.put(name, val);
            }
        }
    }

    private static String extractCookieValue(String cookieHeader, String name) {
        if (cookieHeader == null || cookieHeader.isBlank()) return "";
        for (String part : cookieHeader.split(";")) {
            String trimmed = part.trim();
            int eq = trimmed.indexOf('=');
            if (eq > 0 && trimmed.substring(0, eq).trim().equalsIgnoreCase(name)) {
                return trimmed.substring(eq + 1).trim();
            }
        }
        return "";
    }

    private static String decodeUrlSafe(String value) {
        if (value == null || value.isEmpty()) return "";
        if (!value.contains("%")) return value;
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static String firstGroup(String text, String... regexes) {
        for (String regex : regexes) {
            Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(text);
            if (m.find()) {
                String v = m.group(1);
                if (v != null && !v.isBlank()) return v.trim();
            }
        }
        return "";
    }

    private static String encode(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (Exception e) { return ""; }
    }
}
