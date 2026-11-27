package app.xingluo.video.engine;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import app.xingluo.video.Net;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * Pure-Java MPEG-TS (188-byte packet) and HTTP-FLV demuxer + Android MediaMuxer MP4 remuxer.
 * Supports H.264 Annex-B (0x1B), HEVC (0x24), and ADTS AAC (0x0F) from MPEG-TS as well as
 * AVC + AAC elementary streams from HTTP-FLV.
 */
public final class TsToMp4Muxer {
    public static final int TS_PACKET_SIZE = 188;
    public static final int TS_SYNC_BYTE = 0x47;
    public static final int STREAM_TYPE_H264 = 0x1B;
    public static final int STREAM_TYPE_HEVC = 0x24;
    public static final int STREAM_TYPE_AAC_ADTS = 0x0F;

    private static final int[] AAC_SAMPLE_RATES = {
            96000, 88200, 64000, 48000, 44100, 32000,
            24000, 22050, 16000, 12000, 11025, 8000, 7350
    };

    private TsToMp4Muxer() {}

    public interface SampleSink extends AutoCloseable {
        void onVideoConfig(String mime, int width, int height, byte[] csd0, byte[] csd1);
        void onAudioConfig(String mime, int sampleRate, int channelCount, byte[] audioSpecificConfig);
        void onVideoSample(byte[] data, int offset, int length, long ptsUs, boolean isKeyFrame);
        void onAudioSample(byte[] data, int offset, int length, long ptsUs);
        @Override void close() throws IOException;
    }

    private record BufferedSample(boolean isVideo, byte[] data, long ptsUs, boolean keyFrame) {}

    /**
     * SampleSink backed by Android's {@link MediaMuxer}. Buffers early samples until both video
     * and audio configurations arrive (or single-track threshold is reached), then writes tracks
     * cleanly with monotonic timestamps.
     */
    public static final class MediaMuxerSink implements SampleSink {
        private static final int MAX_PENDING_SAMPLES = 256;

        private final File outputFile;
        private MediaMuxer muxer;
        private boolean started;
        private boolean closed;
        private int videoTrack = -1;
        private int audioTrack = -1;
        private MediaFormat pendingVideoFormat;
        private MediaFormat pendingAudioFormat;
        private long basePtsUs = Long.MIN_VALUE;
        private long lastVideoPtsUs = -1L;
        private long lastAudioPtsUs = -1L;
        private int writtenSamples = 0;
        private final List<BufferedSample> pending = new ArrayList<>();

        public MediaMuxerSink(File outputFile) {
            this.outputFile = outputFile;
        }

        public int sampleCount() {
            return writtenSamples + pending.size();
        }

