package app.xingluo.video.engine.torrent;

import app.xingluo.video.testutil.MiniHttpServer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

public class TorrentTest {

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void bencodeRoundTripAndInvalidInputs() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("z_key", 42L);
        nested.put("a_key", List.of("hello", new byte[]{0x00, (byte) 0xff, 0x10}, 0L, -99L));

        byte[] encoded = Bencode.encode(nested);
        // Keys must be canonically sorted: a_key before z_key
        String asciiPrefix = new String(encoded, StandardCharsets.ISO_8859_1);
        assertTrue(asciiPrefix.indexOf("5:a_key") < asciiPrefix.indexOf("5:z_key"));

        Map<String, Object> decoded = Bencode.asDict(Bencode.decode(encoded));
        assertNotNull(decoded);
        assertEquals(42L, Bencode.asLong(decoded.get("z_key")).longValue());

        List<Object> list = Bencode.asList(decoded.get("a_key"));
        assertNotNull(list);
        assertEquals(4, list.size());
        assertEquals("hello", Bencode.asString(list.get(0)));
        assertArrayEquals(new byte[]{0x00, (byte) 0xff, 0x10}, Bencode.asBytes(list.get(1)));
        assertEquals(0L, Bencode.asLong(list.get(2)).longValue());
        assertEquals(-99L, Bencode.asLong(list.get(3)).longValue());

