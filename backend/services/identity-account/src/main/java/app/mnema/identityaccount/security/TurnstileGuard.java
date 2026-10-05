package app.mnema.identityaccount.security;

import app.mnema.identityaccount.contract.AccountFailure;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;

@Service
public final class TurnstileGuard {
    static final URI SITEVERIFY = URI.create("https://challenges.cloudflare.com/turnstile/v0/siteverify");
    public enum Action { LOGIN, REGISTER }
    public record BrowserConfiguration(String mode, String siteKey) { }

    private final TurnstilePolicy policy;
    private final RestClient http;
    private final Clock clock;
    private final RateLimits limits;

    @Autowired
    public TurnstileGuard(@Value("${identity.turnstile.mode}") String mode,
                          @Value("${APP_ENV:dev}") String environment,
                          @Value("${identity.turnstile.privacy-approved}") boolean privacyApproved,
                          @Value("${identity.turnstile.site-key}") String siteKey,
                          @Value("${identity.turnstile.secret-key}") String secret,
                          @Value("${identity.frontend-origin}") URI frontend,
                          @Value("${identity.issuer}") URI issuer, Clock clock, RateLimits limits) {
        this(new TurnstilePolicy(mode, environment, privacyApproved, siteKey, secret, frontend, issuer), client(), clock, limits);
    }

    TurnstileGuard(TurnstilePolicy policy, RestClient http, Clock clock, RateLimits limits) {
        this.policy = policy;
        this.http = http;
        this.clock = clock;
        this.limits = limits;
    }

    private static RestClient client() {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(factory).build();
    }

    public BrowserConfiguration configuration() {
        return new BrowserConfiguration(policy.mode.name().toLowerCase(Locale.ROOT),
                policy.mode == TurnstilePolicy.Mode.REQUIRED ? policy.siteKey : "");
    }

    /** Every protected request consumes its own Siteverify token; results are never cached or retried. */
    public void verify(String token, Action action, String clientAddress) {
        if (policy.mode == TurnstilePolicy.Mode.DISABLED) return;
        if (policy.mode == TurnstilePolicy.Mode.BLOCKED) throw new AccountFailure(503, "abuse_protection_unavailable");
        if (!limits.allow("turnstile", clientAddress, 30)) throw new AccountFailure(429, "try_later");
        if (token == null || token.isBlank() || token.length() > 2048)
            throw new AccountFailure(403, "abuse_verification_failed");
        JsonNode result;
        try {
            // remoteip is deliberately omitted. The browser still communicates directly with Cloudflare.
            result = http.post().uri(SITEVERIFY).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("secret", policy.secret, "response", token)).retrieve().body(JsonNode.class);
        } catch (RestClientException | JacksonException failure) {
            // Upstream errors may contain credentials or token values; never forward/log them.
            throw new AccountFailure(503, "abuse_protection_unavailable");
        }
        try {
            if (result == null || !result.path("success").booleanValue(false) ||
                    !policy.hostnames.contains(result.path("hostname").stringValue("")) ||
                    !action.name().toLowerCase(Locale.ROOT).equals(result.path("action").stringValue("")))
                throw new AccountFailure(403, "abuse_verification_failed");
            var issued = Instant.parse(result.path("challenge_ts").stringValue(""));
            var now = clock.instant();
            if (issued.isBefore(now.minusSeconds(300)) || issued.isAfter(now.plusSeconds(30)))
                throw new AccountFailure(403, "abuse_verification_failed");
        } catch (JacksonException | DateTimeParseException failure) {
            throw new AccountFailure(403, "abuse_verification_failed");
        }
    }
}
