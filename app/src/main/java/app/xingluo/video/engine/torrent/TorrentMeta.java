package app.xingluo.video.engine.torrent;

import app.xingluo.video.LinkInput;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Metadata model and parser for .torrent files and magnet:?xt=urn:btih:... URIs. */
public final class TorrentMeta {
    private static final Set<String> PLAYABLE_EXTENSIONS = Set.of(
            "mp4", "mkv", "webm", "mov", "avi", "flv", "ts", "m4v",
            "wmv", "mpg", "mpeg", "3gp", "mp3", "m4a", "aac", "flac",
            "ogg", "opus", "wav"
    );

    public static final class TorrentFile {
        public final String path;
        public final String name;
        public final long length;
        public final long offset;

        public TorrentFile(String path, String name, long length, long offset) {
            this.path = path;
            this.name = name;
            this.length = length;
            this.offset = offset;
        }
    }

    private final byte[] infoHash;
    public final String infoHashHex;
    public final String name;
    public final long pieceLength;
    public final List<byte[]> pieceHashes;
    public final List<TorrentFile> files;
    public final List<String> trackers;
    public final List<String> webSeeds;
    public final boolean singleFile;

    private TorrentMeta(
            byte[] infoHash,
            String name,
            long pieceLength,
            List<byte[]> pieceHashes,
            List<TorrentFile> files,
            List<String> trackers,
            List<String> webSeeds,
            boolean singleFile
    ) {
        if (infoHash == null || infoHash.length != 20) {
            throw new IllegalArgumentException("infoHash must be 20 bytes");
        }
        this.infoHash = infoHash.clone();
        this.infoHashHex = Bencode.toHex(this.infoHash);
        this.name = (name == null || name.isBlank()) ? this.infoHashHex : name;
        this.pieceLength = pieceLength;
        this.pieceHashes = List.copyOf(pieceHashes);
        this.files = List.copyOf(files);
        this.trackers = List.copyOf(trackers);
        this.webSeeds = List.copyOf(webSeeds);
        this.singleFile = singleFile;
    }

    public byte[] infoHash() {
        return infoHash.clone();
    }

    public boolean hasMetadata() {
        return pieceLength > 0 && !pieceHashes.isEmpty() && !files.isEmpty();
    }

    public long totalSize() {
        long sum = 0L;
        for (TorrentFile f : files) {
            sum += f.length;
        }
        return sum;
    }

    /**
     * Returns playable media files sorted by descending size; if none match playable
     * media extensions, returns all files sorted by descending size.
     */
    public List<TorrentFile> largestPlayableOrAllFiles() {
        if (files.isEmpty()) return List.of();
        List<TorrentFile> playable = new ArrayList<>();
        for (TorrentFile f : files) {
            if (isPlayableFilename(f.name)) {
                playable.add(f);
            }
        }
        List<TorrentFile> target = playable.isEmpty() ? new ArrayList<>(files) : playable;
        target.sort(Comparator.comparingLong((TorrentFile f) -> f.length).reversed());
        return List.copyOf(target);
    }

