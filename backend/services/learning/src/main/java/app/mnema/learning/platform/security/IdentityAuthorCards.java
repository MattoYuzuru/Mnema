package app.mnema.learning.platform.security;

import app.mnema.learning.platform.json.ContentJsonReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * {@link AuthorCards} over the Identity transport. It has a transport of its own with a deadline far below the general Identity one
 * ({@code learning.identity.author-card-timeout}, 150 ms, at most {@code author-card-concurrency} calls at once): a link preview has a few hundred
 * milliseconds in all, and a card that does not arrive in time is simply left out. An answer ("here is the card" or "there is none") is cached for
 * {@value #TTL_SECONDS} seconds, the lifetime Identity gives its own public cards; anything else (timeout, 5xx, 429, a malformed body) is neither a card
 * nor a known absence, and is remembered for only {@value #FAILURE_TTL_SECONDS} seconds so that a struggling Identity is not asked again by every preview. The endpoints are configuration, the path is built only from a UUID or a login that passed the same shape check as Identity's.
 */
@Component
final class IdentityAuthorCards implements AuthorCards, AutoCloseable {
    static final long TTL_SECONDS = 60;
    /** How long a failed lookup (timeout, 5xx, 429, malformed answer) is remembered: a slow Identity is not asked again by every preview. */
    static final long FAILURE_TTL_SECONDS = 5;
    private static final int MAX_ENTRIES = 5_000;
    private static final int MAX_BODY_BYTES = 8_192;
    private static final int MAX_NAME_CODE_POINTS = 100;
    private static final int MAX_BIO_CODE_POINTS = 500;
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.-]{3,50}");

    private record Cached(Optional<Card> card, Instant expiresAt) { }

    private final IdentityHttp http;
    private final IdentityEndpoints endpoints;
    private final Clock clock;
    private final ContentJsonReader json = new ContentJsonReader(MAX_BODY_BYTES, 8, 256);
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    @Autowired
    IdentityAuthorCards(IdentityEndpoints endpoints,
                        @Value("${learning.identity.author-card-timeout:150ms}") Duration timeout,
                        @Value("${learning.identity.author-card-concurrency:8}") int concurrency) {
        this(new IdentityHttp(timeout, concurrency), endpoints, Clock.systemUTC());
    }

    IdentityAuthorCards(IdentityHttp http, IdentityEndpoints endpoints, Clock clock) {
        this.http = http;
        this.endpoints = endpoints;
        this.clock = clock;
    }

    @Override
    public Optional<Card> byAccount(UUID accountId) {
        if (accountId == null) return Optional.empty();
        String id = accountId.toString();
        return lookup("id:" + id, "/api/accounts/profiles/" + id, body -> id.equals(body.path("accountId").stringValue(null)));
    }

    @Override
    public Optional<Card> byUsername(String username) {
        if (username == null || !USERNAME.matcher(username).matches()) return Optional.empty();
        return lookup("name:" + username.toLowerCase(Locale.ROOT), "/api/accounts/profiles/by-username/" + username,
                body -> username.equalsIgnoreCase(body.path("profileUsername").stringValue(null)));
    }

    private Optional<Card> lookup(String key, String path, java.util.function.Predicate<JsonNode> sameAccount) {
        if (endpoints.base() == null) return Optional.empty();
        Instant now = clock.instant();
        Cached cached = cache.get(key);
        if (cached != null && now.isBefore(cached.expiresAt())) return cached.card();
        try {
            URI uri = endpoints.endpoint(path);
            var response = http.get(uri, null, MAX_BODY_BYTES);
            if (response.statusCode() == 404) return remember(key, now, Optional.empty());
            if (response.statusCode() != 200) return failed(key, now);
            JsonNode body = json.read(response.body());
            String username = body.path("profileUsername").stringValue(null);
            if (!body.isObject() || username == null || !USERNAME.matcher(username).matches() || !sameAccount.test(body)) return failed(key, now);
            return remember(key, now, Optional.of(new Card(username, line(body.path("displayName").stringValue(null), MAX_NAME_CODE_POINTS), line(body.path("bio").stringValue(null), MAX_BIO_CODE_POINTS))));
        } catch (IOException | RuntimeException failure) {
            return failed(key, now);
        }
    }

    private Optional<Card> failed(String key, Instant now) {
        if (cache.size() >= MAX_ENTRIES) cache.clear();
        cache.put(key, new Cached(Optional.empty(), now.plus(Duration.ofSeconds(FAILURE_TTL_SECONDS))));
        return Optional.empty();
    }

    private Optional<Card> remember(String key, Instant now, Optional<Card> card) {
        if (cache.size() >= MAX_ENTRIES) cache.clear();
        cache.put(key, new Cached(card, now.plus(Duration.ofSeconds(TTL_SECONDS))));
        return card;
    }

    /** A text as one line of at most {@code limit} characters; control characters become spaces, bidirectional formatting characters are dropped. Blank is absent. */
    private static String line(String value, int limit) {
        if (value == null) return null;
        StringBuilder line = new StringBuilder();
        value.codePoints().forEach(point -> {
            if (point == 0x200E || point == 0x200F || point == 0x061C || (point >= 0x202A && point <= 0x202E) || (point >= 0x2066 && point <= 0x2069)) return;
            if (Character.isISOControl(point) || Character.isWhitespace(point)) line.append(' ');
            else line.appendCodePoint(point);
        });
        String text = line.toString().replaceAll(" {2,}", " ").strip();
        if (text.isEmpty()) return null;
        return text.codePointCount(0, text.length()) <= limit ? text : text.substring(0, text.offsetByCodePoints(0, limit)).strip();
    }

    @Override
    public void close() {
        http.close();
    }
}
