package app.mnema.identityaccount.federation;

import app.mnema.identityaccount.support.PostgresIntegrationTest;
import app.mnema.identityaccount.account.AccountStore;
import app.mnema.identityaccount.contract.AccountAccess;
import app.mnema.identityaccount.security.*;
import com.fasterxml.jackson.databind.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "identity.deletion.enabled=true",
        "identity.deletion.recovery-period=PT1H",
        "identity.deletion.scan-delay=PT24H"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(FederationHttpIntegrationTest.Clients.class)
class FederationHttpIntegrationTest extends PostgresIntegrationTest {
    static final HttpServer SERVER;
    static final RSAKey PROVIDER_KEY;
    static final Map<String, Map<String, String>> USERS = new ConcurrentHashMap<>();
    static final List<Map<String, String>> TOKEN_REQUESTS = new ArrayList<>();

    static {
        try {
            PROVIDER_KEY = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("provider-fixture").generate();
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SERVER.createContext("/", exchange -> {
                try {
                    String path = exchange.getRequestURI().getPath();
                    String provider = path.split("/")[1];
                    if (path.equals("/yandex/user") &&
                            !"OAuth synthetic-access".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                        exchange.sendResponseHeaders(401, -1);
                        return;
                    }
                    var user = USERS.get(provider);
                    Object result;
                    if (path.endsWith("/jwks")) result = new JWKSet(PROVIDER_KEY.toPublicJWK()).toJSONObject();
                    else if (path.endsWith("/token")) {
                        var parameters = parameters(
                                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                        TOKEN_REQUESTS.add(parameters);
                        Map<String, Object> token = new HashMap<>();
                        token.put("access_token", "synthetic-access");
                        token.put("token_type", "Bearer");
                        token.put("expires_in", 300);
                        if (provider.equals("google")) {
                            var jwt = new SignedJWT(
                                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(PROVIDER_KEY.getKeyID()).build(),
                                    new JWTClaimsSet.Builder().issuer("https://accounts.google.com")
                                            .subject(user.get("subject")).audience("fixture-google")
                                            .issueTime(Date.from(Instant.now()))
                                            .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                                            .claim("nonce", user.get("nonce")).claim("email", user.get("email"))
                                            .claim("email_verified", true).build());
                            jwt.sign(new RSASSASigner(PROVIDER_KEY));
                            token.put("id_token", jwt.serialize());
                        }
                        result = token;
                    } else if (path.endsWith("/emails"))
                        result = List.of(Map.of("email", user.get("email"), "verified", true, "primary", true));
                    else result = switch (provider) {
                            case "google" ->
                                    Map.of("sub", user.get("subject"), "email", user.get("email"), "email_verified",
                                            true);
                            case "github" ->
                                    Map.of("id", user.get("subject"), "email", "untrusted-public@example.test");
                            default ->
                                    Map.of("id", user.get("subject"), "default_email", user.get("email"), "client_id",
                                            "fixture-yandex");
                        };
                    byte[] bytes = new ObjectMapper().writeValueAsBytes(result);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch (Exception e) {
                    exchange.sendResponseHeaders(500, -1);
                } finally {
                    exchange.close();
                }
            });
            SERVER.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static String endpoint(String path) {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort() + path;
    }

    @DynamicPropertySource
    static void fixtures(DynamicPropertyRegistry r) {
        r.add("identity.federation.github-emails-uri", () -> endpoint("/github/emails"));
        r.add("identity.federation.allow-loopback-http", () -> true);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Clients {
        @Bean
        @Primary
        ClientRegistrationRepository fixtureRegistrations() {
            List<ClientRegistration> clients = new ArrayList<>();
            for (String provider : List.of("google", "github", "yandex")) {
                var b = ClientRegistration.withRegistrationId(provider).clientId("fixture-" + provider)
                        .clientSecret("synthetic-client-secret")
                        .clientAuthenticationMethod(provider.equals("yandex") ? ClientAuthenticationMethod.CLIENT_SECRET_POST : ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .redirectUri("https://identity.mnema.test/login/oauth2/code/" + provider)
                        .authorizationUri(endpoint("/" + provider + "/authorize"))
                        .tokenUri(endpoint("/" + provider + "/token")).userInfoUri(endpoint("/" + provider + "/user"))
                        .userNameAttributeName(provider.equals("google") ? "sub" : "id").scope(provider.equals(
                                "google") ? new String[]{"openid", "email", "profile"} : provider.equals("yandex") ? new String[]{"login:email,login:info"} : new String[]{"read:user", "user:email"});
                if (provider.equals("google"))
                    b.issuerUri("https://accounts.google.com").jwkSetUri(endpoint("/google/jwks"));
                clients.add(b.build());
            }
            return new InMemoryClientRegistrationRepository(clients);
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    AccountStore accounts;
    @Autowired
    OwnershipProofs proofs;
    @Autowired
    TransactionTemplate tx;

    record Pending(String provider, String state, Cookie cookie) {
    }

    Pending begin(String provider, String subject, String email, Cookie cookie) throws Exception {
        var request = get("/oauth2/authorization/" + provider).secure(true);
        if (cookie != null) request.cookie(cookie);
        var result = mvc.perform(request).andExpect(status().is3xxRedirection()).andReturn();
        var params = parameters(URI.create(result.getResponse().getRedirectedUrl()).getRawQuery());
        assertThat(params).containsEntry("code_challenge_method", "S256").containsKey("code_challenge");
        var user = new HashMap<String, String>();
        user.put("subject", subject);
        user.put("email", email);
        if (params.containsKey("nonce")) user.put("nonce", params.get("nonce"));
        USERS.put(provider, user);
        Cookie returned = result.getResponse().getCookie("SESSION");
        return new Pending(provider, params.get("state"), returned == null ? cookie : returned);
    }

    MvcResult callback(Pending pending) throws Exception {
        return mvc.perform(get("/login/oauth2/code/" + pending.provider()).secure(true).cookie(pending.cookie())
                .queryParam("state", pending.state()).queryParam("code", "synthetic-code")).andReturn();
    }

    @Autowired
    org.springframework.session.jdbc.JdbcIndexedSessionRepository sessionRepository;

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void expiredStateAndWrongReauthenticationSubjectFailClosed() throws Exception {
        var expired = begin("github", UUID.randomUUID().toString(), UUID.randomUUID() + "@example.test", null);
        String sessionId = new String(Base64.getDecoder().decode(expired.cookie().getValue()), StandardCharsets.UTF_8);
        org.springframework.session.Session session = sessionRepository.findById(sessionId);
        session.setAttribute("identity.oauth-expiry", 0L);
        ((org.springframework.session.SessionRepository) sessionRepository).save(session);
        assertThat(callback(expired).getResponse().getRedirectedUrl()).contains("error=federation_failed");
        var first = callback(begin("google", UUID.randomUUID().toString(), UUID.randomUUID() + "@example.test", null));
        var cookie = first.getResponse().getCookie("SESSION");
        mvc.perform(post("/api/accounts/me/proofs/federated").secure(true).cookie(cookie).with(csrf())
                        .contentType("application/json").content("{\"provider\":\"google\",\"purpose\":\"LINK_IDENTITY\"}"))
                .andExpect(status().isOk());
        var wrongSubject = callback(
                begin("google", UUID.randomUUID().toString(), UUID.randomUUID() + "@example.test", cookie));
        assertThat(wrongSubject.getResponse().getRedirectedUrl()).contains("error=federation_failed");
    }

    @Test
    void earlierOrdinaryLoginStateCannotAcquireALaterLinkIntent() throws Exception {
        var first = callback(begin("google", UUID.randomUUID().toString(), UUID.randomUUID() + "@example.test", null));
        var cookie = first.getResponse().getCookie("SESSION");
        var profile = mvc.perform(get("/api/accounts/session").secure(true).cookie(cookie)).andExpect(status().isOk())
                .andReturn();
        UUID original = UUID.fromString(
                json.readTree(profile.getResponse().getContentAsString()).get("accountId").asText());
        var pending = begin("github", UUID.randomUUID().toString(), UUID.randomUUID() + "@example.test", cookie);
        var proof = tx.execute(
                status -> proofs.issue(accounts.get(original, false).access(), OwnershipProofs.Purpose.LINK_IDENTITY));
        mvc.perform(post("/api/accounts/me/identities/link").secure(true).cookie(cookie).with(csrf())
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("provider", "github", "proof", proof.token()))))
                .andExpect(status().isOk());
        assertThat(callback(pending).getResponse().getRedirectedUrl()).isEqualTo("https://mnema.app/auth/callback");
        assertThat(jdbc.sql("SELECT count(*) FROM app_identity.external_identity WHERE account_id=:id")
                .param("id", original).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void allThreeProvidersUseRealTokenAndUserInfoHttpThenDurableUuidSessions() throws Exception {
        for (String provider : List.of("google", "github", "yandex")) {
            String subject = UUID.randomUUID().toString();
            String email = UUID.randomUUID() + "@example.test";
            var pending = begin(provider, subject, email, null);
            var result = callback(pending);
            assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("https://mnema.app/auth/callback");
            Cookie cookie = result.getResponse().getCookie("SESSION");
            var session = mvc.perform(get("/api/accounts/session").secure(true).cookie(cookie))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.hasPassword").value(false))
                    .andExpect(jsonPath("$.emailVerified").value(!provider.equals("yandex"))).andReturn();
            String accountId = json.readTree(session.getResponse().getContentAsString()).get("accountId").asText();
            assertThat(jdbc.sql("SELECT provider_subject FROM app_identity.external_identity WHERE account_id=:id")
                    .param("id", UUID.fromString(accountId)).query(String.class).single()).isEqualTo(subject);
            assertThat(TOKEN_REQUESTS.getLast()).containsKey("code_verifier");
            assertThat(callback(pending).getResponse().getRedirectedUrl()).contains("error=federation_failed");
        }
    }

    @Test
    void foreignStateAndProviderMixupCannotCreateAccount() throws Exception {
        var pending = begin("github", UUID.randomUUID().toString(), UUID.randomUUID() + "@example.test", null);
        long before = jdbc.sql("SELECT count(*) FROM app_identity.account").query(Long.class).single();
        mvc.perform(get("/login/oauth2/code/yandex").secure(true).cookie(pending.cookie())
                        .queryParam("state", pending.state()).queryParam("code", "synthetic-code"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login?error=federation_failed"));
        assertThat(jdbc.sql("SELECT count(*) FROM app_identity.account").query(Long.class).single()).isEqualTo(before);
        var wrong = begin("github", UUID.randomUUID().toString(), UUID.randomUUID() + "@example.test", null);
        mvc.perform(
                        get("/login/oauth2/code/github").secure(true).cookie(wrong.cookie()).queryParam("state", "wrong-state")
                                .queryParam("code", "synthetic-code")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error=federation_failed"));
    }

    @Test
    void federatedOnlyOwnerReauthenticatesAndExplicitlyLinksSecondProvider() throws Exception {
        String subject = UUID.randomUUID().toString(), email = UUID.randomUUID() + "@example.test";
        var first = callback(begin("google", subject, email, null));
        Cookie cookie = first.getResponse().getCookie("SESSION");
        mvc.perform(post("/api/accounts/me/proofs/federated").secure(true).cookie(cookie).with(csrf())
                        .contentType("application/json").content("{\"provider\":\"google\",\"purpose\":\"LINK_IDENTITY\"}"))
                .andExpect(status().isOk());
        var verified = callback(begin("google", subject, email, cookie));
        assertThat(verified.getResponse().getStatus()).isEqualTo(200);
        var proof = json.readTree(verified.getResponse().getContentAsString()).get("token").asText();
        cookie = verified.getResponse().getCookie("SESSION");
        mvc.perform(post("/api/accounts/me/identities/link").secure(true).cookie(cookie).with(csrf())
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("provider", "github", "proof", proof))))
                .andExpect(status().isOk());
        var linked = callback(
                begin("github", UUID.randomUUID().toString(), UUID.randomUUID() + "@example.test", cookie));
        assertThat(linked.getResponse().getRedirectedUrl()).isEqualTo("https://mnema.app/auth/callback");
        mvc.perform(get("/api/accounts/me/identities").secure(true).cookie(linked.getResponse().getCookie("SESSION")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].providerSubject").doesNotExist());
    }

    @Test
    void exactBoundProviderCompletesOnlyARecoveryContext() throws Exception {
        String subject = UUID.randomUUID().toString();
        String email = UUID.randomUUID() + "@example.test";
        callback(begin("google", subject, email, null));
        UUID account = jdbc.sql("""
                        SELECT account_id FROM app_identity.external_identity
                        WHERE provider='google' AND provider_subject=:subject
                        """).param("subject", subject).query(UUID.class).single();
        jdbc.sql("""
                        UPDATE app_identity.account
                        SET status='BANNED',banned_at=transaction_timestamp(),security_generation=security_generation+1
                        WHERE account_id=:account
                        """).param("account", account).update();

        var proofStart = mvc.perform(post("/api/accounts/deletion/proof/federated").secure(true).with(csrf())
                        .contentType("application/json").content("{\"provider\":\"google\"}"))
                .andExpect(status().isOk()).andReturn();
        var proofResponse = callback(begin("google", subject, email,
                proofStart.getResponse().getCookie("SESSION")));
        String proof = json.readTree(proofResponse.getResponse().getContentAsString()).get("token").asText();
        var deletion = mvc.perform(post("/api/accounts/deletion/confirmed").secure(true).with(csrf())
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("proof", proof))))
                .andExpect(status().isAccepted()).andReturn();
        String operation = json.readTree(deletion.getResponse().getContentAsString()).get("operationId").asText();

        var start = mvc.perform(post("/api/accounts/deletion/recovery/federated").secure(true).with(csrf())
                        .contentType("application/json").content("{\"provider\":\"google\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationUrl").value("/oauth2/authorization/google")).andReturn();
        Cookie intentSession = start.getResponse().getCookie("SESSION");
        var recovered = callback(begin("google", subject, email, intentSession));
        assertThat(recovered.getResponse().getRedirectedUrl())
                .isEqualTo("https://mnema.app/account-deletion/recovery");
        Cookie recovery = recovered.getResponse().getCookie("SESSION");
        mvc.perform(get("/api/accounts/deletion/recovery/" + operation).secure(true).cookie(recovery))
                .andExpect(status().isOk()).andExpect(jsonPath("$.context").value("ACCOUNT_RECOVERY"));
        mvc.perform(get("/api/accounts/me").secure(true).cookie(recovery)).andExpect(status().isForbidden());
    }

    static Map<String, String> parameters(String input) {
        Map<String, String> result = new HashMap<>();
        for (String part : input.split("&")) {
            String[] pair = part.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
        }
        return result;
    }
}
