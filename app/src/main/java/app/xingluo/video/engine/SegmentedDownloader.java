package app.xingluo.video.engine;

import app.xingluo.video.LinkInput;
import app.xingluo.video.Net;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-speed multi-threaded HTTP downloader with Range probing, resumable `.part` + `.part.meta`
 * chunk state persistence, backup URL failover, and graceful single-stream fallback.
 */
public final class SegmentedDownloader {
    public static final long MIN_MULTITHREAD_BYTES = 256L * 1024L;
    public static final long MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024L;
    public static final int MAX_THREADS = 8;

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long PROGRESS_INTERVAL_MS = 250L;
    private static final long META_FLUSH_BYTES = 256L * 1024L;
    private static final Pattern CONTENT_RANGE_PATTERN =
            Pattern.compile("^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$", Pattern.CASE_INSENSITIVE);

    private SegmentedDownloader() {}

    public interface ProgressListener {
        void onProgress(long doneBytes, long totalBytes, long speedBps, int activeThreads);
    }

    public record ProbeResult(
            String finalUrl,
            long totalBytes,
            boolean acceptRanges,
            String etag,
            String contentType
    ) {}

    public static final class ChunkState {
        public final int index;
        public final long start;
        public final long end;
        public final AtomicLong done;

        public ChunkState(int index, long start, long end, long done) {
            this.index = index;
            this.start = start;
            this.end = end;
            this.done = new AtomicLong(Math.max(0L, Math.min(done, end - start + 1L)));
        }

        public long length() {
            return end - start + 1L;
        }

        public boolean isComplete() {
            return done.get() >= length();
        }
    }

    public static final class ResumeMeta {
        public final String url;
        public final String etag;
        public final long totalBytes;
        public final int threads;
        public final List<ChunkState> chunks;

        public ResumeMeta(String url, String etag, long totalBytes, int threads, List<ChunkState> chunks) {
            this.url = url == null ? "" : url;
            this.etag = etag == null ? "" : etag;
            this.totalBytes = totalBytes;
            this.threads = threads;
            this.chunks = List.copyOf(chunks);
        }

        public long totalDone() {
            long sum = 0L;
            for (ChunkState c : chunks) {
                sum += c.done.get();
            }
            return sum;
        }

        public String toJson() {
            try {
                JSONObject root = new JSONObject();
                root.put("url", url);
                root.put("etag", etag);
                root.put("totalBytes", totalBytes);
                root.put("threads", threads);
                JSONArray arr = new JSONArray();
                for (ChunkState c : chunks) {
                    JSONObject item = new JSONObject();
                    item.put("index", c.index);
                    item.put("start", c.start);
                    item.put("end", c.end);
                    item.put("done", c.done.get());
                    arr.put(item);
                }
                root.put("chunks", arr);
                return root.toString();
            } catch (org.json.JSONException e) {
                throw new IllegalStateException(e);
            }
        }

        public static ResumeMeta fromJson(String json) throws IOException {
            try {
                JSONObject root = new JSONObject(json);
                String url = root.optString("url", "");
                String etag = root.optString("etag", "");
                long totalBytes = root.getLong("totalBytes");
                int threads = root.optInt("threads", 1);
                JSONArray arr = root.getJSONArray("chunks");
                List<ChunkState> chunks = new ArrayList<>(arr.length());
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.getJSONObject(i);
                    chunks.add(new ChunkState(
                            item.getInt("index"),
                            item.getLong("start"),
                            item.getLong("end"),
                            item.getLong("done")
                    ));
                }
                return new ResumeMeta(url, etag, totalBytes, threads, chunks);
            } catch (Exception e) {
                throw new IOException("断点续传元数据损坏", e);
            }
        }