        assertThrows(IllegalArgumentException.class, () -> Bencode.decode("i-0e".getBytes(StandardCharsets.US_ASCII)));
        assertThrows(IllegalArgumentException.class, () -> Bencode.decode("i01e".getBytes(StandardCharsets.US_ASCII)));
        assertThrows(IllegalArgumentException.class, () -> Bencode.decode("4:ab".getBytes(StandardCharsets.US_ASCII)));
        assertThrows(IllegalArgumentException.class, () -> Bencode.decode("d3:keye".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void extractInfoSha1MatchesRawInfoDictionary() throws Exception {
        byte[] payload = "sample torrent content".getBytes(StandardCharsets.UTF_8);
        byte[] pieceHash = MessageDigest.getInstance("SHA-1").digest(payload);

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "demo.mp4");
        info.put("length", (long) payload.length);
        info.put("piece length", 16384L);
        info.put("pieces", pieceHash);

        byte[] rawInfo = Bencode.encode(info);
        byte[] expectedInfoHash = MessageDigest.getInstance("SHA-1").digest(rawInfo);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("announce", "https://tracker.example.com/announce");
        root.put("info", info);

        byte[] torrentBytes = Bencode.encode(root);
        byte[] actualInfoHash = Bencode.extractInfoSha1(torrentBytes);

        assertArrayEquals(expectedInfoHash, actualInfoHash);
    }

    @Test
    public void parsesSingleFileTorrent() throws Exception {
        byte[] data = new byte[20000];
        new Random(12345L).nextBytes(data);
        byte[] torrentBytes = buildSingleFileTorrent(
                "episode01.mp4",
                data,
                16384,
                "https://tracker1.example/announce",
                List.of(List.of("udp://tracker2.example:6969/announce")),
                List.of("https://cdn.example.com/episode01.mp4")
        );

        TorrentMeta meta = TorrentMeta.fromTorrentBytes(torrentBytes);
        assertTrue(meta.singleFile);
        assertTrue(meta.hasMetadata());
        assertEquals("episode01.mp4", meta.name);
        assertEquals(20000L, meta.totalSize());
        assertEquals(16384L, meta.pieceLength);
        assertEquals(2, meta.pieceHashes.size());
        assertEquals(2, meta.trackers.size());
        assertEquals(List.of("https://cdn.example.com/episode01.mp4"), meta.webSeeds);
        assertEquals(1, meta.largestPlayableOrAllFiles().size());
        assertEquals("episode01.mp4", meta.largestPlayableOrAllFiles().get(0).name);
    }

    @Test
    public void parsesMultiFileTorrentAndSelectsLargestPlayable() throws Exception {
        byte[] f1 = new byte[200];
        byte[] f2 = new byte[1024];
        byte[] f3 = new byte[8192];
        byte[] f4 = new byte[512];

        ByteArrayOutputStream combined = new ByteArrayOutputStream();
        combined.write(f1);
        combined.write(f2);
        combined.write(f3);
        combined.write(f4);
        byte[] allBytes = combined.toByteArray();
        byte[] pieces = computePieces(allBytes, 4096);

        Map<String, Object> file1 = Map.of("length", 200L, "path", List.of("readme.txt"));
        Map<String, Object> file2 = Map.of("length", 1024L, "path", List.of("extras", "sample.mp4"));
        Map<String, Object> file3 = Map.of("length", 8192L, "path", List.of("..", "main", "movie.mkv"));
        Map<String, Object> file4 = Map.of("length", 512L, "path", List.of("poster.jpg"));

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", "Collection");
        info.put("piece length", 4096L);
        info.put("pieces", pieces);
        info.put("files", List.of(file1, file2, file3, file4));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("url-list", "https://seed.example.org/files/");
        root.put("info", info);

        TorrentMeta meta = TorrentMeta.fromTorrentBytes(Bencode.encode(root));
        assertFalse(meta.singleFile);
        assertEquals(200L + 1024L + 8192L + 512L, meta.totalSize());
        assertEquals(4, meta.files.size());
        assertEquals(0L, meta.files.get(0).offset);
        assertEquals(200L, meta.files.get(1).offset);
        assertEquals(1224L, meta.files.get(2).offset);
        assertEquals("main/movie.mkv", meta.files.get(2).path);
        assertEquals(List.of("https://seed.example.org/files/"), meta.webSeeds);

        List<TorrentMeta.TorrentFile> playable = meta.largestPlayableOrAllFiles();
        assertEquals(2, playable.size());
        assertEquals("movie.mkv", playable.get(0).name);
        assertEquals("sample.mp4", playable.get(1).name);
    }

    @Test
    public void parsesMagnetHexAndBase32WithTrackersAndWebSeeds() {
        // 20 bytes: 0x01, 0x23, ..., 0x01..
        byte[] rawHash = Bencode.fromHex("0123456789abcdef0123456789abcdef01234567");
        String hex = Bencode.toHex(rawHash);
        // Base32 of 0123456789abcdef0123456789abcdef01234567 = AERUKZ4JVPOR6AJDUZTYTF6M44======
        String base32 = encodeBase32(rawHash);

        String hexMagnet = "magnet:?xt=urn:btih:" + hex
                + "&dn=Test%20Video.mp4"
                + "&tr=udp%3A%2F%2Ftracker.example%3A6969%2Fannounce"
                + "&tr=https%3A%2F%2Ftracker.example%2Fannounce"
                + "&ws=https%3A%2F%2Fcdn.example.com%2FTest%2520Video.mp4";
        TorrentMeta fromHex = TorrentMeta.fromMagnet(hexMagnet);

        assertEquals(hex, fromHex.infoHashHex);
        assertArrayEquals(rawHash, fromHex.infoHash());
        assertEquals("Test Video.mp4", fromHex.name);
        assertEquals(2, fromHex.trackers.size());
        assertEquals(List.of("https://cdn.example.com/Test%20Video.mp4"), fromHex.webSeeds);
        assertFalse(fromHex.hasMetadata());

        String base32Magnet = "magnet:?xt=urn:btih:" + base32 + "&dn=Base32%20Clip";
        TorrentMeta fromBase32 = TorrentMeta.fromMagnet(base32Magnet);

        assertEquals(hex, fromBase32.infoHashHex);
        assertArrayEquals(rawHash, fromBase32.infoHash());
        assertEquals("Base32 Clip", fromBase32.name);
    }

    @Test
    public void webSeedDownloadEndToEndViaLocalHttpServer() throws Exception {
        byte[] payload = new byte[100 * 1024 + 321];
        new Random(2026L).nextBytes(payload);

        MiniHttpServer server = MiniHttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/video.mp4", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range != null && range.startsWith("bytes=")) {
                String[] bounds = range.substring("bytes=".length()).split("-");
                int start = Integer.parseInt(bounds[0]);
                int end = bounds.length > 1 && !bounds[1].isEmpty()
                        ? Math.min(payload.length - 1, Integer.parseInt(bounds[1]))
                        : payload.length - 1;
                int len = end - start + 1;
                exchange.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + end + "/" + payload.length);
                exchange.getResponseHeaders().add("Accept-Ranges", "bytes");
                exchange.sendResponseHeaders(206, len);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(payload, start, len);
                }
            } else {
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(payload);
                }
            }
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            String wsUrl = "http://127.0.0.1:" + port + "/video.mp4";
            byte[] torrentBytes = buildSingleFileTorrent(
                    "downloaded_video.mp4",
                    payload,
                    16384,
                    null,
                    null,
                    List.of(wsUrl)
            );
            TorrentMeta meta = TorrentMeta.fromTorrentBytes(torrentBytes);

            File outDir = tempFolder.newFolder("webseed_out");
            AtomicLong lastDone = new AtomicLong(0);
            AtomicLong lastTotal = new AtomicLong(0);

            TorrentDownloader.DownloadResult result = TorrentDownloader.download(
                    meta,
                    outDir,
                    () -> false,
                    (done, total) -> {
                        lastDone.set(done);
                        lastTotal.set(total);
                    }
            );

            assertEquals("webseed", result.mode);
            assertEquals(payload.length, result.totalBytes);
            assertEquals(payload.length, lastDone.get());
            assertEquals(payload.length, lastTotal.get());
            File outFile = result.primaryFile();
            assertNotNull(outFile);
            assertTrue(outFile.isFile());
            assertArrayEquals(payload, Files.readAllBytes(outFile.toPath()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void webSeedDownloadDetectsCorruptedPiece() throws Exception {
        byte[] payload = new byte[32768];
        new Random(77L).nextBytes(payload);
        byte[] corrupted = payload.clone();
        corrupted[100] ^= 0x55; // flip bits so SHA-1 fails

        MiniHttpServer server = MiniHttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bad.mp4", exchange -> {
            exchange.sendResponseHeaders(200, corrupted.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(corrupted);
            }
        });
        server.start();

        try {
            int port = server.getAddress().getPort();
            byte[] torrentBytes = buildSingleFileTorrent(
                    "bad.mp4",
                    payload,
                    16384,
                    null,
                    null,
                    List.of("http://127.0.0.1:" + port + "/bad.mp4")
            );
            TorrentMeta meta = TorrentMeta.fromTorrentBytes(torrentBytes);
            File outDir = tempFolder.newFolder("bad_webseed");

            IOException ex = assertThrows(IOException.class, () -> TorrentDownloader.download(meta, outDir));
            assertTrue(ex.getMessage().contains("SHA-1"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void deadMagnetThrowsActionableMessage() throws Exception {
        TorrentMeta deadMagnet = TorrentMeta.fromMagnet(
                "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=DeadMagnet"
        );
        File outDir = tempFolder.newFolder("dead_magnet");
        IOException ex = assertThrows(IOException.class, () -> TorrentDownloader.download(deadMagnet, outDir));
        assertEquals(TorrentDownloader.NO_PEERS_OR_WEBSEED_MSG, ex.getMessage());
    }

    @Test
    public void httpTrackerAndPeerWireEndToEndDownload() throws Exception {
        byte[] payload = new byte[40000];
        new Random(999L).nextBytes(payload);
        int pieceLength = 16384;

        ServerSocket peerServer = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        int peerPort = peerServer.getLocalPort();

        MiniHttpServer trackerServer = MiniHttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] compactPeer = new byte[]{
                127, 0, 0, 1,
                (byte) ((peerPort >> 8) & 0xff),
                (byte) (peerPort & 0xff)
        };
        byte[] trackerResp = Bencode.encode(Map.of("interval", 60L, "peers", compactPeer));
        trackerServer.createContext("/announce", exchange -> {
            exchange.sendResponseHeaders(200, trackerResp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(trackerResp);
            }
        });
        trackerServer.start();

        int trackerPort = trackerServer.getAddress().getPort();
        byte[] torrentBytes = buildSingleFileTorrent(
                "peer_clip.mp4",
                payload,
                pieceLength,
                "http://127.0.0.1:" + trackerPort + "/announce",
                null,
                null
        );
        TorrentMeta meta = TorrentMeta.fromTorrentBytes(torrentBytes);

        Thread peerThread = new Thread(() -> {
            try (Socket client = peerServer.accept()) {
                DataInputStream in = new DataInputStream(client.getInputStream());
                DataOutputStream out = new DataOutputStream(client.getOutputStream());

                // Receive client handshake and reply with matching handshake
                byte[] clientHs = new byte[68];
                in.readFully(clientHs);
                byte[] serverHs = clientHs.clone();
                Arrays.fill(serverHs, 48, 68, (byte) 'P');
                out.write(serverHs);

                // Send bitfield (3 pieces -> top 3 bits of 1 byte = 0b11100000)
                out.writeInt(2);
                out.writeByte(5);
                out.writeByte(0b11100000);

                // Send unchoke (id=1)
                out.writeInt(1);
                out.writeByte(1);
                out.flush();

                while (true) {
                    int len = in.readInt();
                    if (len == 0) continue;
                    int id = in.readUnsignedByte();
                    byte[] body = new byte[len - 1];
                    if (body.length > 0) in.readFully(body);
                    if (id == 6 && body.length == 12) { // request
                        ByteBuffer bb = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
                        int pieceIdx = bb.getInt();
                        int begin = bb.getInt();
                        int reqLen = bb.getInt();
                        int globalOffset = pieceIdx * pieceLength + begin;

                        out.writeInt(9 + reqLen);
                        out.writeByte(7); // piece
                        out.writeInt(pieceIdx);
                        out.writeInt(begin);
                        out.write(payload, globalOffset, reqLen);
                        out.flush();
                    }
                }
            } catch (Exception ignored) {
                // Socket closed once client finishes downloading all pieces
            }
        });
        peerThread.setDaemon(true);
        peerThread.start();

        try {
            File outDir = tempFolder.newFolder("peer_out");
            TorrentDownloader.DownloadResult result = TorrentDownloader.download(meta, outDir);

            assertEquals("peer", result.mode);
            assertEquals(payload.length, result.totalBytes);
            File outFile = result.primaryFile();
            assertNotNull(outFile);
            assertArrayEquals(payload, Files.readAllBytes(outFile.toPath()));
        } finally {
            trackerServer.stop(0);
            peerServer.close();
        }
    }

    private static byte[] buildSingleFileTorrent(
            String name,
            byte[] payload,
            int pieceLength,
            String announce,
            List<List<String>> announceList,
            List<String> urlList
    ) throws Exception {
        byte[] pieces = computePieces(payload, pieceLength);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", name);
        info.put("length", (long) payload.length);
        info.put("piece length", (long) pieceLength);
        info.put("pieces", pieces);

        Map<String, Object> root = new LinkedHashMap<>();
        if (announce != null) root.put("announce", announce);
        if (announceList != null) root.put("announce-list", announceList);
        if (urlList != null) {
            root.put("url-list", urlList.size() == 1 ? urlList.get(0) : urlList);
        }
        root.put("info", info);
        return Bencode.encode(root);
    }

    private static byte[] computePieces(byte[] data, int pieceLength) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int offset = 0; offset < data.length; offset += pieceLength) {
            int len = Math.min(pieceLength, data.length - offset);
            sha1.reset();
            sha1.update(data, offset, len);
            out.write(sha1.digest());
        }
        return out.toByteArray();
    }

    private static String encodeBase32(byte[] data) {
        final char[] alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                sb.append(alphabet[(buffer >> (bitsLeft - 5)) & 0x1f]);
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            sb.append(alphabet[(buffer << (5 - bitsLeft)) & 0x1f]);
        }
        return sb.toString();
    }
}
