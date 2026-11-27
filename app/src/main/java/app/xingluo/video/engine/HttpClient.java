package app.xingluo.video.engine;

import android.webkit.CookieManager;
import app.xingluo.video.LinkInput;
import app.xingluo.video.Net;
import okhttp3.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Shared OkHttp 4.12 client with strict per-domain CookieManager isolation and realistic browser headers. */
public final class HttpClient {
    public static final String MOBILE_UA = Net.UA;
    public static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static volatile OkHttpClient instance;
    private static volatile CookieStoreBridge cookieBridge = defaultBridge();

    public interface CookieStoreBridge {
        String getCookie(String url);
        void setCookie(String url, String setCookieHeader);
    }

    private static CookieStoreBridge defaultBridge() {
        return new CookieStoreBridge() {
            @Override public String getCookie(String url) {
                try { return CookieManager.getInstance().getCookie(url); } catch (Throwable ignored) { return null; }
            }
            @Override public void setCookie(String url, String setCookieHeader) {
                try { CookieManager.getInstance().setCookie(url, setCookieHeader); } catch (Throwable ignored) {}
            }
        };
    }

    public static void setCookieBridgeForTest(CookieStoreBridge bridge) {
        cookieBridge = bridge == null ? defaultBridge() : bridge;
    }

    public static OkHttpClient client() {
        if (instance == null) {
            synchronized (HttpClient.class) {
                if (instance == null) {
                    instance = new OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(25, TimeUnit.SECONDS)
                            .writeTimeout(25, TimeUnit.SECONDS)
                            .followRedirects(false)
                            .followSslRedirects(false)
                            .connectionPool(new ConnectionPool(12, 60, TimeUnit.SECONDS))
                            .retryOnConnectionFailure(true)
                            .build();
                }
            }
        }
        return instance;
    }

    public static String getCookie(String url) {
        return cookieBridge.getCookie(url);
    }

    public static void saveResponseCookies(String url, Headers headers) {
        for (String val : headers.values("Set-Cookie")) {
            cookieBridge.setCookie(url, val);
        }
    }

    public static final class HttpResult {
        public final int code;
        public final String finalUrl;
        public final String contentType;
        public final String body;
        public final Headers headers;

        public HttpResult(int code, String finalUrl, String contentType, String body, Headers headers) {
            this.code = code;
            this.finalUrl = finalUrl;
            this.contentType = contentType;
            this.body = body;
            this.headers = headers;
        }
    }

    /** Executes GET following up to 9 redirects with per-hop domain-scoped cookies (never forwards cookies across domains). */
    public static HttpResult get(String url, Map<String, String> extraHeaders, Net.Cancellation cancel) throws IOException {
        return execute("GET", url, null, extraHeaders, cancel);
    }

    public static HttpResult postJson(String url, String jsonBody, Map<String, String> extraHeaders, Net.Cancellation cancel) throws IOException {
        RequestBody body = RequestBody.create(jsonBody == null ? "{}" : jsonBody, MediaType.get("application/json; charset=utf-8"));
        return execute("POST", url, body, extraHeaders, cancel);
    }

    public static HttpResult postForm(String url, Map<String, String> form, Map<String, String> extraHeaders, Net.Cancellation cancel) throws IOException {
        FormBody.Builder fb = new FormBody.Builder();
        if (form != null) for (Map.Entry<String, String> e : form.entrySet()) fb.add(e.getKey(), e.getValue() == null ? "" : e.getValue());
        return execute("POST", url, fb.build(), extraHeaders, cancel);
    }

    public static String resolveRedirectUrl(String url, Map<String, String> extraHeaders, Net.Cancellation cancel) throws IOException {
        String current = normalizeUrl(url);
        for (int i = 0; i < 9; i++) {
            Net.check(cancel);
            if (!LinkInput.isWebUrl(current)) throw new IOException("不支持的地址: " + current);
            Request.Builder req = buildRequest("GET", current, null, extraHeaders);
            try (Response resp = client().newCall(req.build()).execute()) {
                saveResponseCookies(current, resp.headers());
                int code = resp.code();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = resp.header("Location");
                    if (loc == null || loc.isBlank()) return current;
                    current = URI.create(current).resolve(loc).toString();
                    continue;
                }
                return current;
            }
        }
        return current;
    }

    private static HttpResult execute(String method, String url, RequestBody body, Map<String, String> extraHeaders, Net.Cancellation cancel) throws IOException {
        String current = normalizeUrl(url);
        for (int i = 0; i < 9; i++) {
            Net.check(cancel);
            if (!LinkInput.isWebUrl(current)) throw new IOException("跳转到了不支持的地址");
            Request.Builder req = buildRequest(method, current, i == 0 ? body : null, extraHeaders);
            try (Response resp = client().newCall(req.build()).execute()) {
                saveResponseCookies(current, resp.headers());
                int code = resp.code();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = resp.header("Location");
                    if (loc == null) throw new IOException("平台重定向缺少目标地址");
                    current = URI.create(current).resolve(loc).toString();
                    if (code == 303) method = "GET";
                    continue;
                }
                if (code < 200 || code >= 300) {
                    String hint = (code == 412 || code == 403 || code == 429)
                            ? "平台访问受限，请点网页识别或登录后重试"
                            : "网络请求未成功";
                    throw new IOException(hint + "（" + code + "）");
                }
                String ct = resp.header("Content-Type", "");
                String text = resp.body() != null ? resp.body().string() : "";
                return new HttpResult(code, current, ct, text, resp.headers());
            }
        }
        throw new IOException("链接跳转次数过多");
    }

    public static Request.Builder buildRequest(String method, String url, RequestBody body, Map<String, String> extraHeaders) {
        Request.Builder rb = new Request.Builder().url(url).method(method, body);
        boolean hasUa = false;
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if ("User-Agent".equalsIgnoreCase(e.getKey())) hasUa = true;
                if (e.getValue() != null && !e.getValue().isBlank()) rb.header(e.getKey(), e.getValue());
            }
        }
        if (!hasUa) rb.header("User-Agent", MOBILE_UA);
        rb.header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        String cookie = getCookie(url);
        if (cookie != null && !cookie.isBlank() && (extraHeaders == null || !extraHeaders.containsKey("Cookie"))) {
            rb.header("Cookie", cookie);
        }
        return rb;
    }

    public static String normalizeUrl(String url) {
        if (url == null) return "";
        if (url.startsWith("http://") && !url.startsWith("http://127.0.0.1") && !url.startsWith("http://localhost")) {
            return "https://" + url.substring(7);
        }
        return url;
    }
}
