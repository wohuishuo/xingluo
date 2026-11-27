package app.xingluo.video.engine.torrent;

import app.xingluo.video.LinkInput;
import app.xingluo.video.Net;
import app.xingluo.video.engine.HttpClient;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pure-Java BitTorrent & Magnet downloader supporting:
 * - BEP-0019 WebSeed multi-thread HTTP Range download with SHA-1 piece verification
 * - BEP-0003 HTTP/HTTPS & BEP-0015 UDP tracker peer discovery
 * - Pure-Java Peer Wire TCP protocol (handshake, bitfield, interested, unchoke, request, piece)
 * - BEP-0010 / BEP-0009 ut_metadata exchange for tracker-backed magnet links
 */
public final class TorrentDownloader {
    public static final String NO_PEERS_OR_WEBSEED_MSG =
            "未发现活跃做种节点或 WebSeed，可复制磁力链接到专用 BT 客户端或稍后重试";

    private static final byte[] PROTOCOL_HEADER = "BitTorrent protocol".getBytes(StandardCharsets.US_ASCII);
    private static final long UDP_MAGIC_CONNECTION_ID = 0x41727101980L;
    private static final int BLOCK_SIZE = 16 * 1024;
    private static final int MAX_PIPELINE = 6;
    private static final int MAX_WEBSEED_THREADS = 4;
    private static final int MAX_PEER_WORKERS = 8;
    private static final int MAX_MESSAGE_BYTES = 4 * 1024 * 1024;

    private static final int MSG_CHOKE = 0;
    private static final int MSG_UNCHOKE = 1;
    private static final int MSG_INTERESTED = 2;
    private static final int MSG_HAVE = 4;
    private static final int MSG_BITFIELD = 5;
    private static final int MSG_REQUEST = 6;
    private static final int MSG_PIECE = 7;
    private static final int MSG_EXTENDED = 20;

    private static final SecureRandom RANDOM = new SecureRandom();

    private TorrentDownloader() {}

    public static final class DownloadResult {
        public final TorrentMeta meta;
        public final List<File> files;
        public final long totalBytes;
        public final String mode;

        public DownloadResult(TorrentMeta meta, List<File> files, long totalBytes, String mode) {
            this.meta = meta;
            this.files = List.copyOf(files);
            this.totalBytes = totalBytes;
            this.mode = mode;
        }

        public File primaryFile() {
            if (files.isEmpty()) return null;
            File best = files.get(0);
            for (File f : files) {
                if (f.length() > best.length()) {
                    best = f;
                }
            }
            return best;
        }
    }

    public static DownloadResult download(TorrentMeta meta, File outputDir) throws IOException {
        return download(meta, outputDir, () -> false, (done, total) -> {});
    }

    public static DownloadResult download(
            TorrentMeta meta,
            File outputDir,
            Net.Cancellation cancel,
            Net.Progress progress
    ) throws IOException {
        if (meta == null) throw new IllegalArgumentException("TorrentMeta must not be null");
        if (outputDir == null) throw new IllegalArgumentException("outputDir must not be null");
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("Cannot create output directory: " + outputDir);
        }
        Net.Cancellation safeCancel = cancel != null ? cancel : () -> false;
        Net.Progress safeProgress = progress != null ? progress : (done, total) -> {};
        Net.check(safeCancel);

        IOException webSeedFailure = null;
        if (!meta.webSeeds.isEmpty()) {
            try {
                return downloadViaWebSeeds(meta, outputDir, safeCancel, safeProgress);
            } catch (CancellationException ce) {
                throw ce;
            } catch (IOException e) {
                webSeedFailure = e;
                if (meta.trackers.isEmpty()) {
                    throw e;
                }
            }
        }

        if (meta.trackers.isEmpty()) {
            if (webSeedFailure != null) throw webSeedFailure;
            throw new IOException(NO_PEERS_OR_WEBSEED_MSG);
        }

