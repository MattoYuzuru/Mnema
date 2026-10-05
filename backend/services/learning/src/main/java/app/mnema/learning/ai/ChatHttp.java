package app.mnema.learning.ai;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Predicate;

/**
 * Bounded JDK {@code HttpClient} transport shared by the provider adapters: no redirects, connect limit, an overall
 * deadline that covers the whole body, an idle limit for streams, and a hard cap on the bytes read. Error response bodies
 * are never read (they could echo prompt text); only status and headers are kept.
 */
final class ChatHttp implements AutoCloseable {
    private final HttpClient client;
    private final AiProperties.EgressMode egress;
    private final long idleNanos;
    private final Duration firstByte;
    private final int maxBodyBytes;

    /** The direct transport. */
    ChatHttp(AiProperties.Transport transport) {
        this(transport, HttpClient.newBuilder().connectTimeout(transport.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build(), AiProperties.EgressMode.DIRECT);
    }

    /** A transport over {@code client} (built by {@link EgressClients}); the limits and caps are the same for every mode. */
    ChatHttp(AiProperties.Transport transport, HttpClient client, AiProperties.EgressMode egress) {
        this.client = client;
        this.egress = egress;
        this.idleNanos = transport.idleStream().toNanos();
        this.firstByte = transport.firstByte();
        this.maxBodyBytes = transport.maxBodyBytes();
    }

    /** The underlying client (no redirects), for transports that read binary bodies themselves ({@link SafeImageFetcher}). */
    HttpClient client() { return client; }

    /** The egress mode of this transport, for the {@code ai_call} log line and metric tag. */
    AiProperties.EgressMode egress() { return egress; }

    /** A transport-level failure; {@code kind} drives the failure mapping, the message never leaves this package. */
    static final class TransportException extends Exception {
        private static final long serialVersionUID = 1L;

        enum Kind { TIMEOUT, IO, TOO_LARGE }

        private final Kind kind;

        TransportException(Kind kind) {
            super(kind.name(), null, false, false);
            this.kind = kind;
        }

        Kind kind() { return kind; }
    }

    /** Status line and the two headers the adapters use; {@code body} is empty for streamed and for non-2xx replies. */
    record Reply(int status, String retryAfter, String requestId, byte[] body) { }

    /**
     * Sends {@code request}. With a {@code lines} consumer a 2xx body is streamed line by line (the consumer returns
     * false to stop early); without one it is read whole, up to the body cap.
     */
    Reply send(HttpRequest.Builder request, Duration budget, Predicate<String> lines) throws TransportException {
        if (budget.isNegative() || budget.isZero()) throw new TransportException(TransportException.Kind.TIMEOUT);
        long deadline = System.nanoTime() + budget.toNanos();
        HttpResponse<InputStream> response;
        try {
            // Headers must arrive within firstByte (or the budget, if shorter): a silent provider then times out with deadline
            // left, so the router can retry and fall back instead of burning the whole call on one candidate.
            Duration headerWait = budget.compareTo(firstByte) < 0 ? budget : firstByte;
            response = client.send(request.timeout(headerWait).build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException exception) {
            throw new TransportException(TransportException.Kind.TIMEOUT);
        } catch (IOException exception) {
            throw new TransportException(TransportException.Kind.IO);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TransportException(TransportException.Kind.IO);
        }
        String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
        String requestId = response.headers().firstValue("x-request-id").orElse(null);
        InputStream raw = response.body();
        if (response.statusCode() / 100 != 2) {
            closeQuietly(raw);
            return new Reply(response.statusCode(), retryAfter, requestId, new byte[0]);
        }
        var bounded = new Bounded(raw, maxBodyBytes);
        try (var watchdog = new Watchdog(bounded, idleNanos, deadline)) {
            try {
                byte[] body = new byte[0];
                if (lines == null) {
                    body = readAll(bounded, watchdog);
                } else {
                    var reader = new BufferedReader(new InputStreamReader(bounded, StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        watchdog.touch();
                        if (!lines.test(line)) break;
                    }
                }
                return new Reply(response.statusCode(), retryAfter, requestId, body);
            } catch (IOException exception) {
                if (watchdog.fired()) throw new TransportException(TransportException.Kind.TIMEOUT);
                if (exception instanceof BodyTooLargeException) throw new TransportException(TransportException.Kind.TOO_LARGE);
                throw new TransportException(TransportException.Kind.IO);
            } finally {
                closeQuietly(bounded);
            }
        }
    }

    private static byte[] readAll(InputStream in, Watchdog watchdog) throws IOException {
        var out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8_192];
        for (int count; (count = in.read(chunk)) != -1; ) {
            watchdog.touch();
            out.write(chunk, 0, count);
        }
        return out.toByteArray();
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // already abandoned
        }
    }

    @Override
    public void close() { client.shutdownNow(); }

    private static final class BodyTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        BodyTooLargeException() { super("body too large", null); }
    }

    /** Counts bytes and fails the read that would exceed the cap. */
    private static final class Bounded extends FilterInputStream {
        private final int limit;
        private long total;

        Bounded(InputStream in, int limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) count(1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) count(count);
            return count;
        }

        private void count(int bytes) throws IOException {
            total += bytes;
            if (total > limit) throw new BodyTooLargeException();
        }
    }
}
