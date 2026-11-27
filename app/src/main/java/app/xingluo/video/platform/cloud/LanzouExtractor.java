package app.xingluo.video.platform.cloud;

import app.xingluo.video.LinkInput;
import app.xingluo.video.MediaParser;
import app.xingluo.video.Net;
import app.xingluo.video.engine.HttpClient;
import app.xingluo.video.model.MediaBundle;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lanzou Cloud (lanzou*.com) share extractor supporting single-file (/fn iframe & passcode)
 * and folder shares (/filemoreajax.php).
 */
public final class LanzouExtractor {
    private static final Pattern IFRAME_FN_PATTERN = Pattern.compile(
            "(?is)<iframe\\b[^>]*\\bsrc\\s*=\\s*['\"](/fn\\?[^'\"]+)['\"][^>]*>");
    private static final Pattern VAR_DECL_PATTERN = Pattern.compile(
            "(?m)(?:var|let|const)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(['\"])(.*?)\\2\\s*;?");
    private static final Pattern AJAXM_URL_PATTERN = Pattern.compile(
            "url\\s*:\\s*['\"](/ajaxm\\.php(?:\\?[^'\"]*)?)['\"]");
    private static final Pattern DATA_BLOCK_PATTERN = Pattern.compile(
            "(?s)data\\s*:\\s*\\{(.*?)\\}");
    private static final Pattern DATA_FIELD_PATTERN = Pattern.compile(
            "['\"]?([A-Za-z0-9_]+)['\"]?\\s*:\\s*(?:(['\"])(.*?)\\2|([A-Za-z0-9_$-]+))");
    private static final int MAX_FOLDER_FILES = 30;

    private LanzouExtractor() {}

    static final class AjaxRequestSpec {
        final String endpointPath;
        final Map<String, String> params;

        AjaxRequestSpec(String endpointPath, Map<String, String> params) {
            this.endpointPath = endpointPath;
            this.params = params;
        }
    }

    static final class AjaxmResponse {
        final String redirectUrl;
        final String inf;

        AjaxmResponse(String redirectUrl, String inf) {
            this.redirectUrl = redirectUrl;
            this.inf = inf;
        }
    }

    static final class FolderFileEntry {
        final String id;
        final String name;
        final String sizeText;

        FolderFileEntry(String id, String name, String sizeText) {
            this.id = id;
            this.name = name;
            this.sizeText = sizeText;
        }
    }

    public static MediaBundle extract(String rawInput, Net.Cancellation cancel) throws Exception {
        String url = LinkInput.extract(rawInput);
        String passcode = LinkInput.extractPasscode(rawInput);

        HttpClient.HttpResult sharePage = HttpClient.get(url, browserHeaders(url), cancel);
        String shareUrl = sharePage.finalUrl;
        String origin = originOf(shareUrl);

        String iframeSrc = extractIframeSrc(sharePage.body);
        if (iframeSrc.isEmpty() && isFolderShare(sharePage.body)) {
            return extractFolder(shareUrl, origin, sharePage.body, passcode, cancel);
        }

        MediaBundle.CloudFileItem item = resolveSingleFile(shareUrl, origin, sharePage.body, iframeSrc, passcode, cancel);
        return MediaBundle.builder(MediaBundle.Type.CLOUD_FILE)
                .title(item.name)
                .platform("蓝奏云")
                .referer(shareUrl)
                .headers(item.headers)
                .cloudFiles(List.of(item))
                .extraInfo("蓝奏云直链支持多线程高速下载")
                .build();
    }

    private static MediaBundle extractFolder(
            String shareUrl, String origin, String html, String passcode, Net.Cancellation cancel) throws Exception {
        AjaxRequestSpec folderSpec = parseFolderRequest(html, passcode);
        String endpoint = origin + folderSpec.endpointPath;
        HttpClient.HttpResult res = HttpClient.postForm(endpoint, folderSpec.params, browserHeaders(shareUrl), cancel);
        List<FolderFileEntry> entries = parseFolderFiles(res.body);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("蓝奏云文件夹中没有可下载的文件");
        }

