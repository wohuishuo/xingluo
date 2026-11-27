package app.xingluo.video.engine.torrent;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure-Java BitTorrent Bencode codec and raw info-dictionary SHA-1 extractor. */
public final class Bencode {
    private static final int MAX_DEPTH = 64;

    private Bencode() {}

    public static final class DecodedValue {
        public final Object value;
        public final int nextOffset;

        public DecodedValue(Object value, int nextOffset) {
            this.value = value;
            this.nextOffset = nextOffset;
        }
    }

    /** Decodes a bencoded payload into Long, byte[], List<Object>, or Map<String, Object>. */
    public static Object decode(byte[] data) {
        return decodePrefix(data, 0).value;
    }

    /** Decodes a single bencoded element starting at {@code offset} and returns value + end offset. */
    public static DecodedValue decodePrefix(byte[] data, int offset) {
        if (data == null || offset < 0 || offset >= data.length) {
            throw new IllegalArgumentException("Empty or invalid bencode input");
        }
        int[] pos = {offset};
        Object value = readValue(data, pos, 0);
        return new DecodedValue(value, pos[0]);
    }

    /** Encodes Number, byte[], String, Collection, or Map into canonical bencode bytes. */
    public static byte[] encode(Object value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeValue(value, out, 0);
        return out.toByteArray();
    }

    /**
     * Locates the top-level "info" dictionary in raw torrent bytes without re-encoding
     * and computes its 20-byte SHA-1 digest.
     */
    public static byte[] extractInfoSha1(byte[] torrentBytes) {
        if (torrentBytes == null || torrentBytes.length < 2 || torrentBytes[0] != 'd') {
            throw new IllegalArgumentException("Invalid torrent: root is not a bencode dictionary");
        }
        int[] pos = {1};
        while (pos[0] < torrentBytes.length && torrentBytes[pos[0]] != 'e') {
            byte[] keyBytes = readBytes(torrentBytes, pos);
            String key = new String(keyBytes, StandardCharsets.UTF_8);
            int valStart = pos[0];
            skipValue(torrentBytes, pos, 1);
            int valEnd = pos[0];
            if ("info".equals(key)) {
                if (valStart >= valEnd || torrentBytes[valStart] != 'd') {
                    throw new IllegalArgumentException("Invalid torrent: 'info' is not a dictionary");
                }
                return sha1(torrentBytes, valStart, valEnd - valStart);
            }
        }
        throw new IllegalArgumentException("Invalid torrent: missing 'info' dictionary");
    }

    public static byte[] sha1(byte[] data, int offset, int length) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(data, offset, length);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    public static byte[] sha1(byte[] data) {
        return sha1(data, 0, data.length);
    }

