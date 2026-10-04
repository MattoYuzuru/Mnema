package app.mnema.learning.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** A local HTTP server for the image source and fetcher tests: handlers by path, recorded requests; tests never reach a network. */
final class ImageServer implements AutoCloseable {
    record Recorded(String method, String path, String query, Map<String, List<String>> headers, String body) { }

    interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private final HttpServer server;
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    final List<Recorded> requests = new CopyOnWriteArrayList<>();

    ImageServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
        server.createContext("/", exchange -> {
            byte[] body;
            try (InputStream in = exchange.getRequestBody()) {
                body = in.readAllBytes();
            }
            requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), exchange.getRequestURI().getRawQuery(),
                    Map.copyOf(exchange.getRequestHeaders()), new String(body, StandardCharsets.UTF_8)));
            Handler handler = handlers.get(exchange.getRequestURI().getPath());
            try {
                if (handler == null) reply(exchange, 404, "text/plain", new byte[0]);
                else handler.handle(exchange);
            } catch (IOException aborted) {
                // the client hung up (a refused download)
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    int port() { return server.getAddress().getPort(); }

    /** {@code http://localhost:port}: the host name the fetcher tests allowlist. */
    String origin() { return "http://localhost:" + port(); }

    String loopbackOrigin() { return "http://127.0.0.1:" + port(); }

    ImageServer on(String path, Handler handler) {
        handlers.put(path, handler);
        return this;
    }

    ImageServer json(String path, String body) {
        return on(path, exchange -> reply(exchange, 200, "application/json", body.getBytes(StandardCharsets.UTF_8)));
    }

    ImageServer bytes(String path, String type, byte[] body) {
        return on(path, exchange -> reply(exchange, 200, type, body));
    }

    ImageServer redirect(String path, String location) {
        return on(path, exchange -> {
            exchange.getResponseHeaders().add("Location", location);
            reply(exchange, 302, "text/plain", new byte[0]);
        });
    }

    static void reply(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    static String fixture(String name) {
        try (InputStream in = ImageServer.class.getResourceAsStream("/ai/image/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | NullPointerException failure) {
            throw new IllegalStateException("Missing fixture " + name);
        }
    }

    @Override
    public void close() { server.stop(0); }
}