    public static TorrentMeta fromTorrentFile(File file) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IOException("Torrent file does not exist: " + file);
        }
        return fromTorrentBytes(Files.readAllBytes(file.toPath()));
    }

    public static TorrentMeta fromTorrentBytes(byte[] torrentBytes) {
        byte[] infoHash = Bencode.extractInfoSha1(torrentBytes);
        Map<String, Object> root = Bencode.asDict(Bencode.decode(torrentBytes));
        if (root == null) {
            throw new IllegalArgumentException("Invalid torrent: root is not a dictionary");
        }

        LinkedHashSet<String> trackers = new LinkedHashSet<>();
        addTrackerIfValid(trackers, Bencode.asString(root.get("announce")));
        List<Object> announceList = Bencode.asList(root.get("announce-list"));
        if (announceList != null) {
            for (Object tierObj : announceList) {
                List<Object> tier = Bencode.asList(tierObj);
                if (tier != null) {
                    for (Object urlObj : tier) {
                        addTrackerIfValid(trackers, Bencode.asString(urlObj));
                    }
                } else {
                    addTrackerIfValid(trackers, Bencode.asString(tierObj));
                }
            }
        }

        LinkedHashSet<String> webSeeds = new LinkedHashSet<>();
        Object urlListObj = root.get("url-list");
        if (urlListObj instanceof List<?> list) {
            for (Object item : list) {
                addWebSeedIfValid(webSeeds, Bencode.asString(item));
            }
        } else {
            addWebSeedIfValid(webSeeds, Bencode.asString(urlListObj));
        }

        Map<String, Object> info = Bencode.asDict(root.get("info"));
        return fromInfoDict(infoHash, info, List.copyOf(trackers), List.copyOf(webSeeds));
    }

    /** Upgrades a magnet-only TorrentMeta with a verified raw bencoded info dictionary. */
    public static TorrentMeta withInfoBytes(TorrentMeta base, byte[] rawInfoBytes) {
        byte[] hash = Bencode.sha1(rawInfoBytes);
        if (!Arrays.equals(hash, base.infoHash)) {
            throw new IllegalArgumentException("Fetched info dictionary SHA-1 does not match magnet infoHash");
        }
        Map<String, Object> info = Bencode.asDict(Bencode.decode(rawInfoBytes));
        return fromInfoDict(hash, info, base.trackers, base.webSeeds);
    }

    private static TorrentMeta fromInfoDict(
            byte[] infoHash,
            Map<String, Object> info,
            List<String> trackers,
            List<String> webSeeds
    ) {
        if (info == null) {
            throw new IllegalArgumentException("Invalid torrent: missing 'info' dictionary");
        }

        String name = Bencode.asString(info.get("name.utf-8"));
        if (name == null || name.isBlank()) {
            name = Bencode.asString(info.get("name"));
        }
        String safeTorrentName = sanitizeSegment(name, Bencode.toHex(infoHash));

        Long pieceLengthObj = Bencode.asLong(info.get("piece length"));
        if (pieceLengthObj == null || pieceLengthObj <= 0) {
            throw new IllegalArgumentException("Invalid torrent: missing or non-positive piece length");
        }
        long pieceLength = pieceLengthObj;

        byte[] piecesRaw = Bencode.asBytes(info.get("pieces"));
        if (piecesRaw == null || piecesRaw.length == 0 || (piecesRaw.length % 20) != 0) {
            throw new IllegalArgumentException("Invalid torrent: 'pieces' must be a non-empty multiple of 20 bytes");
        }
        int pieceCount = piecesRaw.length / 20;
        List<byte[]> pieceHashes = new ArrayList<>(pieceCount);
        for (int i = 0; i < pieceCount; i++) {
            pieceHashes.add(Arrays.copyOfRange(piecesRaw, i * 20, (i + 1) * 20));
        }

        List<TorrentFile> files = new ArrayList<>();
        boolean singleFile;
        List<Object> rawFiles = Bencode.asList(info.get("files"));
        if (rawFiles != null) {
            singleFile = false;
            long runningOffset = 0L;
            for (Object fileObj : rawFiles) {
                Map<String, Object> fileDict = Bencode.asDict(fileObj);
                if (fileDict == null) {
                    throw new IllegalArgumentException("Invalid torrent: file entry is not a dictionary");
                }
                Long lengthObj = Bencode.asLong(fileDict.get("length"));
                if (lengthObj == null || lengthObj < 0) {
                    throw new IllegalArgumentException("Invalid torrent: invalid file length");
                }
                List<Object> pathParts = Bencode.asList(fileDict.get("path.utf-8"));
                if (pathParts == null) {
                    pathParts = Bencode.asList(fileDict.get("path"));
                }
                if (pathParts == null || pathParts.isEmpty()) {
                    throw new IllegalArgumentException("Invalid torrent: missing file path");
                }
                List<String> cleanParts = new ArrayList<>(pathParts.size());
                for (Object partObj : pathParts) {
                    String rawSeg = Bencode.asString(partObj);
                    if (rawSeg == null || rawSeg.isBlank() || ".".equals(rawSeg) || "..".equals(rawSeg)) {
                        continue;
                    }
                    cleanParts.add(sanitizeSegment(rawSeg, "file"));
                }
                if (cleanParts.isEmpty()) {
                    cleanParts.add("file_" + files.size());
                }
                String relPath = String.join("/", cleanParts);
                String fileName = cleanParts.get(cleanParts.size() - 1);
                files.add(new TorrentFile(relPath, fileName, lengthObj, runningOffset));
                runningOffset += lengthObj;
            }
            if (files.isEmpty()) {
                throw new IllegalArgumentException("Invalid torrent: multi-file list is empty");
            }
        } else {
            singleFile = true;
            Long lengthObj = Bencode.asLong(info.get("length"));
            if (lengthObj == null || lengthObj < 0) {
                throw new IllegalArgumentException("Invalid torrent: missing file length");
            }
            files.add(new TorrentFile(safeTorrentName, safeTorrentName, lengthObj, 0L));
        }

        return new TorrentMeta(
                infoHash,
                safeTorrentName,
                pieceLength,
                pieceHashes,
                files,
                trackers,
                webSeeds,
                singleFile
        );
    }

    public static TorrentMeta fromMagnet(String magnetUri) {
        if (magnetUri == null || !magnetUri.toLowerCase(Locale.ROOT).startsWith("magnet:?")) {
            throw new IllegalArgumentException("Invalid magnet URI");
        }
        String query = magnetUri.substring("magnet:?".length());
        byte[] infoHash = null;
        String displayName = null;
        LinkedHashSet<String> trackers = new LinkedHashSet<>();
        LinkedHashSet<String> webSeeds = new LinkedHashSet<>();

        for (String param : query.split("&")) {
            if (param.isEmpty()) continue;
            int eq = param.indexOf('=');
            String key = (eq >= 0 ? param.substring(0, eq) : param).toLowerCase(Locale.ROOT);
            String rawVal = eq >= 0 ? param.substring(eq + 1) : "";
            String val;
            try { val = URLDecoder.decode(rawVal, "UTF-8"); }
            catch (Exception e) { val = rawVal; }

            if ("xt".equals(key)) {
                String lower = val.toLowerCase(Locale.ROOT);
                if (lower.startsWith("urn:btih:")) {
                    String hashPart = val.substring("urn:btih:".length()).trim();
                    infoHash = parseBtihHash(hashPart);
                }
            } else if ("dn".equals(key)) {
                if (!val.isBlank()) {
                    displayName = val.trim();
                }
            } else if ("tr".equals(key)) {
                addTrackerIfValid(trackers, val);
            } else if ("ws".equals(key)) {
                addWebSeedIfValid(webSeeds, val);
            }
        }

        if (infoHash == null) {
            throw new IllegalArgumentException("Magnet URI missing valid urn:btih infoHash");
        }
        String resolvedName = (displayName == null || displayName.isBlank())
                ? Bencode.toHex(infoHash)
                : LinkInput.safeName(displayName);
        return new TorrentMeta(
                infoHash,
                resolvedName,
                0L,
                List.of(),
                List.of(),
                List.copyOf(trackers),
                List.copyOf(webSeeds),
                true
        );
    }

    private static byte[] parseBtihHash(String raw) {
        if (raw.matches("(?i)[0-9a-f]{40}")) {
            return Bencode.fromHex(raw);
        }
        String normalized = raw.replace("=", "").toUpperCase(Locale.ROOT);
        if (normalized.matches("[A-Z2-7]{32}")) {
            byte[] decoded = decodeBase32(normalized);
            if (decoded.length == 20) {
                return decoded;
            }
        }
        throw new IllegalArgumentException("Unsupported btih hash format: " + raw);
    }

    /** RFC 4648 Base32 decoder (5 bits per symbol). */
    public static byte[] decodeBase32(String input) {
        if (input == null) throw new IllegalArgumentException("Null base32 input");
        String clean = input.trim().replace("=", "").toUpperCase(Locale.ROOT);
        ByteArrayOutputStream out = new ByteArrayOutputStream((clean.length() * 5) / 8);
        int buffer = 0;
        int bitsLeft = 0;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            int val;
            if (c >= 'A' && c <= 'Z') {
                val = c - 'A';
            } else if (c >= '2' && c <= '7') {
                val = 26 + (c - '2');
            } else {
                throw new IllegalArgumentException("Invalid Base32 character: " + c);
            }
            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }

    private static void addTrackerIfValid(Set<String> trackers, String url) {
        if (url == null) return;
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("udp://")) {
            trackers.add(trimmed);
        }
    }

    private static void addWebSeedIfValid(Set<String> webSeeds, String url) {
        if (url == null) return;
        String trimmed = url.trim();
        if (LinkInput.isWebUrl(trimmed)) {
            webSeeds.add(trimmed);
        }
    }

    private static String sanitizeSegment(String segment, String fallback) {
        if (segment == null || segment.isBlank()) {
            return fallback;
        }
        String cleaned = LinkInput.safeName(segment);
        return cleaned.isBlank() ? fallback : cleaned;
    }

    private static boolean isPlayableFilename(String filename) {
        if (filename == null) return false;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return false;
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return PLAYABLE_EXTENSIONS.contains(ext);
    }
}
