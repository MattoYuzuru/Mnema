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
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/** Without MNEMA_ADMIN_OWNER_ACCOUNT_ID the console is closed for every token and the earlier editors keep their own authority. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminConsoleDisabledHttpIntegrationTest extends PostgresIntegrationTest {
    private static final UUID EVENTS_OWNER=UUID.randomUUID();
    private static final String ISSUER="https://disabled-console-identity.example.test";
    private static final RSAKey KEY;
    private static final HttpServer IDENTITY;
    private static final ExecutorService EXECUTOR=Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpClient CLIENT=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    static {
        try {
            KEY=new RSAKeyGenerator(2048).keyID("console-disabled-test").generate();
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
                String value=userInfo ? "{\"sub\":\""+subject+"\"}" : "{\"accountId\":\""+subject+"\",\"emailVerified\":true,\"admin\":true}";
                byte[] body=value.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(200,body.length);
                try(var output=exchange.getResponseBody()) { output.write(body); }
            });
            IDENTITY.start();
        } catch(Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    @LocalServerPort int port;
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry properties) {
        properties.add("learning.events.owner-id",EVENTS_OWNER::toString);
        properties.add("learning.identity.issuer",()->ISSUER);
        properties.add("learning.identity.transport-base",()->"http://127.0.0.1:"+IDENTITY.getAddress().getPort());
        properties.add("learning.identity.allow-loopback-http",()->true);
    }
    @AfterAll static void stop() { IDENTITY.stop(0);EXECUTOR.close();CLIENT.close(); }

    @Test void theConsoleAndSupportAreClosedForEveryTokenWhileEventsAndPromoAdministrationKeepTheirOwnAuthority() throws Exception {
        for(String client:new String[]{"mnema-admin-web","mnema-web"}) {
            String token=token(EVENTS_OWNER,client);
            for(String path:new String[]{"/admin/console/access","/admin/console/report?from=2020-01-01&to=2020-01-02","/admin/console/audit","/admin/support/tickets"})
                assertThat(get(path,token).statusCode()).as(client+" "+path).isEqualTo(403);
        }
        String web=token(EVENTS_OWNER,"mnema-web");
        assertThat(get("/admin/events",web).statusCode()).as("the events editor keeps working with the learner client").isEqualTo(200);
        assertThat(get("/admin/promo-codes",web).statusCode()).as("promo administration keeps its live Identity-admin authority").isEqualTo(200);
    }

    private HttpResponse<String> get(String path,String token) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path)).timeout(Duration.ofSeconds(15)).GET()
                .header("Authorization","Bearer "+token).build(),HttpResponse.BodyHandlers.ofString());
    }
    private static String token(UUID subject,String client) throws Exception {
        Instant now=Instant.now();
        var claims=new JWTClaimsSet.Builder().issuer(ISSUER).subject(subject.toString()).audience("mnema-api").issueTime(Date.from(now.minusSeconds(10)))
                .expirationTime(Date.from(now.plusSeconds(300))).claim("scope","learning.read").claim("generation","0").claim("client_id",client).build();
        var token=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(new JOSEObjectType("at+jwt")).keyID(KEY.getKeyID()).build(),claims);
        token.sign(new RSASSASigner(KEY));return token.serialize();
    }
}
