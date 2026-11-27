package app.xingluo.video.engine;

import app.xingluo.video.testutil.MiniHttpServer;
import app.xingluo.video.testutil.MiniHttpServer.HttpExchange;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Unit tests for SegmentedDownloader, HlsDownloader, and TsToMp4Muxer (MPEG-TS & FLV).
 */
public class EngineUnitTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private MiniHttpServer server;
    private String baseUrl;

    @Before
    public void setUp() throws IOException {
        HttpClient.setCookieBridgeForTest(new HttpClient.CookieStoreBridge() {
            @Override public String getCookie(String url) { return null; }
            @Override public void setCookie(String url, String setCookieHeader) {}
        });
        server = MiniHttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        HttpClient.setCookieBridgeForTest(null);
    }

    @Test
    public void parsesMasterAndMediaPlaylistsAndAes128Iv() throws Exception {
        String masterText = ""
                + "#EXTM3U\n"
                + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aac\",NAME=\"Main\",DEFAULT=YES,URI=\"audio/index.m3u8\"\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS=\"avc1.4d401e,mp4a.40.2\",AUDIO=\"aac\"\n"
                + "360p/index.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=3500000,RESOLUTION=1920x1080,CODECS=\"avc1.640028,mp4a.40.2\",AUDIO=\"aac\"\n"
                + "1080p/index.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=7500000,RESOLUTION=3840x2160,CODECS=\"avc1.640033,mp4a.40.2\",AUDIO=\"aac\"\n"
                + "2160p/index.m3u8\n";

        HlsDownloader.MasterPlaylist master = HlsDownloader.parseMasterPlaylist(masterText, baseUrl + "/live/master.m3u8");
        assertEquals(3, master.variants.size());
        assertEquals(1, master.audioRenditions.size());

        HlsDownloader.Variant best1080 = master.selectBest(1080);
        assertNotNull(best1080);
        assertEquals(baseUrl + "/live/1080p/index.m3u8", best1080.uri());
        assertEquals(1920, best1080.width());
        assertEquals(1080, best1080.height());
        assertEquals("avc1.640028,mp4a.40.2", best1080.codecs());

        HlsDownloader.AudioRendition audio = master.findAudio(best1080.audioGroupId());
        assertNotNull(audio);
        assertEquals(baseUrl + "/live/audio/index.m3u8", audio.uri());

        String mediaText = ""
                + "#EXTM3U\n"
                + "#EXT-X-TARGETDURATION:6\n"
                + "#EXT-X-MEDIA-SEQUENCE:42\n"
                + "#EXT-X-MAP:URI=\"init.mp4\"\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"https://keys.example/k1\"\n"
                + "#EXTINF:4.500,seg0\n"
                + "seg0.m4s\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"https://keys.example/k2\",IV=0x000000000000000000000000000000ff\n"
                + "#EXTINF:5.000,seg1\n"
                + "seg1.m4s\n"
                + "#EXT-X-ENDLIST\n";

        HlsDownloader.MediaPlaylist vod = HlsDownloader.parseMediaPlaylist(mediaText, baseUrl + "/live/1080p/index.m3u8");
        assertFalse(vod.isLive);
        assertEquals(42L, vod.mediaSequence);
        assertEquals(6.0, vod.targetDurationSec, 0.01);
        assertNotNull(vod.initMap);
        assertEquals(baseUrl + "/live/1080p/init.mp4", vod.initMap.uri());
        assertEquals(2, vod.segments.size());

        HlsDownloader.Segment s0 = vod.segments.get(0);
        assertEquals(42L, s0.sequence());
        byte[] iv0 = HlsDownloader.deriveIv(s0.key(), s0.sequence());
        assertEquals(16, iv0.length);
        assertEquals(42, iv0[15] & 0xFF);
        for (int i = 0; i < 15; i++) {
            assertEquals(0, iv0[i]);
        }

        HlsDownloader.Segment s1 = vod.segments.get(1);
        assertEquals(43L, s1.sequence());
        byte[] iv1 = HlsDownloader.deriveIv(s1.key(), s1.sequence());
        assertEquals(0xFF, iv1[15] & 0xFF);

        // Verify AES-128 decryption round-trip
        byte[] aesKey = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        byte[] plain = "synthetic-hls-segment-payload-12345".getBytes(StandardCharsets.UTF_8);
        Cipher enc = Cipher.getInstance("AES/CBC/PKCS5Padding");
        enc.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(iv0));
        byte[] encrypted = enc.doFinal(plain);
        byte[] decrypted = HlsDownloader.decryptAes128(encrypted, aesKey, iv0);
        assertArrayEquals(plain, decrypted);

        // Live playlist (no #EXT-X-ENDLIST)
        String liveText = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:100\n#EXTINF:4.0,\nseg100.ts\n";
        HlsDownloader.MediaPlaylist live = HlsDownloader.parseMediaPlaylist(liveText, baseUrl + "/live.m3u8");
        assertTrue(live.isLive);

        // DRM rejection
        String drmText = "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://drm\"\n#EXTINF:4.0,\nseg.ts\n#EXT-X-ENDLIST\n";
        try {
            HlsDownloader.parseMediaPlaylist(drmText, baseUrl + "/drm.m3u8");
            fail("Expected SAMPLE-AES DRM playlist to be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("DRM"));
        }

        // End-to-end AES-128 fMP4 HLS download against local HttpServer
        byte[] initBytes = new byte[]{0x00, 0x00, 0x00, 0x08, 'f', 't', 'y', 'p'};
        byte[] segPlain = new byte[]{0x00, 0x00, 0x00, 0x08, 'm', 'd', 'a', 't'};
        byte[] segEnc = enc.doFinal(segPlain);
        server.createContext("/hls/key.bin", ex -> sendBytes(ex, 200, "application/octet-stream", aesKey));
        server.createContext("/hls/init.mp4", ex -> sendBytes(ex, 200, "video/mp4", initBytes));
        server.createContext("/hls/seg42.m4s", ex -> sendBytes(ex, 200, "video/iso.segment", segEnc));
        server.createContext("/hls/play.m3u8", ex -> {
            String body = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:42\n"
                    + "#EXT-X-MAP:URI=\"/hls/init.mp4\"\n"
                    + "#EXT-X-KEY:METHOD=AES-128,URI=\"/hls/key.bin\"\n"
                    + "#EXTINF:4.0,\n/hls/seg42.m4s\n#EXT-X-ENDLIST\n";
            sendBytes(ex, 200, "application/vnd.apple.mpegurl", body.getBytes(StandardCharsets.UTF_8));
        });

        File hlsOut = tempFolder.newFile("hls_fmp4.mp4");
        File hlsWork = tempFolder.newFolder("hls_work");
        HlsDownloader.download(baseUrl + "/hls/play.m3u8", null, hlsOut, hlsWork, 1080, () -> false, null);
        byte[] expectedCombined = new byte[initBytes.length + segPlain.length];
        System.arraycopy(initBytes, 0, expectedCombined, 0, initBytes.length);
        System.arraycopy(segPlain, 0, expectedCombined, initBytes.length, segPlain.length);
        assertArrayEquals(expectedCombined, Files.readAllBytes(hlsOut.toPath()));
    }

    @Test
    public void demuxesSyntheticMpegTsPackets() throws Exception {
        RecordingSink sink = new RecordingSink();
        TsToMp4Muxer.TsDemuxer demuxer = new TsToMp4Muxer.TsDemuxer(sink);

        final int pmtPid = 0x1000;
        final int videoPid = 0x0100;
        final int audioPid = 0x0101;

        byte[] pat = buildPatPacket(pmtPid);
        byte[] pmt = buildPmtPacket(pmtPid, videoPid, TsToMp4Muxer.STREAM_TYPE_H264,
                audioPid, TsToMp4Muxer.STREAM_TYPE_AAC_ADTS);

        // Synthetic H.264 SPS (baseline), PPS, and IDR slice
        byte[] sps = new byte[]{0x67, 0x42, (byte) 0xC0, 0x1E, (byte) 0xD9, 0x00, (byte) 0xA0, 0x47, (byte) 0xFE, (byte) 0x88};
        byte[] pps = new byte[]{0x68, (byte) 0xCE, 0x3C, (byte) 0x80};
        byte[] idr = new byte[]{0x65, (byte) 0x88, (byte) 0x84, 0x21, 0x11, 0x22, 0x33};

        ByteArrayOutputStream annexB = new ByteArrayOutputStream();
        annexB.write(new byte[]{0, 0, 0, 1}); annexB.write(sps);
        annexB.write(new byte[]{0, 0, 0, 1}); annexB.write(pps);
        annexB.write(new byte[]{0, 0, 0, 1}); annexB.write(idr);

        long videoPts90k = 90_000L; // 1_000_000 us
        byte[] videoPes = buildPesPacket(0xE0, videoPts90k, annexB.toByteArray());
        byte[] videoTs = buildTsPacket(videoPid, true, videoPes);

        // Synthetic ADTS AAC frame: profile=AAC-LC(1 -> objType 2), freqIdx=4 (44100Hz), chan=2
        byte[] rawAac = new byte[]{0x21, 0x10, 0x04, 0x60, 0x00, 0x1C};
        int adtsLen = 7 + rawAac.length;
        byte[] adts = new byte[adtsLen];
        adts[0] = (byte) 0xFF;
        adts[1] = (byte) 0xF1; // MPEG-4, no CRC
        adts[2] = (byte) ((1 << 6) | (4 << 2) | 0); // profile=1 (AAC-LC), freqIdx=4, chan bit2=0
        adts[3] = (byte) ((2 << 6) | ((adtsLen >> 11) & 0x03));
        adts[4] = (byte) ((adtsLen >> 3) & 0xFF);
        adts[5] = (byte) (((adtsLen & 0x07) << 5) | 0x1F);
        adts[6] = (byte) 0xFC;
        System.arraycopy(rawAac, 0, adts, 7, rawAac.length);

        long audioPts90k = 180_000L; // 2_000_000 us
        byte[] audioPes = buildPesPacket(0xC0, audioPts90k, adts);
        byte[] audioTs = buildTsPacket(audioPid, true, audioPes);

        demuxer.feed(pat, 0, pat.length);
        demuxer.feed(pmt, 0, pmt.length);
        demuxer.feed(videoTs, 0, videoTs.length);
        demuxer.feed(audioTs, 0, audioTs.length);
        demuxer.flush();

        assertEquals("video/avc", sink.videoMime);
        assertNotNull(sink.videoCsd0);
        assertNotNull(sink.videoCsd1);
        assertEquals(4 + sps.length, sink.videoCsd0.length);
        assertEquals(4 + pps.length, sink.videoCsd1.length);

        assertEquals("audio/mp4a-latm", sink.audioMime);
        assertEquals(44100, sink.audioSampleRate);
        assertEquals(2, sink.audioChannels);
        assertArrayEquals(new byte[]{0x12, 0x10}, sink.audioAsc);

        assertEquals(1, sink.videoSamples.size());
        RecordedSample vs = sink.videoSamples.get(0);
        assertTrue(vs.keyFrame);
        assertEquals(1_000_000L, vs.ptsUs);
        // 4-byte length-prefixed IDR NALU
        assertEquals(4 + idr.length, vs.data.length);
        assertEquals(idr.length, vs.data[3] & 0xFF);
        assertEquals(0x65, vs.data[4] & 0xFF);

        assertEquals(1, sink.audioSamples.size());
        RecordedSample as = sink.audioSamples.get(0);
        assertEquals(2_000_000L, as.ptsUs);
        assertArrayEquals(rawAac, as.data);
    }

    @Test
    public void demuxesSyntheticFlvStream() throws Exception {
        byte[] sps = new byte[]{0x67, 0x42, (byte) 0xC0, 0x1E};
        byte[] pps = new byte[]{0x68, (byte) 0xCE, 0x3C, (byte) 0x80};
        byte[] idr = new byte[]{0x65, 0x11, 0x22, 0x33, 0x44};
        byte[] rawAac = new byte[]{0x21, 0x10, 0x05, 0x70};

        ByteArrayOutputStream flv = new ByteArrayOutputStream();
        // FLV header (9 bytes) + PreviousTagSize0 (4 bytes)
        flv.write(new byte[]{'F', 'L', 'V', 1, 5, 0, 0, 0, 9});
        writeBe32(flv, 0);

        // Tag 1: Audio AAC Sequence Header (asc = 0x12, 0x10 -> 44100Hz stereo)
        writeFlvTag(flv, 8, 0, new byte[]{(byte) 0xAF, 0x00, 0x12, 0x10});

        // Tag 2: Video AVC Sequence Header (AVCC)
        ByteArrayOutputStream avcc = new ByteArrayOutputStream();
        avcc.write(new byte[]{0x17, 0x00, 0x00, 0x00, 0x00}); // keyframe + AVC + seq header + cts=0
        avcc.write(new byte[]{0x01, 0x42, (byte) 0xC0, 0x1E, (byte) 0xFF, (byte) 0xE1});
        avcc.write(0); avcc.write(sps.length); avcc.write(sps);
        avcc.write(1);
        avcc.write(0); avcc.write(pps.length); avcc.write(pps);
        writeFlvTag(flv, 9, 0, avcc.toByteArray());

        // Tag 3: Video AVC NALU (timestamp 123 ms, cts 10 ms)
        ByteArrayOutputStream videoNaluTag = new ByteArrayOutputStream();
        videoNaluTag.write(new byte[]{0x17, 0x01, 0x00, 0x00, 0x0A});
        writeBe32(videoNaluTag, idr.length);
        videoNaluTag.write(idr);
        writeFlvTag(flv, 9, 123, videoNaluTag.toByteArray());

        // Tag 4: Audio Raw AAC (timestamp 200 ms)
        ByteArrayOutputStream audioRawTag = new ByteArrayOutputStream();
        audioRawTag.write(new byte[]{(byte) 0xAF, 0x01});
        audioRawTag.write(rawAac);
        writeFlvTag(flv, 8, 200, audioRawTag.toByteArray());

        RecordingSink sink = new RecordingSink();
        long readBytes = TsToMp4Muxer.FlvReader.demux(
                new ByteArrayInputStream(flv.toByteArray()),
                sink,
                () -> false,
                () -> false,
                null
        );
        assertEquals(flv.size(), readBytes);
        assertEquals("video/avc", sink.videoMime);
        assertEquals("audio/mp4a-latm", sink.audioMime);
        assertEquals(44100, sink.audioSampleRate);
        assertEquals(2, sink.audioChannels);

        assertEquals(1, sink.videoSamples.size());
        assertTrue(sink.videoSamples.get(0).keyFrame);
        assertEquals(133_000L, sink.videoSamples.get(0).ptsUs);

        assertEquals(1, sink.audioSamples.size());
        assertEquals(200_000L, sink.audioSamples.get(0).ptsUs);
        assertArrayEquals(rawAac, sink.audioSamples.get(0).data);
    }

    @Test
    public void persistsResumeMetaAndDownloadsSegmentedWithResumeAndFallback() throws Exception {
        // 1. ResumeMeta JSON persistence round-trip
        File resumeDir = tempFolder.newFolder("resume");
        File metaFile = new File(resumeDir, "sample.mp4.part.meta");
        List<SegmentedDownloader.ChunkState> chunks = List.of(
                new SegmentedDownloader.ChunkState(0, 0, 262143, 262144),
                new SegmentedDownloader.ChunkState(1, 262144, 524287, 1000)
        );
        SegmentedDownloader.ResumeMeta original = new SegmentedDownloader.ResumeMeta(
                baseUrl + "/video.bin", "\"etag-v1\"", 524288L, 2, chunks);
        original.save(metaFile);

        SegmentedDownloader.ResumeMeta loaded = SegmentedDownloader.ResumeMeta.load(metaFile);
        assertEquals(original.url, loaded.url);
        assertEquals("\"etag-v1\"", loaded.etag);
        assertEquals(524288L, loaded.totalBytes);
        assertEquals(2, loaded.chunks.size());
        assertEquals(262144L, loaded.chunks.get(0).done.get());
        assertEquals(1000L, loaded.chunks.get(1).done.get());

        // Deterministic 512 KB payload
        final byte[] payload = new byte[512 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ((i * 31 + 7) & 0xFF);
        }

        // 2. Range 206 multi-thread download
        AtomicInteger rangeRequests = new AtomicInteger();
        server.createContext("/range-video", ex -> {
            if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.getResponseHeaders().set("Accept-Ranges", "bytes");
                ex.getResponseHeaders().set("ETag", "\"v512k\"");
                ex.getResponseHeaders().set("Content-Type", "video/mp4");
                ex.getResponseHeaders().set("Content-Length", String.valueOf(payload.length));
                ex.sendResponseHeaders(200, -1);
                ex.close();
                return;
            }
            String range = ex.getRequestHeaders().getFirst("Range");
            if (range != null && range.startsWith("bytes=")) {
                rangeRequests.incrementAndGet();
                String[] parts = range.substring(6).split("-", 2);
                int start = Integer.parseInt(parts[0]);
                int end = parts[1].isEmpty() ? payload.length - 1 : Math.min(payload.length - 1, Integer.parseInt(parts[1]));
                int len = end - start + 1;
                ex.getResponseHeaders().set("Accept-Ranges", "bytes");
                ex.getResponseHeaders().set("ETag", "\"v512k\"");
                ex.getResponseHeaders().set("Content-Type", "video/mp4");
                ex.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + payload.length);
                ex.sendResponseHeaders(206, len);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(payload, start, len);
                }
            } else {
                sendBytes(ex, 200, "video/mp4", payload);
            }
        });

        File outMulti = new File(tempFolder.getRoot(), "multi.mp4");
        List<Long> reportedDone = new CopyOnWriteArrayList<>();
        SegmentedDownloader.download(
                baseUrl + "/range-video",
                null,
                outMulti,
                resumeDir,
                4,
                () -> false,
                (done, total, speed, active) -> reportedDone.add(done)
        );
        assertArrayEquals(payload, Files.readAllBytes(outMulti.toPath()));
        assertTrue(rangeRequests.get() >= 4);
        assertFalse(reportedDone.isEmpty());
        assertEquals(Long.valueOf(payload.length), reportedDone.get(reportedDone.size() - 1));
        File[] leftOver = SegmentedDownloader.resumeFiles(outMulti, resumeDir, baseUrl + "/range-video");
        assertFalse(leftOver[0].exists());
        assertFalse(leftOver[1].exists());

        // 3. Resume from pre-populated .part + .part.meta (first 3 chunks already done)
        File outResume = new File(tempFolder.getRoot(), "resumed.mp4");
        File[] rf = SegmentedDownloader.resumeFiles(outResume, resumeDir, baseUrl + "/range-video");
        try (RandomAccessFile raf = new RandomAccessFile(rf[0], "rw")) {
            raf.setLength(payload.length);
            raf.write(payload, 0, 384 * 1024);
        }
        int chunkSize = payload.length / 4;
        List<SegmentedDownloader.ChunkState> preChunks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            long s = (long) i * chunkSize;
            long e = (i == 3) ? payload.length - 1L : (s + chunkSize - 1L);
            long d = (i < 3) ? (e - s + 1L) : 0L;
            preChunks.add(new SegmentedDownloader.ChunkState(i, s, e, d));
        }
        new SegmentedDownloader.ResumeMeta(baseUrl + "/range-video", "\"v512k\"", payload.length, 4, preChunks).save(rf[1]);

        int beforeResumeReqs = rangeRequests.get();
        SegmentedDownloader.download(baseUrl + "/range-video", null, outResume, resumeDir, 4, () -> false, null);
        assertArrayEquals(payload, Files.readAllBytes(outResume.toPath()));
        // Only chunk 3 (1 request) was needed beyond any head check
        assertEquals(1, rangeRequests.get() - beforeResumeReqs);

        // 4. Fallback to 200 OK single-stream when server does not support Range
        server.createContext("/no-range-video", ex -> {
            if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.getResponseHeaders().set("Content-Type", "video/mp4");
                ex.getResponseHeaders().set("Content-Length", String.valueOf(payload.length));
                ex.sendResponseHeaders(200, -1);
                ex.close();
                return;
            }
            sendBytes(ex, 200, "video/mp4", payload);
        });
        File outFallback = new File(tempFolder.getRoot(), "fallback200.mp4");
        SegmentedDownloader.download(baseUrl + "/no-range-video", null, outFallback, resumeDir, 4, () -> false, null);
        assertArrayEquals(payload, Files.readAllBytes(outFallback.toPath()));

        // 5. Rejects text/html error payload
        server.createContext("/html-error", ex -> {
            byte[] html = "<html><body>403</body></html>".getBytes(StandardCharsets.UTF_8);
            sendBytes(ex, 200, "text/html; charset=utf-8", html);
        });
        File outErr = new File(tempFolder.getRoot(), "err.mp4");
        try {
            SegmentedDownloader.download(baseUrl + "/html-error", null, outErr, resumeDir, 4, () -> false, null);
            fail("Expected text/html payload to be rejected");
        } catch (IOException expected) {
            assertFalse(outErr.exists());
        }
    }

    // ---- Helpers ----

    private static void sendBytes(HttpExchange ex, int status, String contentType, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Content-Length", String.valueOf(data.length));
            ex.sendResponseHeaders(status, -1);
            ex.close();
            return;
        }
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }

    private static byte[] buildPatPacket(int pmtPid) {
        byte[] pkt = new byte[188];
        Arrays.fill(pkt, (byte) 0xFF);
        pkt[0] = 0x47;
        pkt[1] = 0x40; // PUSI=1, PID=0
        pkt[2] = 0x00;
        pkt[3] = 0x10; // payload only
        pkt[4] = 0x00; // pointer_field
        int sectionLen = 5 + 4 + 4; // 5 bytes table header + 4 bytes program entry + 4 bytes CRC
        pkt[5] = 0x00; // table_id = PAT
        pkt[6] = (byte) (0xB0 | ((sectionLen >> 8) & 0x0F));
        pkt[7] = (byte) (sectionLen & 0xFF);
        pkt[8] = 0x00; pkt[9] = 0x01; // transport_stream_id
        pkt[10] = (byte) 0xC1;
        pkt[11] = 0x00; pkt[12] = 0x00;
        // Program 1 -> pmtPid
        pkt[13] = 0x00; pkt[14] = 0x01;
        pkt[15] = (byte) (0xE0 | ((pmtPid >> 8) & 0x1F));
        pkt[16] = (byte) (pmtPid & 0xFF);
        return pkt;
    }

    private static byte[] buildPmtPacket(int pmtPid, int vPid, int vType, int aPid, int aType) {
        byte[] pkt = new byte[188];
        Arrays.fill(pkt, (byte) 0xFF);
        pkt[0] = 0x47;
        pkt[1] = (byte) (0x40 | ((pmtPid >> 8) & 0x1F));
        pkt[2] = (byte) (pmtPid & 0xFF);
        pkt[3] = 0x10;
        pkt[4] = 0x00; // pointer_field
        int sectionLen = 9 + 5 + 5 + 4; // 9 bytes PMT header + 2x5 ES entries + 4 CRC
        pkt[5] = 0x02; // table_id = PMT
        pkt[6] = (byte) (0xB0 | ((sectionLen >> 8) & 0x0F));
        pkt[7] = (byte) (sectionLen & 0xFF);
        pkt[8] = 0x00; pkt[9] = 0x01; // program_number
        pkt[10] = (byte) 0xC1;
        pkt[11] = 0x00; pkt[12] = 0x00;
        pkt[13] = (byte) (0xE0 | ((vPid >> 8) & 0x1F));
        pkt[14] = (byte) (vPid & 0xFF);
        pkt[15] = (byte) 0xF0; pkt[16] = 0x00; // program_info_length=0
        // ES 1: Video
        pkt[17] = (byte) vType;
        pkt[18] = (byte) (0xE0 | ((vPid >> 8) & 0x1F));
        pkt[19] = (byte) (vPid & 0xFF);
        pkt[20] = (byte) 0xF0; pkt[21] = 0x00;
        // ES 2: Audio
        pkt[22] = (byte) aType;
        pkt[23] = (byte) (0xE0 | ((aPid >> 8) & 0x1F));
        pkt[24] = (byte) (aPid & 0xFF);
        pkt[25] = (byte) 0xF0; pkt[26] = 0x00;
        return pkt;
    }

    private static byte[] buildPesPacket(int streamId, long pts90k, byte[] esPayload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x00); out.write(0x00); out.write(0x01);
        out.write(streamId);
        int pesLen = 3 + 5 + esPayload.length;
        out.write((pesLen >> 8) & 0xFF);
        out.write(pesLen & 0xFF);
        out.write(0x80); // flags1
        out.write(0x80); // PTS present
        out.write(5);    // header_data_length
        out.write((int) (0x21 | (((pts90k >> 30) & 0x07) << 1)));
        out.write((int) ((pts90k >> 22) & 0xFF));
        out.write((int) (0x01 | (((pts90k >> 15) & 0x7F) << 1)));
        out.write((int) ((pts90k >> 7) & 0xFF));
        out.write((int) (0x01 | ((pts90k & 0x7F) << 1)));
        out.write(esPayload);
        return out.toByteArray();
    }

    private static byte[] buildTsPacket(int pid, boolean pusi, byte[] payload) {
        byte[] pkt = new byte[188];
        pkt[0] = 0x47;
        pkt[1] = (byte) ((pusi ? 0x40 : 0x00) | ((pid >> 8) & 0x1F));
        pkt[2] = (byte) (pid & 0xFF);
        int maxPayload = 184;
        if (payload.length < maxPayload) {
            int afTotal = maxPayload - payload.length;
            pkt[3] = 0x30; // adaptation + payload
            pkt[4] = (byte) (afTotal - 1);
            if (afTotal > 1) {
                pkt[5] = 0x00;
                Arrays.fill(pkt, 6, 4 + afTotal, (byte) 0xFF);
            }
            System.arraycopy(payload, 0, pkt, 4 + afTotal, payload.length);
        } else {
            pkt[3] = 0x10;
            System.arraycopy(payload, 0, pkt, 4, maxPayload);
        }
        return pkt;
    }

    private static void writeFlvTag(ByteArrayOutputStream out, int tagType, long timestampMs, byte[] payload) throws IOException {
        out.write(tagType & 0xFF);
        int size = payload.length;
        out.write((size >> 16) & 0xFF);
        out.write((size >> 8) & 0xFF);
        out.write(size & 0xFF);
        out.write((int) ((timestampMs >> 16) & 0xFF));
        out.write((int) ((timestampMs >> 8) & 0xFF));
        out.write((int) (timestampMs & 0xFF));
        out.write((int) ((timestampMs >> 24) & 0xFF));
        out.write(0); out.write(0); out.write(0); // streamId
        out.write(payload);
        writeBe32(out, 11 + size);
    }

    private static void writeBe32(ByteArrayOutputStream out, int value) {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private record RecordedSample(byte[] data, long ptsUs, boolean keyFrame) {}

    private static final class RecordingSink implements TsToMp4Muxer.SampleSink {
        String videoMime;
        int videoWidth;
        int videoHeight;
        byte[] videoCsd0;
        byte[] videoCsd1;
        String audioMime;
        int audioSampleRate;
        int audioChannels;
        byte[] audioAsc;
        final List<RecordedSample> videoSamples = new ArrayList<>();
        final List<RecordedSample> audioSamples = new ArrayList<>();

        @Override
        public void onVideoConfig(String mime, int width, int height, byte[] csd0, byte[] csd1) {
            this.videoMime = mime;
            this.videoWidth = width;
            this.videoHeight = height;
            this.videoCsd0 = csd0;
            this.videoCsd1 = csd1;
        }

        @Override
        public void onAudioConfig(String mime, int sampleRate, int channelCount, byte[] asc) {
            this.audioMime = mime;
            this.audioSampleRate = sampleRate;
            this.audioChannels = channelCount;
            this.audioAsc = asc;
        }

        @Override
        public void onVideoSample(byte[] data, int offset, int length, long ptsUs, boolean isKeyFrame) {
            videoSamples.add(new RecordedSample(Arrays.copyOfRange(data, offset, offset + length), ptsUs, isKeyFrame));
        }

        @Override
        public void onAudioSample(byte[] data, int offset, int length, long ptsUs) {
            audioSamples.add(new RecordedSample(Arrays.copyOfRange(data, offset, offset + length), ptsUs, true));
        }

        @Override
        public void close() {}
    }
}
