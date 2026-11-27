package app.xingluo.video.engine;

import app.xingluo.video.LinkInput;
import app.xingluo.video.Net;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live stream recorder for rolling Live HLS playlists and HTTP-FLV streams.
 * Supports graceful "stop and save" ({@link AtomicBoolean}) as well as saving valid partial MP4
 * output on network drops after media frames have been captured.
 */
public final class LiveRecorder {
    private static final long PROGRESS_INTERVAL_MS = 350L;

    private LiveRecorder() {}

    public static void record(
            String streamUrl,
            Map<String, String> headers,
            File outputMp4,
            AtomicBoolean stopAndSave,
            Net.Cancellation cancel,
            SegmentedDownloader.ProgressListener progress
    ) throws IOException {
        record(streamUrl, headers, outputMp4, 1080, stopAndSave, cancel, progress);
    }

    public static void record(
            String streamUrl,
            Map<String, String> headers,
            File outputMp4,
            int preferredQuality,
            AtomicBoolean stopAndSave,
            Net.Cancellation cancel,
            SegmentedDownloader.ProgressListener progress
    ) throws IOException {
        Net.check(cancel);
        AtomicBoolean stop = stopAndSave != null ? stopAndSave : new AtomicBoolean(false);
        if (outputMp4.getParentFile() != null && !outputMp4.getParentFile().exists()) {
            outputMp4.getParentFile().mkdirs();
        }

        String lowerUrl = streamUrl.toLowerCase(Locale.ROOT);
        if (lowerUrl.contains(".flv")) {
            recordFlv(streamUrl, headers, outputMp4, stop, cancel, progress);
            return;
        }
        if (lowerUrl.contains(".m3u8")) {
            recordLiveHls(streamUrl, headers, outputMp4, preferredQuality, stop, cancel, progress);
            return;
        }

        // Probe Content-Type or first bytes when extension is ambiguous.
        String current = HttpClient.normalizeUrl(streamUrl);
        for (int hop = 0; hop < 9; hop++) {
            Net.check(cancel);
            if (!LinkInput.isWebUrl(current)) {
                throw new IOException("不支持的直播流地址");
            }
            Request.Builder rb = HttpClient.buildRequest("GET", current, null, headers);
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
                    throw new IOException("直播流连接失败（" + code + "）");
                }
                String ct = resp.header("Content-Type", "").toLowerCase(Locale.ROOT);
                if (ct.contains("mpegurl")) {
                    resp.close();
                    recordLiveHls(current, headers, outputMp4, preferredQuality, stop, cancel, progress);
                    return;
                }
                ResponseBody body = resp.body();
                if (body == null) {
                    throw new IOException("直播流响应体为空");
                }
                BufferedInputStream bin = new BufferedInputStream(body.byteStream(), 64 * 1024);
                bin.mark(16);
                byte[] magic = new byte[7];
                int read = bin.read(magic);
                bin.reset();
                if (read >= 3 && magic[0] == 'F' && magic[1] == 'L' && magic[2] == 'V') {
                    consumeFlvStream(bin, outputMp4, stop, cancel, progress);
                    return;
                }
                String headStr = read > 0 ? new String(magic, 0, read, StandardCharsets.UTF_8) : "";
                if (headStr.startsWith("#EXTM3U")) {
                    resp.close();
                    recordLiveHls(current, headers, outputMp4, preferredQuality, stop, cancel, progress);
                    return;
                }
                throw new IOException("无法识别的直播流格式（" + ct + "）");
            }
        }
        throw new IOException("直播流跳转次数过多");
    }

    public static void recordFlv(
            String flvUrl,
            Map<String, String> headers,
            File outputMp4,
            AtomicBoolean stopAndSave,
            Net.Cancellation cancel,
            SegmentedDownloader.ProgressListener progress
    ) throws IOException {
        String current = HttpClient.normalizeUrl(flvUrl);
        for (int hop = 0; hop < 9; hop++) {
            Net.check(cancel);
            if (!LinkInput.isWebUrl(current)) {
                throw new IOException("不支持的 FLV 流地址");
            }
            Request.Builder rb = HttpClient.buildRequest("GET", current, null, headers);
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
                    throw new IOException("FLV 直播流请求失败（" + code + "）");
                }
                ResponseBody body = resp.body();
                if (body == null) {
                    throw new IOException("FLV 直播流响应体为空");
                }
                consumeFlvStream(body.byteStream(), outputMp4, stopAndSave, cancel, progress);
                return;
            }
        }
        throw new IOException("FLV 流跳转次数过多");
    }

    private static void consumeFlvStream(
            InputStream stream,
            File outputMp4,
            AtomicBoolean stopAndSave,
            Net.Cancellation cancel,
            SegmentedDownloader.ProgressListener progress
    ) throws IOException {
        File tmp = new File(outputMp4.getParentFile(), outputMp4.getName() + ".live.tmp");
        long startMs = System.currentTimeMillis();
        AtomicLong lastEmit = new AtomicLong(0L);
        AtomicLong recordedBytes = new AtomicLong(0L);
        boolean savedPartial = false;

        try (TsToMp4Muxer.MediaMuxerSink sink = new TsToMp4Muxer.MediaMuxerSink(tmp)) {
            try {
                TsToMp4Muxer.FlvReader.demux(
                        stream,
                        sink,
                        stopAndSave::get,
                        cancel,
                        bytes -> {
                            recordedBytes.set(bytes);
                            emitLiveProgress(progress, bytes, startMs, lastEmit);
                        }
                );
            } catch (IOException streamDrop) {
                // Network drop after capturing samples: cleanly finalize and preserve recorded video.
                if (sink.sampleCount() == 0) {
                    throw streamDrop;
                }
            }
            if (sink.sampleCount() == 0) {
                throw new IOException("未录制到任何有效音视频数据");
            }
            savedPartial = true;
        } catch (CancellationException ce) {
            tmp.delete();
            throw ce;
        } catch (IOException | RuntimeException e) {
            if (!savedPartial) {
                tmp.delete();
            }
            if (e instanceof IOException ioe) {
                throw ioe;
            }
            throw new IOException(e.getMessage(), e);
        }

        finalizeRecordedFile(tmp, outputMp4);
        if (progress != null) {
            progress.onProgress(outputMp4.length(), -1L, 0L, 0);
        }
    }

    public static void recordLiveHls(
            String m3u8Url,
            Map<String, String> headers,
            File outputMp4,
            int preferredQuality,
            AtomicBoolean stopAndSave,
            Net.Cancellation cancel,
            SegmentedDownloader.ProgressListener progress
    ) throws IOException {
        Net.check(cancel);
        String activePlaylistUrl = resolveVariantUrl(m3u8Url, headers, preferredQuality, cancel);

        File tmp = new File(outputMp4.getParentFile(), outputMp4.getName() + ".live.tmp");
        Set<String> seenSegments = new HashSet<>();
        Map<String, byte[]> keyCache = new ConcurrentHashMap<>();
        long highestSeq = -1L;
        long startMs = System.currentTimeMillis();
        AtomicLong lastEmit = new AtomicLong(0L);
        AtomicLong totalBytes = new AtomicLong(0L);

        boolean fmp4Mode = false;
        boolean fmp4InitWritten = false;
        FileOutputStream fmp4Out = null;
        TsToMp4Muxer.MediaMuxerSink tsSink = new TsToMp4Muxer.MediaMuxerSink(tmp);
        TsToMp4Muxer.TsDemuxer tsDemuxer = new TsToMp4Muxer.TsDemuxer(tsSink);
        int fmp4SegmentsWritten = 0;

        try {
            int consecutiveErrors = 0;
            while (!stopAndSave.get()) {
                Net.check(cancel);
                HlsDownloader.MediaPlaylist playlist;
                try {
                    HttpClient.HttpResult res = HttpClient.get(activePlaylistUrl, headers, cancel);
                    activePlaylistUrl = res.finalUrl;
                    playlist = HlsDownloader.parseMediaPlaylist(res.body, activePlaylistUrl);
                    consecutiveErrors = 0;
                } catch (CancellationException ce) {
                    throw ce;
                } catch (IOException pollErr) {
                    consecutiveErrors++;
                    if (consecutiveErrors >= 3 || stopAndSave.get()) {
                        if (tsSink.sampleCount() > 0 || fmp4SegmentsWritten > 0) {
                            break; // Save already-recorded portion on network drop.
                        }
                        throw pollErr;
                    }
                    sleepInterruptibly(1000L, stopAndSave, cancel);
                    continue;
                }

                if (playlist.initMap != null && tsSink.sampleCount() == 0) {
                    fmp4Mode = true;
                    if (!fmp4InitWritten) {
                        byte[] initBytes = HlsDownloader.fetchBytes(
                                playlist.initMap.uri(),
                                headers,
                                playlist.initMap.byteRangeLength() > 0 ? playlist.initMap.byteRangeOffset() : null,
                                playlist.initMap.byteRangeLength() > 0 ? playlist.initMap.byteRangeLength() : null,
                                cancel
                        );
                        fmp4Out = new FileOutputStream(tmp);
                        fmp4Out.write(initBytes);
                        totalBytes.addAndGet(initBytes.length);
                        fmp4InitWritten = true;
                    }
                }

                boolean downloadedAny = false;
                for (HlsDownloader.Segment seg : playlist.segments) {
                    if (stopAndSave.get()) {
                        break;
                    }
                    Net.check(cancel);
                    String segKey = seg.sequence() + "|" + seg.uri();
                    if (seg.sequence() <= highestSeq && seenSegments.contains(segKey)) {
                        continue;
                    }
                    if (seenSegments.contains(segKey)) {
                        continue;
                    }
                    try {
                        Long off = seg.byteRangeLength() > 0 ? seg.byteRangeOffset() : null;
                        Long len = seg.byteRangeLength() > 0 ? seg.byteRangeLength() : null;
                        byte[] segBytes = HlsDownloader.fetchBytes(seg.uri(), headers, off, len, cancel);
                        if (seg.key() != null) {
                            byte[] keyBytes = HlsDownloader.getOrFetchKey(seg.key().uri(), headers, keyCache, cancel);
                            segBytes = HlsDownloader.decryptAes128(
                                    segBytes,
                                    keyBytes,
                                    HlsDownloader.deriveIv(seg.key(), seg.sequence())
                            );
                        }
                        seenSegments.add(segKey);
                        highestSeq = Math.max(highestSeq, seg.sequence());
                        downloadedAny = true;

                        if (fmp4Mode && fmp4Out != null) {
                            fmp4Out.write(segBytes);
                            fmp4SegmentsWritten++;
                        } else {
                            tsDemuxer.feed(segBytes, 0, segBytes.length);
                        }
                        long bytesNow = totalBytes.addAndGet(segBytes.length);
                        emitLiveProgress(progress, bytesNow, startMs, lastEmit);
                    } catch (CancellationException ce) {
                        throw ce;
                    } catch (IOException segErr) {
                        if (tsSink.sampleCount() > 0 || fmp4SegmentsWritten > 0) {
                            stopAndSave.set(true);
                            break;
                        }
                        throw segErr;
                    }
                }

                if (!playlist.isLive || stopAndSave.get()) {
                    break;
                }
                long waitMs = downloadedAny
                        ? Math.max(1000L, Math.min(5000L, (long) (playlist.targetDurationSec * 500.0)))
                        : Math.max(800L, Math.min(3000L, (long) (playlist.targetDurationSec * 400.0)));
                sleepInterruptibly(waitMs, stopAndSave, cancel);
            }

            if (fmp4Mode) {
                if (fmp4Out != null) {
                    fmp4Out.getFD().sync();
                    fmp4Out.close();
                    fmp4Out = null;
                }
                if (fmp4SegmentsWritten == 0) {
                    throw new IOException("未录制到任何 fMP4 直播分片");
                }
            } else {
                tsDemuxer.flush();
                if (tsSink.sampleCount() == 0) {
                    throw new IOException("未录制到任何有效 HLS 直播画面或声音");
                }
                tsSink.close();
            }
        } catch (CancellationException ce) {
            closeQuietly(fmp4Out);
            closeQuietly(tsSink);
            tmp.delete();
            throw ce;
        } catch (IOException | RuntimeException e) {
            closeQuietly(fmp4Out);
            closeQuietly(tsSink);
            tmp.delete();
            if (e instanceof IOException ioe) {
                throw ioe;
            }
            throw new IOException(e.getMessage(), e);
        } finally {
            closeQuietly(fmp4Out);
            closeQuietly(tsSink);
        }

        finalizeRecordedFile(tmp, outputMp4);
        if (progress != null) {
            progress.onProgress(outputMp4.length(), -1L, 0L, 0);
        }
    }

    private static String resolveVariantUrl(
            String m3u8Url,
            Map<String, String> headers,
            int preferredQuality,
            Net.Cancellation cancel
    ) throws IOException {
        HttpClient.HttpResult res = HttpClient.get(m3u8Url, headers, cancel);
        if (!HlsDownloader.isMasterPlaylist(res.body)) {
            return res.finalUrl;
        }
        HlsDownloader.MasterPlaylist master = HlsDownloader.parseMasterPlaylist(res.body, res.finalUrl);
        HlsDownloader.Variant best = master.selectBest(preferredQuality);
        if (best == null) {
            throw new IOException("直播 M3U8 未包含可用清晰度");
        }
        return best.uri();
    }

    private static void finalizeRecordedFile(File tmp, File outputMp4) throws IOException {
        if (!tmp.exists() || tmp.length() == 0) {
            throw new IOException("录制文件为空");
        }
        if (outputMp4.exists()) {
            outputMp4.delete();
        }
        if (!tmp.renameTo(outputMp4)) {
            throw new IOException("无法保存录制完成的 MP4 文件");
        }
    }

    private static void emitLiveProgress(
            SegmentedDownloader.ProgressListener progress,
            long recordedBytes,
            long startMs,
            AtomicLong lastEmitMs
    ) {
        if (progress == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long prev = lastEmitMs.get();
        if (now - prev >= PROGRESS_INTERVAL_MS && lastEmitMs.compareAndSet(prev, now)) {
            long elapsed = Math.max(1L, now - startMs);
            long speed = recordedBytes * 1000L / elapsed;
            progress.onProgress(recordedBytes, -1L, speed, 1);
        }
    }

    private static void sleepInterruptibly(long millis, AtomicBoolean stopAndSave, Net.Cancellation cancel) {
        long end = System.currentTimeMillis() + millis;
        while (!stopAndSave.get() && System.currentTimeMillis() < end) {
            Net.check(cancel);
            try {
                Thread.sleep(Math.min(100L, Math.max(10L, end - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException();
            }
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {}
        }
    }
}
