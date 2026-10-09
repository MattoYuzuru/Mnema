package app.mnema.learning.admin;

import app.mnema.learning.support.PostgresIntegrationTest;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Signed access tokens, the actual servlet security chains, live Identity verification and real PostgreSQL. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminConsoleHttpIntegrationTest extends PostgresIntegrationTest {
    private static final UUID OWNER=UUID.randomUUID();
    private static final String ISSUER="https://console-identity.example.test";
    private static final RSAKey KEY;
    private static final HttpServer IDENTITY;
    private static final ExecutorService EXECUTOR=Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpClient CLIENT=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final AtomicInteger LIVENESS_CALLS=new AtomicInteger();
    private static volatile int identityStatus=200;
    private static volatile boolean admin=true;
    private static volatile boolean mismatch;
    static {
        try {
            KEY=new RSAKeyGenerator(2048).keyID("console-http-test").generate();
            IDENTITY=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            IDENTITY.setExecutor(EXECUTOR);
            IDENTITY.createContext("/oauth2/jwks", exchange -> {
                byte[] body=new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(200,body.length);
                try(var output=exchange.getResponseBody()) { output.write(body); }
            });
            IDENTITY.createContext("/", exchange -> {
                String subject;
                try { subject=SignedJWT.parse(exchange.getRequestHeaders().getFirst("Authorization").substring(7)).getJWTClaimsSet().getSubject(); }
                catch(java.text.ParseException failure) { subject="invalid"; }
                boolean userInfo=exchange.getRequestURI().getPath().equals("/userinfo");
                if(userInfo) LIVENESS_CALLS.incrementAndGet();
                if(mismatch) subject=UUID.randomUUID().toString();
                String value=userInfo ? "{\"sub\":\""+subject+"\"}" : "{\"accountId\":\""+subject+"\",\"emailVerified\":true,\"admin\":"+admin+"}";
                byte[] body=value.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(identityStatus,body.length);
                try(var output=exchange.getResponseBody()) { output.write(body); }
            });
            IDENTITY.start();
        } catch(Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    @LocalServerPort int port;
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry properties) {
        properties.add("learning.admin.owner-id",OWNER::toString);
        properties.add("learning.events.owner-id",OWNER::toString);
        properties.add("learning.identity.issuer",()->ISSUER);
        properties.add("learning.identity.transport-base",()->"http://127.0.0.1:"+IDENTITY.getAddress().getPort());
        properties.add("learning.identity.allow-loopback-http",()->true);
    }
    @BeforeEach void reset() { identityStatus=200;admin=true;mismatch=false;LIVENESS_CALLS.set(0); }
    @AfterAll static void stop() { IDENTITY.stop(0);EXECUTOR.close();CLIENT.close(); }

    @Test void eachConsoleEndpointRequiresSignedScopedCurrentExactOwner() throws Exception {
        String owner=token(OWNER,"learning.read",false);
        String stranger=token(UUID.randomUUID(),"learning.read",false);
        for(String path:paths()) {
            assertThat(get(path,null).statusCode()).isEqualTo(401);
            assertThat(get(path,stranger).statusCode()).isEqualTo(403);
            var result=get(path,owner);assertThat(result.statusCode()).as(path).isEqualTo(200);
            assertThat(result.headers().firstValue("Cache-Control")).contains("private, no-store");
        }
        assertThat(LIVENESS_CALLS.get()).isEqualTo(8);
        assertThat(get("/admin/console/access",token(OWNER,"account.read",false)).statusCode()).isEqualTo(403);
        assertThat(get("/admin/console/access",token(OWNER,"learning.read",true)).statusCode()).isEqualTo(401);
        assertThat(get("/admin/console/report?from=0000-12-31&to=0001-01-01",owner).statusCode()).isEqualTo(400);
    }

    @Test void revokedOrUnverifiableOwnerNeverReceivesCachedPrivateReport() throws Exception {
        String owner=token(OWNER,"learning.read",false);
        assertThat(get("/admin/console/access",owner).statusCode()).isEqualTo(200);
        identityStatus=401;
        for(String path:paths()) assertThat(get(path,owner).statusCode()).as(path).isEqualTo(401);
        identityStatus=503;
        assertThat(get("/admin/console/report?from=2020-01-01&to=2020-01-02",owner).statusCode()).isEqualTo(503);
        identityStatus=200;mismatch=true;
        assertThat(get("/admin/console/audit",owner).statusCode()).isEqualTo(503);
    }

    @Test void ownerReadAccessDoesNotSilentlyGrantEditorialOrDelegableRoles() throws Exception {
        String earlier=token(OWNER,"learning.read",false);
        var granted=JSON.readTree(get("/admin/console/access",earlier).body()).path("permissions");
        assertThat(granted.path("promos").booleanValue()).isTrue();
        admin=false;
        String owner=token(OWNER,"learning.read",false,"1");
        var response=get("/admin/console/access",owner);assertThat(response.statusCode()).isEqualTo(200);
        var permissions=JSON.readTree(response.body()).path("permissions");
        assertThat(permissions.path("events").booleanValue()).isTrue();
        assertThat(permissions.path("promos").booleanValue()).isFalse();
        assertThat(permissions.path("moderation").booleanValue()).isFalse();
        assertThat(get("/admin/promo-codes",owner).statusCode()).isEqualTo(403);
        assertThat(get("/admin/console/report?from=2020-01-01&to=2020-01-02",owner).statusCode()).isEqualTo(200);
        admin=true;
        var restored=JSON.readTree(get("/admin/console/access",owner).body()).path("permissions");
        assertThat(restored.path("promos").booleanValue()).isTrue();
        assertThat(restored.path("moderation").booleanValue()).isTrue();
    }

    @Test void aLearnerWebTokenNeverReachesConsoleOrSupportRoutesEvenForTheOwnerAccount() throws Exception {
        String web=token(OWNER,"learning.read",false,"0","mnema-web");
        String untagged=token(OWNER,"learning.read",false,"0",null);
        String owner=token(OWNER,"learning.read",false);
        String stranger=token(UUID.randomUUID(),"learning.read",false);
        var routes=new java.util.ArrayList<>(paths()); routes.add("/admin/support/tickets"); routes.add("/admin/support/tickets/1");
        for(String path:routes) {
            assertThat(get(path,web).statusCode()).as("web "+path).isEqualTo(403);
            assertThat(get(path,untagged).statusCode()).as("untagged "+path).isEqualTo(403);
            assertThat(get(path,stranger).statusCode()).as("admin client, not the owner "+path).isEqualTo(403);
        }
        assertThat(post("/admin/support/tickets/1/commands",token(OWNER,"learning.write",false,"0","mnema-web"),"{}").statusCode()).isEqualTo(403);
        assertThat(get("/admin/support/tickets",owner).statusCode()).as("owner through the admin client reaches the unconfigured bridge").isEqualTo(503);
        assertThat(JSON.readTree(get("/admin/support/tickets",owner).body()).path("code").stringValue("")).isEqualTo("SUPPORT_UNAVAILABLE");
        assertThat(JSON.readTree(get("/admin/console/access",owner).body()).path("permissions").path("support").booleanValue()).isFalse();
        assertThat(get("/admin/events",web).statusCode()).as("the events-owner flow keeps working with the learner client").isEqualTo(200);
    }

    private HttpResponse<String> post(String path,String token,String body) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path)).timeout(Duration.ofSeconds(15))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private List<String> paths() { return List.of("/admin/console/access","/admin/console/report?from=2020-01-01&to=2020-01-02","/admin/console/users/"+OWNER+"?from=2020-01-01&to=2020-01-02","/admin/console/audit"); }
    private HttpResponse<String> get(String path,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path)).timeout(Duration.ofSeconds(15)).GET();
        if(token!=null) request.header("Authorization","Bearer "+token);
        return CLIENT.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private static String token(UUID subject,String scope,boolean expired) throws Exception {
        return token(subject,scope,expired,"0");
    }
    private static String token(UUID subject,String scope,boolean expired,String generation) throws Exception {
        return token(subject,scope,expired,generation,"mnema-admin-web");
    }
    private static String token(UUID subject,String scope,boolean expired,String generation,String client) throws Exception {
        Instant now=Instant.now();
        var claims=new JWTClaimsSet.Builder().issuer(ISSUER).subject(subject.toString()).audience("mnema-api").issueTime(Date.from(now.minusSeconds(10)))
                .expirationTime(Date.from(now.plusSeconds(expired ? -60 : 300))).claim("scope",scope).claim("generation",generation);
        if(client!=null) claims.claim("client_id",client);
        var token=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(new JOSEObjectType("at+jwt")).keyID(KEY.getKeyID()).build(),claims.build());
        token.sign(new RSASSASigner(KEY));return token.serialize();
    }
}
