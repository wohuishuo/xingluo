package app.xingluo.video.diag;

import app.xingluo.video.engine.HttpClient;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Fast GitHub release checker for wohuishuo/xingluo with API, redirect fallback, and labeled community mirrors.
 */
public final class UpdateChecker {
    public static final String REPO = "wohuishuo/xingluo";
    public static final String DIRECT_API_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    public static final String DIRECT_REDIRECT_URL = "https://github.com/" + REPO + "/releases/latest";

    private static final long TIMEOUT_MS = 4000L;
    private static final String[] MIRROR_PREFIXES = {
            "https://gh-proxy.com/",
            "https://mirror.ghproxy.com/"
    };
    private static final String[] MIRROR_NAMES = {
            "gh-proxy.com",
            "mirror.ghproxy.com"
    };

    public static final class UpdateResult {
        public final boolean success;
        public final boolean updateAvailable;
        public final String currentVersion;
        public final String latestVersion;
        public final String tagName;
        public final String releaseName;
        public final String releaseNotes;
        public final String downloadUrl;
        public final long assetSizeBytes;
        public final String sourceLabel;
        public final boolean fromCommunityMirror;
        public final String errorMessage;

        public UpdateResult(
                boolean success,
                boolean updateAvailable,
                String currentVersion,
                String latestVersion,
                String tagName,
                String releaseName,
                String releaseNotes,
                String downloadUrl,
                long assetSizeBytes,
                String sourceLabel,
                boolean fromCommunityMirror,
                String errorMessage) {
            this.success = success;
            this.updateAvailable = updateAvailable;
            this.currentVersion = safe(currentVersion);
            this.latestVersion = safe(latestVersion);
            this.tagName = safe(tagName);
            this.releaseName = safe(releaseName);
            this.releaseNotes = safe(releaseNotes);
            this.downloadUrl = safe(downloadUrl);
            this.assetSizeBytes = assetSizeBytes;
            this.sourceLabel = safe(sourceLabel);
            this.fromCommunityMirror = fromCommunityMirror;
            this.errorMessage = safe(errorMessage);
        }

        public static UpdateResult error(String currentVersion, String errorMessage) {
            return new UpdateResult(false, false, currentVersion, "", "", "", "", "", -1L, "", false, errorMessage);
        }
    }

    private UpdateChecker() {}

    /**
     * Checks for updates in order:
     * 1) GitHub Releases API (4s timeout)
     * 2) GitHub releases/latest redirect Location parsing (rate-limit resilient)
     * 3) Labeled community mirror fallbacks (gh-proxy.com, mirror.ghproxy.com)
     */
    public static UpdateResult check(String currentVersion) {
        OkHttpClient client = HttpClient.client().newBuilder()
                .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build();

        String lastError = "";

        // 1) Direct GitHub API
        try {
            String json = fetchBody(client, DIRECT_API_URL, true);
            return parseReleaseJson(json, currentVersion, "GitHub 官方 API", false, "");
        } catch (Exception e) {
            lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }

        // 2) Direct GitHub /releases/latest redirect Location header (bypasses API rate limits)
        try {
            String location = fetchRedirectLocation(client, DIRECT_REDIRECT_URL);
            String tag = parseRedirectTag(location);
            if (!tag.isEmpty()) {
                String cleanVer = normalizeVersion(tag);
                String releasePage = "https://github.com/" + REPO + "/releases/tag/" + tag;
                boolean newer = isNewer(tag, currentVersion);
                return new UpdateResult(
                        true,
                        newer,
                        currentVersion,
                        cleanVer,
                        tag,
                        "星落 " + tag,
                        "已通过 GitHub Releases 重定向通道检测到版本（API 暂不可用或受限）。",
                        releasePage,
                        -1L,
                        "GitHub 官方直连（重定向通道）",
                        false,
                        ""
                );
            }
        } catch (Exception e) {
            lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }

        // 3) Community mirror fallbacks (explicitly labeled)
        for (int i = 0; i < MIRROR_PREFIXES.length; i++) {
            String prefix = MIRROR_PREFIXES[i];
            String mirrorName = MIRROR_NAMES[i];
            String label = "社区镜像备用通道（" + mirrorName + "，非官方源）";
            try {
                String json = fetchBody(client, prefix + DIRECT_API_URL, true);
                return parseReleaseJson(json, currentVersion, label, true, prefix);
            } catch (Exception e) {
                lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
        }

        return UpdateResult.error(currentVersion,
                lastError.isEmpty() ? "检查更新失败，请确认网络或代理状态" : ("检查更新失败：" + lastError));
    }

    /** Parses a GitHub release JSON payload into an UpdateResult. */
    public static UpdateResult parseReleaseJson(
            String json,
            String currentVersion,
            String sourceLabel,
            boolean fromCommunityMirror,
            String mirrorDownloadPrefix) throws Exception {
        JSONObject obj = new JSONObject(json);
        String tagName = obj.optString("tag_name", "").trim();
        if (tagName.isEmpty()) {
            throw new IllegalArgumentException("Release JSON missing tag_name");
        }
        String releaseName = obj.optString("name", tagName).trim();
        String body = obj.optString("body", "").trim();
        String htmlUrl = obj.optString("html_url", "https://github.com/" + REPO + "/releases/tag/" + tagName).trim();

        String downloadUrl = "";
        long assetSize = -1L;
        JSONArray assets = obj.optJSONArray("assets");
        if (assets != null) {
            String fallbackAssetUrl = "";
            long fallbackAssetSize = -1L;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) continue;
                String url = asset.optString("browser_download_url", "").trim();
                long size = asset.optLong("size", -1L);
                String name = asset.optString("name", "").toLowerCase(Locale.ROOT);
                if (url.isEmpty()) continue;
                if (name.endsWith(".apk")) {
                    downloadUrl = url;
                    assetSize = size;
                    break;
                }
                if (fallbackAssetUrl.isEmpty()) {
                    fallbackAssetUrl = url;
                    fallbackAssetSize = size;
                }
            }
            if (downloadUrl.isEmpty() && !fallbackAssetUrl.isEmpty()) {
                downloadUrl = fallbackAssetUrl;
                assetSize = fallbackAssetSize;
            }
        }
        if (downloadUrl.isEmpty()) {
            downloadUrl = htmlUrl;
        }
        if (fromCommunityMirror && mirrorDownloadPrefix != null && !mirrorDownloadPrefix.isBlank()
                && !downloadUrl.startsWith(mirrorDownloadPrefix)) {
            downloadUrl = mirrorDownloadPrefix + downloadUrl;
        }

