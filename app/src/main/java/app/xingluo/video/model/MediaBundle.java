package app.xingluo.video.model;

import java.util.*;

/** Unified media extraction contract across video, audio, gallery, live, cloud, and torrent sources. */
public final class MediaBundle {
    public enum Type { VIDEO, AUDIO, GALLERY, LIVE, CLOUD_FILE, TORRENT }

    public static final class ImageItem {
        public final String url;
        public final List<String> backupUrls;
        public final String filename;
        public final int width;
        public final int height;
        public final boolean aiGenerated;
        public final boolean r18;
        public final List<String> tags;

        public ImageItem(String url, String filename, int width, int height) {
            this(url, List.of(), filename, width, height, false, false, List.of());
        }

        public ImageItem(String url, List<String> backupUrls, String filename, int width, int height,
                         boolean aiGenerated, boolean r18, List<String> tags) {
            this.url = url;
            this.backupUrls = backupUrls == null ? List.of() : List.copyOf(backupUrls);
            this.filename = filename == null ? "" : filename;
            this.width = width;
            this.height = height;
            this.aiGenerated = aiGenerated;
            this.r18 = r18;
            this.tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }

    public static final class CloudFileItem {
        public final String id;
        public final String name;
        public final long size;
        public final String downloadUrl;
        public final Map<String, String> headers;

        public CloudFileItem(String id, String name, long size, String downloadUrl, Map<String, String> headers) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "file" : name;
            this.size = size;
            this.downloadUrl = downloadUrl == null ? "" : downloadUrl;
            this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    public final Type type;
    public final String title;
    public final String author;
    public final String platform;
    public final List<String> tags;
    public final String referer;
    public final Map<String, String> headers;
    public final long estimatedBytes;
    public final boolean watermarkFree;
    public final List<Integer> qualityOptions;
    public final List<String> videoUrls;
    public final List<String> audioUrls;
    public final String hlsUrl;
    public final String liveUrl;
    public final List<ImageItem> images;
    public final List<CloudFileItem> cloudFiles;
    public final String subfolder;
    public final String torrentUriOrPath;
    public final String extraInfo;

    private MediaBundle(Builder b) {
        this.type = b.type;
        this.title = b.title == null || b.title.isBlank() ? "星落媒体" : b.title;
        this.author = b.author == null ? "" : b.author;
        this.platform = b.platform == null ? "网页" : b.platform;
        this.tags = List.copyOf(b.tags);
        this.referer = b.referer == null ? "" : b.referer;
        this.headers = Map.copyOf(b.headers);
        this.estimatedBytes = b.estimatedBytes;
        this.watermarkFree = b.watermarkFree;
        this.qualityOptions = List.copyOf(b.qualityOptions);
        this.videoUrls = List.copyOf(b.videoUrls);
        this.audioUrls = List.copyOf(b.audioUrls);
        this.hlsUrl = b.hlsUrl == null ? "" : b.hlsUrl;
        this.liveUrl = b.liveUrl == null ? "" : b.liveUrl;
        this.images = List.copyOf(b.images);
        this.cloudFiles = List.copyOf(b.cloudFiles);
        this.subfolder = b.subfolder == null ? "" : b.subfolder;
        this.torrentUriOrPath = b.torrentUriOrPath == null ? "" : b.torrentUriOrPath;
        this.extraInfo = b.extraInfo == null ? "" : b.extraInfo;
    }

    public static Builder builder(Type type) { return new Builder(type); }

    public static final class Builder {
        private final Type type;
        private String title = "星落媒体";
        private String author = "";
        private String platform = "网页";
        private final List<String> tags = new ArrayList<>();
        private String referer = "";
        private final Map<String, String> headers = new LinkedHashMap<>();
        private long estimatedBytes = -1;
        private boolean watermarkFree = false;
        private final List<Integer> qualityOptions = new ArrayList<>();
        private final List<String> videoUrls = new ArrayList<>();
        private final List<String> audioUrls = new ArrayList<>();
        private String hlsUrl = "";
        private String liveUrl = "";
        private final List<ImageItem> images = new ArrayList<>();
        private final List<CloudFileItem> cloudFiles = new ArrayList<>();
        private String subfolder = "";
        private String torrentUriOrPath = "";
        private String extraInfo = "";

        public Builder(Type type) { this.type = type; }
        public Builder title(String v) { if (v != null && !v.isBlank()) this.title = v; return this; }
        public Builder author(String v) { if (v != null) this.author = v; return this; }
        public Builder platform(String v) { if (v != null) this.platform = v; return this; }
        public Builder tags(Collection<String> v) { if (v != null) for (String s : v) if (s != null && !s.isBlank()) this.tags.add(s.trim()); return this; }
        public Builder referer(String v) { if (v != null) this.referer = v; return this; }
        public Builder header(String k, String v) { if (k != null && v != null) this.headers.put(k, v); return this; }
        public Builder headers(Map<String, String> v) { if (v != null) this.headers.putAll(v); return this; }
        public Builder estimatedBytes(long v) { this.estimatedBytes = v; return this; }
        public Builder watermarkFree(boolean v) { this.watermarkFree = v; return this; }
        public Builder qualityOptions(Collection<Integer> v) { if (v != null) this.qualityOptions.addAll(v); return this; }
        public Builder videoUrls(Collection<String> v) { if (v != null) this.videoUrls.addAll(v); return this; }
        public Builder videoUrl(String v) { if (v != null && !v.isBlank()) this.videoUrls.add(v); return this; }
        public Builder audioUrls(Collection<String> v) { if (v != null) this.audioUrls.addAll(v); return this; }
        public Builder audioUrl(String v) { if (v != null && !v.isBlank()) this.audioUrls.add(v); return this; }
        public Builder hlsUrl(String v) { if (v != null) this.hlsUrl = v; return this; }
        public Builder liveUrl(String v) { if (v != null) this.liveUrl = v; return this; }
        public Builder images(Collection<ImageItem> v) { if (v != null) this.images.addAll(v); return this; }
        public Builder addImage(ImageItem item) { if (item != null) this.images.add(item); return this; }
        public Builder cloudFiles(Collection<CloudFileItem> v) { if (v != null) this.cloudFiles.addAll(v); return this; }
        public Builder subfolder(String v) { if (v != null) this.subfolder = v; return this; }
        public Builder torrentUriOrPath(String v) { if (v != null) this.torrentUriOrPath = v; return this; }
        public Builder extraInfo(String v) { if (v != null) this.extraInfo = v; return this; }
        public MediaBundle build() { return new MediaBundle(this); }
    }
}