        byte[] peerId = generatePeerId();
        List<InetSocketAddress> peers = discoverPeers(meta, peerId, safeCancel);
        if (peers.isEmpty()) {
            throw new IOException(NO_PEERS_OR_WEBSEED_MSG);
        }
        return downloadViaPeers(meta, peerId, peers, outputDir, safeCancel, safeProgress);
    }

    // -------------------------------------------------------------------------
    // BEP-0019 WebSeed HTTP Range Multi-Thread Downloader
    // -------------------------------------------------------------------------

    private static DownloadResult downloadViaWebSeeds(
            TorrentMeta meta,
            File outputDir,
            Net.Cancellation cancel,
            Net.Progress progress
    ) throws IOException {
        if (!meta.hasMetadata()) {
            return downloadRawWebSeedMagnet(meta, outputDir, cancel, progress);
        }

        long totalSize = meta.totalSize();
        int pieceCount = meta.pieceHashes.size();
        OkHttpClient http = HttpClient.client().newBuilder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .build();

        try (TargetStorage storage = new TargetStorage(meta, outputDir)) {
            if (totalSize == 0L) {
                progress.update(0L, 0L);
                return new DownloadResult(meta, storage.files(), 0L, "webseed");
            }

            int threads = Math.min(MAX_WEBSEED_THREADS, Math.max(1, pieceCount));
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            AtomicInteger nextPiece = new AtomicInteger(0);
            AtomicLong downloadedBytes = new AtomicLong(0L);
            AtomicLong lastProgressAt = new AtomicLong(0L);

            try {
                List<Callable<Void>> workers = new ArrayList<>(threads);
                for (int t = 0; t < threads; t++) {
                    workers.add(() -> {
                        while (true) {
                            Net.check(cancel);
                            int pieceIdx = nextPiece.getAndIncrement();
                            if (pieceIdx >= pieceCount) return null;

                            long pieceStart = (long) pieceIdx * meta.pieceLength;
                            long pieceEnd = Math.min(pieceStart + meta.pieceLength, totalSize);
                            int pieceLen = (int) (pieceEnd - pieceStart);

                            byte[] pieceBuf = fetchAndVerifyWebSeedPiece(
                                    http, meta, pieceIdx, pieceStart, pieceLen, cancel);
                            storage.writePiece(pieceIdx, pieceBuf);

                            long done = downloadedBytes.addAndGet(pieceLen);
                            long now = System.currentTimeMillis();
                            long prev = lastProgressAt.get();
                            if (done == totalSize || (now - prev >= 150 && lastProgressAt.compareAndSet(prev, now))) {
                                progress.update(done, totalSize);
                            }
                        }
                    });
                }

                List<Future<Void>> futures = pool.invokeAll(workers);
                for (Future<Void> f : futures) {
                    try {
                        f.get();
                    } catch (ExecutionException ee) {
                        Throwable cause = ee.getCause();
                        if (cause instanceof CancellationException ce) throw ce;
                        if (cause instanceof IOException ioe) throw ioe;
                        throw new IOException("WebSeed download failed", cause);
                    }
                }
                progress.update(totalSize, totalSize);
                return new DownloadResult(meta, storage.files(), totalSize, "webseed");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Download interrupted");
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private static byte[] fetchAndVerifyWebSeedPiece(
            OkHttpClient http,
            TorrentMeta meta,
            int pieceIdx,
            long pieceStart,
            int pieceLen,
            Net.Cancellation cancel
    ) throws IOException {
        IOException lastError = null;
        int seedCount = meta.webSeeds.size();
        int maxAttempts = Math.max(2, seedCount * 2);

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            Net.check(cancel);
            String seedBase = meta.webSeeds.get((pieceIdx + attempt) % seedCount);
            try {
                byte[] buf = downloadPieceSliceFromSeed(http, meta, seedBase, pieceStart, pieceLen, cancel);
                byte[] actualSha1 = Bencode.sha1(buf);
                byte[] expectedSha1 = meta.pieceHashes.get(pieceIdx);
                if (!Arrays.equals(actualSha1, expectedSha1)) {
                    throw new IOException("WebSeed 分片 SHA-1 校验失败 (piece " + pieceIdx + ")");
                }
                return buf;
            } catch (CancellationException ce) {
                throw ce;
            } catch (IOException e) {
                lastError = e;
            }
        }
        throw lastError != null ? lastError : new IOException("WebSeed 下载失败");
    }

    private static byte[] downloadPieceSliceFromSeed(
            OkHttpClient http,
            TorrentMeta meta,
            String seedBase,
            long pieceStart,
            int pieceLen,
            Net.Cancellation cancel
    ) throws IOException {
        byte[] pieceBuf = new byte[pieceLen];
        long pieceEnd = pieceStart + pieceLen;
        int written = 0;

        for (TorrentMeta.TorrentFile file : meta.files) {
            long fileStartGlobal = file.offset;
            long fileEndGlobal = file.offset + file.length;
            if (fileEndGlobal <= pieceStart || fileStartGlobal >= pieceEnd) {
                continue;
            }
            long overlapStart = Math.max(pieceStart, fileStartGlobal);
            long overlapEnd = Math.min(pieceEnd, fileEndGlobal);
            int sliceLen = (int) (overlapEnd - overlapStart);
            if (sliceLen <= 0) continue;

            long fileRangeStart = overlapStart - fileStartGlobal;
            long fileRangeEnd = fileRangeStart + sliceLen - 1;
            String fileUrl = resolveWebSeedFileUrl(seedBase, meta, file);

            readHttpRange(http, fileUrl, fileRangeStart, fileRangeEnd, pieceBuf, written, sliceLen, cancel);
            written += sliceLen;
        }
        if (written != pieceLen) {
            throw new IOException("Incomplete piece assembly: expected " + pieceLen + ", got " + written);
        }
        return pieceBuf;
    }

    private static void readHttpRange(
            OkHttpClient http,
            String url,
            long rangeStart,
            long rangeEnd,
            byte[] dest,
            int destOffset,
            int expectedLen,
            Net.Cancellation cancel
    ) throws IOException {
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", HttpClient.MOBILE_UA)
                .header("Range", "bytes=" + rangeStart + "-" + rangeEnd)
                .header("Accept-Encoding", "identity")
                .build();
        try (Response resp = http.newCall(req).execute()) {
            Net.check(cancel);
            int code = resp.code();
            if (code != 206 && code != 200) {
                throw new IOException("WebSeed HTTP error " + code + " for " + url);
            }
            ResponseBody body = resp.body();
            if (body == null) {
                throw new IOException("Empty WebSeed response body");
            }
            try (InputStream in = body.byteStream()) {
                if (code == 200 && rangeStart > 0) {
                    skipFully(in, rangeStart, cancel);
                }
                readFully(in, dest, destOffset, expectedLen, cancel);
            }
        }
    }

    private static DownloadResult downloadRawWebSeedMagnet(
            TorrentMeta meta,
            File outputDir,
            Net.Cancellation cancel,
            Net.Progress progress
    ) throws IOException {
        OkHttpClient http = HttpClient.client().newBuilder()
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .build();

        String baseName = LinkInput.safeName(meta.name);
        File target = new File(outputDir, baseName);
        IOException lastError = null;
        for (String url : meta.webSeeds) {
            Net.check(cancel);
            try {
                Request req = new Request.Builder()
                        .url(url)
                        .header("User-Agent", HttpClient.MOBILE_UA)
                        .header("Accept-Encoding", "identity")
                        .build();
                try (Response resp = http.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        throw new IOException("WebSeed HTTP error " + resp.code());
                    }
                    long total = resp.body().contentLength();
                    long done = 0L;
                    try (InputStream in = resp.body().byteStream();
                         RandomAccessFile raf = new RandomAccessFile(target, "rw")) {
                        raf.setLength(0L);
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        long last = 0L;
                        while ((n = in.read(buf)) != -1) {
                            Net.check(cancel);
                            raf.write(buf, 0, n);
                            done += n;
                            long now = System.currentTimeMillis();
                            if (now - last >= 150) {
                                progress.update(done, total);
                                last = now;
                            }
                        }
                    }
                    progress.update(done, total > 0 ? total : done);
                    return new DownloadResult(meta, List.of(target), done, "webseed");
                }
            } catch (CancellationException ce) {
                throw ce;
            } catch (IOException e) {
                lastError = e;
            }
        }
        throw lastError != null ? lastError : new IOException(NO_PEERS_OR_WEBSEED_MSG);
    }

    static String resolveWebSeedFileUrl(String seedBase, TorrentMeta meta, TorrentMeta.TorrentFile file) {
        if (meta.singleFile) {
            if (seedBase.endsWith("/")) {
                return seedBase + encodePathSegment(meta.name);
            }
            return seedBase;
        }
        String base = seedBase.endsWith("/") ? seedBase : (seedBase + "/");
        return base + encodePathSegment(meta.name) + "/" + encodePathSegments(file.path);
    }

    private static String encodePathSegments(String path) {
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(encodePathSegment(parts[i]));
        }
        return sb.toString();
    }

    private static String encodePathSegment(String seg) {
        try { return URLEncoder.encode(seg, "UTF-8").replace("+", "%20"); }
        catch (Exception e) { return seg; }
    }

    // -------------------------------------------------------------------------
    // BEP-0003 HTTP/HTTPS & BEP-0015 UDP Tracker Announce
    // -------------------------------------------------------------------------

    static List<InetSocketAddress> discoverPeers(
            TorrentMeta meta,
            byte[] peerId,
            Net.Cancellation cancel
    ) {
        if (meta.trackers.isEmpty()) return List.of();
        Set<InetSocketAddress> discovered = Collections.synchronizedSet(new LinkedHashSet<>());
        int threads = Math.min(4, meta.trackers.size());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long left = meta.hasMetadata() ? meta.totalSize() : 0L;
        byte[] infoHash = meta.infoHash();

        try {
            List<Callable<Void>> tasks = new ArrayList<>(meta.trackers.size());
            for (String tracker : meta.trackers) {
                tasks.add(() -> {
                    Net.check(cancel);
                    try {
                        String lower = tracker.toLowerCase();
                        List<InetSocketAddress> found;
                        if (lower.startsWith("http://") || lower.startsWith("https://")) {
                            found = announceHttpTracker(tracker, infoHash, peerId, left, cancel);
                        } else if (lower.startsWith("udp://")) {
                            found = announceUdpTracker(tracker, infoHash, peerId, left, cancel);
                        } else {
                            found = List.of();
                        }
                        discovered.addAll(found);
                    } catch (CancellationException ce) {
                        throw ce;
                    } catch (Exception ignored) {
                        // Individual tracker failures are tolerated across tracker lists.
                    }
                    return null;
                });
            }
            List<Future<Void>> futures = pool.invokeAll(tasks, 12, TimeUnit.SECONDS);
            for (Future<Void> f : futures) {
                if (f.isDone() && !f.isCancelled()) {
                    try {
                        f.get();
                    } catch (ExecutionException ee) {
                        if (ee.getCause() instanceof CancellationException ce) throw ce;
                    }
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Tracker announce interrupted");
        } finally {
            pool.shutdownNow();
        }
        return new ArrayList<>(discovered);
    }

    static List<InetSocketAddress> announceHttpTracker(
            String trackerUrl,
            byte[] infoHash,
            byte[] peerId,
            long left,
            Net.Cancellation cancel
    ) throws IOException {
        Net.check(cancel);
        String sep = trackerUrl.contains("?") ? "&" : "?";
        String announceUrl = trackerUrl + sep
                + "info_hash=" + percentEncodeRaw(infoHash)
                + "&peer_id=" + percentEncodeRaw(peerId)
                + "&port=6881"
                + "&uploaded=0"
                + "&downloaded=0"
                + "&left=" + Math.max(0L, left)
                + "&compact=1"
                + "&event=started";

        OkHttpClient client = HttpClient.client().newBuilder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
        Request req = new Request.Builder()
                .url(announceUrl)
                .header("User-Agent", HttpClient.MOBILE_UA)
                .build();
        try (Response resp = client.newCall(req).execute()) {
            Net.check(cancel);
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("HTTP tracker status " + resp.code());
            }
            byte[] body = resp.body().bytes();
            Map<String, Object> dict = Bencode.asDict(Bencode.decode(body));
            if (dict == null) {
                throw new IOException("HTTP tracker returned non-dictionary bencode");
            }
            String failure = Bencode.asString(dict.get("failure reason"));
            if (failure != null && !failure.isBlank()) {
                throw new IOException("Tracker failure: " + failure);
            }
            List<InetSocketAddress> peers = new ArrayList<>();
            Object peersObj = dict.get("peers");
            if (peersObj instanceof byte[] compact) {
                parseCompactIpv4Peers(compact, 0, compact.length, peers);
            } else if (peersObj instanceof List<?> list) {
                for (Object item : list) {
                    Map<String, Object> pDict = Bencode.asDict(item);
                    if (pDict == null) continue;
                    String ip = Bencode.asString(pDict.get("ip"));
                    Long port = Bencode.asLong(pDict.get("port"));
                    if (ip != null && port != null && port > 0 && port <= 65535) {
                        peers.add(InetSocketAddress.createUnresolved(ip, port.intValue()));
                    }
                }
            }
            return peers;
        }
    }

    static List<InetSocketAddress> announceUdpTracker(
            String trackerUrl,
            byte[] infoHash,
            byte[] peerId,
            long left,
            Net.Cancellation cancel
    ) throws IOException {
        Net.check(cancel);
        URI uri = URI.create(trackerUrl);
        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null || host.isBlank() || port <= 0 || port > 65535) {
            throw new IOException("Invalid UDP tracker URI: " + trackerUrl);
        }

        InetAddress targetAddr = resolveIpv4OrDefault(host);
        try (DatagramSocket sock = new DatagramSocket()) {
            sock.setSoTimeout(4000);
            sock.connect(targetAddr, port);

            // Step 1: Connect request (16 bytes)
            int txId1 = RANDOM.nextInt();
            ByteBuffer connectReq = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN);
            connectReq.putLong(UDP_MAGIC_CONNECTION_ID);
            connectReq.putInt(0); // action = connect
            connectReq.putInt(txId1);
            byte[] reqBytes1 = connectReq.array();
            sock.send(new DatagramPacket(reqBytes1, reqBytes1.length));

            byte[] respBuf1 = new byte[64];
            DatagramPacket respPkt1 = new DatagramPacket(respBuf1, respBuf1.length);
            sock.receive(respPkt1);
            Net.check(cancel);
            if (respPkt1.getLength() < 16) {
                throw new IOException("Short UDP tracker connect response");
            }
            ByteBuffer bb1 = ByteBuffer.wrap(respBuf1, 0, respPkt1.getLength()).order(ByteOrder.BIG_ENDIAN);
            int action1 = bb1.getInt();
            int respTx1 = bb1.getInt();
            if (action1 != 0 || respTx1 != txId1) {
                throw new IOException("Invalid UDP tracker connect response");
            }
            long connectionId = bb1.getLong();

            // Step 2: Announce request (98 bytes)
            int txId2 = RANDOM.nextInt();
            int key = RANDOM.nextInt();
            ByteBuffer announceReq = ByteBuffer.allocate(98).order(ByteOrder.BIG_ENDIAN);
            announceReq.putLong(connectionId);
            announceReq.putInt(1); // action = announce
            announceReq.putInt(txId2);
            announceReq.put(infoHash);
            announceReq.put(peerId);
            announceReq.putLong(0L); // downloaded
            announceReq.putLong(Math.max(0L, left));
            announceReq.putLong(0L); // uploaded
            announceReq.putInt(2); // event = started
            announceReq.putInt(0); // IP = default
            announceReq.putInt(key);
            announceReq.putInt(-1); // num_want = default
            announceReq.putShort((short) 6881);
            byte[] reqBytes2 = announceReq.array();
            sock.send(new DatagramPacket(reqBytes2, reqBytes2.length));

            byte[] respBuf2 = new byte[4096];
            DatagramPacket respPkt2 = new DatagramPacket(respBuf2, respBuf2.length);
            sock.receive(respPkt2);
            Net.check(cancel);
            if (respPkt2.getLength() < 20) {
                throw new IOException("Short UDP tracker announce response");
            }
            ByteBuffer bb2 = ByteBuffer.wrap(respBuf2, 0, respPkt2.getLength()).order(ByteOrder.BIG_ENDIAN);
            int action2 = bb2.getInt();
            int respTx2 = bb2.getInt();
            if (action2 != 1 || respTx2 != txId2) {
                throw new IOException("Invalid UDP tracker announce response (action=" + action2 + ")");
            }
            bb2.getInt(); // interval
            bb2.getInt(); // leechers
            bb2.getInt(); // seeders

            List<InetSocketAddress> peers = new ArrayList<>();
            parseCompactIpv4Peers(respBuf2, 20, respPkt2.getLength() - 20, peers);
            return peers;
        }
    }

    private static InetAddress resolveIpv4OrDefault(String host) throws IOException {
        InetAddress[] all = InetAddress.getAllByName(host);
        for (InetAddress addr : all) {
            if (addr instanceof Inet4Address) return addr;
        }
        return all[0];
    }

    private static void parseCompactIpv4Peers(byte[] data, int offset, int length, List<InetSocketAddress> out) {
        int usable = length - (length % 6);
        for (int i = offset; i < offset + usable; i += 6) {
            int b0 = data[i] & 0xff;
            int b1 = data[i + 1] & 0xff;
            int b2 = data[i + 2] & 0xff;
            int b3 = data[i + 3] & 0xff;
            int port = ((data[i + 4] & 0xff) << 8) | (data[i + 5] & 0xff);
            if (port == 0 || (b0 == 0 && b1 == 0 && b2 == 0 && b3 == 0)) continue;
            String ip = b0 + "." + b1 + "." + b2 + "." + b3;
            out.add(new InetSocketAddress(ip, port));
        }
    }

    static String percentEncodeRaw(byte[] bytes) {
        final char[] hex = "0123456789ABCDEF".toCharArray();
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte raw : bytes) {
            int b = raw & 0xff;
            if ((b >= '0' && b <= '9')
                    || (b >= 'A' && b <= 'Z')
                    || (b >= 'a' && b <= 'z')
                    || b == '.' || b == '-' || b == '_' || b == '~') {
                sb.append((char) b);
            } else {
                sb.append('%').append(hex[b >>> 4]).append(hex[b & 0x0f]);
            }
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Pure-Java Peer Wire TCP Protocol (BEP-0003 + BEP-0009 ut_metadata)
    // -------------------------------------------------------------------------

    private static DownloadResult downloadViaPeers(
            TorrentMeta initialMeta,
            byte[] peerId,
            List<InetSocketAddress> peers,
            File outputDir,
            Net.Cancellation cancel,
            Net.Progress progress
    ) throws IOException {
        AtomicReference<TorrentMeta> metaRef = new AtomicReference<>(initialMeta);
        AtomicReference<PeerPieceCoordinator> coordinatorRef = new AtomicReference<>();
        AtomicReference<TargetStorage> storageRef = new AtomicReference<>();
        Object initLock = new Object();

        if (initialMeta.hasMetadata()) {
            TargetStorage storage = new TargetStorage(initialMeta, outputDir);
            storageRef.set(storage);
            coordinatorRef.set(new PeerPieceCoordinator(initialMeta, storage, progress));
        }

        int workerCount = Math.min(MAX_PEER_WORKERS, peers.size());
        ExecutorService pool = Executors.newFixedThreadPool(workerCount);
        AtomicInteger nextPeerIdx = new AtomicInteger(0);
        AtomicInteger reachablePeers = new AtomicInteger(0);

        try {
            List<Callable<Void>> workers = new ArrayList<>(workerCount);
            for (int w = 0; w < workerCount; w++) {
                workers.add(() -> {
                    while (true) {
                        Net.check(cancel);
                        PeerPieceCoordinator coord = coordinatorRef.get();
                        if (coord != null && coord.isComplete()) {
                            return null;
                        }
                        int idx = nextPeerIdx.getAndIncrement();
                        if (idx >= peers.size()) {
                            return null;
                        }
                        InetSocketAddress addr = peers.get(idx);
                        runPeerSession(
                                addr, peerId, metaRef, coordinatorRef, storageRef,
                                initLock, outputDir, reachablePeers, cancel, progress);
                    }
                });
            }

            List<Future<Void>> futures = pool.invokeAll(workers);
            for (Future<Void> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException ee) {
                    if (ee.getCause() instanceof CancellationException ce) throw ce;
                }
            }

            TorrentMeta finalMeta = metaRef.get();
            PeerPieceCoordinator coord = coordinatorRef.get();
            TargetStorage storage = storageRef.get();
            if (coord != null && coord.isComplete() && storage != null) {
                long total = finalMeta.totalSize();
                progress.update(total, total);
                return new DownloadResult(finalMeta, storage.files(), total, "peer");
            }
            if (reachablePeers.get() == 0) {
                throw new IOException(NO_PEERS_OR_WEBSEED_MSG);
            }
            throw new IOException(NO_PEERS_OR_WEBSEED_MSG);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Peer download interrupted");
        } finally {
            pool.shutdownNow();
            TargetStorage storage = storageRef.get();
            if (storage != null) {
                storage.close();
            }
        }
    }

    private static void runPeerSession(
            InetSocketAddress addr,
            byte[] peerId,
            AtomicReference<TorrentMeta> metaRef,
            AtomicReference<PeerPieceCoordinator> coordinatorRef,
            AtomicReference<TargetStorage> storageRef,
            Object initLock,
            File outputDir,
            AtomicInteger reachablePeers,
            Net.Cancellation cancel,
            Net.Progress progress
    ) {
        InetSocketAddress resolved = addr.isUnresolved()
                ? new InetSocketAddress(addr.getHostString(), addr.getPort())
                : addr;

        try (Socket sock = new Socket()) {
            sock.connect(resolved, 5000);
            sock.setSoTimeout(12000);
            Net.check(cancel);

            DataOutputStream out = new DataOutputStream(sock.getOutputStream());
            DataInputStream in = new DataInputStream(sock.getInputStream());

            byte[] infoHash = metaRef.get().infoHash();
            boolean peerSupportsExt = exchangeHandshake(in, out, infoHash, peerId);
            reachablePeers.incrementAndGet();

            if (!metaRef.get().hasMetadata() && peerSupportsExt) {
                sendExtendedHandshake(out);
            }

            // Express interest immediately (msg id=2)
            sendSimpleMessage(out, MSG_INTERESTED);

            boolean peerChoking = true;
            BitSet peerPieces = new BitSet();
            int peerUtMetadataId = -1;
            byte[] metadataBuffer = null;
            boolean[] metadataPieceReceived = null;
            int metadataTotalPieces = 0;

            final List<ActivePiece> activePieces = new ArrayList<>();

            while (true) {
                Net.check(cancel);
                PeerPieceCoordinator coord = coordinatorRef.get();
                if (coord != null && coord.isComplete()) {
                    return;
                }

                if (!peerChoking && coord != null) {
                    pipelineRequests(out, coord, peerPieces, activePieces);
                }

                int len;
                try {
                    len = in.readInt();
                } catch (EOFException eof) {
                    releaseActivePieces(coordinatorRef.get(), activePieces);
                    return;
                }
                if (len == 0) {
                    continue; // keep-alive
                }
                if (len < 0 || len > MAX_MESSAGE_BYTES) {
                    releaseActivePieces(coordinatorRef.get(), activePieces);
                    return;
                }

                int msgId = in.readUnsignedByte();
                int payloadLen = len - 1;
                byte[] payload = new byte[payloadLen];
                if (payloadLen > 0) {
                    in.readFully(payload);
                }

                switch (msgId) {
                    case MSG_CHOKE -> peerChoking = true;
                    case MSG_UNCHOKE -> peerChoking = false;
                    case MSG_HAVE -> {
                        if (payload.length == 4) {
                            int pieceIdx = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).getInt();
                            if (pieceIdx >= 0) {
                                peerPieces.set(pieceIdx);
                            }
                        }
                    }
                    case MSG_BITFIELD -> {
                        for (int byteIdx = 0; byteIdx < payload.length; byteIdx++) {
                            int b = payload[byteIdx] & 0xff;
                            for (int bit = 0; bit < 8; bit++) {
                                if ((b & (0x80 >> bit)) != 0) {
                                    peerPieces.set((byteIdx * 8) + bit);
                                }
                            }
                        }
                    }
                    case MSG_PIECE -> {
                        if (payload.length < 8 || coord == null) break;
                        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
                        int pieceIdx = bb.getInt();
                        int begin = bb.getInt();
                        int blockLen = payload.length - 8;
                        ActivePiece target = findActivePiece(activePieces, pieceIdx);
                        if (target != null) {
                            boolean finished = target.onBlock(begin, payload, 8, blockLen);
                            if (finished) {
                                activePieces.remove(target);
                                if (coord.verifyAndCommit(pieceIdx, target.data)) {
                                    // Committed successfully
                                } else {
                                    coord.releasePiece(pieceIdx);
                                }
                            }
                        }
                    }
                    case MSG_EXTENDED -> {
                        if (payload.length < 2) break;
                        int extId = payload[0] & 0xff;
                        if (extId == 0) {
                            Map<String, Object> extDict = Bencode.asDict(
                                    Bencode.decodePrefix(payload, 1).value);
                            if (extDict != null) {
                                Map<String, Object> m = Bencode.asDict(extDict.get("m"));
                                Long utId = m != null ? Bencode.asLong(m.get("ut_metadata")) : null;
                                Long metaSize = Bencode.asLong(extDict.get("metadata_size"));
                                if (utId != null && utId > 0 && metaSize != null && metaSize > 0
                                        && metaSize <= 8 * 1024 * 1024 && !metaRef.get().hasMetadata()) {
                                    peerUtMetadataId = utId.intValue();
                                    int totalSize = metaSize.intValue();
                                    metadataBuffer = new byte[totalSize];
                                    metadataTotalPieces = (totalSize + BLOCK_SIZE - 1) / BLOCK_SIZE;
                                    metadataPieceReceived = new boolean[metadataTotalPieces];
                                    for (int p = 0; p < metadataTotalPieces; p++) {
                                        sendUtMetadataRequest(out, peerUtMetadataId, p);
                                    }
                                }
                            }
                        } else if (peerUtMetadataId > 0 && extId == 1 && metadataBuffer != null) {
                            Bencode.DecodedValue header = Bencode.decodePrefix(payload, 1);
                            Map<String, Object> msgDict = Bencode.asDict(header.value);
                            Long msgType = msgDict != null ? Bencode.asLong(msgDict.get("msg_type")) : null;
                            Long pieceIdxObj = msgDict != null ? Bencode.asLong(msgDict.get("piece")) : null;
                            if (msgType != null && msgType == 1 && pieceIdxObj != null) {
                                int pIdx = pieceIdxObj.intValue();
                                if (pIdx >= 0 && pIdx < metadataTotalPieces) {
                                    int dataStart = header.nextOffset;
                                    int dataLen = payload.length - dataStart;
                                    int destPos = pIdx * BLOCK_SIZE;
                                    if (destPos + dataLen <= metadataBuffer.length) {
                                        System.arraycopy(payload, dataStart, metadataBuffer, destPos, dataLen);
                                        metadataPieceReceived[pIdx] = true;
                                        if (allTrue(metadataPieceReceived)) {
                                            synchronized (initLock) {
                                                if (!metaRef.get().hasMetadata()) {
                                                    TorrentMeta full = TorrentMeta.withInfoBytes(
                                                            metaRef.get(), metadataBuffer);
                                                    metaRef.set(full);
                                                    TargetStorage st = new TargetStorage(full, outputDir);
                                                    storageRef.set(st);
                                                    coordinatorRef.set(
                                                            new PeerPieceCoordinator(full, st, progress));
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    default -> {
                        // Ignore unhandled peer wire messages (not interested, cancel, port, etc.)
                    }
                }
            }
        } catch (CancellationException ce) {
            throw ce;
        } catch (Exception ignored) {
            // Peer connection failed or timed out; worker moves to next peer.
        }
    }

    private static boolean exchangeHandshake(
            DataInputStream in,
            DataOutputStream out,
            byte[] infoHash,
            byte[] peerId
    ) throws IOException {
        byte[] reserved = new byte[8];
        reserved[5] |= 0x10; // BEP-0010 extension protocol bit

        ByteBuffer hs = ByteBuffer.allocate(68);
        hs.put((byte) 19);
        hs.put(PROTOCOL_HEADER);
        hs.put(reserved);
        hs.put(infoHash);
        hs.put(peerId);
        out.write(hs.array());
        out.flush();

        byte[] peerHs = new byte[68];
        in.readFully(peerHs);
        if ((peerHs[0] & 0xff) != 19) {
            throw new IOException("Invalid BitTorrent handshake length");
        }
        byte[] proto = Arrays.copyOfRange(peerHs, 1, 20);
        if (!Arrays.equals(proto, PROTOCOL_HEADER)) {
            throw new IOException("Invalid BitTorrent protocol header");
        }
        byte[] peerHash = Arrays.copyOfRange(peerHs, 28, 48);
        if (!Arrays.equals(peerHash, infoHash)) {
            throw new IOException("Peer infoHash mismatch");
        }
        return (peerHs[25] & 0x10) != 0;
    }

    private static void sendSimpleMessage(DataOutputStream out, int msgId) throws IOException {
        out.writeInt(1);
        out.writeByte(msgId);
        out.flush();
    }

    private static void sendExtendedHandshake(DataOutputStream out) throws IOException {
        Map<String, Object> root = Map.of("m", Map.of("ut_metadata", 1));
        byte[] body = Bencode.encode(root);
        out.writeInt(2 + body.length);
        out.writeByte(MSG_EXTENDED);
        out.writeByte(0);
        out.write(body);
        out.flush();
    }

    private static void sendUtMetadataRequest(DataOutputStream out, int peerUtMetadataId, int piece)
            throws IOException {
        Map<String, Object> req = Map.of("msg_type", 0, "piece", piece);
        byte[] body = Bencode.encode(req);
        out.writeInt(2 + body.length);
        out.writeByte(MSG_EXTENDED);
        out.writeByte(peerUtMetadataId);
        out.write(body);
        out.flush();
    }

    private static void pipelineRequests(
            DataOutputStream out,
            PeerPieceCoordinator coord,
            BitSet peerPieces,
            List<ActivePiece> activePieces
    ) throws IOException {
        int inFlight = 0;
        for (ActivePiece ap : activePieces) {
            inFlight += ap.inFlightCount();
        }
        while (inFlight < MAX_PIPELINE) {
            ActivePiece target = null;
            for (ActivePiece ap : activePieces) {
                if (ap.hasMoreToRequest()) {
                    target = ap;
                    break;
                }
            }
            if (target == null) {
                int nextIdx = coord.acquireNextPiece(peerPieces);
                if (nextIdx < 0) break;
                int pieceSize = coord.pieceSize(nextIdx);
                target = new ActivePiece(nextIdx, pieceSize);
                activePieces.add(target);
            }
            while (inFlight < MAX_PIPELINE && target.hasMoreToRequest()) {
                int[] req = target.nextBlockRequest();
                out.writeInt(13);
                out.writeByte(MSG_REQUEST);
                out.writeInt(target.pieceIndex);
                out.writeInt(req[0]);
                out.writeInt(req[1]);
                inFlight++;
            }
        }
        out.flush();
    }

    private static ActivePiece findActivePiece(List<ActivePiece> activePieces, int pieceIdx) {
        for (ActivePiece ap : activePieces) {
            if (ap.pieceIndex == pieceIdx) return ap;
        }
        return null;
    }

    private static void releaseActivePieces(PeerPieceCoordinator coord, List<ActivePiece> activePieces) {
        if (coord == null) return;
        for (ActivePiece ap : activePieces) {
            coord.releasePiece(ap.pieceIndex);
        }
        activePieces.clear();
    }

    private static final class ActivePiece {
        final int pieceIndex;
        final byte[] data;
        final int totalBlocks;
        final boolean[] received;
        int nextBlockToRequest = 0;
        int receivedCount = 0;

        ActivePiece(int pieceIndex, int pieceSize) {
            this.pieceIndex = pieceIndex;
            this.data = new byte[pieceSize];
            this.totalBlocks = (pieceSize + BLOCK_SIZE - 1) / BLOCK_SIZE;
            this.received = new boolean[totalBlocks];
        }

        boolean hasMoreToRequest() {
            return nextBlockToRequest < totalBlocks;
        }

        int inFlightCount() {
            int requested = nextBlockToRequest;
            int doneBeforeReq = 0;
            for (int i = 0; i < requested; i++) {
                if (received[i]) doneBeforeReq++;
            }
            return requested - doneBeforeReq;
        }

        int[] nextBlockRequest() {
            int blockIdx = nextBlockToRequest++;
            int begin = blockIdx * BLOCK_SIZE;
            int len = Math.min(BLOCK_SIZE, data.length - begin);
            return new int[]{begin, len};
        }

        boolean onBlock(int begin, byte[] src, int srcOffset, int len) {
            if (begin < 0 || begin + len > data.length || (begin % BLOCK_SIZE) != 0) {
                return false;
            }
            int blockIdx = begin / BLOCK_SIZE;
            if (blockIdx >= totalBlocks) return false;
            System.arraycopy(src, srcOffset, data, begin, len);
            if (!received[blockIdx]) {
                received[blockIdx] = true;
                receivedCount++;
            }
            return receivedCount == totalBlocks;
        }
    }

    private static final class PeerPieceCoordinator {
        private final TorrentMeta meta;
        private final TargetStorage storage;
        private final Net.Progress progress;
        private final long totalSize;
        private final int pieceCount;
        private final boolean[] completed;
        private final Set<Integer> inProgress = ConcurrentHashMap.newKeySet();
        private final AtomicInteger completedCount = new AtomicInteger(0);
        private final AtomicLong bytesDone = new AtomicLong(0L);

        PeerPieceCoordinator(TorrentMeta meta, TargetStorage storage, Net.Progress progress) {
            this.meta = meta;
            this.storage = storage;
            this.progress = progress;
            this.totalSize = meta.totalSize();
            this.pieceCount = meta.pieceHashes.size();
            this.completed = new boolean[pieceCount];
        }

        synchronized int acquireNextPiece(BitSet peerHas) {
            for (int i = 0; i < pieceCount; i++) {
                if (!completed[i] && !inProgress.contains(i) && peerHas.get(i)) {
                    inProgress.add(i);
                    return i;
                }
            }
            // Endgame fallback if another peer is stalling on remaining pieces
            for (int i = 0; i < pieceCount; i++) {
                if (!completed[i] && peerHas.get(i)) {
                    return i;
                }
            }
            return -1;
        }

        synchronized void releasePiece(int pieceIndex) {
            inProgress.remove(pieceIndex);
        }

        boolean verifyAndCommit(int pieceIndex, byte[] pieceData) throws IOException {
            byte[] actual = Bencode.sha1(pieceData);
            if (!Arrays.equals(actual, meta.pieceHashes.get(pieceIndex))) {
                return false;
            }
            synchronized (this) {
                if (completed[pieceIndex]) {
                    return true;
                }
                storage.writePiece(pieceIndex, pieceData);
                completed[pieceIndex] = true;
                inProgress.remove(pieceIndex);
                completedCount.incrementAndGet();
                long done = bytesDone.addAndGet(pieceData.length);
                progress.update(done, totalSize);
                return true;
            }
        }

        boolean isComplete() {
            return completedCount.get() >= pieceCount;
        }

        int pieceSize(int pieceIndex) {
            long start = (long) pieceIndex * meta.pieceLength;
            return (int) Math.min(meta.pieceLength, totalSize - start);
        }
    }

    // -------------------------------------------------------------------------
    // Storage & Utility Helpers
    // -------------------------------------------------------------------------

    private static final class TargetStorage implements Closeable {
        private final TorrentMeta meta;
        private final List<File> fileList;
        private final List<RandomAccessFile> rafs;
        private final List<Object> locks;

        TargetStorage(TorrentMeta meta, File outputDir) throws IOException {
            this.meta = meta;
            this.fileList = new ArrayList<>(meta.files.size());
            this.rafs = new ArrayList<>(meta.files.size());
            this.locks = new ArrayList<>(meta.files.size());

            File baseDir = meta.singleFile
                    ? outputDir
                    : new File(outputDir, LinkInput.safeName(meta.name));
            if (!baseDir.exists() && !baseDir.mkdirs()) {
                throw new IOException("Cannot create torrent output dir: " + baseDir);
            }

            try {
                for (TorrentMeta.TorrentFile tf : meta.files) {
                    File target = meta.singleFile
                            ? new File(baseDir, LinkInput.safeName(meta.name))
                            : new File(baseDir, tf.path);
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IOException("Cannot create parent dir: " + parent);
                    }
                    RandomAccessFile raf = new RandomAccessFile(target, "rw");
                    raf.setLength(tf.length);
                    fileList.add(target);
                    rafs.add(raf);
                    locks.add(new Object());
                }
            } catch (IOException e) {
                close();
                throw e;
            }
        }

        List<File> files() {
            return fileList;
        }

        void writePiece(int pieceIndex, byte[] pieceBytes) throws IOException {
            long pieceStart = (long) pieceIndex * meta.pieceLength;
            long pieceEnd = pieceStart + pieceBytes.length;

            for (int i = 0; i < meta.files.size(); i++) {
                TorrentMeta.TorrentFile tf = meta.files.get(i);
                long fileStart = tf.offset;
                long fileEnd = tf.offset + tf.length;
                if (fileEnd <= pieceStart || fileStart >= pieceEnd) {
                    continue;
                }
                long overlapStart = Math.max(pieceStart, fileStart);
                long overlapEnd = Math.min(pieceEnd, fileEnd);
                int len = (int) (overlapEnd - overlapStart);
                if (len <= 0) continue;

                int srcPos = (int) (overlapStart - pieceStart);
                long filePos = overlapStart - fileStart;

                RandomAccessFile raf = rafs.get(i);
                synchronized (locks.get(i)) {
                    raf.seek(filePos);
                    raf.write(pieceBytes, srcPos, len);
                }
            }
        }

        @Override
        public void close() throws IOException {
            IOException first = null;
            for (RandomAccessFile raf : rafs) {
                try {
                    raf.close();
                } catch (IOException e) {
                    if (first == null) first = e;
                }
            }
            if (first != null) throw first;
        }
    }

    private static byte[] generatePeerId() {
        byte[] id = new byte[20];
        byte[] prefix = "-XL0200-".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(prefix, 0, id, 0, prefix.length);
        final String alpha = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        for (int i = prefix.length; i < 20; i++) {
            id[i] = (byte) alpha.charAt(RANDOM.nextInt(alpha.length()));
        }
        return id;
    }

    private static void readFully(
            InputStream in,
            byte[] dest,
            int offset,
            int len,
            Net.Cancellation cancel
    ) throws IOException {
        int remaining = len;
        int pos = offset;
        while (remaining > 0) {
            Net.check(cancel);
            int n = in.read(dest, pos, remaining);
            if (n < 0) {
                throw new EOFException("Unexpected EOF while reading HTTP range body");
            }
            pos += n;
            remaining -= n;
        }
    }

    private static void skipFully(InputStream in, long bytes, Net.Cancellation cancel) throws IOException {
        long remaining = bytes;
        byte[] skipBuf = new byte[8192];
        while (remaining > 0) {
            Net.check(cancel);
            int toRead = (int) Math.min(skipBuf.length, remaining);
            int n = in.read(skipBuf, 0, toRead);
            if (n < 0) {
                throw new EOFException("Unexpected EOF while skipping HTTP body");
            }
            remaining -= n;
        }
    }

    private static boolean allTrue(boolean[] flags) {
        for (boolean f : flags) {
            if (!f) return false;
        }
        return true;
    }
}