        String latestVer = normalizeVersion(tagName);
        boolean newer = isNewer(tagName, currentVersion);
        return new UpdateResult(
                true,
                newer,
                currentVersion,
                latestVer,
                tagName,
                releaseName.isEmpty() ? tagName : releaseName,
                body,
                downloadUrl,
                assetSize,
                sourceLabel,
                fromCommunityMirror,
                ""
        );
    }

    /** Extracts the release tag from a GitHub /releases/latest redirect Location URL. */
    public static String parseRedirectTag(String location) {
        if (location == null || location.isBlank()) return "";
        String marker = "/releases/tag/";
        int idx = location.indexOf(marker);
        if (idx < 0) return "";
        String tail = location.substring(idx + marker.length());
        int q = tail.indexOf('?');
        if (q >= 0) tail = tail.substring(0, q);
        int hash = tail.indexOf('#');
        if (hash >= 0) tail = tail.substring(0, hash);
        int slash = tail.indexOf('/');
        if (slash >= 0) tail = tail.substring(0, slash);
        return tail.trim();
    }

    /**
     * Semantic version comparison returning true iff remoteTag is strictly newer than localVersion.
     * Supports leading 'v'/'V', multi-digit numbers, uneven segment counts, and pre-release tags (-beta.1).
     */
    public static boolean isNewer(String remoteTag, String localVersion) {
        ParsedVersion remote = parseVersion(remoteTag);
        ParsedVersion local = parseVersion(localVersion);
        if (remote == null || local == null) return false;

        int max = Math.max(remote.numbers.size(), local.numbers.size());
        for (int i = 0; i < max; i++) {
            int r = i < remote.numbers.size() ? remote.numbers.get(i) : 0;
            int l = i < local.numbers.size() ? local.numbers.get(i) : 0;
            if (r != l) return r > l;
        }

        // Same numeric core: stable (no pre-release) > pre-release.
        if (remote.preRelease.isEmpty() && !local.preRelease.isEmpty()) return true;
        if (!remote.preRelease.isEmpty() && local.preRelease.isEmpty()) return false;
        if (remote.preRelease.isEmpty()) return false;
        return comparePreRelease(remote.preRelease, local.preRelease) > 0;
    }

    private static int comparePreRelease(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int max = Math.max(pa.length, pb.length);
        for (int i = 0; i < max; i++) {
            if (i >= pa.length) return -1;
            if (i >= pb.length) return 1;
            String sa = pa[i];
            String sb = pb[i];
            Integer ia = tryInt(sa);
            Integer ib = tryInt(sb);
            if (ia != null && ib != null) {
                int c = Integer.compare(ia, ib);
                if (c != 0) return c;
            } else if (ia != null) {
                return -1; // SemVer: numeric identifiers have lower precedence than non-numeric.
            } else if (ib != null) {
                return 1;
            } else {
                int c = sa.compareToIgnoreCase(sb);
                if (c != 0) return c;
            }
        }
        return 0;
    }

    private static Integer tryInt(String s) {
        try { return Integer.valueOf(s); } catch (NumberFormatException e) { return null; }
    }

    private static final class ParsedVersion {
        final List<Integer> numbers;
        final String preRelease;

        ParsedVersion(List<Integer> numbers, String preRelease) {
            this.numbers = numbers;
            this.preRelease = preRelease;
        }
    }

    private static String normalizeVersion(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1).trim();
        return s;
    }

    private static ParsedVersion parseVersion(String raw) {
        String s = normalizeVersion(raw);
        if (s.isEmpty()) return null;
        int plus = s.indexOf('+');
        if (plus >= 0) s = s.substring(0, plus);
        String pre = "";
        int dash = s.indexOf('-');
        if (dash >= 0) {
            pre = s.substring(dash + 1).trim();
            s = s.substring(0, dash).trim();
        }
        if (s.isEmpty()) return null;
        String[] parts = s.split("\\.");
        List<Integer> nums = new ArrayList<>(parts.length);
        for (String p : parts) {
            if (p.isEmpty()) return null;
            try {
                nums.add(Integer.parseInt(p));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return new ParsedVersion(nums, pre);
    }

    private static String fetchBody(OkHttpClient client, String url, boolean apiHeaders) throws IOException {
        Request.Builder rb = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", "xingluo-android");
        if (apiHeaders) {
            rb.header("Accept", "application/vnd.github+json");
        }
        try (Response resp = client.newCall(rb.build()).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code());
            }
            return resp.body() != null ? resp.body().string() : "";
        }
    }

    private static String fetchRedirectLocation(OkHttpClient client, String url) throws IOException {
        Request req = new Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "xingluo-android")
                .build();
        try (Response resp = client.newCall(req).execute()) {
            int code = resp.code();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = resp.header("Location", "");
                if (loc == null || loc.isBlank()) throw new IOException("Empty redirect Location");
                return loc;
            }
            throw new IOException("Unexpected status " + code);
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
