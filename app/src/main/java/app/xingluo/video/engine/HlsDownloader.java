package app.xingluo.video.engine;

import app.xingluo.video.LinkInput;
import app.xingluo.video.MediaFiles;
import app.xingluo.video.Net;
import app.xingluo.video.VideoQuality;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java HLS M3U8 downloader with master playlist selection via {@link VideoQuality},
 * AES-128 segment decryption, DRM rejection, concurrent segment fetching with resume skip,
 * fMP4 concatenation, and MPEG-TS -> MP4 remuxing.
 */
public final class HlsDownloader {
    public static final int MAX_SEGMENT_THREADS = 4;

    private static final Pattern ATTR_PATTERN = Pattern.compile("([A-Z0-9-]+)=(\"([^\"]*)\"|[^,]*)");
    private static final Pattern RES_PATTERN = Pattern.compile("^(\\d+)x(\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RANGE_PATTERN = Pattern.compile("^(\\d+)(?:@(\\d+))?$");

    private HlsDownloader() {}

    public record Variant(
            String uri,
            long bandwidth,
            int width,
            int height,
            String codecs,
            String audioGroupId
    ) {}

    public record AudioRendition(
            String groupId,
            String uri,
            String name,
            boolean isDefault,
            boolean autoselect
    ) {}

    public static final class MasterPlaylist {
        public final List<Variant> variants;
        public final List<AudioRendition> audioRenditions;

        public MasterPlaylist(List<Variant> variants, List<AudioRendition> audioRenditions) {
            this.variants = List.copyOf(variants);
            this.audioRenditions = List.copyOf(audioRenditions);
        }

        public Variant selectBest(int preferredQuality) {
            if (variants.isEmpty()) {
                return null;
            }
            int pref = preferredQuality > 0 ? preferredQuality : 1080;
            Variant best = variants.get(0);
            int bestSize = VideoQuality.shortSide(best.width(), best.height());
            long bestBw = best.bandwidth();
            for (int i = 1; i < variants.size(); i++) {
                Variant candidate = variants.get(i);
                int candSize = VideoQuality.shortSide(candidate.width(), candidate.height());
                if (bestSize <= 0 && candSize <= 0) {
                    if (candidate.bandwidth() > bestBw) {
                        best = candidate;
                        bestBw = candidate.bandwidth();
                    }
                } else if (VideoQuality.better(candSize, candidate.bandwidth(), bestSize, bestBw, pref)) {
                    best = candidate;
                    bestSize = candSize;
                    bestBw = candidate.bandwidth();
                }
            }
            return best;
        }

        public AudioRendition findAudio(String groupId) {
            if (groupId == null || groupId.isBlank()) {
                return null;
            }
            AudioRendition fallback = null;
            for (AudioRendition r : audioRenditions) {
                if (groupId.equals(r.groupId()) && r.uri() != null && !r.uri().isBlank()) {
                    if (r.isDefault()) {
                        return r;
                    }
                    if (fallback == null) {
                        fallback = r;
                    }
                }
            }
            return fallback;
        }
    }

    public record KeyInfo(String method, String uri, byte[] explicitIv) {}

    public record MapInfo(String uri, long byteRangeLength, long byteRangeOffset) {}

    public record Segment(
            long sequence,
            double durationSec,
            String uri,
            KeyInfo key,
            MapInfo map,
            long byteRangeLength,
            long byteRangeOffset
    ) {}

    public static final class MediaPlaylist {
        public final double targetDurationSec;
        public final long mediaSequence;
        public final boolean isLive;
        public final List<Segment> segments;
        public final MapInfo initMap;

        public MediaPlaylist(
                double targetDurationSec,
                long mediaSequence,
                boolean isLive,
                List<Segment> segments,
                MapInfo initMap
        ) {
            this.targetDurationSec = targetDurationSec;
            this.mediaSequence = mediaSequence;
            this.isLive = isLive;
            this.segments = List.copyOf(segments);
            this.initMap = initMap;
        }
    }

    public static boolean isMasterPlaylist(String text) {
        return text != null && text.contains("#EXT-X-STREAM-INF");
    }

    public static MasterPlaylist parseMasterPlaylist(String text, String baseUrl) {
        List<Variant> variants = new ArrayList<>();
        List<AudioRendition> audios = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");
        Variant pending = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#EXT-X-MEDIA:")) {
                Map<String, String> attrs = parseAttributes(line.substring("#EXT-X-MEDIA:".length()));
                if ("AUDIO".equalsIgnoreCase(attrs.get("TYPE"))) {
                    String uri = attrs.get("URI");
                    audios.add(new AudioRendition(
                            attrs.getOrDefault("GROUP-ID", ""),
                            uri == null || uri.isBlank() ? "" : resolveUri(baseUrl, uri),
                            attrs.getOrDefault("NAME", ""),
                            "YES".equalsIgnoreCase(attrs.get("DEFAULT")),
                            "YES".equalsIgnoreCase(attrs.get("AUTOSELECT"))
                    ));
                }
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                Map<String, String> attrs = parseAttributes(line.substring("#EXT-X-STREAM-INF:".length()));
                long bw = parseLong(attrs.get("BANDWIDTH"), parseLong(attrs.get("AVERAGE-BANDWIDTH"), 0L));
                int[] res = parseResolution(attrs.get("RESOLUTION"));
                pending = new Variant(
                        "",
                        bw,
                        res[0],
                        res[1],
                        attrs.getOrDefault("CODECS", ""),
                        attrs.getOrDefault("AUDIO", "")
                );
            } else if (line.startsWith("#")) {
                continue;
            } else if (pending != null) {
                variants.add(new Variant(
                        resolveUri(baseUrl, line),
                        pending.bandwidth(),
                        pending.width(),
                        pending.height(),
                        pending.codecs(),
                        pending.audioGroupId()
                ));
                pending = null;
            }
        }
        return new MasterPlaylist(variants, audios);
    }

    public static MediaPlaylist parseMediaPlaylist(String text, String baseUrl) throws IOException {
        String[] lines = text.split("\\r?\\n");
        double targetDuration = 5.0;
        long mediaSequence = 0L;
        boolean hasEndList = false;

        KeyInfo currentKey = null;
        MapInfo currentMap = null;
        MapInfo firstMap = null;
        double pendingDuration = -1.0;
        long pendingRangeLen = -1L;
        long pendingRangeOff = 0L;
        long lastSubRangeEnd = 0L;
        long index = 0L;

        List<Segment> segments = new ArrayList<>();

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                targetDuration = parseDouble(line.substring("#EXT-X-TARGETDURATION:".length()), 5.0);
            } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                mediaSequence = parseLong(line.substring("#EXT-X-MEDIA-SEQUENCE:".length()), 0L);
            } else if (line.startsWith("#EXT-X-ENDLIST")) {
                hasEndList = true;
            } else if (line.startsWith("#EXT-X-KEY:")) {
                currentKey = parseKey(line.substring("#EXT-X-KEY:".length()), baseUrl);
            } else if (line.startsWith("#EXT-X-MAP:")) {
                currentMap = parseMap(line.substring("#EXT-X-MAP:".length()), baseUrl);
                if (firstMap == null) {
                    firstMap = currentMap;
                }
            } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                long[] r = parseByteRange(line.substring("#EXT-X-BYTERANGE:".length()), lastSubRangeEnd);
                pendingRangeLen = r[0];
                pendingRangeOff = r[1];
                if (pendingRangeLen > 0) {
                    lastSubRangeEnd = pendingRangeOff + pendingRangeLen;
                }
            } else if (line.startsWith("#EXTINF:")) {
                String body = line.substring("#EXTINF:".length());
                int comma = body.indexOf(',');
                String durText = comma >= 0 ? body.substring(0, comma) : body;
                pendingDuration = parseDouble(durText, 0.0);
            } else if (line.startsWith("#")) {
                continue;
            } else {
                long seq = mediaSequence + index;
                segments.add(new Segment(
                        seq,
                        pendingDuration >= 0 ? pendingDuration : targetDuration,
                        resolveUri(baseUrl, line),
                        currentKey,
                        currentMap,
                        pendingRangeLen,
                        pendingRangeOff
                ));
                index++;
                pendingDuration = -1.0;
                pendingRangeLen = -1L;
                pendingRangeOff = 0L;
            }
        }
        return new MediaPlaylist(targetDuration, mediaSequence, !hasEndList, segments, firstMap);
    }

    private static KeyInfo parseKey(String attrText, String baseUrl) throws IOException {
        Map<String, String> attrs = parseAttributes(attrText);
        String method = attrs.getOrDefault("METHOD", "NONE").trim().toUpperCase(Locale.ROOT);
        String keyFormat = attrs.getOrDefault("KEYFORMAT", "").toLowerCase(Locale.ROOT);
        if (keyFormat.contains("widevine") || keyFormat.contains("edef8ba9")
                || keyFormat.contains("playready") || keyFormat.contains("fairplay")) {
            throw new IOException("不支持 DRM 加密视频（" + method + "）");
        }
        if ("NONE".equals(method)) {
            return null;
        }
        if (!"AES-128".equals(method)) {
            throw new IOException("不支持 DRM 加密视频（" + method + "）");
        }
        String uri = attrs.get("URI");
        if (uri == null || uri.isBlank()) {
            throw new IOException("HLS AES-128 缺少密钥地址");
        }
        byte[] iv = null;
        String ivStr = attrs.get("IV");
        if (ivStr != null && !ivStr.isBlank()) {
            iv = parseHexIv(ivStr.trim());
        }
        return new KeyInfo(method, resolveUri(baseUrl, uri), iv);
    }

    private static MapInfo parseMap(String attrText, String baseUrl) throws IOException {
        Map<String, String> attrs = parseAttributes(attrText);
        String uri = attrs.get("URI");
        if (uri == null || uri.isBlank()) {
            throw new IOException("HLS #EXT-X-MAP 缺少 URI");
        }
        long len = -1L;
        long off = 0L;
        if (attrs.containsKey("BYTERANGE")) {
            long[] r = parseByteRange(attrs.get("BYTERANGE"), 0L);
            len = r[0];
            off = r[1];
        }
        return new MapInfo(resolveUri(baseUrl, uri), len, off);
    }

    public static byte[] deriveIv(KeyInfo key, long sequence) {
        if (key != null && key.explicitIv() != null) {
            return Arrays.copyOf(key.explicitIv(), 16);
        }
        byte[] iv = new byte[16];
        long seq = sequence;
        for (int i = 15; i >= 8; i--) {
            iv[i] = (byte) (seq & 0xFF);
            seq >>>= 8;
        }
        return iv;
    }

    public static byte[] decryptAes128(byte[] cipherBytes, byte[] keyBytes, byte[] iv) throws IOException {
        if (keyBytes == null || keyBytes.length != 16) {
            throw new IOException("AES-128 密钥长度无效");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(cipherBytes);
        } catch (Exception e) {
            try {
                Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new IvParameterSpec(iv));
                return cipher.doFinal(cipherBytes);
            } catch (Exception fallbackEx) {
                throw new IOException("AES-128 分片解密失败", e);
            }
        }
    }

    public static void download(
            String m3u8Url,
            Map<String, String> headers,
            File outputMp4,
            File workDir,
            int preferredQuality,
            Net.Cancellation cancel,
            SegmentedDownloader.ProgressListener listener
    ) throws IOException {
        Net.check(cancel);
        if (workDir != null && !workDir.exists()) {
            workDir.mkdirs();
        }
        File effectiveWorkDir = workDir != null ? workDir : new File(outputMp4.getParentFile(), ".hls_work");
        if (!effectiveWorkDir.exists()) {
            effectiveWorkDir.mkdirs();
        }

        HttpClient.HttpResult rootResult = HttpClient.get(m3u8Url, headers, cancel);
        String playlistUrl = rootResult.finalUrl;
        String playlistText = rootResult.body;
        String separateAudioUrl = null;

        if (isMasterPlaylist(playlistText)) {
            MasterPlaylist master = parseMasterPlaylist(playlistText, playlistUrl);
            Variant selected = master.selectBest(preferredQuality);
            if (selected == null) {
                throw new IOException("M3U8 主列表未包含可下载的视频流");
            }
            AudioRendition audio = master.findAudio(selected.audioGroupId());
            if (audio != null && !audio.uri().isBlank()) {
                separateAudioUrl = audio.uri();
            }
            HttpClient.HttpResult variantResult = HttpClient.get(selected.uri(), headers, cancel);
            playlistUrl = variantResult.finalUrl;
            playlistText = variantResult.body;
        }

        MediaPlaylist media = parseMediaPlaylist(playlistText, playlistUrl);
        if (media.segments.isEmpty()) {
            throw new IOException("M3U8 列表没有可下载的分片");
        }

        if (separateAudioUrl == null) {
            downloadMediaTrack(media, headers, outputMp4, effectiveWorkDir, cancel, listener);
            return;
        }

        File videoOnly = new File(effectiveWorkDir, "video_track.mp4");
        File audioOnly = new File(effectiveWorkDir, "audio_track.mp4");
        File audioWork = new File(effectiveWorkDir, "audio");
        audioWork.mkdirs();

        downloadMediaTrack(media, headers, videoOnly, effectiveWorkDir, cancel, listener);

        HttpClient.HttpResult audioRes = HttpClient.get(separateAudioUrl, headers, cancel);
        MediaPlaylist audioPlaylist = parseMediaPlaylist(audioRes.body, audioRes.finalUrl);
        if (audioPlaylist.segments.isEmpty()) {
            Files.move(videoOnly.toPath(), outputMp4.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        downloadMediaTrack(audioPlaylist, headers, audioOnly, audioWork, cancel, null);
        MediaFiles.mux(videoOnly, audioOnly, outputMp4, cancel);
    }

    private static void downloadMediaTrack(
            MediaPlaylist playlist,
            Map<String, String> headers,
            File outputMp4,
            File workDir,
            Net.Cancellation cancel,
            SegmentedDownloader.ProgressListener listener
    ) throws IOException {
        boolean isFmp4 = playlist.initMap != null;
        Map<String, byte[]> keyCache = new ConcurrentHashMap<>();
        List<File> segmentFiles = new ArrayList<>(playlist.segments.size());
        String ext = isFmp4 ? ".m4s" : ".ts";
        for (Segment s : playlist.segments) {
            segmentFiles.add(new File(workDir, String.format(Locale.ROOT, "seg_%06d%s", s.sequence(), ext)));
        }

        File initFile = null;
        if (isFmp4) {
            initFile = new File(workDir, "init.mp4");
            if (!initFile.exists() || initFile.length() == 0) {
                byte[] initBytes = fetchBytes(
                        playlist.initMap.uri(),
                        headers,
                        playlist.initMap.byteRangeLength() > 0 ? playlist.initMap.byteRangeOffset() : null,
                        playlist.initMap.byteRangeLength() > 0 ? playlist.initMap.byteRangeLength() : null,
                        cancel
                );
                writeAtomic(initFile, initBytes);
            }
        }

        int totalSegs = playlist.segments.size();
        int threadCount = Math.max(1, Math.min(MAX_SEGMENT_THREADS, totalSegs));
        AtomicLong doneBytes = new AtomicLong(0L);
        AtomicInteger completedSegs = new AtomicInteger(0);
        AtomicInteger activeWorkers = new AtomicInteger(0);
        long startMs = System.currentTimeMillis();

        for (File f : segmentFiles) {
            if (f.exists() && f.length() > 0) {
                doneBytes.addAndGet(f.length());
                completedSegs.incrementAndGet();
            }
        }

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (int i = 0; i < totalSegs; i++) {
                final Segment seg = playlist.segments.get(i);
                final File target = segmentFiles.get(i);
                if (target.exists() && target.length() > 0) {
                    continue;
                }
                futures.add(pool.submit((Callable<Void>) () -> {
                    activeWorkers.incrementAndGet();
                    try {
                        Net.check(cancel);
                        byte[] raw = fetchSegmentWithRetry(seg, headers, cancel);
                        if (seg.key() != null) {
                            byte[] keyBytes = getOrFetchKey(seg.key().uri(), headers, keyCache, cancel);
                            raw = decryptAes128(raw, keyBytes, deriveIv(seg.key(), seg.sequence()));
                        }
                        writeAtomic(target, raw);
                        long totalDoneNow = doneBytes.addAndGet(raw.length);
                        int doneCount = completedSegs.incrementAndGet();
                        if (listener != null) {
                            long estTotal = doneCount > 0 ? (totalDoneNow * totalSegs) / doneCount : -1L;
                            long elapsed = Math.max(1L, System.currentTimeMillis() - startMs);
                            long speed = totalDoneNow * 1000L / elapsed;
                            listener.onProgress(totalDoneNow, estTotal, speed, Math.max(1, activeWorkers.get()));
                        }
                        return null;
                    } finally {
                        activeWorkers.decrementAndGet();
                    }
                }));
            }
            for (Future<Void> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof CancellationException ce) {
                        throw ce;
                    }
                    if (cause instanceof IOException ioe) {
                        throw ioe;
                    }
                    throw new IOException("HLS 分片下载失败", cause);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        if (outputMp4.getParentFile() != null && !outputMp4.getParentFile().exists()) {
            outputMp4.getParentFile().mkdirs();
        }
        if (isFmp4) {
            concatFiles(initFile, segmentFiles, outputMp4, cancel);
        } else {
            TsToMp4Muxer.muxTsFiles(segmentFiles, outputMp4, cancel);
        }
        if (listener != null) {
            long finalSize = outputMp4.length();
            listener.onProgress(finalSize, finalSize, 0L, 0);
        }
    }

    static byte[] getOrFetchKey(
            String keyUri,
            Map<String, String> headers,
            Map<String, byte[]> cache,
            Net.Cancellation cancel
    ) throws IOException {
        byte[] existing = cache.get(keyUri);
        if (existing != null) {
            return existing;
        }
        byte[] fetched = fetchBytes(keyUri, headers, null, null, cancel);
        if (fetched.length != 16) {
            throw new IOException("AES-128 密钥大小异常: " + fetched.length);
        }
        cache.put(keyUri, fetched);
        return fetched;
    }

    private static byte[] fetchSegmentWithRetry(Segment seg, Map<String, String> headers, Net.Cancellation cancel)
            throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            Net.check(cancel);
            try {
                Long off = seg.byteRangeLength() > 0 ? seg.byteRangeOffset() : null;
                Long len = seg.byteRangeLength() > 0 ? seg.byteRangeLength() : null;
                return fetchBytes(seg.uri(), headers, off, len, cancel);
            } catch (CancellationException ce) {
                throw ce;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("HLS 分片下载失败");
    }

    static byte[] fetchBytes(
            String url,
            Map<String, String> headers,
            Long rangeOffset,
            Long rangeLength,
            Net.Cancellation cancel
    ) throws IOException {
        String current = HttpClient.normalizeUrl(url);
        for (int hop = 0; hop < 9; hop++) {
            Net.check(cancel);
            if (!LinkInput.isWebUrl(current)) {
                throw new IOException("不支持的分片地址: " + current);
            }
            Request.Builder rb = HttpClient.buildRequest("GET", current, null, headers);
            if (rangeOffset != null && rangeLength != null && rangeLength > 0) {
                long end = rangeOffset + rangeLength - 1L;
                rb.header("Range", "bytes=" + rangeOffset + "-" + end);
            }
            try (Response resp = HttpClient.client().newCall(rb.build()).execute()) {
                HttpClient.saveResponseCookies(current, resp.headers());
                int code = resp.code();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = resp.header("Location");
                    if (loc == null || loc.isBlank()) {
                        throw new IOException("重定向缺少目标地址");
                    }
                    current = URI.create(current).resolve(loc).toString();
                    continue;
                }
                if (code < 200 || code >= 300) {
                    throw new IOException("分片请求失败（" + code + "）");
                }
                ResponseBody body = resp.body();
                if (body == null) {
                    return new byte[0];
                }
                try (InputStream in = body.byteStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[32 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        Net.check(cancel);
                        out.write(buf, 0, n);
                    }
                    return out.toByteArray();
                }
            }
        }
        throw new IOException("分片跳转次数过多");
    }

    private static void concatFiles(File initFile, List<File> segments, File output, Net.Cancellation cancel)
            throws IOException {
        File tmp = new File(output.getParentFile(), output.getName() + ".fmp4.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            if (initFile != null && initFile.exists()) {
                copyFileInto(initFile, out, buf, cancel);
            }
            for (File seg : segments) {
                Net.check(cancel);
                if (seg.exists() && seg.length() > 0) {
                    copyFileInto(seg, out, buf, cancel);
                }
            }
            out.getFD().sync();
        } catch (IOException | RuntimeException e) {
            tmp.delete();
            throw e;
        }
        if (output.exists()) {
            output.delete();
        }
        if (!tmp.renameTo(output)) {
            throw new IOException("无法写入 fMP4 输出文件");
        }
    }

    private static void copyFileInto(File src, FileOutputStream out, byte[] buf, Net.Cancellation cancel)
            throws IOException {
        try (FileInputStream in = new FileInputStream(src)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                Net.check(cancel);
                out.write(buf, 0, n);
            }
        }
    }

    private static void writeAtomic(File target, byte[] bytes) throws IOException {
        File part = new File(target.getParentFile(), target.getName() + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(bytes);
            out.getFD().sync();
        }
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    static Map<String, String> parseAttributes(String input) {
        Map<String, String> map = new HashMap<>();
        if (input == null) {
            return map;
        }
        Matcher m = ATTR_PATTERN.matcher(input);
        while (m.find()) {
            String key = m.group(1).toUpperCase(Locale.ROOT);
            String val = m.group(3) != null ? m.group(3) : m.group(2);
            map.put(key, val != null ? val.trim() : "");
        }
        return map;
    }

    private static String resolveUri(String baseUrl, String relative) {
        if (relative == null || relative.isBlank()) {
            return baseUrl;
        }
        try {
            return URI.create(baseUrl).resolve(relative.trim()).toString();
        } catch (Exception e) {
            return relative.trim();
        }
    }

    private static int[] parseResolution(String res) {
        if (res == null) {
            return new int[]{0, 0};
        }
        Matcher m = RES_PATTERN.matcher(res.trim());
        if (!m.matches()) {
            return new int[]{0, 0};
        }
        return new int[]{
                (int) parseLong(m.group(1), 0L),
                (int) parseLong(m.group(2), 0L)
        };
    }

    private static long[] parseByteRange(String value, long defaultOffset) {
        if (value == null) {
            return new long[]{-1L, 0L};
        }
        Matcher m = RANGE_PATTERN.matcher(value.trim());
        if (!m.matches()) {
            return new long[]{-1L, 0L};
        }
        long len = parseLong(m.group(1), -1L);
        long off = m.group(2) != null ? parseLong(m.group(2), defaultOffset) : defaultOffset;
        return new long[]{len, off};
    }

    private static byte[] parseHexIv(String hexRaw) throws IOException {
        String hex = hexRaw.startsWith("0x") || hexRaw.startsWith("0X") ? hexRaw.substring(2) : hexRaw;
        if (hex.length() > 32 || hex.isEmpty()) {
            throw new IOException("AES-128 IV 格式无效");
        }
        String padded = "0".repeat(32 - hex.length()) + hex;
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            int hi = Character.digit(padded.charAt(i * 2), 16);
            int lo = Character.digit(padded.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IOException("AES-128 IV 包含非法十六进制字符");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static long parseLong(String s, long fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String s, double fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
