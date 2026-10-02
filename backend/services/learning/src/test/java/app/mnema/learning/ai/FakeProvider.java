package app.mnema.learning.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** A scripted local OpenAI-compatible server on a loopback port; tests never reach a network. */
final class FakeProvider implements AutoCloseable {
    record Recorded(String path, Map<String, String> headers, String body) { }

    /** One scripted reply: status, headers, body bytes, optional delay before answering and pauses between stream lines. */
    record Reply(int status, Map<String, String> headers, String body, long delayMillis, long lineGapMillis, boolean stream) {
        static Reply json(int status, String body) { return new Reply(status, Map.of("Content-Type", "application/json"), body, 0, 0, false); }

        static Reply fixtureJson(String fixture) { return json(200, fixture(fixture)); }

        static Reply status(int status, String... headers) {
            Map<String, String> map = new LinkedHashMap<>();
            for (int index = 0; index < headers.length; index += 2) map.put(headers[index], headers[index + 1]);
            return new Reply(status, map, fixture("error-server.json"), 0, 0, false);
        }

        static Reply sse(String fixture) { return new Reply(200, Map.of("Content-Type", "text/event-stream"), fixture(fixture), 0, 0, true); }

        Reply withHeader(String name, String value) {
            Map<String, String> merged = new LinkedHashMap<>(headers);
            merged.put(name, value);
            return new Reply(status, merged, body, delayMillis, lineGapMillis, stream);
        }

        Reply delayed(long millis) { return new Reply(status, headers, body, millis, lineGapMillis, stream); }

        Reply gap(long millis) { return new Reply(status, headers, body, delayMillis, millis, stream); }
    }

    private final HttpServer server;
    private final Deque<Reply> script = new ArrayDeque<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();

    private FakeProvider(HttpServer server) { this.server = server; }

    static FakeProvider start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            FakeProvider provider = new FakeProvider(server);
            server.createContext("/", provider::handle);
            server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return provider;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static String fixture(String name) {
        try (InputStream in = FakeProvider.class.getResourceAsStream("/ai/fixtures/" + name)) {
            if (in == null) throw new IllegalStateException("Missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    FakeProvider enqueue(Reply... replies) {
        synchronized (script) {
            for (Reply reply : replies) script.add(reply);
        }
        return this;
    }

    String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    List<Recorded> requests() { return new ArrayList<>(requests); }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((key, values) -> headers.put(key.toLowerCase(java.util.Locale.ROOT), values.get(0)));
        requests.add(new Recorded(exchange.getRequestURI().getPath(), headers, body));
        Reply reply;
        synchronized (script) {
            reply = script.poll();
        }
        if (reply == null) reply = Reply.json(500, "{\"error\":\"script exhausted\"}");
        try (exchange) {
            sleep(reply.delayMillis());
            reply.headers().forEach((key, value) -> exchange.getResponseHeaders().add(key, value));
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            if (reply.stream()) {
                exchange.sendResponseHeaders(reply.status(), 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    for (String line : reply.body().split("\n", -1)) {
                        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        sleep(reply.lineGapMillis());
                    }
                }
            } else {
                exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                }
            }
        } catch (IOException disconnected) {
            // the client gave up (timeout tests); nothing to do
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() { server.stop(0); }
}
