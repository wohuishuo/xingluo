package app.xingluo.video.testutil;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Pure java.net ServerSocket test HTTP server compatible with com.sun.net.httpserver call sites. */
public final class MiniHttpServer {
    public interface Handler { void handle(HttpExchange exchange) throws IOException; }

    public static final class Headers {
        private final Map<String, List<String>> map = new LinkedHashMap<>();
        public void add(String key, String value) {
            String k = normalize(key);
            map.computeIfAbsent(k, x -> new ArrayList<>()).add(value);
        }
        public void set(String key, String value) {
            List<String> l = new ArrayList<>();
            l.add(value);
            map.put(normalize(key), l);
        }
        public String getFirst(String key) {
            List<String> l = map.get(normalize(key));
            return (l == null || l.isEmpty()) ? null : l.get(0);
        }
        private static String normalize(String k) { return k == null ? "" : k.toLowerCase(Locale.ROOT); }
        Map<String, List<String>> raw() { return map; }
    }

    public static final class HttpExchange implements Closeable {
        private final Socket socket;
        private final String method;
        private final Headers reqHeaders;
        private final Headers respHeaders = new Headers();
        private final ByteArrayOutputStream respBody = new ByteArrayOutputStream();
        private int status = 200;
        private boolean sent = false;

        HttpExchange(Socket socket, String method, Headers reqHeaders) {
            this.socket = socket;
            this.method = method;
            this.reqHeaders = reqHeaders;
        }
        public String getRequestMethod() { return method; }
        public Headers getRequestHeaders() { return reqHeaders; }
        public Headers getResponseHeaders() { return respHeaders; }
        public void sendResponseHeaders(int code, long length) throws IOException {
            this.status = code;
            if (length >= 0 && respHeaders.getFirst("Content-Length") == null) {
                respHeaders.set("Content-Length", String.valueOf(length));
            }
        }
        public OutputStream getResponseBody() { return respBody; }
        @Override public void close() throws IOException { flushResponse(); }

        void flushResponse() throws IOException {
            if (sent) return;
            sent = true;
            byte[] body = respBody.toByteArray();
            if (!"HEAD".equalsIgnoreCase(method) && respHeaders.getFirst("Content-Length") == null) {
                respHeaders.set("Content-Length", String.valueOf(body.length));
            }
            respHeaders.set("Connection", "close");
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 ").append(status).append(" OK\r\n");
            for (Map.Entry<String, List<String>> e : respHeaders.raw().entrySet()) {
                for (String v : e.getValue()) sb.append(e.getKey()).append(": ").append(v).append("\r\n");
            }
            sb.append("\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
            if (!"HEAD".equalsIgnoreCase(method) && body.length > 0) out.write(body);
            out.flush();
            socket.close();
        }
    }

    private final ServerSocket ss;
    private final Map<String, Handler> contexts = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running = false;

    private MiniHttpServer(InetSocketAddress addr) throws IOException {
        ss = new ServerSocket();
        ss.bind(addr);
    }

    public static MiniHttpServer create(InetSocketAddress addr, int backlog) throws IOException {
        return new MiniHttpServer(addr);
    }

    public void createContext(String path, Handler handler) { contexts.put(path, handler); }
    public InetSocketAddress getAddress() { return (InetSocketAddress) ss.getLocalSocketAddress(); }

    public void start() {
        running = true;
        pool.execute(() -> {
            while (running && !ss.isClosed()) {
                try {
                    Socket s = ss.accept();
                    pool.execute(() -> serve(s));
                } catch (IOException ignored) { break; }
            }
        });
    }

    private void serve(Socket s) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
            String line = reader.readLine();
            if (line == null) { s.close(); return; }
            String[] parts = line.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String rawTarget = parts.length > 1 ? parts[1] : "/";
            int q = rawTarget.indexOf('?');
            String path = q >= 0 ? rawTarget.substring(0, q) : rawTarget;
            Headers reqH = new Headers();
            String hLine;
            while ((hLine = reader.readLine()) != null && !hLine.isEmpty()) {
                int idx = hLine.indexOf(':');
                if (idx > 0) reqH.add(hLine.substring(0, idx).trim(), hLine.substring(idx + 1).trim());
            }
            Handler h = contexts.get(path);
            if (h == null) {
                for (Map.Entry<String, Handler> e : contexts.entrySet()) {
                    if (path.startsWith(e.getKey())) { h = e.getValue(); break; }
                }
            }
            HttpExchange ex = new HttpExchange(s, method, reqH);
            if (h != null) {
                h.handle(ex);
                ex.flushResponse();
            } else {
                ex.sendResponseHeaders(404, 0);
                ex.flushResponse();
            }
        } catch (Throwable ignored) {
            try { s.close(); } catch (IOException e2) {}
        }
    }

    public void stop(int delay) {
        running = false;
        try { ss.close(); } catch (IOException ignored) {}
        pool.shutdownNow();
    }
}
