package app.xingluo.video.platform.global;

import app.xingluo.video.LinkInput;
import app.xingluo.video.Preferences;
import app.xingluo.video.engine.HttpClient;
import app.xingluo.video.model.MediaBundle;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Filters extracted gallery images to exclude avatars, UI chrome, tiny thumbnails, duplicates, and unwanted AI/R-18 works. */
public final class ImageFilter {
    private static final double MAX_BANNER_ASPECT_RATIO = 4.5d;
    private static final Pattern TRACKING_PIXEL_1X1 = Pattern.compile("(?<![0-9])1x1(?![0-9])");

    private static final List<String> JUNK_URL_TOKENS = List.of(
            "/user-profile/",
            "/custom-thumb/",
            "/common/",
            "/badge/",
            "/badges/",
            "/sprite/",
            "/sprites/",
            "favicon",
            "avatar",
            "logo",
            "banner",
            "bg_",
            "background",
            ".svg",
            ".ico"
    );

    public static final class Config {
        public final int minPx;
        public final boolean skipBg;
        public final boolean skipAi;
        public final boolean skipR18;

        public Config(int minPx, boolean skipBg, boolean skipAi, boolean skipR18) {
            this.minPx = Math.max(0, minPx);
            this.skipBg = skipBg;
            this.skipAi = skipAi;
            this.skipR18 = skipR18;
        }

        public static Config defaults() {
            return new Config(400, true, false, false);
        }

        public static Config from(Preferences prefs) {
            if (prefs == null) return defaults();
            return new Config(prefs.pixivMinPx(), prefs.pixivSkipBg(), prefs.pixivSkipAi(), false);
        }
    }

    private ImageFilter() {}

    public static List<MediaBundle.ImageItem> filter(List<MediaBundle.ImageItem> items,
                                                     int minPx, boolean skipBg, boolean skipAi, boolean skipR18) {
        return filter(items, new Config(minPx, skipBg, skipAi, skipR18));
    }

    public static List<MediaBundle.ImageItem> filter(List<MediaBundle.ImageItem> items, Config config) {
        if (items == null || items.isEmpty()) return List.of();
        Config cfg = config == null ? Config.defaults() : config;
        Set<String> seenUrls = new LinkedHashSet<>();
        List<MediaBundle.ImageItem> result = new ArrayList<>();
        for (MediaBundle.ImageItem item : items) {
            if (!accept(item, cfg)) continue;
            String normalized = normalizeUrl(item.url);
            if (normalized.isEmpty() || !seenUrls.add(normalized)) continue;
            result.add(item);
        }
        return List.copyOf(result);
    }

    public static boolean accept(MediaBundle.ImageItem item, Config config) {
        if (item == null || isJunkUrl(item.url)) return false;
        Config cfg = config == null ? Config.defaults() : config;
        if (cfg.skipAi && item.aiGenerated) return false;
        if (cfg.skipR18 && (item.r18 || hasR18Tag(item.tags))) return false;
        if (item.width > 0 && item.width < cfg.minPx) return false;
        if (item.height > 0 && item.height < cfg.minPx) return false;
        if (cfg.skipBg && item.width > 0 && item.height > 0) {
            double ratio = Math.max((double) item.width / item.height, (double) item.height / item.width);
            if (ratio > MAX_BANNER_ASPECT_RATIO) return false;
        }
        return true;
    }

    public static boolean isJunkUrl(String url) {
        if (url == null || url.isBlank() || !LinkInput.isWebUrl(url)) return true;
        String lower = url.trim().toLowerCase(Locale.ROOT);
        for (String token : JUNK_URL_TOKENS) {
            if (lower.contains(token)) return true;
        }
        return TRACKING_PIXEL_1X1.matcher(lower).find();
    }

    public static String normalizeUrl(String url) {
        if (url == null || url.isBlank()) return "";
        String normalized = HttpClient.normalizeUrl(url.trim());
        int hash = normalized.indexOf('#');
        if (hash >= 0) normalized = normalized.substring(0, hash);
        try {
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getRawAuthority() == null ? "" : uri.getRawAuthority().toLowerCase(Locale.ROOT);
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            String query = uri.getRawQuery();
            return scheme + "://" + host + path + (query == null || query.isEmpty() ? "" : "?" + query);
        } catch (Exception ignored) {
            return normalized;
        }
    }

    private static boolean hasR18Tag(List<String> tags) {
        if (tags == null || tags.isEmpty()) return false;
        for (String tag : tags) {
            if (tag == null) continue;
            String upper = tag.trim().toUpperCase(Locale.ROOT);
            if ("R-18".equals(upper) || "R-18G".equals(upper)) return true;
        }
        return false;
    }
}
