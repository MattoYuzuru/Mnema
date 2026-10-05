package app.mnema.learning.platform.security;

import app.mnema.learning.platform.json.ContentJsonReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link AccountStandings} over the Identity transport. A standing with a verified email is cached for {@value #TTL_SECONDS} seconds per account,
 * so an administrator who loses the grant is refused after at most that long; an unverified or unknown standing is never cached, so a learner who
 * has just confirmed the email is not told otherwise. The cache is a bounded best-effort optimization of one instance, not state.
 */
@Component
final class IdentityAccountStandings implements AccountStandings {
    static final long TTL_SECONDS = 60;
    private static final int MAX_ENTRIES = 10_000;
    private static final int MAX_BODY_BYTES = 16_384;

    private record Cached(Standing standing, Instant expiresAt) { }

    private final IdentityHttp http;
    private final IdentityEndpoints endpoints;
    private final Clock clock;
    private final ContentJsonReader json = new ContentJsonReader(MAX_BODY_BYTES, 16, 2_048);
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    @Autowired
    IdentityAccountStandings(IdentityHttp http, IdentityEndpoints endpoints) {
        this(http, endpoints, Clock.systemUTC());
    }

    IdentityAccountStandings(IdentityHttp http, IdentityEndpoints endpoints, Clock clock) {
        this.http = http;
        this.endpoints = endpoints;
        this.clock = clock;
    }

    @Override
    public Optional<Standing> of(Jwt token) {
        if (endpoints.base() == null || token == null || token.getSubject() == null) return Optional.empty();
        Instant now = clock.instant();
        Cached cached = cache.get(token.getSubject());
        if (cached != null && now.isBefore(cached.expiresAt())) return Optional.of(cached.standing());
        try {
            var response = http.get(endpoints.endpoint("/api/accounts/me"), token.getTokenValue(), MAX_BODY_BYTES);
            if (response.statusCode() != 200) return Optional.empty();
            JsonNode body = json.read(response.body());
            if (!token.getSubject().equals(body.path("accountId").stringValue(null))
                    || !body.path("emailVerified").isBoolean() || !body.path("admin").isBoolean()) {
                return Optional.empty();
            }
            Standing standing = new Standing(body.path("emailVerified").booleanValue(), body.path("admin").booleanValue());
            if (standing.emailVerified()) {
                if (cache.size() >= MAX_ENTRIES) cache.clear();
                cache.put(token.getSubject(), new Cached(standing, now.plus(Duration.ofSeconds(TTL_SECONDS))));
            } else {
                cache.remove(token.getSubject());
            }
            return Optional.of(standing);
        } catch (IOException | RuntimeException failure) {
            return Optional.empty();
        }
    }
}