    public static String asString(Object obj) {
        if (obj instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (obj instanceof String s) {
            return s;
        }
        return null;
    }

    public static byte[] asBytes(Object obj) {
        if (obj instanceof byte[] bytes) {
            return bytes;
        }
        if (obj instanceof String s) {
            return s.getBytes(StandardCharsets.UTF_8);
        }
        return null;
    }

    public static Long asLong(Object obj) {
        if (obj instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asDict(Object obj) {
        if (obj instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object obj) {
        if (obj instanceof List<?> list) {
            return (List<Object>) list;
        }
        return null;
    }

    public static String toHex(byte[] bytes) {
        if (bytes == null) return "";
        char[] hexChars = new char[bytes.length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            hexChars[i * 2] = digits[v >>> 4];
            hexChars[i * 2 + 1] = digits[v & 0x0f];
        }
        return new String(hexChars);
    }

    public static byte[] fromHex(String hex) {
        if (hex == null || (hex.length() & 1) != 0) {
            throw new IllegalArgumentException("Invalid hex string");
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("Invalid hex character in: " + hex);
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static Object readValue(byte[] data, int[] pos, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Bencode nesting exceeds limit");
        }
        if (pos[0] >= data.length) {
            throw new IllegalArgumentException("Unexpected end of bencode input");
        }
        byte b = data[pos[0]];
        if (b == 'i') {
            return readInt(data, pos);
        }
        if (b == 'l') {
            pos[0]++;
            List<Object> list = new ArrayList<>();
            while (pos[0] < data.length && data[pos[0]] != 'e') {
                list.add(readValue(data, pos, depth + 1));
            }
            if (pos[0] >= data.length || data[pos[0]] != 'e') {
                throw new IllegalArgumentException("Unterminated bencode list");
            }
            pos[0]++;
            return list;
        }
        if (b == 'd') {
            pos[0]++;
            Map<String, Object> dict = new LinkedHashMap<>();
            while (pos[0] < data.length && data[pos[0]] != 'e') {
                byte[] keyBytes = readBytes(data, pos);
                String key = new String(keyBytes, StandardCharsets.UTF_8);
                Object val = readValue(data, pos, depth + 1);
                dict.put(key, val);
            }
            if (pos[0] >= data.length || data[pos[0]] != 'e') {
                throw new IllegalArgumentException("Unterminated bencode dictionary");
            }
            pos[0]++;
            return dict;
        }
        if (b >= '0' && b <= '9') {
            return readBytes(data, pos);
        }
        throw new IllegalArgumentException("Invalid bencode token at offset " + pos[0]);
    }

    private static Long readInt(byte[] data, int[] pos) {
        pos[0]++; // skip 'i'
        int start = pos[0];
        int end = start;
        while (end < data.length && data[end] != 'e') {
            end++;
        }
        if (end >= data.length) {
            throw new IllegalArgumentException("Unterminated bencode integer");
        }
        if (end == start) {
            throw new IllegalArgumentException("Empty bencode integer");
        }
        String raw = new String(data, start, end - start, StandardCharsets.US_ASCII);
        if ("-0".equals(raw)) {
            throw new IllegalArgumentException("Invalid bencode integer: -0");
        }
        if (raw.startsWith("0") && raw.length() > 1) {
            throw new IllegalArgumentException("Leading zero in bencode integer: " + raw);
        }
        if (raw.startsWith("-0")) {
            throw new IllegalArgumentException("Leading zero in negative bencode integer: " + raw);
        }
        for (int i = (raw.charAt(0) == '-' ? 1 : 0); i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("Invalid digit in bencode integer: " + raw);
            }
        }
        if ("-".equals(raw)) {
            throw new IllegalArgumentException("Invalid bencode integer: -");
        }
        pos[0] = end + 1;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bencode integer overflow: " + raw, e);
        }
    }

    private static byte[] readBytes(byte[] data, int[] pos) {
        int colon = pos[0];
        while (colon < data.length && data[colon] != ':') {
            byte c = data[colon];
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("Invalid byte string length at offset " + colon);
            }
            colon++;
        }
        if (colon == pos[0] || colon >= data.length) {
            throw new IllegalArgumentException("Missing colon in bencode byte string");
        }
        String lenStr = new String(data, pos[0], colon - pos[0], StandardCharsets.US_ASCII);
        if (lenStr.length() > 1 && lenStr.charAt(0) == '0') {
            throw new IllegalArgumentException("Leading zero in bencode byte string length: " + lenStr);
        }
        long lenLong;
        try {
            lenLong = Long.parseLong(lenStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid bencode string length: " + lenStr, e);
        }
        int start = colon + 1;
        if (lenLong < 0 || lenLong > data.length - start) {
            throw new IllegalArgumentException("Truncated bencode byte string");
        }
        int len = (int) lenLong;
        byte[] out = Arrays.copyOfRange(data, start, start + len);
        pos[0] = start + len;
        return out;
    }

    private static void skipValue(byte[] data, int[] pos, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Bencode nesting exceeds limit");
        }
        if (pos[0] >= data.length) {
            throw new IllegalArgumentException("Unexpected end of bencode input");
        }
        byte b = data[pos[0]];
        if (b == 'i') {
            readInt(data, pos);
            return;
        }
        if (b == 'l') {
            pos[0]++;
            while (pos[0] < data.length && data[pos[0]] != 'e') {
                skipValue(data, pos, depth + 1);
            }
            if (pos[0] >= data.length || data[pos[0]] != 'e') {
                throw new IllegalArgumentException("Unterminated bencode list");
            }
            pos[0]++;
            return;
        }
        if (b == 'd') {
            pos[0]++;
            while (pos[0] < data.length && data[pos[0]] != 'e') {
                skipBytes(data, pos);
                skipValue(data, pos, depth + 1);
            }
            if (pos[0] >= data.length || data[pos[0]] != 'e') {
                throw new IllegalArgumentException("Unterminated bencode dictionary");
            }
            pos[0]++;
            return;
        }
        if (b >= '0' && b <= '9') {
            skipBytes(data, pos);
            return;
        }
        throw new IllegalArgumentException("Invalid bencode token at offset " + pos[0]);
    }

    private static void skipBytes(byte[] data, int[] pos) {
        int colon = pos[0];
        while (colon < data.length && data[colon] != ':') {
            byte c = data[colon];
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("Invalid byte string length at offset " + colon);
            }
            colon++;
        }
        if (colon == pos[0] || colon >= data.length) {
            throw new IllegalArgumentException("Missing colon in bencode byte string");
        }
        long lenLong = Long.parseLong(new String(data, pos[0], colon - pos[0], StandardCharsets.US_ASCII));
        int start = colon + 1;
        if (lenLong < 0 || lenLong > data.length - start) {
            throw new IllegalArgumentException("Truncated bencode byte string");
        }
        pos[0] = start + (int) lenLong;
    }

    private static void writeValue(Object value, ByteArrayOutputStream out, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Bencode encoding nesting exceeds limit");
        }
        if (value == null) {
            throw new IllegalArgumentException("Cannot bencode null value");
        }
        if (value instanceof Number n) {
            out.write('i');
            byte[] numBytes = Long.toString(n.longValue()).getBytes(StandardCharsets.US_ASCII);
            out.write(numBytes, 0, numBytes.length);
            out.write('e');
            return;
        }
        if (value instanceof byte[] bytes) {
            writeByteString(bytes, out);
            return;
        }
        if (value instanceof String s) {
            writeByteString(s.getBytes(StandardCharsets.UTF_8), out);
            return;
        }
        if (value instanceof Collection<?> col) {
            out.write('l');
            for (Object item : col) {
                writeValue(item, out, depth + 1);
            }
            out.write('e');
            return;
        }
        if (value instanceof Map<?, ?> map) {
            List<Map.Entry<byte[], Object>> entries = new ArrayList<>(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                byte[] keyBytes;
                if (entry.getKey() instanceof byte[] kb) {
                    keyBytes = kb;
                } else if (entry.getKey() instanceof String ks) {
                    keyBytes = ks.getBytes(StandardCharsets.UTF_8);
                } else {
                    throw new IllegalArgumentException("Bencode dictionary keys must be String or byte[]");
                }
                entries.add(Map.entry(keyBytes, entry.getValue()));
            }
            entries.sort((a, b) -> compareUnsignedBytes(a.getKey(), b.getKey()));
            out.write('d');
            for (Map.Entry<byte[], Object> entry : entries) {
                writeByteString(entry.getKey(), out);
                writeValue(entry.getValue(), out, depth + 1);
            }
            out.write('e');
            return;
        }
        throw new IllegalArgumentException("Unsupported bencode type: " + value.getClass().getName());
    }

    private static int compareUnsignedBytes(byte[] a, byte[] b) {
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int cmp = Integer.compare(a[i] & 0xFF, b[i] & 0xFF);
            if (cmp != 0) return cmp;
        }
        return Integer.compare(a.length, b.length);
    }

    private static void writeByteString(byte[] bytes, ByteArrayOutputStream out) {
        byte[] lenBytes = Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII);
        out.write(lenBytes, 0, lenBytes.length);
        out.write(':');
        out.write(bytes, 0, bytes.length);
    }
}