        public void save(File file) throws IOException {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            File tmp = new File(parent, file.getName() + ".tmp");
            Files.write(tmp.toPath(), toJson().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        public static ResumeMeta load(File file) throws IOException {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            return fromJson(text);
        }
    }

    public static void download(
            String url,
            Map<String, String> headers,
            File destFile,
            File resumeDir,
            int threads,
            Net.Cancellation cancel,
            ProgressListener listener
    ) throws IOException {
        download(List.of(url), headers, destFile, resumeDir, threads, cancel, listener);
    }

    public static void download(
            List<String> candidateUrls,
            Map<String, String> headers,
            File destFile,
            File resumeDir,
            int threads,
            Net.Cancellation cancel,
            ProgressListener listener
    ) throws IOException {
        if (candidateUrls == null || candidateUrls.isEmpty()) {
            throw new IOException("没有可用的下载地址");
        }
        List<String> urls = new ArrayList<>();
        for (String u : candidateUrls) {
            if (u != null && !u.isBlank()) {
                urls.add(u);
            }
        }
        if (urls.isEmpty()) {
            throw new IOException("没有可用的下载地址");
        }
        Net.check(cancel);
        int threadCount = Math.max(1, Math.min(MAX_THREADS, threads));

        ProbeResult probe = null;
        IOException lastProbeError = null;
        int primaryIndex = 0;
        for (int i = 0; i < urls.size(); i++) {
            Net.check(cancel);
            try {
                probe = probe(urls.get(i), headers, cancel);
                primaryIndex = i;
                break;
            } catch (IOException e) {
                lastProbeError = e;
            }
        }
        if (probe == null) {
            throw lastProbeError != null ? lastProbeError : new IOException("探测下载资源失败");
        }
        if (probe.totalBytes() > MAX_FILE_BYTES) {
            throw new IOException("视频超过 4 GB，请尝试较短的视频");
        }

        List<String> orderedUrls = new ArrayList<>(urls.size());
        orderedUrls.add(probe.finalUrl());
        for (int i = 0; i < urls.size(); i++) {
            if (i != primaryIndex) {
                orderedUrls.add(urls.get(i));
            }
        }

        File effectiveResumeDir = resumeDir != null ? resumeDir : destFile.getParentFile();
        if (effectiveResumeDir != null && !effectiveResumeDir.exists()) {
            effectiveResumeDir.mkdirs();
        }
        File[] resumeFiles = resumeFiles(destFile, effectiveResumeDir, urls.get(0));
        File partFile = resumeFiles[0];
        File metaFile = resumeFiles[1];

        if (destFile.getParentFile() != null && !destFile.getParentFile().exists()) {
            destFile.getParentFile().mkdirs();
        }

        if (probe.acceptRanges() && probe.totalBytes() >= MIN_MULTITHREAD_BYTES && threadCount > 1) {
            downloadMultiThread(orderedUrls, probe, headers, partFile, metaFile, threadCount, cancel, listener);
        } else {
            downloadSingleStream(orderedUrls, probe, headers, partFile, metaFile, cancel, listener);
        }

        Net.check(cancel);
        moveOrCopy(partFile, destFile);
        if (metaFile.exists()) {
            metaFile.delete();
        }
    }

    public static File[] resumeFiles(File destFile, File resumeDir, String keyUrl) {
        File dir = resumeDir != null ? resumeDir : destFile.getParentFile();
        boolean sameDir = dir != null && dir.equals(destFile.getParentFile());
        String baseName = sameDir ? destFile.getName() : shortHash(keyUrl) + "_" + destFile.getName();
        return new File[]{
                new File(dir, baseName + ".part"),
                new File(dir, baseName + ".part.meta")
        };
    }

    public static ProbeResult probe(String url, Map<String, String> headers, Net.Cancellation cancel) throws IOException {
        ProbeResult headResult = tryProbeHead(url, headers, cancel);
        if (headResult != null && headResult.totalBytes() > 0 && headResult.acceptRanges()) {
            return headResult;
        }
        try {
            return probeRangeZero(url, headers, cancel);
        } catch (IOException rangeErr) {
            if (headResult != null && headResult.totalBytes() > 0) {
                return headResult;
            }
            throw rangeErr;
        }
    }

    private static ProbeResult tryProbeHead(String url, Map<String, String> headers, Net.Cancellation cancel) throws IOException {
        try (ResponseHolder resp = openFollowRedirects("HEAD", url, headers, null, cancel)) {
            int code = resp.response.code();
            if (code == 405 || code == 501 || code == 403 || code == 400) {
                return null;
            }
            if (code < 200 || code >= 300) {
                throw httpError(code);
            }
            String ct = nonNull(resp.response.header("Content-Type"));
            rejectErrorPayloadContentType(ct);
            long contentLength = parseLong(resp.response.header("Content-Length"), -1L);
            String acceptRangesHdr = nonNull(resp.response.header("Accept-Ranges"));
            boolean acceptRanges = "bytes".equalsIgnoreCase(acceptRangesHdr.trim());
            String etag = nonNull(resp.response.header("ETag"));
            return new ProbeResult(resp.finalUrl, contentLength, acceptRanges, etag, ct);
        }
    }

    private static ProbeResult probeRangeZero(String url, Map<String, String> headers, Net.Cancellation cancel) throws IOException {
        try (ResponseHolder resp = openFollowRedirects("GET", url, headers, "bytes=0-0", cancel)) {
            int code = resp.response.code();
            String ct = nonNull(resp.response.header("Content-Type"));
            rejectErrorPayloadContentType(ct);
            if (code == 206) {
                long total = parseContentRangeTotal(resp.response.header("Content-Range"));
                if (total <= 0) {
                    total = parseLong(resp.response.header("Content-Length"), -1L);
                }
                String etag = nonNull(resp.response.header("ETag"));
                return new ProbeResult(resp.finalUrl, total, total > 0, etag, ct);
            }
            if (code >= 200 && code < 300) {
                long total = parseLong(resp.response.header("Content-Length"), -1L);
                String etag = nonNull(resp.response.header("ETag"));
                return new ProbeResult(resp.finalUrl, total, false, etag, ct);
            }
            throw httpError(code);
        }
    }

    private static void downloadMultiThread(
            List<String> urls,
            ProbeResult probe,
            Map<String, String> headers,
            File partFile,
            File metaFile,
            int threads,
            Net.Cancellation cancel,
            ProgressListener listener
    ) throws IOException {
        long totalBytes = probe.totalBytes();
        ResumeMeta meta = loadOrCreateMeta(urls.get(0), probe, partFile, metaFile, threads);

        final Object metaLock = new Object();
        final AtomicLong totalDone = new AtomicLong(meta.totalDone());
        final AtomicLong lastEmitMs = new AtomicLong(0L);
        final AtomicLong lastSpeedSampleMs = new AtomicLong(System.currentTimeMillis());
        final AtomicLong lastSpeedSampleBytes = new AtomicLong(totalDone.get());
        final AtomicLong currentSpeedBps = new AtomicLong(0L);
        final AtomicInteger activeWorkers = new AtomicInteger(0);

        emitProgress(listener, totalDone.get(), totalBytes, 0L, threads, true, lastEmitMs);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Void>> futures = new ArrayList<>(meta.chunks.size());
            for (ChunkState chunk : meta.chunks) {
                if (chunk.isComplete()) {
                    continue;
                }
                futures.add(pool.submit((Callable<Void>) () -> {
                    activeWorkers.incrementAndGet();
                    try {
                        downloadChunk(urls, chunk, headers, partFile, metaFile, meta, metaLock,
                                totalDone, totalBytes, activeWorkers, lastEmitMs,
                                lastSpeedSampleMs, lastSpeedSampleBytes, currentSpeedBps,
                                cancel, listener);
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
                    throw new IOException("多线程下载失败", cause);
                }
            }
        } finally {
            pool.shutdownNow();
            synchronized (metaLock) {
                try {
                    meta.save(metaFile);
                } catch (IOException ignored) {}
            }
        }

        long finalDone = totalDone.get();
        if (totalBytes <= 0 || finalDone != totalBytes) {
            throw new IOException("下载中断，文件不完整（" + finalDone + "/" + totalBytes + "）");
        }
        emitProgress(listener, finalDone, totalBytes, currentSpeedBps.get(), 0, true, lastEmitMs);
    }

    private static void downloadChunk(
            List<String> urls,
            ChunkState chunk,
            Map<String, String> headers,
            File partFile,
            File metaFile,
            ResumeMeta meta,
            Object metaLock,
            AtomicLong totalDone,
            long totalBytes,
            AtomicInteger activeWorkers,
            AtomicLong lastEmitMs,
            AtomicLong lastSpeedSampleMs,
            AtomicLong lastSpeedSampleBytes,
            AtomicLong currentSpeedBps,
            Net.Cancellation cancel,
            ProgressListener listener
    ) throws IOException {
        IOException lastError = null;
        for (String url : urls) {
            for (int attempt = 0; attempt < 2; attempt++) {
                Net.check(cancel);
                if (chunk.isComplete()) {
                    return;
                }
                long offset = chunk.start + chunk.done.get();
                if (offset > chunk.end) {
                    return;
                }
                String rangeHeader = "bytes=" + offset + "-" + chunk.end;
                try (ResponseHolder resp = openFollowRedirects("GET", url, headers, rangeHeader, cancel)) {
                    int code = resp.response.code();
                    if (code != 206 && !(code == 200 && offset == 0 && chunk.end == totalBytes - 1)) {
                        throw httpError(code);
                    }
                    String ct = nonNull(resp.response.header("Content-Type"));
                    rejectErrorPayloadContentType(ct);
                    ResponseBody body = resp.response.body();
                    if (body == null) {
                        throw new IOException("响应体为空");
                    }
                    try (InputStream in = body.byteStream();
                         RandomAccessFile raf = new RandomAccessFile(partFile, "rw")) {
                        raf.seek(offset);
                        byte[] buf = new byte[BUFFER_SIZE];
                        long unsavedBytes = 0L;
                        long remaining = chunk.end - offset + 1L;
                        int n;
                        while (remaining > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, remaining))) != -1) {
                            Net.check(cancel);
                            raf.write(buf, 0, n);
                            remaining -= n;
                            chunk.done.addAndGet(n);
                            long doneNow = totalDone.addAndGet(n);
                            unsavedBytes += n;

                            long now = System.currentTimeMillis();
                            long speedWindow = now - lastSpeedSampleMs.get();
                            if (speedWindow >= 500L) {
                                synchronized (metaLock) {
                                    long w = now - lastSpeedSampleMs.get();
                                    if (w >= 500L) {
                                        long delta = doneNow - lastSpeedSampleBytes.get();
                                        currentSpeedBps.set(Math.max(0L, delta * 1000L / w));
                                        lastSpeedSampleMs.set(now);
                                        lastSpeedSampleBytes.set(doneNow);
                                    }
                                }
                            }
                            if (unsavedBytes >= META_FLUSH_BYTES) {
                                synchronized (metaLock) {
                                    meta.save(metaFile);
                                }
                                unsavedBytes = 0L;
                            }
                            emitProgress(listener, doneNow, totalBytes, currentSpeedBps.get(),
                                    Math.max(1, activeWorkers.get()), false, lastEmitMs);
                        }
                        if (chunk.isComplete()) {
                            synchronized (metaLock) {
                                meta.save(metaFile);
                            }
                            return;
                        }
                        throw new IOException("分片下载未完成，服务器提前关闭连接");
                    }
                } catch (CancellationException ce) {
                    throw ce;
                } catch (IOException e) {
                    lastError = e;
                }
            }
        }
        throw lastError != null ? lastError : new IOException("分片下载失败");
    }

