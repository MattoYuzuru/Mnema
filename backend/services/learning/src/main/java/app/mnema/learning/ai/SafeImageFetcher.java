package app.mnema.learning.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Downloads one image of a search result without letting a result steer the server to somewhere it should not go (SSRF), and
 * without trusting what comes back:
 * <ul>
 *   <li>https only, and only the exact host names of the candidate's own source ({@code hosts}); no user info, no custom port;</li>
 *   <li>the host is resolved here and refused when any address is not public unicast ({@link ImageAddressPolicy}: loopback, private,
 *       link-local, the metadata address, CGNAT, unique-local, IPv4-mapped forms of those). A provider reached through the egress proxy
 *       is resolved by the proxy: only the host allowlist applies;</li>
 *   <li>redirects are followed by hand, at most {@value #MAX_REDIRECTS}, <b>to the same host only</b>, and every hop is validated again;</li>
 *   <li>{@code Content-Type} must be jpeg, png, webp or gif, a {@code Content-Length} above the cap is refused before reading, the body
 *       is streamed under a hard cap of 10 MiB and its magic bytes must match the declared type;</li>
 *   <li>connect 5 s (the client's), the whole fetch within {@code total}.</li>
 * </ul>
 * <b>Residual risk (accepted):</b> the JDK client resolves the name again when it connects, so a DNS rebinding between the check and the
 * connect is not excluded. It is acceptable only because the hosts are fixed, public, allowlisted names of the sources, never a name
 * a search result chooses; a result whose URL names another host is refused before any DNS lookup.
 */
final class SafeImageFetcher {
    private static final Logger LOG = LoggerFactory.getLogger(SafeImageFetcher.class);
    static final int MAX_BYTES = 10 * 1024 * 1024;
    static final int MAX_REDIRECTS = 2;
    /**
     * The wanted types first, then any type at a low weight: the Openverse thumbnail endpoint answers 406 to a list of image types alone
     * (live check 2026-10-05). What arrives is still checked against the content type and magic-byte allowlist below.
     */
    static final String ACCEPT = "image/jpeg,image/png,image/webp,image/gif,*/*;q=0.1";
    private static final Duration IDLE = Duration.ofSeconds(10);

    private final boolean httpsOnly;
    private final ImageAddressPolicy.Resolver resolver;
    private final String userAgent;

    /** @param httpsOnly false is for tests against a loopback server only */
    SafeImageFetcher(boolean httpsOnly, ImageAddressPolicy.Resolver resolver, String userAgent) {
        this.httpsOnly = httpsOnly;
        this.resolver = resolver;
        this.userAgent = userAgent;
    }

    /**
     * @param client a client that does not follow redirects ({@link EgressClients} builds them so)
     * @param proxied the client reaches the host through the egress proxy (DNS is the proxy's)
     * @param hosts the exact lower-case host names of the candidate's own source
     */
    AiResult<ImageSearch.Image> fetch(HttpClient client, boolean proxied, Set<String> hosts, String url, Duration total) {
        long deadline = System.nanoTime() + total.toNanos();
        URI current;
        try {
            current = URI.create(url);
        } catch (RuntimeException malformed) {
            return refused("bad_url");
        }
        String host = host(current);
        for (int hop = 0; ; hop++) {
            String refusal = validate(current, host, hosts, proxied);
            if (refusal != null) return refused(refusal);
            long left = deadline - System.nanoTime();
            if (left <= 0) return AiResult.failed(new AiFailure.Timeout());
            HttpResponse<InputStream> response;
            try {
                response = client.send(HttpRequest.newBuilder(current).GET().timeout(Duration.ofNanos(left))
                        .header("User-Agent", userAgent).header("Accept", ACCEPT).build(),
                        HttpResponse.BodyHandlers.ofInputStream());
            } catch (HttpTimeoutException timeout) {
                return AiResult.failed(new AiFailure.Timeout());
            } catch (IOException failure) {
                return AiResult.failed(new AiFailure.Transient("io"));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return AiResult.failed(new AiFailure.Transient("interrupted"));
            }
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                close(response.body());
                if (hop >= MAX_REDIRECTS) return refused("too_many_redirects");
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null) return refused("redirect_without_location");
                URI next;
                try {
                    next = current.resolve(location);
                } catch (RuntimeException malformed) {
                    return refused("bad_redirect");
                }
                // same host only: a redirect is never a way to reach another name (or an address literal)
                if (!host.equals(host(next))) return refused("redirect_other_host");
                current = next;
                continue;
            }
            if (status == 429) {
                close(response.body());
                return AiResult.failed(new AiFailure.RateLimited(Duration.ZERO));
            }
            if (status / 100 != 2) {
                close(response.body());
                return AiResult.failed(status >= 500 ? new AiFailure.Transient("http_" + status) : new AiFailure.InvalidOutput("http_" + status));
            }
            return read(response, deadline);
        }
    }

    private AiResult<ImageSearch.Image> read(HttpResponse<InputStream> response, long deadline) {
        InputStream raw = response.body();
        String type = response.headers().firstValue("Content-Type").map(SafeImageFetcher::mediaType).orElse("");
        if (!(type.equals("image/jpeg") || type.equals("image/png") || type.equals("image/webp") || type.equals("image/gif"))) {
            close(raw);
            return AiResult.failed(new AiFailure.InvalidOutput("content_type"));
        }
        OptionalLong declared = response.headers().firstValueAsLong("Content-Length");
        if (declared.isPresent() && declared.getAsLong() > MAX_BYTES) {
            close(raw);
            return AiResult.failed(new AiFailure.InvalidOutput("too_large"));
        }
        Capped capped = new Capped(raw);
        try (var watchdog = new Watchdog(capped, IDLE.toNanos(), deadline)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(declared.isPresent() ? (int) Math.min(declared.getAsLong(), MAX_BYTES) : 64 * 1024);
            byte[] chunk = new byte[16 * 1024];
            try {
                for (int count; (count = capped.read(chunk)) != -1; ) {
                    watchdog.touch();
                    out.write(chunk, 0, count);
                }
            } catch (IOException failure) {
                if (failure instanceof TooLarge) return AiResult.failed(new AiFailure.InvalidOutput("too_large"));
                return AiResult.failed(watchdog.fired() ? new AiFailure.Timeout() : new AiFailure.Transient("io"));
            } finally {
                close(capped);
            }
            byte[] bytes = out.toByteArray();
            if (bytes.length == 0 || !magicMatches(type, bytes)) return AiResult.failed(new AiFailure.InvalidOutput("magic"));
            return AiResult.ok(new ImageSearch.Image(bytes, type));
        }
    }

    /** Null when {@code uri} may be requested, else a short stable reason. */
    private String validate(URI uri, String host, Set<String> hosts, boolean proxied) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (httpsOnly ? !scheme.equals("https") : !(scheme.equals("https") || scheme.equals("http"))) return "scheme";
        if (uri.getRawUserInfo() != null || host == null || !hosts.contains(host)) return "host";
        if (httpsOnly && uri.getPort() != -1 && uri.getPort() != 443) return "port";
        if (proxied) return null;
        try {
            if (!ImageAddressPolicy.allPublic(resolver.resolve(host))) return "address";
        } catch (UnknownHostException unknown) {
            return "unresolved";
        }
        return null;
    }

    private static String host(URI uri) {
        return uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
    }

    private static String mediaType(String header) {
        int semicolon = header.indexOf(';');
        return (semicolon < 0 ? header : header.substring(0, semicolon)).strip().toLowerCase(Locale.ROOT);
    }

    static boolean magicMatches(String type, byte[] b) {
        return switch (type) {
            case "image/jpeg" -> b.length > 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff;
            case "image/png" -> b.length > 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && b[4] == 0x0d
                    && b[5] == 0x0a && b[6] == 0x1a && b[7] == 0x0a;
            case "image/gif" -> b.length > 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8' && (b[4] == '7' || b[4] == '9') && b[5] == 'a';
            case "image/webp" -> b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E'
                    && b[10] == 'B' && b[11] == 'P';
            default -> false;
        };
    }

    private AiResult<ImageSearch.Image> refused(String reason) {
        LOG.warn("image_fetch_refused reason={}", reason);
        return AiResult.failed(new AiFailure.Refusal(reason));
    }

    private static void close(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // abandoned
        }
    }

    private static final class TooLarge extends IOException {
        private static final long serialVersionUID = 1L;

        TooLarge() { super("too large", null); }
    }

    /** Fails the read that would pass the cap. */
    private static final class Capped extends FilterInputStream {
        private long total;

        Capped(InputStream in) { super(in); }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0 && (total += count) > MAX_BYTES) throw new TooLarge();
            return count;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1 && ++total > MAX_BYTES) throw new TooLarge();
            return value;
        }
    }
}
