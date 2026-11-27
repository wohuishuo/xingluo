package app.xingluo.video.platform.cloud;

import app.xingluo.video.LinkInput;
import app.xingluo.video.Net;
import app.xingluo.video.engine.HttpClient;
import app.xingluo.video.model.MediaBundle;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Quark Cloud Drive (pan.quark.cn) share link extractor using WebView session cookies.
 */
public final class QuarkExtractor {
    static final String AUTH_REQUIRED_MSG = "请点网页识别登录夸克网盘账号后再加速下载";
    private static final String QUARK_ORIGIN = "https://pan.quark.cn";
    private static final String QUARK_REFERER = "https://pan.quark.cn/";
    private static final String API_BASE = "https://drive-pc.quark.cn/1/clouddrive";
    private static final String QUERY_SUFFIX = "pr=ucpro&fr=pc&uc_param_str=";
    private static final Pattern PWD_ID_PATTERN = Pattern.compile("/s/([A-Za-z0-9]+)");
    private static final int MAX_FOLDERS = 20;
    private static final int MAX_FILES = 100;
    private static final int TASK_POLL_LIMIT = 25;
    private static final long TASK_POLL_INTERVAL_MS = 400L;

    private QuarkExtractor() {}

    static final class ShareTokenResult {
        final String stoken;
        final String title;

        ShareTokenResult(String stoken, String title) {
            this.stoken = stoken;
            this.title = title;
        }
    }

    static final class QuarkFile {
        final String fid;
        final String name;
        final long size;
        final String shareFidToken;
        final boolean dir;

        QuarkFile(String fid, String name, long size, String shareFidToken, boolean dir) {
            this.fid = fid;
            this.name = name;
            this.size = size;
            this.shareFidToken = shareFidToken;
            this.dir = dir;
        }
    }

    static final class DownloadEntry {
        final String fid;
        final String name;
        final long size;
        final String downloadUrl;

        DownloadEntry(String fid, String name, long size, String downloadUrl) {
            this.fid = fid;
            this.name = name;
            this.size = size;
            this.downloadUrl = downloadUrl;
        }
    }

    static final class SaveResult {
        final String taskId;
        final List<String> immediateSavedFids;

        SaveResult(String taskId, List<String> immediateSavedFids) {
            this.taskId = taskId;
            this.immediateSavedFids = immediateSavedFids;
        }
    }

    public static MediaBundle extract(String rawInput, Net.Cancellation cancel) throws Exception {
        String url = LinkInput.extract(rawInput);
        String passcode = LinkInput.extractPasscode(rawInput);
        String pwdId = extractPwdId(url);

        ShareTokenResult tokenRes = fetchStoken(pwdId, passcode, cancel);
        List<QuarkFile> files = collectShareFiles(pwdId, tokenRes.stoken, cancel);
        if (files.isEmpty()) {
            throw new IllegalArgumentException("该夸克网盘分享中没有可下载的文件");
        }

        String cookie = mergedQuarkCookies();
        if (cookie.isBlank()) {
            throw new IllegalStateException(AUTH_REQUIRED_MSG);
        }

        List<DownloadEntry> resolved = resolveDownloadsWithFallback(pwdId, tokenRes.stoken, files, cancel);
        if (resolved.isEmpty()) {
            throw new IllegalStateException(AUTH_REQUIRED_MSG);
        }

        String latestCookie = mergedQuarkCookies();
        if (latestCookie.isBlank()) {
            latestCookie = cookie;
        }
        Map<String, String> dlHeaders = downloadHeaders(latestCookie);

        List<MediaBundle.CloudFileItem> items = new ArrayList<>(resolved.size());
        long totalBytes = 0;
        for (int i = 0; i < resolved.size(); i++) {
            DownloadEntry d = resolved.get(i);
            QuarkFile fallbackMeta = i < files.size() ? files.get(i) : null;
            String name = !d.name.isBlank() ? d.name : (fallbackMeta != null ? fallbackMeta.name : "quark_file_" + (i + 1));
            long size = d.size > 0 ? d.size : (fallbackMeta != null ? fallbackMeta.size : -1);
            if (size > 0) totalBytes += size;
            items.add(new MediaBundle.CloudFileItem(d.fid, name, size, d.downloadUrl, dlHeaders));
        }

        String title = !tokenRes.title.isBlank() ? tokenRes.title : items.get(0).name;
        return MediaBundle.builder(MediaBundle.Type.CLOUD_FILE)
                .title(title)
                .platform("夸克网盘")
                .referer(QUARK_REFERER)
                .headers(dlHeaders)
                .cloudFiles(items)
                .estimatedBytes(totalBytes > 0 ? totalBytes : -1)
                .extraInfo("已附加夸克登录态 Cookie，支持 8 线程高速下载")
                .build();
    }

