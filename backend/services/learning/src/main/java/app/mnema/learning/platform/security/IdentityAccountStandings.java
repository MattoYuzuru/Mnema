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
 * {@link AccountStandings} over the Identity transport. Verified standings are cached for {@value #TTL_SECONDS} seconds per account and token
 * generation. Privileged role decisions use {@link #fresh(Jwt)} and always ask Identity; an unverified or unknown standing is never cached.
 * The cache is a bounded best-effort optimization of one instance, not state.
 */
@Component
final class IdentityAccountStandings implements AccountStandings {
    static final long TTL_SECONDS = 60;
    private static final int MAX_ENTRIES = 10_000;
    private static final int MAX_BODY_BYTES = 16_384;

    private record Cached(Standing standing, Instant expiresAt) { }
    private record CacheKey(String accountId, long generation) { }

    private final IdentityHttp http;
    private final IdentityEndpoints endpoints;
    private final Clock clock;
    private final ContentJsonReader json = new ContentJsonReader(MAX_BODY_BYTES, 16, 2_048);
    private final Map<CacheKey, Cached> cache = new ConcurrentHashMap<>();

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
        return read(token, true);
    }

    @Override
    public Optional<Standing> fresh(Jwt token) {
        return read(token, false);
    }

    private Optional<Standing> read(Jwt token, boolean useCache) {
        if (endpoints.base() == null || token == null || token.getSubject() == null) return Optional.empty();
        CacheKey key;
        try {
            if (!(token.getClaim("generation") instanceof String generation)) return Optional.empty();
            long value = Long.parseLong(generation);
            if (value < 0) return Optional.empty();
            key = new CacheKey(token.getSubject(), value);
        } catch (NumberFormatException failure) { return Optional.empty(); }
        Instant now = clock.instant();
        Cached cached = useCache ? cache.get(key) : null;
        if (cached != null && now.isBefore(cached.expiresAt())) return Optional.of(cached.standing());
        try {
            var response = http.get(endpoints.endpoint("/api/accounts/me"), token.getTokenValue(), MAX_BODY_BYTES);
            if (response.statusCode() != 200) { cache.remove(key); return Optional.empty(); }
            JsonNode body = json.read(response.body());
            if (!token.getSubject().equals(body.path("accountId").stringValue(null))
                    || !body.path("emailVerified").isBoolean() || !body.path("admin").isBoolean()) {
                cache.remove(key);
                return Optional.empty();
            }
            Standing standing = new Standing(body.path("emailVerified").booleanValue(), body.path("admin").booleanValue());
            if (standing.emailVerified()) {
                if (cache.size() >= MAX_ENTRIES) cache.clear();
                cache.put(key, new Cached(standing, now.plus(Duration.ofSeconds(TTL_SECONDS))));
            } else {
                cache.remove(key);
            }
            return Optional.of(standing);
        } catch (IOException | RuntimeException failure) {
            cache.remove(key);
            return Optional.empty();
        }
    }
}
