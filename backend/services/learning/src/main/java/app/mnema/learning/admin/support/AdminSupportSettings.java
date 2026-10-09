package app.mnema.learning.admin.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;

/** A fixed machine endpoint; empty configuration keeps the support bridge disabled. */
@Component
public final class AdminSupportSettings {
    private final URI endpoint;
    private final String secret;
    private final Duration timeout;
    private final int concurrency;

    public AdminSupportSettings(@Value("${learning.admin.support.endpoint:}") String endpoint,
                                @Value("${learning.admin.support.secret:}") String secret,
                                @Value("${learning.admin.support.allow-loopback-http:false}") boolean allowLoopbackHttp,
                                @Value("${learning.admin.support.timeout:PT6S}") Duration timeout,
                                @Value("${learning.admin.support.concurrency:4}") int concurrency) {
        if (endpoint == null || secret == null || endpoint.isEmpty() != secret.isEmpty()
                || timeout == null || timeout.compareTo(Duration.ofSeconds(1)) < 0
                || timeout.compareTo(Duration.ofSeconds(10)) > 0 || concurrency < 1 || concurrency > 8) {
            throw new IllegalArgumentException("Invalid administrative support configuration");
        }
        URI parsed = null;
        if (!endpoint.isEmpty()) {
            try { parsed = URI.create(endpoint); }
            catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Invalid support endpoint");
            }
            boolean loopback = allowLoopbackHttp && "http".equals(parsed.getScheme())
                    && ("127.0.0.1".equals(parsed.getHost()) || "[::1]".equals(parsed.getHost()));
            if ((!"https".equals(parsed.getScheme()) && !loopback) || parsed.getHost() == null
                    || parsed.getRawUserInfo() != null || parsed.getRawQuery() != null || parsed.getRawFragment() != null
                    || !"/internal/support".equals(parsed.getRawPath()) || parsed.getPort() == 0 || parsed.getPort() > 65535
                    || !secret.matches("[A-Za-z0-9_-]{32,256}")) {
                throw new IllegalArgumentException("Support requires a fixed HTTPS endpoint and distinct private credential");
            }
        }
        this.endpoint = parsed;
        this.secret = secret;
        this.timeout = timeout;
        this.concurrency = concurrency;
    }

    public boolean enabled() { return endpoint != null; }
    URI endpoint() { return endpoint; }
    String secret() { return secret; }
    Duration timeout() { return timeout; }
    int concurrency() { return concurrency; }
}