    static String extractPwdId(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("夸克网盘分享链接无效");
        }
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if (path != null) {
                Matcher m = PWD_ID_PATTERN.matcher(path);
                if (m.find()) return m.group(1);
            }
        } catch (Exception ignored) {}
        Matcher fallback = PWD_ID_PATTERN.matcher(url);
        if (fallback.find()) return fallback.group(1);
        throw new IllegalArgumentException("未能从链接中识别夸克网盘分享 ID");
    }

    private static ShareTokenResult fetchStoken(String pwdId, String passcode, Net.Cancellation cancel) throws Exception {
        JSONObject req = new JSONObject();
        req.put("pwd_id", pwdId);
        req.put("passcode", passcode == null ? "" : passcode);
        String body = postQuarkJson(API_BASE + "/share/sharepage/token?" + QUERY_SUFFIX, req.toString(), cancel);
        return parseStoken(body);
    }

    private static JSONObject parseJsonObj(String jsonText) {
        try { return new JSONObject(jsonText); }
        catch (org.json.JSONException e) { throw new IllegalArgumentException("夸克网盘返回数据格式异常", e); }
    }

    static ShareTokenResult parseStoken(String jsonText) {
        JSONObject root = parseJsonObj(jsonText);
        checkQuarkResponse(root, "获取夸克分享令牌失败", true);
        JSONObject data = root.optJSONObject("data");
        if (data == null) {
            throw new IllegalStateException(AUTH_REQUIRED_MSG);
        }
        String stoken = data.optString("stoken", "").trim();
        if (stoken.isEmpty()) {
            throw new IllegalStateException("夸克网盘未返回分享令牌 stoken");
        }
        String title = data.optString("title", "").trim();
        return new ShareTokenResult(stoken, title);
    }

    private static List<QuarkFile> collectShareFiles(String pwdId, String stoken, Net.Cancellation cancel) throws Exception {
        List<QuarkFile> files = new ArrayList<>();
        ArrayDeque<String> dirQueue = new ArrayDeque<>();
        dirQueue.add("0");
        Set<String> visitedDirs = new HashSet<>();

        while (!dirQueue.isEmpty() && visitedDirs.size() < MAX_FOLDERS && files.size() < MAX_FILES) {
            String pdirFid = dirQueue.poll();
            if (!visitedDirs.add(pdirFid)) continue;
            int page = 1;
            while (files.size() < MAX_FILES) {
                Net.check(cancel);
                String url = API_BASE + "/share/sharepage/detail?" + QUERY_SUFFIX
                        + "&pwd_id=" + encode(pwdId)
                        + "&stoken=" + encode(stoken)
                        + "&pdir_fid=" + encode(pdirFid)
                        + "&_page=" + page
                        + "&_size=50"
                        + "&_fetch_banner=1&_fetch_share=1&_fetch_total=1"
                        + "&_sort=file_type:asc,updated_at:desc";
                String resp = getQuark(url, cancel);
                List<QuarkFile> pageItems = parseShareFiles(resp);
                if (pageItems.isEmpty()) break;
                for (QuarkFile item : pageItems) {
                    if (item.dir) {
                        if (!item.fid.isEmpty() && visitedDirs.size() + dirQueue.size() < MAX_FOLDERS) {
                            dirQueue.add(item.fid);
                        }
                    } else if (!item.fid.isEmpty()) {
                        files.add(item);
                        if (files.size() >= MAX_FILES) break;
                    }
                }
                if (pageItems.size() < 50) break;
                page++;
            }
        }
        return files;
    }

    static List<QuarkFile> parseShareFiles(String jsonText) {
        JSONObject root = parseJsonObj(jsonText);
        checkQuarkResponse(root, "获取夸克分享文件列表失败", false);
        JSONObject data = root.optJSONObject("data");
        if (data == null) return List.of();
        JSONArray list = data.optJSONArray("list");
        if (list == null) return List.of();

        List<QuarkFile> out = new ArrayList<>(list.length());
        for (int i = 0; i < list.length(); i++) {
            JSONObject obj = list.optJSONObject(i);
            if (obj == null) continue;
            String fid = obj.optString("fid", "").trim();
            String name = obj.optString("file_name", "").trim();
            long size = obj.optLong("size", -1L);
            String token = obj.optString("share_fid_token", "").trim();
            boolean isDir = obj.optBoolean("dir", false) || obj.optInt("file_type", 1) == 0;
            out.add(new QuarkFile(fid, name, size, token, isDir));
        }
        return out;
    }

    private static List<DownloadEntry> resolveDownloadsWithFallback(
            String pwdId, String stoken, List<QuarkFile> files, Net.Cancellation cancel) throws Exception {
        try {
            List<DownloadEntry> direct = fetchDirectShareDownloads(pwdId, stoken, files, cancel);
            if (!direct.isEmpty()) return direct;
        } catch (IllegalStateException authErr) {
            throw authErr;
        } catch (Exception ignored) {
            Net.check(cancel);
        }
        return fetchViaSaveToSelf(pwdId, stoken, files, cancel);
    }

    private static List<DownloadEntry> fetchDirectShareDownloads(
            String pwdId, String stoken, List<QuarkFile> files, Net.Cancellation cancel) throws Exception {
        JSONArray fids = new JSONArray();
        JSONArray tokens = new JSONArray();
        for (QuarkFile f : files) {
            fids.put(f.fid);
            tokens.put(f.shareFidToken);
        }
        JSONObject req = new JSONObject();
        req.put("fids", fids);
        req.put("pwd_id", pwdId);
        req.put("stoken", stoken);
        req.put("share_fid_tokens", tokens);
        String resp = postQuarkJson(API_BASE + "/share/sharepage/download?" + QUERY_SUFFIX, req.toString(), cancel);
        return parseDownloadUrls(resp);
    }

    private static List<DownloadEntry> fetchViaSaveToSelf(
            String pwdId, String stoken, List<QuarkFile> files, Net.Cancellation cancel) throws Exception {
        JSONArray fidList = new JSONArray();
        JSONArray tokenList = new JSONArray();
        for (QuarkFile f : files) {
            fidList.put(f.fid);
            tokenList.put(f.shareFidToken);
        }
        JSONObject saveReq = new JSONObject();
        saveReq.put("fid_list", fidList);
        saveReq.put("fid_token_list", tokenList);
        saveReq.put("to_pdir_fid", "0");
        saveReq.put("pwd_id", pwdId);
        saveReq.put("stoken", stoken);
        saveReq.put("pdir_fid", "0");
        saveReq.put("scene", "link");

        String saveBody = postQuarkJson(API_BASE + "/share/sharepage/save?" + QUERY_SUFFIX, saveReq.toString(), cancel);
        SaveResult saveResult = parseSaveTaskResult(saveBody);

        List<String> savedFids = saveResult.immediateSavedFids;
        if (savedFids.isEmpty()) {
            if (saveResult.taskId.isEmpty()) {
                throw new IllegalStateException("夸克转存任务创建失败");
            }
            savedFids = pollSaveTask(saveResult.taskId, cancel);
        }
        if (savedFids.isEmpty()) {
            throw new IllegalStateException("夸克转存后未返回文件 FID");
        }

        JSONObject dlReq = new JSONObject();
        dlReq.put("fids", new JSONArray(savedFids));
        String dlBody = postQuarkJson(API_BASE + "/file/download?" + QUERY_SUFFIX, dlReq.toString(), cancel);
        return parseDownloadUrls(dlBody);
    }

    private static List<String> pollSaveTask(String taskId, Net.Cancellation cancel) throws Exception {
        for (int i = 0; i < TASK_POLL_LIMIT; i++) {
            Net.check(cancel);
            String url = API_BASE + "/task?" + QUERY_SUFFIX
                    + "&task_id=" + encode(taskId)
                    + "&retry_index=" + i;
            String resp = getQuark(url, cancel);
            List<String> fids = parseTaskSavedFids(resp);
            if (!fids.isEmpty()) return fids;
            try {
                Thread.sleep(TASK_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException();
            }
        }
        throw new IllegalStateException("夸克网盘转存超时，请稍后重试");
    }

    static String parseSaveTaskId(String jsonText) {
        return parseSaveTaskResult(jsonText).taskId;
    }

    static SaveResult parseSaveTaskResult(String jsonText) {
        JSONObject root = parseJsonObj(jsonText);
        checkQuarkResponse(root, "夸克网盘转存失败", false);
        JSONObject data = root.optJSONObject("data");
        if (data == null) return new SaveResult("", List.of());
        String taskId = data.optString("task_id", "").trim();
        List<String> immediate = extractSavedTopFids(data.optJSONObject("save_as"));
        return new SaveResult(taskId, immediate);
    }

    static List<String> parseTaskSavedFids(String jsonText) {
        JSONObject root = parseJsonObj(jsonText);
        checkQuarkResponse(root, "查询夸克转存任务失败", false);
        JSONObject data = root.optJSONObject("data");
        if (data == null) return List.of();
        int status = data.optInt("status", 0);
        if (status == 3 || status < 0) {
            String msg = data.optString("task_title", root.optString("message", "转存任务失败"));
            throw new IllegalStateException("夸克网盘转存失败：" + msg);
        }
        if (status != 2 && !data.optBoolean("finished", false)) {
            return List.of();
        }
        return extractSavedTopFids(data.optJSONObject("save_as"));
    }

    private static List<String> extractSavedTopFids(JSONObject saveAs) {
        if (saveAs == null) return List.of();
        JSONArray arr = saveAs.optJSONArray("save_as_top_fids");
        if (arr == null || arr.length() == 0) return List.of();
        List<String> out = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            String fid = arr.optString(i, "").trim();
            if (!fid.isEmpty()) out.add(fid);
        }
        return out;
    }

    static List<DownloadEntry> parseDownloadUrls(String jsonText) {
        JSONObject root = parseJsonObj(jsonText);
        checkQuarkResponse(root, "获取夸克下载地址失败", false);
        Object dataObj = root.opt("data");
        List<DownloadEntry> out = new ArrayList<>();
        if (dataObj instanceof JSONArray arr) {
            for (int i = 0; i < arr.length(); i++) {
                addDownloadEntry(arr.optJSONObject(i), out);
            }
        } else if (dataObj instanceof JSONObject obj) {
            JSONArray list = obj.optJSONArray("list");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    addDownloadEntry(list.optJSONObject(i), out);
                }
            } else {
                addDownloadEntry(obj, out);
            }
        }
        return out;
    }

    private static void addDownloadEntry(JSONObject obj, List<DownloadEntry> out) {
        if (obj == null) return;
        String dlUrl = obj.optString("download_url", "").trim();
        if (dlUrl.isEmpty()) return;
        String fid = obj.optString("fid", "").trim();
        String name = obj.optString("file_name", "").trim();
        long size = obj.optLong("size", -1L);
        out.add(new DownloadEntry(fid, name, size, dlUrl));
    }

    private static void checkQuarkResponse(JSONObject root, String fallbackAction, boolean allowPasscodeMessage) {
        int code = root.optInt("code", 0);
        int status = root.optInt("status", 200);
        if (code == 0 && (status == 200 || status == 0)) return;
        String msg = root.optString("message", "").trim();
        if (status == 401 || status == 403 || code == 401 || code == 403
                || code == 41001 || code == 41002
                || msg.contains("登录") || msg.toLowerCase(Locale.ROOT).contains("login")
                || msg.toLowerCase(Locale.ROOT).contains("cookie")
                || msg.toLowerCase(Locale.ROOT).contains("unauthorized")) {
            throw new IllegalStateException(AUTH_REQUIRED_MSG + (msg.isEmpty() ? "" : "（" + msg + "）"));
        }
        if (allowPasscodeMessage && (code == 41010 || code == 41011 || msg.contains("提取码") || msg.contains("密码"))) {
            throw new IllegalArgumentException("夸克网盘提取码错误或未输入提取码" + (msg.isEmpty() ? "" : "：" + msg));
        }
        if (code == 41004 || code == 41008 || msg.contains("失效") || msg.contains("不存在") || msg.contains("删除")) {
            throw new IllegalArgumentException("夸克网盘分享链接已失效或不存在" + (msg.isEmpty() ? "" : "：" + msg));
        }
        // Any other non-zero code on authenticated/gated endpoints still requires clear login hint if session is stale.
        throw new IllegalArgumentException(AUTH_REQUIRED_MSG + "（" + fallbackAction + "，code=" + code + (msg.isEmpty() ? "" : "，" + msg) + "）");
    }

    private static String getQuark(String url, Net.Cancellation cancel) throws IOException {
        try {
            return HttpClient.get(url, apiHeaders(), cancel).body;
        } catch (IOException e) {
            throw mapHttpAuthException(e);
        }
    }

    private static String postQuarkJson(String url, String json, Net.Cancellation cancel) throws IOException {
        try {
            return HttpClient.postJson(url, json, apiHeaders(), cancel).body;
        } catch (IOException e) {
            throw mapHttpAuthException(e);
        }
    }

    private static IOException mapHttpAuthException(IOException e) {
        String msg = e.getMessage();
        if (msg != null && (msg.contains("401") || msg.contains("403") || msg.contains("访问受限"))) {
            return new IOException(AUTH_REQUIRED_MSG + "（" + msg + "）", e);
        }
        return e;
    }

    private static Map<String, String> apiHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", HttpClient.DESKTOP_UA);
        h.put("Origin", QUARK_ORIGIN);
        h.put("Referer", QUARK_REFERER);
        String cookie = mergedQuarkCookies();
        if (!cookie.isBlank()) {
            h.put("Cookie", cookie);
        }
        return h;
    }

    private static Map<String, String> downloadHeaders(String cookie) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Cookie", cookie);
        h.put("Origin", QUARK_ORIGIN);
        h.put("Referer", QUARK_REFERER);
        h.put("User-Agent", HttpClient.DESKTOP_UA);
        return h;
    }

    static String mergedQuarkCookies() {
        Map<String, String> jar = new LinkedHashMap<>();
        parseCookiePairs(HttpClient.getCookie("https://pan.quark.cn/"), jar);
        parseCookiePairs(HttpClient.getCookie("https://drive-pc.quark.cn/"), jar);
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

    private static String encode(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (Exception e) { return ""; }
    }
}