        @Override
        public void onVideoConfig(String mime, int width, int height, byte[] csd0, byte[] csd1) {
            if (closed || pendingVideoFormat != null) {
                return;
            }
            MediaFormat fmt = MediaFormat.createVideoFormat(mime, Math.max(16, width), Math.max(16, height));
            if (csd0 != null && csd0.length > 0) {
                fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd0));
            }
            if (csd1 != null && csd1.length > 0) {
                fmt.setByteBuffer("csd-1", ByteBuffer.wrap(csd1));
            }
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024);
            pendingVideoFormat = fmt;
            tryStart();
        }

        @Override
        public void onAudioConfig(String mime, int sampleRate, int channelCount, byte[] asc) {
            if (closed || pendingAudioFormat != null) {
                return;
            }
            MediaFormat fmt = MediaFormat.createAudioFormat(
                    mime,
                    sampleRate > 0 ? sampleRate : 44100,
                    channelCount > 0 ? channelCount : 2
            );
            if (asc != null && asc.length > 0) {
                fmt.setByteBuffer("csd-0", ByteBuffer.wrap(asc));
            }
            fmt.setInteger(MediaFormat.KEY_IS_ADTS, 0);
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, 2);
            pendingAudioFormat = fmt;
            tryStart();
        }

        @Override
        public void onVideoSample(byte[] data, int offset, int length, long ptsUs, boolean isKeyFrame) {
            if (closed || length <= 0) {
                return;
            }
            byte[] copy = Arrays.copyOfRange(data, offset, offset + length);
            if (!started) {
                pending.add(new BufferedSample(true, copy, ptsUs, isKeyFrame));
                if (pending.size() >= MAX_PENDING_SAMPLES) {
                    forceStart();
                }
                return;
            }
            if (videoTrack >= 0) {
                writeSample(true, copy, ptsUs, isKeyFrame);
            }
        }

        @Override
        public void onAudioSample(byte[] data, int offset, int length, long ptsUs) {
            if (closed || length <= 0) {
                return;
            }
            byte[] copy = Arrays.copyOfRange(data, offset, offset + length);
            if (!started) {
                pending.add(new BufferedSample(false, copy, ptsUs, true));
                if (pending.size() >= MAX_PENDING_SAMPLES) {
                    forceStart();
                }
                return;
            }
            if (audioTrack >= 0) {
                writeSample(false, copy, ptsUs, true);
            }
        }

        private void tryStart() {
            if (!started && pendingVideoFormat != null && pendingAudioFormat != null) {
                forceStart();
            }
        }

        private void forceStart() {
            if (started || (pendingVideoFormat == null && pendingAudioFormat == null)) {
                return;
            }
            try {
                File parent = outputFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                if (pendingVideoFormat != null) {
                    videoTrack = muxer.addTrack(pendingVideoFormat);
                }
                if (pendingAudioFormat != null) {
                    audioTrack = muxer.addTrack(pendingAudioFormat);
                }
                muxer.start();
                started = true;
                for (BufferedSample s : pending) {
                    if (s.isVideo && videoTrack >= 0) {
                        writeSample(true, s.data, s.ptsUs, s.keyFrame);
                    } else if (!s.isVideo && audioTrack >= 0) {
                        writeSample(false, s.data, s.ptsUs, s.keyFrame);
                    }
                }
                pending.clear();
            } catch (Exception e) {
                pending.clear();
                releaseMuxerQuietly();
                throw new IllegalStateException("初始化 MP4 封装器失败: " + e.getMessage(), e);
            }
        }

        private void writeSample(boolean isVideo, byte[] data, long ptsUs, boolean isKeyFrame) {
            if (basePtsUs == Long.MIN_VALUE && ptsUs >= 0) {
                basePtsUs = ptsUs;
            }
            long normalized = basePtsUs == Long.MIN_VALUE ? Math.max(0L, ptsUs) : Math.max(0L, ptsUs - basePtsUs);
            if (isVideo) {
                if (normalized <= lastVideoPtsUs && lastVideoPtsUs >= 0) {
                    normalized = lastVideoPtsUs + 1L;
                }
                lastVideoPtsUs = normalized;
            } else {
                if (normalized <= lastAudioPtsUs && lastAudioPtsUs >= 0) {
                    normalized = lastAudioPtsUs + 1L;
                }
                lastAudioPtsUs = normalized;
            }
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            info.set(0, data.length, normalized, isKeyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            muxer.writeSampleData(isVideo ? videoTrack : audioTrack, ByteBuffer.wrap(data), info);
            writtenSamples++;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            try {
                if (!started && (pendingVideoFormat != null || pendingAudioFormat != null)) {
                    forceStart();
                }
                if (started && muxer != null && writtenSamples > 0) {
                    muxer.stop();
                }
            } catch (RuntimeException e) {
                throw new IOException("MP4 封装收尾失败: " + e.getMessage(), e);
            } finally {
                closed = true;
                releaseMuxerQuietly();
            }
        }

        private void releaseMuxerQuietly() {
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Exception ignored) {}
                muxer = null;
            }
        }
    }

    /** Convenience method to remux ordered MPEG-TS segment files into a single MP4 file. */
    public static void muxTsFiles(List<File> tsFiles, File outputMp4, Net.Cancellation cancel) throws IOException {
        if (tsFiles == null || tsFiles.isEmpty()) {
            throw new IOException("没有可合并的 TS 分片");
        }
        File tmp = new File(outputMp4.getParentFile(), outputMp4.getName() + ".mux.tmp");
        try (MediaMuxerSink sink = new MediaMuxerSink(tmp)) {
            TsDemuxer demuxer = new TsDemuxer(sink);
            byte[] buf = new byte[64 * 1024];
            for (File seg : tsFiles) {
                Net.check(cancel);
                if (seg == null || !seg.exists() || seg.length() == 0) {
                    continue;
                }
                try (InputStream in = new FileInputStream(seg)) {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        Net.check(cancel);
                        demuxer.feed(buf, 0, n);
                    }
                }
            }
            demuxer.flush();
            if (sink.sampleCount() == 0) {
                throw new IOException("TS 分片中未解析出有效音视频帧");
            }
        } catch (IOException | RuntimeException e) {
            tmp.delete();
            if (e instanceof IOException ioe) {
                throw ioe;
            }
            throw new IOException(e.getMessage(), e);
        }
        if (outputMp4.exists()) {
            outputMp4.delete();
        }
        if (!tmp.renameTo(outputMp4)) {
            throw new IOException("无法写入输出 MP4 文件");
        }
    }

    /** Convenience method to remux an HTTP-FLV stream directly into an MP4 file. */
    public static void muxFlvStream(InputStream in, File outputMp4, Net.Cancellation cancel) throws IOException {
        File tmp = new File(outputMp4.getParentFile(), outputMp4.getName() + ".flv.tmp");
        try (MediaMuxerSink sink = new MediaMuxerSink(tmp)) {
            FlvReader.demux(in, sink, () -> false, cancel, null);
            if (sink.sampleCount() == 0) {
                throw new IOException("FLV 流中未解析出有效音视频帧");
            }
        } catch (IOException | RuntimeException e) {
            tmp.delete();
            if (e instanceof IOException ioe) {
                throw ioe;
            }
            throw new IOException(e.getMessage(), e);
        }
        if (outputMp4.exists()) {
            outputMp4.delete();
        }
        if (!tmp.renameTo(outputMp4)) {
            throw new IOException("无法写入输出 MP4 文件");
        }
    }

    /**
     * Pure-Java MPEG-TS 188-byte packet demuxer.
     * Parses PAT (PID 0), PMT, PES headers with 90kHz PTS, H.264 Annex-B (0x1B),
     * HEVC Annex-B (0x24), and ADTS AAC (0x0F).
     */
    public static final class TsDemuxer {
        private final SampleSink sink;
        private final ByteArrayOutputStream packetBuffer = new ByteArrayOutputStream();
        private final Map<Integer, Integer> streamTypes = new HashMap<>();
        private final Map<Integer, ByteArrayOutputStream> pesBuffers = new HashMap<>();

        private int pmtPid = -1;
        private int videoPid = -1;
        private int videoStreamType = -1;
        private int audioPid = -1;

        private byte[] h264Sps;
        private byte[] h264Pps;
        private boolean videoConfigEmitted;

        private byte[] hevcVps;
        private byte[] hevcSps;
        private byte[] hevcPps;

        private boolean audioConfigEmitted;
        private int audioSampleRate = 44100;

        private long lastRawPts = -1L;
        private long ptsWrapOffset = 0L;

        public TsDemuxer(SampleSink sink) {
            this.sink = sink;
        }

        public void feed(byte[] data, int offset, int length) {
            if (data == null || length <= 0) {
                return;
            }
            packetBuffer.write(data, offset, length);
            byte[] buf = packetBuffer.toByteArray();
            int pos = 0;
            while (pos + TS_PACKET_SIZE <= buf.length) {
                if ((buf[pos] & 0xFF) != TS_SYNC_BYTE) {
                    pos++;
                    continue;
                }
                if (pos + TS_PACKET_SIZE < buf.length && (buf[pos + TS_PACKET_SIZE] & 0xFF) != TS_SYNC_BYTE) {
                    // Check if next sync aligns; if not, keep scanning unless only 1 packet remains.
                    if (pos + 2 * TS_PACKET_SIZE <= buf.length) {
                        pos++;
                        continue;
                    }
                }
                parseTsPacket(buf, pos);
                pos += TS_PACKET_SIZE;
            }
            packetBuffer.reset();
            if (pos < buf.length) {
                packetBuffer.write(buf, pos, buf.length - pos);
            }
        }

        public void flush() {
            for (Map.Entry<Integer, ByteArrayOutputStream> e : pesBuffers.entrySet()) {
                if (e.getValue().size() > 0) {
                    processPes(e.getKey(), e.getValue().toByteArray());
                    e.getValue().reset();
                }
            }
        }

        private void parseTsPacket(byte[] pkt, int off) {
            boolean tei = (pkt[off + 1] & 0x80) != 0;
            if (tei) {
                return;
            }
            boolean pusi = (pkt[off + 1] & 0x40) != 0;
            int pid = ((pkt[off + 1] & 0x1F) << 8) | (pkt[off + 2] & 0xFF);
            int afc = (pkt[off + 3] >> 4) & 0x03;

            if (afc == 0 || afc == 2) {
                return;
            }
            int payloadOff = off + 4;
            if (afc == 3) {
                int afLen = pkt[payloadOff] & 0xFF;
                payloadOff += 1 + afLen;
            }
            int payloadEnd = off + TS_PACKET_SIZE;
            if (payloadOff >= payloadEnd) {
                return;
            }

            if (pid == 0x0000) {
                parsePat(pkt, payloadOff, payloadEnd, pusi);
            } else if (pid == pmtPid) {
                parsePmt(pkt, payloadOff, payloadEnd, pusi);
            } else if (pid == videoPid || pid == audioPid) {
                ByteArrayOutputStream pes = pesBuffers.computeIfAbsent(pid, k -> new ByteArrayOutputStream());
                if (pusi && pes.size() > 0) {
                    processPes(pid, pes.toByteArray());
                    pes.reset();
                }
                pes.write(pkt, payloadOff, payloadEnd - payloadOff);
            }
        }

        private void parsePat(byte[] pkt, int start, int end, boolean pusi) {
            int pos = start;
            if (pusi) {
                int pointer = pkt[pos++] & 0xFF;
                pos += pointer;
            }
            if (pos + 8 > end || (pkt[pos] & 0xFF) != 0x00) {
                return;
            }
            int sectionLen = ((pkt[pos + 1] & 0x0F) << 8) | (pkt[pos + 2] & 0xFF);
            int sectionEnd = Math.min(end, pos + 3 + sectionLen - 4); // exclude CRC32
            int entryPos = pos + 8;
            while (entryPos + 4 <= sectionEnd) {
                int programNumber = ((pkt[entryPos] & 0xFF) << 8) | (pkt[entryPos + 1] & 0xFF);
                int pid = ((pkt[entryPos + 2] & 0x1F) << 8) | (pkt[entryPos + 3] & 0xFF);
                if (programNumber != 0) {
                    pmtPid = pid;
                    break;
                }
                entryPos += 4;
            }
        }

        private void parsePmt(byte[] pkt, int start, int end, boolean pusi) {
            int pos = start;
            if (pusi) {
                int pointer = pkt[pos++] & 0xFF;
                pos += pointer;
            }
            if (pos + 12 > end || (pkt[pos] & 0xFF) != 0x02) {
                return;
            }
            int sectionLen = ((pkt[pos + 1] & 0x0F) << 8) | (pkt[pos + 2] & 0xFF);
            int sectionEnd = Math.min(end, pos + 3 + sectionLen - 4);
            int programInfoLen = ((pkt[pos + 10] & 0x0F) << 8) | (pkt[pos + 11] & 0xFF);
            int entryPos = pos + 12 + programInfoLen;
            while (entryPos + 5 <= sectionEnd) {
                int streamType = pkt[entryPos] & 0xFF;
                int elemPid = ((pkt[entryPos + 1] & 0x1F) << 8) | (pkt[entryPos + 2] & 0xFF);
                int esInfoLen = ((pkt[entryPos + 3] & 0x0F) << 8) | (pkt[entryPos + 4] & 0xFF);
                streamTypes.put(elemPid, streamType);
                if ((streamType == STREAM_TYPE_H264 || streamType == STREAM_TYPE_HEVC) && videoPid < 0) {
                    videoPid = elemPid;
                    videoStreamType = streamType;
                } else if (streamType == STREAM_TYPE_AAC_ADTS && audioPid < 0) {
                    audioPid = elemPid;
                }
                entryPos += 5 + esInfoLen;
            }
        }

        private void processPes(int pid, byte[] pes) {
            if (pes.length < 9) {
                return;
            }
            if ((pes[0] & 0xFF) != 0x00 || (pes[1] & 0xFF) != 0x00 || (pes[2] & 0xFF) != 0x01) {
                return;
            }
            int flags = pes[7] & 0xFF;
            int headerDataLen = pes[8] & 0xFF;
            int esOffset = 9 + headerDataLen;
            if (esOffset >= pes.length) {
                return;
            }

            long ptsUs = 0L;
            if ((flags & 0x80) != 0 && pes.length >= 14) {
                long rawPts = (((long) (pes[9] & 0x0E)) << 29)
                        | (((long) (pes[10] & 0xFF)) << 22)
                        | (((long) (pes[11] & 0xFE)) << 14)
                        | (((long) (pes[12] & 0xFF)) << 7)
                        | (((long) (pes[13] & 0xFE)) >> 1);
                if (lastRawPts >= 0 && (lastRawPts - rawPts) > (1L << 32)) {
                    ptsWrapOffset += (1L << 33);
                }
                lastRawPts = rawPts;
                ptsUs = ((rawPts + ptsWrapOffset) * 1_000_000L) / 90_000L;
            }

            if (pid == videoPid) {
                if (videoStreamType == STREAM_TYPE_HEVC) {
                    processHevcAccessUnit(pes, esOffset, pes.length, ptsUs);
                } else {
                    processH264AccessUnit(pes, esOffset, pes.length, ptsUs);
                }
            } else if (pid == audioPid) {
                processAdtsAudio(pes, esOffset, pes.length, ptsUs);
            }
        }

        private void processH264AccessUnit(byte[] data, int start, int end, long ptsUs) {
            List<int[]> nalus = splitAnnexBNalus(data, start, end);
            if (nalus.isEmpty()) {
                return;
            }
            ByteArrayOutputStream mp4Sample = new ByteArrayOutputStream();
            boolean isKeyFrame = false;
            for (int[] range : nalus) {
                int nOff = range[0];
                int nLen = range[1];
                if (nLen <= 0) {
                    continue;
                }
                int nalType = data[nOff] & 0x1F;
                if (nalType == 7) {
                    h264Sps = Arrays.copyOfRange(data, nOff, nOff + nLen);
                } else if (nalType == 8) {
                    h264Pps = Arrays.copyOfRange(data, nOff, nOff + nLen);
                } else if (nalType == 9) {
                    continue; // Access Unit Delimiter not needed in MP4 sample.
                } else {
                    if (nalType == 5) {
                        isKeyFrame = true;
                    }
                    writeLengthPrefixedNalu(mp4Sample, data, nOff, nLen);
                }
            }
            if (!videoConfigEmitted && h264Sps != null && h264Pps != null) {
                int[] dim = parseH264SpsDimensions(h264Sps);
                sink.onVideoConfig(
                        MediaFormat.MIMETYPE_VIDEO_AVC,
                        dim[0], dim[1],
                        withAnnexBPrefix(h264Sps),
                        withAnnexBPrefix(h264Pps)
                );
                videoConfigEmitted = true;
            }
            byte[] sampleBytes = mp4Sample.toByteArray();
            if (sampleBytes.length > 0 && videoConfigEmitted) {
                sink.onVideoSample(sampleBytes, 0, sampleBytes.length, ptsUs, isKeyFrame);
            }
        }

        private void processHevcAccessUnit(byte[] data, int start, int end, long ptsUs) {
            List<int[]> nalus = splitAnnexBNalus(data, start, end);
            if (nalus.isEmpty()) {
                return;
            }
            ByteArrayOutputStream mp4Sample = new ByteArrayOutputStream();
            boolean isKeyFrame = false;
            for (int[] range : nalus) {
                int nOff = range[0];
                int nLen = range[1];
                if (nLen <= 0) {
                    continue;
                }
                int nalType = (data[nOff] >> 1) & 0x3F;
                if (nalType == 32) {
                    hevcVps = Arrays.copyOfRange(data, nOff, nOff + nLen);
                } else if (nalType == 33) {
                    hevcSps = Arrays.copyOfRange(data, nOff, nOff + nLen);
                } else if (nalType == 34) {
                    hevcPps = Arrays.copyOfRange(data, nOff, nOff + nLen);
                } else if (nalType == 35) {
                    continue; // AUD
                } else {
                    if (nalType == 19 || nalType == 20 || nalType == 21) {
                        isKeyFrame = true;
                    }
                    writeLengthPrefixedNalu(mp4Sample, data, nOff, nLen);
                }
            }
            if (!videoConfigEmitted && hevcVps != null && hevcSps != null && hevcPps != null) {
                byte[] hvcc = buildHvcc(hevcVps, hevcSps, hevcPps);
                sink.onVideoConfig(MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1080, hvcc, null);
                videoConfigEmitted = true;
            }
            byte[] sampleBytes = mp4Sample.toByteArray();
            if (sampleBytes.length > 0 && videoConfigEmitted) {
                sink.onVideoSample(sampleBytes, 0, sampleBytes.length, ptsUs, isKeyFrame);
            }
        }

        private void processAdtsAudio(byte[] data, int start, int end, long ptsUs) {
            int pos = start;
            int frameIndex = 0;
            while (pos + 7 <= end) {
                if ((data[pos] & 0xFF) != 0xFF || (data[pos + 1] & 0xF0) != 0xF0) {
                    pos++;
                    continue;
                }
                boolean hasCrc = (data[pos + 1] & 0x01) == 0;
                int headerLen = hasCrc ? 9 : 7;
                int profile = ((data[pos + 2] >> 6) & 0x03) + 1;
                int freqIdx = (data[pos + 2] >> 2) & 0x0F;
                int chanCfg = ((data[pos + 2] & 0x01) << 2) | ((data[pos + 3] >> 6) & 0x03);
                int frameLen = ((data[pos + 3] & 0x03) << 11)
                        | ((data[pos + 4] & 0xFF) << 3)
                        | ((data[pos + 5] >> 5) & 0x07);
                if (frameLen < headerLen || pos + frameLen > end) {
                    break;
                }

                if (!audioConfigEmitted) {
                    audioSampleRate = freqIdx < AAC_SAMPLE_RATES.length ? AAC_SAMPLE_RATES[freqIdx] : 44100;
                    int channels = chanCfg > 0 ? chanCfg : 2;
                    byte[] asc = new byte[]{
                            (byte) (((profile & 0x1F) << 3) | ((freqIdx >> 1) & 0x07)),
                            (byte) (((freqIdx & 0x01) << 7) | ((chanCfg & 0x0F) << 3))
                    };
                    sink.onAudioConfig(MediaFormat.MIMETYPE_AUDIO_AAC, audioSampleRate, channels, asc);
                    audioConfigEmitted = true;
                }

                int payloadOff = pos + headerLen;
                int payloadLen = frameLen - headerLen;
                if (payloadLen > 0) {
                    long framePtsUs = ptsUs + (frameIndex * 1024L * 1_000_000L) / Math.max(8000, audioSampleRate);
                    sink.onAudioSample(data, payloadOff, payloadLen, framePtsUs);
                    frameIndex++;
                }
                pos += frameLen;
            }
        }
    }

    /**
     * Pure-Java HTTP-FLV stream demuxer.
     * Parses FLV header + AudioTag (AAC sequence header & raw AAC) + VideoTag (AVC sequence header & NALUs).
     */
    public static final class FlvReader {
        private FlvReader() {}

        public static long demux(
                InputStream in,
                SampleSink sink,
                BooleanSupplier stopSignal,
                Net.Cancellation cancel,
                LongConsumer progressBytes
        ) throws IOException {
            byte[] header = new byte[9];
            if (!readFully(in, header, 0, 9)) {
                throw new IOException("FLV 流头部不完整");
            }
            if (header[0] != 'F' || header[1] != 'L' || header[2] != 'V') {
                throw new IOException("无效的 FLV 流签名");
            }
            int dataOffset = ((header[5] & 0xFF) << 24)
                    | ((header[6] & 0xFF) << 16)
                    | ((header[7] & 0xFF) << 8)
                    | (header[8] & 0xFF);
            long totalRead = 9;
            if (dataOffset > 9) {
                byte[] extra = new byte[dataOffset - 9];
                if (!readFully(in, extra, 0, extra.length)) {
                    throw new IOException("FLV 扩展头部不完整");
                }
                totalRead += extra.length;
            }
            byte[] prevTagSize = new byte[4];
            if (!readFully(in, prevTagSize, 0, 4)) {
                return totalRead;
            }
            totalRead += 4;

            int naluLengthSize = 4;
            boolean videoConfigSent = false;
            boolean audioConfigSent = false;
            byte[] tagHeader = new byte[11];

            while (true) {
                if (cancel != null) {
                    Net.check(cancel);
                }
                if (stopSignal != null && stopSignal.getAsBoolean()) {
                    break;
                }
                if (!readFully(in, tagHeader, 0, 11)) {
                    break; // Clean EOF
                }
                int tagType = tagHeader[0] & 0xFF;
                int dataSize = ((tagHeader[1] & 0xFF) << 16)
                        | ((tagHeader[2] & 0xFF) << 8)
                        | (tagHeader[3] & 0xFF);
                long timestampMs = ((long) (tagHeader[7] & 0xFF) << 24)
                        | ((long) (tagHeader[4] & 0xFF) << 16)
                        | ((long) (tagHeader[5] & 0xFF) << 8)
                        | (long) (tagHeader[6] & 0xFF);

                if (dataSize > 16 * 1024 * 1024) {
                    throw new IOException("FLV Tag 大小异常: " + dataSize);
                }
                byte[] payload = new byte[dataSize];
                if (dataSize > 0 && !readFully(in, payload, 0, dataSize)) {
                    break;
                }
                if (!readFully(in, prevTagSize, 0, 4)) {
                    totalRead += 11 + dataSize;
                    // Still process last tag before returning
                    processTag(tagType, payload, timestampMs, sink,
                            new int[]{naluLengthSize}, new boolean[]{videoConfigSent, audioConfigSent});
                    break;
                }
                totalRead += 15L + dataSize;
                if (progressBytes != null) {
                    progressBytes.accept(totalRead);
                }

                int[] naluRef = new int[]{naluLengthSize};
                boolean[] cfgRef = new boolean[]{videoConfigSent, audioConfigSent};
                processTag(tagType, payload, timestampMs, sink, naluRef, cfgRef);
                naluLengthSize = naluRef[0];
                videoConfigSent = cfgRef[0];
                audioConfigSent = cfgRef[1];
            }
            return totalRead;
        }

        private static void processTag(
                int tagType,
                byte[] payload,
                long timestampMs,
                SampleSink sink,
                int[] naluLengthSizeRef,
                boolean[] cfgSentRef
        ) {
            if (tagType == 8 && payload.length >= 2) {
                int soundFormat = (payload[0] >> 4) & 0x0F;
                if (soundFormat == 10) { // AAC
                    int aacPacketType = payload[1] & 0xFF;
                    if (aacPacketType == 0 && payload.length >= 4) {
                        byte[] asc = Arrays.copyOfRange(payload, 2, payload.length);
                        int[] audioInfo = parseAudioSpecificConfig(asc);
                        sink.onAudioConfig(MediaFormat.MIMETYPE_AUDIO_AAC, audioInfo[0], audioInfo[1], asc);
                        cfgSentRef[1] = true;
                    } else if (aacPacketType == 1 && payload.length > 2 && cfgSentRef[1]) {
                        sink.onAudioSample(payload, 2, payload.length - 2, timestampMs * 1000L);
                    }
                }
            } else if (tagType == 9 && payload.length >= 5) {
                int frameType = (payload[0] >> 4) & 0x0F;
                int codecId = payload[0] & 0x0F;
                if (codecId == 7) { // AVC / H.264
                    int avcPacketType = payload[1] & 0xFF;
                    if (avcPacketType == 0 && payload.length >= 13) {
                        AvccConfig avcc = parseAvcc(payload, 5, payload.length);
                        if (avcc != null) {
                            naluLengthSizeRef[0] = avcc.naluLengthSize;
                            int[] dim = avcc.sps != null ? parseH264SpsDimensions(avcc.sps) : new int[]{1280, 720};
                            sink.onVideoConfig(
                                    MediaFormat.MIMETYPE_VIDEO_AVC,
                                    dim[0], dim[1],
                                    avcc.sps != null ? withAnnexBPrefix(avcc.sps) : null,
                                    avcc.pps != null ? withAnnexBPrefix(avcc.pps) : null
                            );
                            cfgSentRef[0] = true;
                        }
                    } else if (avcPacketType == 1 && payload.length > 5 && cfgSentRef[0]) {
                        int cts = ((payload[2] & 0xFF) << 16)
                                | ((payload[3] & 0xFF) << 8)
                                | (payload[4] & 0xFF);
                        if ((cts & 0x800000) != 0) {
                            cts |= 0xFF000000;
                        }
                        long ptsUs = Math.max(0L, timestampMs + Math.max(0, cts)) * 1000L;
                        byte[] normalized = normalizeLengthPrefixedNalus(payload, 5, payload.length, naluLengthSizeRef[0]);
                        if (normalized.length > 0) {
                            sink.onVideoSample(normalized, 0, normalized.length, ptsUs, frameType == 1);
                        }
                    }
                }
            }
        }
    }

    private record AvccConfig(int naluLengthSize, byte[] sps, byte[] pps) {}

    private static AvccConfig parseAvcc(byte[] data, int off, int end) {
        if (off + 7 > end) {
            return null;
        }
        int naluLengthSize = (data[off + 4] & 0x03) + 1;
        int numSps = data[off + 5] & 0x1F;
        int pos = off + 6;
        byte[] firstSps = null;
        for (int i = 0; i < numSps; i++) {
            if (pos + 2 > end) {
                return null;
            }
            int spsLen = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            pos += 2;
            if (pos + spsLen > end) {
                return null;
            }
            if (firstSps == null && spsLen > 0) {
                firstSps = Arrays.copyOfRange(data, pos, pos + spsLen);
            }
            pos += spsLen;
        }
        if (pos >= end) {
            return new AvccConfig(naluLengthSize, firstSps, null);
        }
        int numPps = data[pos++] & 0xFF;
        byte[] firstPps = null;
        for (int i = 0; i < numPps; i++) {
            if (pos + 2 > end) {
                break;
            }
            int ppsLen = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            pos += 2;
            if (pos + ppsLen > end) {
                break;
            }
            if (firstPps == null && ppsLen > 0) {
                firstPps = Arrays.copyOfRange(data, pos, pos + ppsLen);
            }
            pos += ppsLen;
        }
        return new AvccConfig(naluLengthSize, firstSps, firstPps);
    }

    private static byte[] normalizeLengthPrefixedNalus(byte[] data, int start, int end, int naluLenSize) {
        if (naluLenSize == 4) {
            return Arrays.copyOfRange(data, start, end);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = start;
        while (pos + naluLenSize <= end) {
            int len = 0;
            for (int i = 0; i < naluLenSize; i++) {
                len = (len << 8) | (data[pos + i] & 0xFF);
            }
            pos += naluLenSize;
            if (len <= 0 || pos + len > end) {
                break;
            }
            writeLengthPrefixedNalu(out, data, pos, len);
            pos += len;
        }
        return out.toByteArray();
    }

    private static int[] parseAudioSpecificConfig(byte[] asc) {
        if (asc == null || asc.length < 2) {
            return new int[]{44100, 2};
        }
        int freqIdx = ((asc[0] & 0x07) << 1) | ((asc[1] >> 7) & 0x01);
        int channels = (asc[1] >> 3) & 0x0F;
        int sampleRate = freqIdx < AAC_SAMPLE_RATES.length ? AAC_SAMPLE_RATES[freqIdx] : 44100;
        return new int[]{sampleRate, channels > 0 ? channels : 2};
    }

    static List<int[]> splitAnnexBNalus(byte[] data, int start, int end) {
        List<int[]> nalus = new ArrayList<>();
        int i = start;
        int naluStart = -1;
        while (i < end) {
            int scLen = 0;
            if (i + 3 < end && data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1) {
                scLen = 4;
            } else if (i + 2 < end && data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                scLen = 3;
            }
            if (scLen > 0) {
                if (naluStart >= 0 && i > naluStart) {
                    nalus.add(new int[]{naluStart, i - naluStart});
                }
                i += scLen;
                naluStart = i;
            } else {
                i++;
            }
        }
        if (naluStart >= 0 && end > naluStart) {
            nalus.add(new int[]{naluStart, end - naluStart});
        }
        return nalus;
    }

    private static void writeLengthPrefixedNalu(ByteArrayOutputStream out, byte[] data, int off, int len) {
        out.write((len >> 24) & 0xFF);
        out.write((len >> 16) & 0xFF);
        out.write((len >> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(data, off, len);
    }

    private static byte[] withAnnexBPrefix(byte[] nalu) {
        byte[] out = new byte[4 + nalu.length];
        out[0] = 0;
        out[1] = 0;
        out[2] = 0;
        out[3] = 1;
        System.arraycopy(nalu, 0, out, 4, nalu.length);
        return out;
    }

    private static byte[] buildHvcc(byte[] vps, byte[] sps, byte[] pps) {
        // Minimal valid ISO/IEC 14496-15 HEVCDecoderConfigurationRecord
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(1); // configurationVersion
        out.write(0); // general_profile_space / tier / profile_idc
        out.write(0); out.write(0); out.write(0); out.write(0); // profile_compatibility_flags
        for (int i = 0; i < 6; i++) out.write(0); // constraint_indicator_flags
        out.write(120); // general_level_idc (4.0)
        out.write(0xF0); out.write(0x00); // min_spatial_segmentation_idc
        out.write(0xFC); // parallelismType
        out.write(0xFC); // chromaFormat
        out.write(0xF8); // bitDepthLumaMinus8
        out.write(0xF8); // bitDepthChromaMinus8
        out.write(0); out.write(0); // avgFrameRate
        out.write(0x0F); // constantFrameRate / numTemporalLayers / lengthSizeMinusOne=3 (4 bytes)
        out.write(3); // numOfArrays: VPS, SPS, PPS
        writeHvccArray(out, 32, vps);
        writeHvccArray(out, 33, sps);
        writeHvccArray(out, 34, pps);
        return out.toByteArray();
    }

    private static void writeHvccArray(ByteArrayOutputStream out, int naluType, byte[] nalu) {
        out.write(0x80 | (naluType & 0x3F));
        out.write(0); out.write(1); // numNalus = 1
        out.write((nalu.length >> 8) & 0xFF);
        out.write(nalu.length & 0xFF);
        out.write(nalu, 0, nalu.length);
    }

    /** Minimal H.264 SPS Exp-Golomb parser to recover width/height, defaulting to 1280x720. */
    static int[] parseH264SpsDimensions(byte[] sps) {
        try {
            byte[] rbsp = removeEmulationPrevention(sps);
            BitReader br = new BitReader(rbsp, 1); // skip NAL header byte
            int profileIdc = br.readBits(8);
            br.readBits(8); // constraint flags + reserved
            br.readBits(8); // level_idc
            br.readUe(); // seq_parameter_set_id
            if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 || profileIdc == 244
                    || profileIdc == 44 || profileIdc == 83 || profileIdc == 86 || profileIdc == 118
                    || profileIdc == 128 || profileIdc == 138) {
                int chromaFormatIdc = br.readUe();
                if (chromaFormatIdc == 3) {
                    br.readBits(1);
                }
                br.readUe(); // bit_depth_luma_minus8
                br.readUe(); // bit_depth_chroma_minus8
                br.readBits(1); // qpprime_y_zero_transform_bypass_flag
                if (br.readBits(1) == 1) { // seq_scaling_matrix_present_flag
                    int count = chromaFormatIdc != 3 ? 8 : 12;
                    for (int i = 0; i < count; i++) {
                        if (br.readBits(1) == 1) {
                            int size = i < 6 ? 16 : 64;
                            int lastScale = 8, nextScale = 8;
                            for (int j = 0; j < size; j++) {
                                if (nextScale != 0) {
                                    nextScale = (lastScale + br.readSe() + 256) % 256;
                                }
                                lastScale = nextScale == 0 ? lastScale : nextScale;
                            }
                        }
                    }
                }
            }
            br.readUe(); // log2_max_frame_num_minus4
            int picOrderCntType = br.readUe();
            if (picOrderCntType == 0) {
                br.readUe();
            } else if (picOrderCntType == 1) {
                br.readBits(1);
                br.readSe();
                br.readSe();
                int numRefFramesInPicOrderCntCycle = br.readUe();
                for (int i = 0; i < numRefFramesInPicOrderCntCycle; i++) {
                    br.readSe();
                }
            }
            br.readUe(); // max_num_ref_frames
            br.readBits(1); // gaps_in_frame_num_value_allowed_flag
            int picWidthInMbsMinus1 = br.readUe();
            int picHeightInMapUnitsMinus1 = br.readUe();
            int frameMbsOnlyFlag = br.readBits(1);
            if (frameMbsOnlyFlag == 0) {
                br.readBits(1);
            }
            br.readBits(1); // direct_8x8_inference_flag
            int cropLeft = 0, cropRight = 0, cropTop = 0, cropBottom = 0;
            if (br.readBits(1) == 1) {
                cropLeft = br.readUe();
                cropRight = br.readUe();
                cropTop = br.readUe();
                cropBottom = br.readUe();
            }
            int width = ((picWidthInMbsMinus1 + 1) * 16) - (cropLeft + cropRight) * 2;
            int height = ((2 - frameMbsOnlyFlag) * (picHeightInMapUnitsMinus1 + 1) * 16) - (cropTop + cropBottom) * 2;
            if (width >= 16 && height >= 16 && width <= 8192 && height <= 8192) {
                return new int[]{width, height};
            }
        } catch (Exception ignored) {}
        return new int[]{1280, 720};
    }

    private static byte[] removeEmulationPrevention(byte[] src) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(src.length);
        for (int i = 0; i < src.length; i++) {
            if (i + 2 < src.length && src[i] == 0 && src[i + 1] == 0 && src[i + 2] == 3) {
                out.write(0);
                out.write(0);
                i += 2;
            } else {
                out.write(src[i]);
            }
        }
        return out.toByteArray();
    }

    private static final class BitReader {
        private final byte[] data;
        private int bytePos;
        private int bitPos;

        BitReader(byte[] data, int bytePos) {
            this.data = data;
            this.bytePos = bytePos;
        }

        int readBits(int n) {
            int val = 0;
            for (int i = 0; i < n; i++) {
                if (bytePos >= data.length) {
                    throw new IndexOutOfBoundsException();
                }
                int bit = (data[bytePos] >> (7 - bitPos)) & 1;
                val = (val << 1) | bit;
                bitPos++;
                if (bitPos == 8) {
                    bitPos = 0;
                    bytePos++;
                }
            }
            return val;
        }

        int readUe() {
            int zeros = 0;
            while (readBits(1) == 0) {
                zeros++;
            }
            int suffix = zeros > 0 ? readBits(zeros) : 0;
            return ((1 << zeros) - 1) + suffix;
        }

        int readSe() {
            int ue = readUe();
            int sign = (ue & 1) == 1 ? 1 : -1;
            return sign * ((ue + 1) >> 1);
        }
    }

    private static boolean readFully(InputStream in, byte[] dst, int off, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = in.read(dst, off + read, len - read);
            if (n < 0) {
                return false;
            }
            read += n;
        }
        return true;
    }
}
