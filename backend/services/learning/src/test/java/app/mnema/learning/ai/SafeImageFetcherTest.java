package app.mnema.learning.ai;

import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The SSRF acceptance of #296: a redirect to an internal address, an IPv4-mapped IPv6 address, a response that is too large are all refused. */
class SafeImageFetcherTest {
    private static final byte[] PNG = StubImageSearch.png(8, 8, new byte[32]);
    private static final Duration TOTAL = Duration.ofSeconds(10);
    /** What the fixture host names "resolve" to, so no test depends on DNS. */
    private static final Map<String, String> DNS = Map.of("localhost", "93.184.216.34", "internal.test", "127.0.0.1", "mapped.test", "::ffff:127.0.0.1",
            "metadata.test", "169.254.169.254", "both.test", "93.184.216.34,10.0.0.1");

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(2)).build();
    private ImageServer server;

    private static final ImageAddressPolicy.Resolver RESOLVER = host -> {
        String answer = DNS.get(host);
        if (answer == null) throw new UnknownHostException(host);
        String[] literals = answer.split(",");
        InetAddress[] out = new InetAddress[literals.length];
        for (int index = 0; index < literals.length; index++) out[index] = InetAddress.getByName(literals[index]);
        return out;
    };

    @BeforeEach
    void start() {
        server = new ImageServer();
        server.bytes("/ok.png", "image/png", PNG);
        server.redirect("/same", "/ok.png");
        server.redirect("/hop1", "/hop2");
        server.redirect("/hop2", "/hop3");
        server.redirect("/hop3", "/ok.png");
        server.redirect("/to-loopback", server.loopbackOrigin() + "/ok.png");
        server.redirect("/to-mapped", "http://[::ffff:127.0.0.1]:" + server.port() + "/ok.png");
        server.redirect("/to-metadata", "http://169.254.169.254/latest/meta-data/");
        server.redirect("/to-other-name", "http://internal.test:" + server.port() + "/ok.png");
        server.redirect("/to-https-elsewhere", "https://example.org/ok.png");
        server.on("/no-location", exchange -> ImageServer.reply(exchange, 302, "text/plain", new byte[0]));
        server.bytes("/html", "text/html", "<html>".getBytes());
        server.bytes("/fake-png", "image/png", "this is not a png at all".getBytes());
        server.bytes("/jpeg-as-png", "image/png", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 0, 0, 0, 0, 0});
        server.on("/declared-large", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/jpeg");
            exchange.sendResponseHeaders(200, SafeImageFetcher.MAX_BYTES + 1L);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff});
                out.flush();
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        server.on("/streamed-large", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/jpeg");
            exchange.sendResponseHeaders(200, 0);
            byte[] chunk = new byte[64 * 1024];
            chunk[0] = (byte) 0xff;
            chunk[1] = (byte) 0xd8;
            chunk[2] = (byte) 0xff;
            try (OutputStream out = exchange.getResponseBody()) {
                for (int written = 0; written <= SafeImageFetcher.MAX_BYTES + chunk.length; written += chunk.length) out.write(chunk);
            }
        });
        server.on("/exactly-at-the-cap", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/jpeg");
            exchange.sendResponseHeaders(200, SafeImageFetcher.MAX_BYTES);
            byte[] all = new byte[SafeImageFetcher.MAX_BYTES];
            all[0] = (byte) 0xff;
            all[1] = (byte) 0xd8;
            all[2] = (byte) 0xff;
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(all);
            }
        });
        server.on("/slow", (HttpExchange exchange) -> {
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, PNG.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(PNG, 0, 10);
                out.flush();
                try {
                    Thread.sleep(3_000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        server.on("/throttled", exchange -> ImageServer.reply(exchange, 429, "text/plain", new byte[0]));
        server.on("/broken", exchange -> ImageServer.reply(exchange, 503, "text/plain", new byte[0]));
    }

    @AfterEach
    void stop() { server.close(); }

    private SafeImageFetcher fetcher() { return new SafeImageFetcher(false, RESOLVER, "Mnema-Test/1.0 (+https://example.org)"); }

    private AiResult<ImageSearch.Image> fetch(String url) { return fetch(fetcher(), url, Set.of("localhost")); }

    private AiResult<ImageSearch.Image> fetch(SafeImageFetcher fetcher, String url, Set<String> hosts) {
        return fetcher.fetch(client, false, hosts, url, TOTAL);
    }

    private static String reason(AiResult<ImageSearch.Image> result) {
        AiFailure failure = ((AiResult.Failed<ImageSearch.Image>) result).failure();
        return switch (failure) {
            case AiFailure.Refusal refusal -> "refused:" + refusal.detail();
            case AiFailure.InvalidOutput invalid -> "invalid:" + invalid.detail();
            case AiFailure.Transient transientFailure -> "transient:" + transientFailure.detail();
            default -> failure.outcome();
        };
    }

    @Test
    void anImageOfAnAllowedTypeIsReturnedWithItsBytesAndVerifiedType() {
        var result = fetch(server.origin() + "/ok.png");

        assertThat(result).isInstanceOf(AiResult.Ok.class);
        ImageSearch.Image image = ((AiResult.Ok<ImageSearch.Image>) result).value();
        assertThat(image.bytes()).isEqualTo(PNG);
        assertThat(image.mimeType()).isEqualTo("image/png");
        assertThat(server.requests.getFirst().headers().get("User-agent")).containsExactly("Mnema-Test/1.0 (+https://example.org)");
        assertThat(image.toString()).doesNotContain("localhost");
    }

    @Test
    void aRedirectOnTheSameHostIsFollowedUpToTwoHopsAndATripleHopIsRefused() {
        assertThat(fetch(server.origin() + "/same")).isInstanceOf(AiResult.Ok.class);
        assertThat(reason(fetch(server.origin() + "/hop1"))).isEqualTo("refused:too_many_redirects");
    }

    @Test
    void aRedirectToAnInternalAddressOrAnotherHostIsRefusedWithoutRequestingIt() {
        assertThat(reason(fetch(server.origin() + "/to-loopback"))).isEqualTo("refused:redirect_other_host");
        assertThat(reason(fetch(server.origin() + "/to-mapped"))).isEqualTo("refused:redirect_other_host");
        assertThat(reason(fetch(server.origin() + "/to-metadata"))).isEqualTo("refused:redirect_other_host");
        assertThat(reason(fetch(server.origin() + "/to-other-name"))).isEqualTo("refused:redirect_other_host");
        assertThat(reason(fetch(server.origin() + "/to-https-elsewhere"))).isEqualTo("refused:redirect_other_host");
        assertThat(reason(fetch(server.origin() + "/no-location"))).isEqualTo("refused:redirect_without_location");
        // none of the targets was ever requested: only the redirecting paths were
        assertThat(server.requests).extracting(ImageServer.Recorded::path).doesNotContain("/ok.png", "/latest/meta-data/");
    }

    @Test
    void aHostThatResolvesToAnInternalAddressIsRefusedBeforeAnyRequest() {
        SafeImageFetcher fetcher = fetcher();
        for (String host : new String[] {"internal.test", "mapped.test", "metadata.test", "both.test"}) {
            var result = fetch(fetcher, "http://" + host + ":" + server.port() + "/ok.png", Set.of(host));
            assertThat(reason(result)).as(host).isEqualTo("refused:address");
        }
        assertThat(server.requests).isEmpty();
    }

    @Test
    void aProxiedProviderIsNotResolvedHereButTheHostAllowlistStillApplies() {
        // DNS is the proxy's: the address check is skipped (the client here is not a proxy, which only makes the request local)
        var proxied = fetcher().fetch(client, true, Set.of("internal.test", "localhost"), server.origin() + "/ok.png", TOTAL);
        assertThat(proxied).isInstanceOf(AiResult.Ok.class);
        var elsewhere = fetcher().fetch(client, true, Set.of("localhost"), "http://169.254.169.254/latest/meta-data/", TOTAL);
        assertThat(reason(elsewhere)).isEqualTo("refused:host");
    }

    @Test
    void aNonHttpsSchemeAnUnlistedHostAndUserInfoAreRefused() {
        SafeImageFetcher strict = new SafeImageFetcher(true, RESOLVER, "Mnema-Test/1.0");
        assertThat(reason(fetch(strict, server.origin() + "/ok.png", Set.of("localhost")))).isEqualTo("refused:scheme");
        assertThat(reason(fetch(strict, "ftp://localhost/ok.png", Set.of("localhost")))).isEqualTo("refused:scheme");
        assertThat(reason(fetch(strict, "https://localhost:8443/ok.png", Set.of("localhost")))).isEqualTo("refused:port");
        assertThat(reason(fetch(fetcher(), server.origin() + "/ok.png", Set.of("pixabay.com")))).isEqualTo("refused:host");
        assertThat(reason(fetch(fetcher(), "http://user:pw@localhost:" + server.port() + "/ok.png", Set.of("localhost")))).isEqualTo("refused:host");
        assertThat(reason(fetch(fetcher(), "http://127.0.0.1:" + server.port() + "/ok.png", Set.of("localhost")))).isEqualTo("refused:host");
        assertThat(reason(fetch(fetcher(), "http://[::ffff:127.0.0.1]:" + server.port() + "/ok.png", Set.of("localhost")))).isEqualTo("refused:host");
        assertThat(reason(fetch(fetcher(), "not a url", Set.of("localhost")))).isEqualTo("refused:bad_url");
        assertThat(reason(fetch(fetcher(), "http://nowhere.test/ok.png", Set.of("nowhere.test")))).isEqualTo("refused:unresolved");
        assertThat(server.requests).isEmpty();
    }

    @Test
    void aResponseDeclaredLargerThanTenMebibytesIsRefusedBeforeItIsRead() {
        assertThat(reason(fetch(server.origin() + "/declared-large"))).isEqualTo("invalid:too_large");
    }

    @Test
    void aStreamedBodyThatGrowsPastTenMebibytesIsCutOff() {
        assertThat(reason(fetch(server.origin() + "/streamed-large"))).isEqualTo("invalid:too_large");
    }

    @Test
    void aBodyOfExactlyTheCapIsAccepted() {
        var result = fetch(server.origin() + "/exactly-at-the-cap");
        assertThat(result).isInstanceOf(AiResult.Ok.class);
        assertThat(((AiResult.Ok<ImageSearch.Image>) result).value().bytes()).hasSize(SafeImageFetcher.MAX_BYTES);
    }

    @Test
    void aWrongContentTypeAndBytesThatAreNotTheDeclaredTypeAreRefused() {
        assertThat(reason(fetch(server.origin() + "/html"))).isEqualTo("invalid:content_type");
        assertThat(reason(fetch(server.origin() + "/fake-png"))).isEqualTo("invalid:magic");
        assertThat(reason(fetch(server.origin() + "/jpeg-as-png"))).isEqualTo("invalid:magic");
    }

    @Test
    void statusFailuresMapToTypedFailuresAndAStalledBodyTimesOut() {
        assertThat(reason(fetch(server.origin() + "/throttled"))).isEqualTo("RATE_LIMITED");
        assertThat(reason(fetch(server.origin() + "/broken"))).isEqualTo("transient:http_503");
        assertThat(reason(fetch(server.origin() + "/missing"))).isEqualTo("invalid:http_404");
        var stalled = fetcher().fetch(client, false, Set.of("localhost"), server.origin() + "/slow", Duration.ofMillis(800));
        assertThat(reason(stalled)).isEqualTo("TIMEOUT");
    }

    @Test
    void theMagicBytesOfEveryAllowedTypeAreRecognised() {
        assertThat(SafeImageFetcher.magicMatches("image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xdb})).isTrue();
        assertThat(SafeImageFetcher.magicMatches("image/gif", "GIF89a....".getBytes())).isTrue();
        assertThat(SafeImageFetcher.magicMatches("image/gif", "GIF87a....".getBytes())).isTrue();
        assertThat(SafeImageFetcher.magicMatches("image/webp", "RIFF....WEBPVP8 ".getBytes())).isTrue();
        assertThat(SafeImageFetcher.magicMatches("image/webp", "RIFF....WAVEfmt ".getBytes())).isFalse();
        assertThat(SafeImageFetcher.magicMatches("image/svg+xml", "<svg/>".getBytes())).isFalse();
    }
}