    private static void downloadSingleStream(
            List<String> urls,
            ProbeResult probe,
            Map<String, String> headers,
            File partFile,
            File metaFile,
            Net.Cancellation cancel,
            ProgressListener listener
    ) throws IOException {
        if (metaFile.exists()) {
            metaFile.delete();
        }
        IOException lastError = null;
        for (String url : urls) {
            Net.check(cancel);
            long existing = (probe.acceptRanges() && partFile.exists()) ? partFile.length() : 0L;
            if (probe.totalBytes() > 0 && existing > probe.totalBytes()) {
                existing = 0L;
            }
            if (probe.totalBytes() > 0 && existing == probe.totalBytes() && existing > 0) {
                emitProgress(listener, existing, probe.totalBytes(), 0L, 1, true, new AtomicLong(0L));
                return;
            }
            String rangeHdr = existing > 0 ? ("bytes=" + existing + "-") : null;
            try (ResponseHolder resp = openFollowRedirects("GET", url, headers, rangeHdr, cancel)) {
                int code = resp.response.code();
                if (code < 200 || code >= 300) {
                    throw httpError(code);
                }
                String ct = nonNull(resp.response.header("Content-Type"));
                rejectErrorPayloadContentType(ct);
                ResponseBody body = resp.response.body();
                if (body == null) {
                    throw new IOException("响应体为空");
                }
                boolean append = (code == 206 && existing > 0);
                long startOffset = append ? existing : 0L;
                long contentLen = parseLong(resp.response.header("Content-Length"), -1L);
                long expectedTotal = probe.totalBytes() > 0
                        ? probe.totalBytes()
                        : (contentLen > 0 ? startOffset + contentLen : -1L);

                if (code == 206 && resp.response.header("Content-Range") != null) {
                    long crTotal = parseContentRangeTotal(resp.response.header("Content-Range"));
                    if (crTotal > 0) {
                        expectedTotal = crTotal;
                    }
                }

                long done = startOffset;
                AtomicLong lastEmit = new AtomicLong(0L);
                long speedStartMs = System.currentTimeMillis();
                long speedStartBytes = done;

                try (InputStream in = body.byteStream();
                     RandomAccessFile raf = new RandomAccessFile(partFile, "rw")) {
                    if (!append) {
                        raf.setLength(0L);
                    }
                    raf.seek(startOffset);
                    byte[] buf = new byte[BUFFER_SIZE];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        Net.check(cancel);
                        raf.write(buf, 0, n);
                        done += n;
                        if (done > MAX_FILE_BYTES) {
                            throw new IOException("视频超过 4 GB");
                        }
                        long elapsed = Math.max(1L, System.currentTimeMillis() - speedStartMs);
                        long speed = (done - speedStartBytes) * 1000L / elapsed;
                        emitProgress(listener, done, expectedTotal, speed, 1, false, lastEmit);
                    }
                    raf.getFD().sync();
                }

                if (expectedTotal <= 0 || done != expectedTotal) {
                    throw new IOException("下载中断，文件不完整（" + done + "/" + expectedTotal + "）");
                }
                emitProgress(listener, done, expectedTotal, 0L, 0, true, lastEmit);
                return;
            } catch (CancellationException ce) {
                throw ce;
            } catch (IOException e) {
                lastError = e;
            }
        }
        throw lastError != null ? lastError : new IOException("下载失败");
    }

    private static ResumeMeta loadOrCreateMeta(
            String url,
            ProbeResult probe,
            File partFile,
            File metaFile,
            int threads
    ) throws IOException {
        long totalBytes = probe.totalBytes();
        if (metaFile.exists() && partFile.exists() && partFile.length() == totalBytes) {
            try {
                ResumeMeta existing = ResumeMeta.load(metaFile);
                boolean etagMatches = existing.etag.isEmpty() || probe.etag().isEmpty()
                        || existing.etag.equals(probe.etag());
                if (existing.totalBytes == totalBytes && etagMatches && isValidChunkLayout(existing.chunks, totalBytes)) {
                    return existing;
                }
            } catch (IOException ignored) {
                // Corrupt or stale metadata; rebuild cleanly.
            }
        }

        List<ChunkState> chunks = new ArrayList<>(threads);
        long base = totalBytes / threads;
        long rem = totalBytes % threads;
        long cursor = 0L;
        for (int i = 0; i < threads; i++) {
            long len = base + (i < rem ? 1L : 0L);
            long end = cursor + len - 1L;
            chunks.add(new ChunkState(i, cursor, end, 0L));
            cursor = end + 1L;
        }

        try (RandomAccessFile raf = new RandomAccessFile(partFile, "rw")) {
            raf.setLength(totalBytes);
        }
        ResumeMeta meta = new ResumeMeta(url, probe.etag(), totalBytes, threads, chunks);
        meta.save(metaFile);
        return meta;
    }

    private static boolean isValidChunkLayout(List<ChunkState> chunks, long totalBytes) {
        if (chunks.isEmpty()) {
            return false;
        }
        long expectedStart = 0L;
        for (ChunkState c : chunks) {
            if (c.start != expectedStart || c.end < c.start) {
                return false;
            }
            expectedStart = c.end + 1L;
        }
        return expectedStart == totalBytes;
    }

    private static void emitProgress(
            ProgressListener listener,
            long done,
            long total,
            long speedBps,
            int activeThreads,
            boolean force,
            AtomicLong lastEmitMs
    ) {
        if (listener == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long prev = lastEmitMs.get();
        if (force || now - prev >= PROGRESS_INTERVAL_MS) {
            if (force || lastEmitMs.compareAndSet(prev, now)) {
                listener.onProgress(done, total, Math.max(0L, speedBps), activeThreads);
            }
        }
    }

    private static final class ResponseHolder implements AutoCloseable {
        final Response response;
        final String finalUrl;

        ResponseHolder(Response response, String finalUrl) {
            this.response = response;
            this.finalUrl = finalUrl;
        }

        @Override
        public void close() {
            response.close();
        }
    }

    private static ResponseHolder openFollowRedirects(
            String method,
            String url,
            Map<String, String> headers,
            String rangeHeader,
            Net.Cancellation cancel
    ) throws IOException {
        String current = HttpClient.normalizeUrl(url);
        OkHttpClient client = HttpClient.client();
        for (int hop = 0; hop < 9; hop++) {
            Net.check(cancel);
            if (!LinkInput.isWebUrl(current)) {
                throw new IOException("跳转到了不支持的地址: " + current);
            }
            Request.Builder rb = HttpClient.buildRequest(method, current, null, headers);
            if (rangeHeader != null) {
                rb.header("Range", rangeHeader);
            }
            Response resp = client.newCall(rb.build()).execute();
            HttpClient.saveResponseCookies(current, resp.headers());
            int code = resp.code();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = resp.header("Location");
                resp.close();
                if (loc == null || loc.isBlank()) {
                    throw new IOException("重定向缺少目标地址");
                }
                current = URI.create(current).resolve(loc).toString();
                continue;
            }
            return new ResponseHolder(resp, current);
        }
        throw new IOException("链接跳转次数过多");
    }

    private static void rejectErrorPayloadContentType(String contentType) throws IOException {
        String lower = contentType.toLowerCase(Locale.ROOT);
        if (lower.contains("text/html") || lower.contains("application/json")) {
            throw new IOException("服务器返回了网页或 JSON 错误响应，而非媒体流（" + contentType + "）");
        }
    }

    private static IOException httpError(int code) {
        String hint = (code == 412 || code == 403 || code == 429)
                ? "平台访问受限，请点网页识别或登录后重试"
                : "网络请求未成功";
        return new IOException(hint + "（" + code + "）");
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseContentRangeTotal(String contentRange) {
        if (contentRange == null) {
            return -1L;
        }
        Matcher m = CONTENT_RANGE_PATTERN.matcher(contentRange.trim());
        if (!m.matches()) {
            return -1L;
        }
        String total = m.group(3);
        if ("*".equals(total)) {
            return -1L;
        }
        return parseLong(total, -1L);
    }

    private static void moveOrCopy(File src, File dst) throws IOException {
        if (src.equals(dst)) {
            return;
        }
        try {
            Files.move(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
            src.delete();
        }
    }

    private static String shortHash(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(nonNull(input).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                sb.append(String.format(Locale.ROOT, "%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(nonNull(input).hashCode());
        }
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }
}
