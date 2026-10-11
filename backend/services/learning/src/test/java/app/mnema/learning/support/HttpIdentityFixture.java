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
    private static final AtomicInteger USER_INFO_CALLS = new AtomicInteger();

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
                byte[] body = ("{\"sub\":\"" + subject + "\",\"mnema_public_profile\":" + PUBLIC_PROFILE.contains(subject) + "}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
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

    public static int userInfoCalls() { return USER_INFO_CALLS.get(); }

    public static String token(UUID actor, String scopes) {
        try {
            var claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject(actor.toString()).audience("mnema-api")
                    .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .claim("generation", "0").claim("scope", scopes).build();
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
