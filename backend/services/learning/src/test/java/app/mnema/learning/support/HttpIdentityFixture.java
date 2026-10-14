package app.mnema.learning.support;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A signing Identity for HTTP tests of the real security filters: it serves the public key (JWKS) and a {@code /userinfo} that answers with the subject
 * of the presented token (or 401 once the account is {@link #revoke revoked}). One instance serves the whole test JVM; a test class registers it with
 * {@link #register} and sends requests through {@link #send}.
 */
public final class HttpIdentityFixture {
    public static final String ISSUER = "https://shared-decks-identity.example.test";
    private static final RSAKey KEY;
    private static final HttpServer IDENTITY;
    private static final HttpClient CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private static final java.util.Set<String> REVOKED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> PUBLIC_PROFILE = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> TRUSTED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> NEW_ACCOUNT = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** accounts whose /userinfo leaves the two anti-abuse claims out, like an Identity that predates them */
    private static final java.util.Set<String> NO_SIGNALS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final AtomicInteger USER_INFO_CALLS = new AtomicInteger();
    /** Public author cards by account id and by lower-case login, as Identity's {@code /api/accounts/profiles/...} answers them. */
    private static final Map<String, String> CARDS_BY_ID = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, String> CARDS_BY_NAME = new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicInteger CARD_CALLS = new AtomicInteger();
    private static volatile int cardFailure;
    private static volatile long cardDelayMillis;
    /** lower-case profile username to account id: the public profiles of the by-username lookup (consent on, login set) */
    private static final java.util.Map<String, UUID> PROFILES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicInteger PROFILE_LOOKUPS = new AtomicInteger();
    /** a login for which the lookup answers 429 with Retry-After 7, and one for which it answers 500 */
    public static final String LIMITED_LOGIN = "limited-login";
    public static final String BROKEN_LOGIN = "broken-login";

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("shared-decks-test").generate();
            IDENTITY = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            IDENTITY.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            IDENTITY.createContext("/oauth2/jwks", exchange -> {
                byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            IDENTITY.createContext("/userinfo", exchange -> {
                USER_INFO_CALLS.incrementAndGet();
                String subject;
                try {
                    subject = SignedJWT.parse(exchange.getRequestHeaders().getFirst("Authorization").substring(7)).getJWTClaimsSet().getSubject();
                } catch (java.text.ParseException | RuntimeException failure) {
                    subject = "invalid";
                }
                int status = REVOKED.contains(subject) ? 401 : 200;
                byte[] body = ("{\"sub\":\"" + subject + "\",\"mnema_public_profile\":" + PUBLIC_PROFILE.contains(subject)
                        + (NO_SIGNALS.contains(subject) ? "" : ",\"mnema_trusted\":" + TRUSTED.contains(subject) + ",\"mnema_new_account\":" + NEW_ACCOUNT.contains(subject)) + "}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            // One handler for everything Identity publishes under /api/accounts/profiles/: the public author cards (by account id and by login, for link
            // previews) and the by-username lookup of Share/13 (a login that is limited, broken, public or unknown).
            IDENTITY.createContext("/api/accounts/profiles/", exchange -> {
                CARD_CALLS.incrementAndGet();
                if (cardDelayMillis > 0) {
                    try { Thread.sleep(cardDelayMillis); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                String rest = exchange.getRequestURI().getPath().substring("/api/accounts/profiles/".length());
                boolean byName = rest.startsWith("by-username/");
                String requested = byName ? rest.substring("by-username/".length()) : rest;
                if (byName) PROFILE_LOOKUPS.incrementAndGet();
                String lower = requested.toLowerCase(java.util.Locale.ROOT);
                int status = 200;
                String body;
                if (cardFailure != 0) {
                    status = cardFailure;
                    body = "{}";
                } else if (byName && LIMITED_LOGIN.equals(requested)) {
                    status = 429;
                    body = "{\"code\":\"try_later\"}";
                    exchange.getResponseHeaders().set("Retry-After", "7");
                } else if (byName && BROKEN_LOGIN.equals(requested)) {
                    status = 500;
                    body = "{}";
                } else {
                    String card = byName ? CARDS_BY_NAME.get(lower) : CARDS_BY_ID.get(requested);
                    UUID account = byName ? PROFILES.get(lower) : null;
                    if (card != null) {
                        body = card;
                    } else if (account != null) {
                        body = "{\"accountId\":\"" + account + "\",\"profileUsername\":\"" + requested + "\",\"displayName\":null,\"bio\":null,\"avatarPresent\":false}";
                    } else {
                        status = 404;
                        body = "{\"code\":\"profile_not_found\"}";
                    }
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            IDENTITY.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> IDENTITY.stop(0)));
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private HttpIdentityFixture() { }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("learning.identity.issuer", () -> ISSUER);
        registry.add("learning.identity.transport-base", () -> "http://127.0.0.1:" + IDENTITY.getAddress().getPort());
        registry.add("learning.identity.allow-loopback-http", () -> true);
    }

    public static void revoke(UUID account) { REVOKED.add(account.toString()); }

    public static void restore(UUID account) { REVOKED.remove(account.toString()); }

    /** Identity's answer for the claim {@code mnema_public_profile} (consent on and a login set) of the account; false by default. */
    public static void publicProfile(UUID account, boolean ready) {
        if (ready) PUBLIC_PROFILE.add(account.toString());
        else PUBLIC_PROFILE.remove(account.toString());
    }

    /** Identity's answers for the anti-abuse claims {@code mnema_trusted} and {@code mnema_new_account} of the account; both false by default. */
    public static void signals(UUID account, boolean trusted, boolean newAccount) {
        NO_SIGNALS.remove(account.toString());
        if (trusted) TRUSTED.add(account.toString());
        else TRUSTED.remove(account.toString());
        if (newAccount) NEW_ACCOUNT.add(account.toString());
        else NEW_ACCOUNT.remove(account.toString());
    }

    /** From now on Identity leaves the two anti-abuse claims out of the account's /userinfo (an old Identity); {@link #signals} brings them back. */
    public static void withoutSignals(UUID account) { NO_SIGNALS.add(account.toString()); }

    /** Identity publishes this public card (display name and bio may be null: the owner did not agree to show them). */
    public static void authorCard(UUID account, String username, String displayName, String bio) {
        String json = "{\"accountId\":\"" + account + "\",\"profileUsername\":" + quote(username) + ",\"displayName\":" + quote(displayName)
                + ",\"bio\":" + quote(bio) + ",\"avatarPresent\":false}";
        CARDS_BY_ID.put(account.toString(), json);
        CARDS_BY_NAME.put(username.toLowerCase(java.util.Locale.ROOT), json);
    }

    public static void withdrawAuthorCard(UUID account) {
        String json = CARDS_BY_ID.remove(account.toString());
        if (json != null) CARDS_BY_NAME.values().remove(json);
    }

    /** Every card request answers with this status (0: normal behaviour) after {@code delayMillis}; for the failure paths of link previews. */
    public static void authorCardFailure(int status, long delayMillis) {
        cardFailure = status;
        cardDelayMillis = delayMillis;
    }

    public static int authorCardCalls() { return CARD_CALLS.get(); }

    private static String quote(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    /** Gives the account a public profile with this login (Identity: consent on, a login set); the lookup is case-insensitive. */
    public static void publicLogin(String username, UUID account) { PROFILES.put(username.toLowerCase(java.util.Locale.ROOT), account); }

    /** The profile is hidden or the consent withdrawn: the lookup answers 404 like for an unknown login. */
    public static void hideLogin(String username) { PROFILES.remove(username.toLowerCase(java.util.Locale.ROOT)); }

    public static int profileLookups() { return PROFILE_LOOKUPS.get(); }

    public static int userInfoCalls() { return USER_INFO_CALLS.get(); }

    public static String token(UUID actor, String scopes) { return token(actor, scopes, null); }

    /** An access token of the owner console's client ({@code client_id} claim, RFC 9068), as Identity issues to {@code mnema-admin-web}. */
    public static String adminToken(UUID actor) { return token(actor, "learning.read learning.write", "mnema-admin-web"); }

    public static String token(UUID actor, String scopes, String clientId) {
        try {
            var builder = new JWTClaimsSet.Builder().issuer(ISSUER).subject(actor.toString()).audience("mnema-api")
                    .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .claim("generation", "0").claim("scope", scopes);
            if (clientId != null) builder.claim("client_id", clientId);
            var claims = builder.build();
            var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).type(new JOSEObjectType("at+jwt")).build(), claims);
            token.sign(new RSASSASigner(KEY));
            return token.serialize();
        } catch (com.nimbusds.jose.JOSEException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static String reader(UUID actor) { return token(actor, "learning.read learning.write"); }

    /** One request with the given bearer (may be null) and extra headers; the body is JSON text or null. */
    public static HttpResponse<String> send(int port, String method, String path, String bearer, String body, Map<String, String> headers) {
        try {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path)).timeout(Duration.ofSeconds(20))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            if (bearer != null) request.header("Authorization", "Bearer " + bearer);
            if (body != null) request.header("Content-Type", "application/json");
            headers.forEach(request::header);
            return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }
}