        List<MediaBundle.CloudFileItem> items = new ArrayList<>();
        int count = Math.min(entries.size(), MAX_FOLDER_FILES);
        for (int i = 0; i < count; i++) {
            Net.check(cancel);
            FolderFileEntry entry = entries.get(i);
            String fileShareUrl = origin + "/" + entry.id;
            HttpClient.HttpResult filePage = HttpClient.get(fileShareUrl, browserHeaders(shareUrl), cancel);
            String iframe = extractIframeSrc(filePage.body);
            MediaBundle.CloudFileItem resolved = resolveSingleFile(
                    filePage.finalUrl, originOf(filePage.finalUrl), filePage.body, iframe, "", cancel);
            String name = !entry.name.isBlank() ? entry.name : resolved.name;
            items.add(new MediaBundle.CloudFileItem(entry.id, name, resolved.size, resolved.downloadUrl, resolved.headers));
        }

        String folderTitle = extractFileName(html);
        if (folderTitle.isBlank()) folderTitle = items.get(0).name;
        return MediaBundle.builder(MediaBundle.Type.CLOUD_FILE)
                .title(folderTitle)
                .platform("蓝奏云")
                .referer(shareUrl)
                .headers(items.get(0).headers)
                .cloudFiles(items)
                .extraInfo("蓝奏云直链支持多线程高速下载（共 " + items.size() + " 个文件）")
                .build();
    }

    private static MediaBundle.CloudFileItem resolveSingleFile(
            String shareUrl, String origin, String shareHtml, String iframeSrc,
            String passcode, Net.Cancellation cancel) throws Exception {
        String targetHtml = shareHtml;
        String refererUrl = shareUrl;
        if (!iframeSrc.isEmpty()) {
            String iframeUrl = URI.create(shareUrl).resolve(iframeSrc).toString();
            HttpClient.HttpResult iframeRes = HttpClient.get(iframeUrl, browserHeaders(shareUrl), cancel);
            targetHtml = iframeRes.body;
            refererUrl = iframeRes.finalUrl;
        }

        AjaxRequestSpec spec = parseAjaxmRequest(targetHtml, passcode);
        String ajaxUrl = origin + spec.endpointPath;
        HttpClient.HttpResult ajaxRes = HttpClient.postForm(ajaxUrl, spec.params, ajaxHeaders(refererUrl, origin), cancel);
        AjaxmResponse parsed = parseAjaxmResponse(ajaxRes.body);

        Map<String, String> redirectHeaders = new LinkedHashMap<>();
        redirectHeaders.put("User-Agent", HttpClient.DESKTOP_UA);
        redirectHeaders.put("Referer", refererUrl);
        redirectHeaders.put("Cookie", "down_ip=1");
        String cdnUrl = HttpClient.resolveRedirectUrl(parsed.redirectUrl, redirectHeaders, cancel);

        String name = extractFileName(shareHtml);
        if (name.isBlank() && !parsed.inf.isBlank() && !"0".equals(parsed.inf) && !"1".equals(parsed.inf)) {
            name = parsed.inf;
        }
        if (name.isBlank()) {
            name = filenameFromUrl(cdnUrl);
        }

        Map<String, String> dlHeaders = new LinkedHashMap<>();
        dlHeaders.put("User-Agent", HttpClient.DESKTOP_UA);
        dlHeaders.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        return new MediaBundle.CloudFileItem("", name, -1L, cdnUrl, dlHeaders);
    }

    static String extractIframeSrc(String html) {
        if (html == null || html.isEmpty()) return "";
        Matcher m = IFRAME_FN_PATTERN.matcher(html);
        if (m.find()) return MediaParser.entities(m.group(1).trim());
        return "";
    }

    static boolean isFolderShare(String html) {
        return html != null && html.contains("filemoreajax.php");
    }

    /**
     * Strips JavaScript line (//) and block comments while preserving string literals (including https:// URLs).
     */
    static String stripJsComments(String src) {
        if (src == null || src.isEmpty()) return "";
        StringBuilder out = new StringBuilder(src.length());
        int n = src.length();
        char quote = 0;
        boolean escape = false;
        for (int i = 0; i < n; i++) {
            char c = src.charAt(i);
            if (quote != 0) {
                out.append(c);
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                out.append(c);
                continue;
            }
            if (c == '/' && i + 1 < n) {
                char next = src.charAt(i + 1);
                if (next == '/') {
                    i += 2;
                    while (i < n && src.charAt(i) != '\n' && src.charAt(i) != '\r') i++;
                    if (i < n) out.append(src.charAt(i));
                    continue;
                }
                if (next == '*') {
                    i += 2;
                    while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) i++;
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    static AjaxRequestSpec parseAjaxmRequest(String html, String passcode) {
        String clean = stripJsComments(html);
        Map<String, String> vars = extractJsVars(clean);

        String endpoint = "/ajaxm.php";
        Matcher urlMatcher = AJAXM_URL_PATTERN.matcher(clean);
        if (urlMatcher.find()) {
            endpoint = urlMatcher.group(1).trim();
        }

        Map<String, String> params = extractDataParams(clean, vars, "downprocess");
        if (params.isEmpty()) {
            throw new IllegalArgumentException("未能解析蓝奏云下载参数，页面结构可能已变更");
        }
        params.putIfAbsent("action", "downprocess");
        params.putIfAbsent("ves", "1");
        if (passcode != null && !passcode.isEmpty()) {
            params.put("p", passcode);
        } else if ((params.containsKey("p") && params.get("p").isEmpty())
                || (clean.contains("#pwd") && !params.containsKey("p"))) {
            throw new IllegalArgumentException("该蓝奏云分享需要提取码，请在链接后附上密码");
        }
        return new AjaxRequestSpec(endpoint, params);
    }

    static AjaxRequestSpec parseFolderRequest(String html, String passcode) {
        String clean = stripJsComments(html);
        Map<String, String> vars = extractJsVars(clean);
        Map<String, String> params = extractDataParams(clean, vars, "fid");
        if (params.isEmpty()) {
            throw new IllegalArgumentException("未能解析蓝奏云文件夹参数");
        }
        params.putIfAbsent("lx", "2");
        params.putIfAbsent("pg", "1");
        params.putIfAbsent("rep", "0");
        params.putIfAbsent("up", "1");
        params.putIfAbsent("ls", "1");
        if (passcode != null && !passcode.isEmpty()) {
            params.put("pwd", passcode);
        }
        return new AjaxRequestSpec("/filemoreajax.php", params);
    }

    private static JSONObject parseJsonObj(String jsonText) {
        try { return new JSONObject(jsonText); }
        catch (org.json.JSONException e) { throw new IllegalArgumentException("蓝奏云返回数据格式异常", e); }
    }

    static List<FolderFileEntry> parseFolderFiles(String jsonText) {
        JSONObject root = parseJsonObj(jsonText);
        int zt = root.optInt("zt", 0);
        if (zt != 1) {
            String info = root.optString("info", root.optString("inf", ""));
            throw new IllegalArgumentException("获取蓝奏云文件夹列表失败" + (info.isEmpty() ? "" : "：" + info));
        }
        JSONArray text = root.optJSONArray("text");
        if (text == null) return List.of();
        List<FolderFileEntry> out = new ArrayList<>(text.length());
        for (int i = 0; i < text.length(); i++) {
            JSONObject obj = text.optJSONObject(i);
            if (obj == null) continue;
            String id = obj.optString("id", "").trim();
            String name = obj.optString("name_all", "").trim();
            String size = obj.optString("size", "").trim();
            if (!id.isEmpty()) out.add(new FolderFileEntry(id, name, size));
        }
        return out;
    }

    static AjaxmResponse parseAjaxmResponse(String jsonText) {
        JSONObject root = parseJsonObj(jsonText);
        int zt = root.optInt("zt", 0);
        String inf = root.optString("inf", "").trim();
        if (zt != 1) {
            throw new IllegalArgumentException("蓝奏云解析失败" + (inf.isEmpty() ? "（zt=" + zt + "）" : "：" + inf));
        }
        String dom = root.optString("dom", "").trim();
        String url = root.optString("url", "").trim();
        if (url.isEmpty()) {
            throw new IllegalStateException("蓝奏云未返回下载跳转地址");
        }
        String fullUrl;
        if (url.startsWith("http://") || url.startsWith("https://")) {
            fullUrl = url;
        } else {
            String cleanDom = dom.endsWith("/") ? dom.substring(0, dom.length() - 1) : dom;
            if (url.startsWith("/file")) {
                fullUrl = cleanDom + url;
            } else if (url.startsWith("/")) {
                fullUrl = cleanDom + "/file" + url;
            } else {
                fullUrl = cleanDom + "/file/" + url;
            }
        }
        return new AjaxmResponse(fullUrl, inf);
    }

    private static Map<String, String> extractJsVars(String cleanJs) {
        Map<String, String> vars = new HashMap<>();
        Matcher m = VAR_DECL_PATTERN.matcher(cleanJs);
        while (m.find()) {
            vars.put(m.group(1), m.group(3));
        }
        return vars;
    }

    private static Map<String, String> extractDataParams(String cleanJs, Map<String, String> vars, String preferredToken) {
        Matcher blockMatcher = DATA_BLOCK_PATTERN.matcher(cleanJs);
        Map<String, String> fallback = new LinkedHashMap<>();
        while (blockMatcher.find()) {
            String block = blockMatcher.group(1);
            Map<String, String> parsed = parseDataBlock(block, vars);
            if (parsed.isEmpty()) continue;
            if (block.contains(preferredToken)) return parsed;
            if (fallback.isEmpty()) fallback = parsed;
        }
        return fallback;
    }

    private static Map<String, String> parseDataBlock(String block, Map<String, String> vars) {
        Map<String, String> params = new LinkedHashMap<>();
        Matcher fm = DATA_FIELD_PATTERN.matcher(block);
        while (fm.find()) {
            String key = fm.group(1);
            String quotedVal = fm.group(3);
            String rawIdent = fm.group(4);
            String value;
            if (quotedVal != null) {
                value = quotedVal;
            } else if (rawIdent != null) {
                value = vars.getOrDefault(rawIdent, rawIdent.matches("-?\\d+") ? rawIdent : "");
            } else {
                value = "";
            }
            params.put(key, value);
        }
        return params;
    }

    private static String extractFileName(String html) {
        if (html == null || html.isEmpty()) return "";
        for (String regex : new String[]{
                "(?is)<div[^>]*class\\s*=\\s*['\"]b['\"][^>]*>(.*?)</div>",
                "(?is)<div[^>]*class\\s*=\\s*['\"]md['\"][^>]*>([^<]+)",
                "(?is)<title[^>]*>(.*?)</title>"
        }) {
            Matcher m = Pattern.compile(regex).matcher(html);
            if (m.find()) {
                String raw = m.group(1).replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").trim();
                raw = raw.replaceAll("\\s*-\\s*蓝奏云\\s*$", "").trim();
                if (!raw.isEmpty() && !raw.equals("蓝奏云")) return raw;
            }
        }
        return "";
    }

    private static String filenameFromUrl(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path != null) {
                int slash = path.lastIndexOf('/');
                if (slash >= 0 && slash + 1 < path.length()) {
                    String name = path.substring(slash + 1).trim();
                    if (!name.isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {}
        return "lanzou_file";
    }

    private static String originOf(String url) {
        URI u = URI.create(url);
        return u.getScheme() + "://" + u.getAuthority();
    }

    private static Map<String, String> browserHeaders(String referer) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", HttpClient.DESKTOP_UA);
        h.put("Referer", referer);
        return h;
    }

    private static Map<String, String> ajaxHeaders(String referer, String origin) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", HttpClient.DESKTOP_UA);
        h.put("Referer", referer);
        h.put("Origin", origin);
        h.put("X-Requested-With", "XMLHttpRequest");
        return h;
    }
}
