package app.mnema.identityaccount.security;

import app.mnema.identityaccount.account.AccountStore;
import app.mnema.identityaccount.contract.*;
import app.mnema.identityaccount.local.LocalAccounts;
import app.mnema.identityaccount.profile.Profiles;
import app.mnema.identityaccount.moderation.Moderation;
import app.mnema.identityaccount.federation.FederatedAccounts;
import app.mnema.identityaccount.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class IdentitySecurityIntegrationTest extends PostgresIntegrationTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    LocalAccounts local;
    @Autowired
    AccountStore accounts;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    OwnershipProofs proofs;
    @Autowired
    Profiles profiles;
    @Autowired
    Moderation moderation;
    @Autowired
    FederatedAccounts federation;
    @Autowired
    JwtDecoder decoder;
    @Autowired
    RegisteredClientRepository clients;
    @Autowired
    org.springframework.security.crypto.password.PasswordEncoder passwords;
    @Autowired
    org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService authorizations;
    final String password = "correct-horse-battery-42";

    AccountAccess account() {
        String key = UUID.randomUUID().toString();
        return local.register(key + "@example.test", key, password, null, key);
    }

    String body(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    Cookie login(AccountAccess access) throws Exception {
        String email = accounts.get(access.accountId(), false).email();
        var result = mvc.perform(post("/api/accounts/login").secure(true).with(csrf()).contentType("application/json")
                        .content(body(Map.of("login", email, "password", password))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountId").value(access.accountId().toString()))
                .andReturn();
        return Arrays.stream(result.getResponse().getCookies()).filter(c -> c.getName().equals("SESSION")).findFirst()
                .orElseThrow();
    }

    @Test
    void normalRuntimeRestartRetainsSessionGrantAndSigningIdentity() throws Exception {
        var account = account();
        var cookie = login(account);
        String access = token(account, "mnema-api", "https://identity.mnema.test", "at+jwt", Instant.now().plusSeconds(120));
        try (var restarted = new org.springframework.boot.builder.SpringApplicationBuilder(app.mnema.identityaccount.IdentityAccountApplication.class)
                .run("--server.port=0", "--spring.datasource.url=" + fixtureJdbcUrl(), "--spring.datasource.username=mnema", "--spring.datasource.password=mnema",
                        "--identity.issuer=https://identity.mnema.test", "--identity.signing.jwk-set-file=" + SIGNING_FILE,
                        "--identity.signing.active-kid=fresh-test-key", "--identity.frontend-origin=https://mnema.app",
                        "--identity.redirect-uri=https://mnema.app/auth/callback", "--identity.avatar.access-key=", "--identity.avatar.secret-key=",
                        "--identity.postbox.access-key=", "--identity.postbox.secret-key=",
                        "--SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID=",
                        "--SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GITHUB_CLIENT_ID=",
                        "--SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_YANDEX_CLIENT_ID=")) {
            var web = (org.springframework.web.context.WebApplicationContext) restarted;
            var restartedMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web)
                    .addFilters(restarted.getBean("springSessionRepositoryFilter", jakarta.servlet.Filter.class))
                    .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
            restartedMvc.perform(get("/api/accounts/me").secure(true).cookie(cookie))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.accountId").value(account.accountId().toString()));
            restartedMvc.perform(get("/api/accounts/me").header("Authorization", "Bearer " + access))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void restoredSessionRechecksGenerationAndModerationHttpUsesCurrentAuthority() throws Exception {
        var target = account();
        var cookie = login(target);
        jdbc.sql("UPDATE app_identity.account SET security_generation=security_generation+1 WHERE account_id=:id")
                .param("id", target.accountId()).update();
        mvc.perform(get("/api/accounts/me").secure(true).cookie(cookie)).andExpect(status().isUnauthorized());
        var admin = account();
        jdbc.sql("UPDATE app_identity.account SET is_admin=true WHERE account_id=:id").param("id", admin.accountId())
                .update();
        var adminCookie = login(admin);
        var subordinate = account();
        String route = "/api/accounts/admin/accounts/" + subordinate.accountId();
        mvc.perform(post(route + "/admin").secure(true).cookie(adminCookie).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(delete(route + "/admin").secure(true).cookie(adminCookie).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(post(route + "/ban").secure(true).cookie(adminCookie).with(csrf()).contentType("application/json")
                .content("{\"reason\":\"synthetic moderation\"}")).andExpect(status().isNoContent());
        mvc.perform(post(route + "/unban").secure(true).cookie(adminCookie).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void loginSessionIsDurableRotatesAndRejectsCsrfCors() throws Exception {
        var account = account();
        var initial = mvc.perform(get("/api/accounts/csrf").secure(true)).andExpect(status().isOk()).andReturn();
        Cookie before = initial.getResponse().getCookie("SESSION");
        String token = json.readTree(initial.getResponse().getContentAsString()).get("token").asText();
        mvc.perform(post("/api/accounts/login").secure(true).cookie(before).contentType("application/json")
                        .content(body(Map.of("login", accounts.get(account.accountId(), false).email(), "password", password))))
                .andExpect(status().isForbidden());
        var logged = mvc.perform(post("/api/accounts/login").secure(true).cookie(before).header("X-CSRF-TOKEN", token)
                        .contentType("application/json")
                        .content(body(Map.of("login", accounts.get(account.accountId(), false).email(), "password", password))))
                .andExpect(status().isOk()).andReturn();
        Cookie cookie = logged.getResponse().getCookie("SESSION");
        assertThat(cookie.getValue()).isNotEqualTo(before.getValue());
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.isHttpOnly()).isTrue();
        mvc.perform(get("/api/accounts/session").secure(true).cookie(cookie)).andExpect(status().isOk());
        mvc.perform(get("/api/accounts/session").secure(true).cookie(before)).andExpect(status().isUnauthorized());
        assertThat(jdbc.sql("SELECT count(*) FROM app_identity.spring_session WHERE principal_name=:id")
                .param("id", account.accountId().toString()).query(Long.class).single()).isEqualTo(1);
        mvc.perform(options("/api/accounts/me").header("Origin", "https://evil.test")
                .header("Access-Control-Request-Method", "PUT")).andExpect(status().isForbidden());
        mvc.perform(options("/api/accounts/me").header("Origin", "https://mnema.app")
                        .header("Access-Control-Request-Method", "PUT")).andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://mnema.app"));
        mvc.perform(post("/api/accounts/logout").secure(true).cookie(cookie).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/accounts/me").secure(true).cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void preservedBcryptLocalLoginRemainsIndependentOfProfileRenameAndFailureCounterCommits() throws Exception {
        var a = account();
        String login = jdbc.sql("SELECT login_name FROM app_identity.local_credential WHERE account_id=:id")
                .param("id", a.accountId()).query(String.class).single();
        assertThat(local.login(login.toUpperCase(Locale.ROOT), password, "fixture")).isEqualTo(a);
        profiles.update(a, "renamed-user-" + UUID.randomUUID().toString().substring(0, 8), "Changed", "Bio");
        assertThat(local.login(login, password, "fixture")).isEqualTo(a);
        assertThatThrownBy(() -> local.login(login, "wrong", "fixture")).isInstanceOf(AccountFailure.class);
        assertThat(jdbc.sql("SELECT failed_login_attempts FROM app_identity.local_credential WHERE account_id=:id")
                .param("id", a.accountId()).query(Integer.class).single()).isEqualTo(1);
        assertThatThrownBy(() -> local.login("unknown", "wrong", "fixture")).isInstanceOf(AccountFailure.class);
        var cookie = login(a);
        mvc.perform(put("/api/accounts/me").secure(true).cookie(cookie).with(csrf()).contentType("application/json")
                .content("{\"admin\":true}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/accounts/profiles/" + a.accountId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.email").doesNotExist()).andExpect(jsonPath("$.admin").doesNotExist());
    }

    @Test
    void passwordChangeAndBanImmediatelyInvalidateSignedTokensAndSessions() throws Exception {
        var a = account();
        var cookie = login(a);
        String old = token(a, "mnema-api", "https://identity.mnema.test", "at+jwt", Instant.now().plusSeconds(120));
        mvc.perform(get("/api/accounts/me").header("Authorization", "Bearer " + old)).andExpect(status().isOk());
        local.changePassword(a, password, "brand-new-password-43");
        mvc.perform(get("/api/accounts/me").header("Authorization", "Bearer " + old))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/accounts/me").secure(true).cookie(cookie)).andExpect(status().isUnauthorized());
        String email = accounts.get(a.accountId(), false).email();
        assertThatThrownBy(() -> local.login(email, password, "change-fixture")).isInstanceOf(AccountFailure.class);
        var changed = local.login(email, "brand-new-password-43", "change-fixture");
        assertThat(changed.generation()).isEqualTo(1);
        var admin = account();
        jdbc.sql("UPDATE app_identity.account SET is_admin=true WHERE account_id=:id").param("id", admin.accountId())
                .update();
        moderation.apply(admin, a.accountId(), Moderation.Action.BAN, "fixture moderation");
        assertThatThrownBy(() -> accounts.require(changed, false)).isInstanceOf(AccountFailure.class);
        moderation.apply(admin, a.accountId(), Moderation.Action.UNBAN, null);
        assertThatThrownBy(() -> accounts.require(changed, false)).isInstanceOf(AccountFailure.class);
    }

    @Test
    void jwtContractRejectsWrongIssuerAudienceTypeSubjectExpiryAndGeneration() throws Exception {
        var a = account();
        assertThat(decoder.decode(
                        token(a, "mnema-api", "https://identity.mnema.test", "at+jwt", Instant.now().plusSeconds(120)))
                .getSubject()).isEqualTo(a.accountId().toString());
        for (String invalid : List.of(
                token(a, "wrong", "https://identity.mnema.test", "at+jwt", Instant.now().plusSeconds(120)),
                token(a, "mnema-api", "https://wrong.test", "at+jwt", Instant.now().plusSeconds(120)),
                token(a, "mnema-api", "https://identity.mnema.test", "JWT", Instant.now().plusSeconds(120)),
                token(a, "mnema-api", "https://identity.mnema.test", "at+jwt", Instant.now().minusSeconds(120)),
                token(new AccountAccess(a.accountId(), 99), "mnema-api", "https://identity.mnema.test", "at+jwt",
                        Instant.now().plusSeconds(120))))
            assertThatThrownBy(() -> decoder.decode(invalid)).isInstanceOf(
                    org.springframework.security.oauth2.jwt.JwtException.class);
    }

    @Test
    void proofBindsAccountPurposeGenerationExpiryAndSingleUse() {
        var a = account();
        var b = account();
        var proof = tx.execute(s -> proofs.issue(a, OwnershipProofs.Purpose.LINK_IDENTITY));
        assertThatThrownBy(() -> tx.executeWithoutResult(
                s -> proofs.consume(b, proof.token(), OwnershipProofs.Purpose.LINK_IDENTITY))).isInstanceOf(
                AccountFailure.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(
                s -> proofs.consume(a, proof.token(), OwnershipProofs.Purpose.UNLINK_IDENTITY))).isInstanceOf(
                AccountFailure.class);
        tx.executeWithoutResult(s -> proofs.consume(a, proof.token(), OwnershipProofs.Purpose.LINK_IDENTITY));
        assertThatThrownBy(() -> tx.executeWithoutResult(
                s -> proofs.consume(a, proof.token(), OwnershipProofs.Purpose.LINK_IDENTITY))).isInstanceOf(
                AccountFailure.class);
        var expired = tx.execute(s -> proofs.issue(a, OwnershipProofs.Purpose.DELETE_ACCOUNT));
        jdbc.sql(
                        "UPDATE app_identity.ownership_challenge SET expires_at=statement_timestamp()-interval '1 second' WHERE account_id=:id")
                .param("id", a.accountId()).update();
        assertThatThrownBy(() -> tx.executeWithoutResult(
                s -> proofs.consume(a, expired.token(), OwnershipProofs.Purpose.DELETE_ACCOUNT))).isInstanceOf(
                AccountFailure.class);
    }

    @Test
    void rootDiscoveryAndRealPkceCodeExchangeEnforceVerifierAndRevocation() throws Exception {
        mvc.perform(get("/.well-known/openid-configuration")).andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer").value("https://identity.mnema.test"))
                .andExpect(jsonPath("$.token_endpoint").value("https://identity.mnema.test/oauth2/token"));
        var a = account();
        var cookie = login(a);
        String verifier = "synthetic-pkce-verifier-0123456789-abcdefghijklmnopqrstuvwxyz";
        String code = authorize(cookie, verifier);
        var result = mvc.perform(
                post("/oauth2/token").param("grant_type", "authorization_code").param("client_id", "mnema-web")
                        .param("redirect_uri", "https://mnema.app/auth/callback").param("code", code)
                        .param("code_verifier", verifier)).andExpect(status().isOk()).andReturn();
        JsonNode tokens = json.readTree(result.getResponse().getContentAsString());
        assertThat(tokens.has("refresh_token")).isFalse();
        String access = tokens.get("access_token").asText();
        assertThat(authorizations.findByToken(access,
                org.springframework.security.oauth2.server.authorization.OAuth2TokenType.ACCESS_TOKEN)).isNotNull();
        decoder.decode(access);
        mvc.perform(get("/api/accounts/me").header("Authorization", "Bearer " + access)).andExpect(status().isOk());
        mvc.perform(post("/oauth2/token").param("grant_type", "authorization_code").param("client_id", "mnema-web")
                .param("redirect_uri", "https://mnema.app/auth/callback").param("code", code)
                .param("code_verifier", verifier)).andExpect(status().isBadRequest());
        String denied = authorize(cookie, verifier);
        mvc.perform(post("/oauth2/token").param("grant_type", "authorization_code").param("client_id", "mnema-web")
                        .param("redirect_uri", "https://mnema.app/auth/callback").param("code", denied)
                        .param("code_verifier", "wrong-verifier-01234567890123456789012345678901"))
                .andExpect(status().isBadRequest());
        String revoked = authorize(cookie, verifier);
        tx.executeWithoutResult(s -> {
            accounts.get(a.accountId(), true);
            accounts.revoke(a.accountId());
        });
        mvc.perform(post("/oauth2/token").param("grant_type", "authorization_code").param("client_id", "mnema-web")
                .param("redirect_uri", "https://mnema.app/auth/callback").param("code", revoked)
                .param("code_verifier", verifier)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/accounts/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentCodeExchangeHasExactlyOneSuccess() throws Exception {
        var a = account();
        var cookie = login(a);
        String verifier = "synthetic-concurrent-verifier-0123456789-abcdefghijklmnop";
        String code = authorize(cookie, verifier);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++)
                results.add(executor.submit(() -> {
                    start.await();
                    return mvc.perform(post("/oauth2/token").param("grant_type", "authorization_code")
                                    .param("client_id", "mnema-web").param("redirect_uri", "https://mnema.app/auth/callback")
                                    .param("code", code).param("code_verifier", verifier)).andReturn().getResponse()
                            .getStatus();
                }));
            start.countDown();
            assertThat(List.of(results.get(0).get(10, java.util.concurrent.TimeUnit.SECONDS),
                    results.get(1).get(10, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 400);
        }
    }

    @Test
    void coldBrowserLoginResumesOnlySavedAuthorizationRequest() throws Exception {
        var a = account();
        String verifier = "synthetic-cold-browser-verifier-0123456789-abcdefghijklmnop";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        var initial = mvc.perform(
                        get("/oauth2/authorize").secure(true).header("Accept", "text/html")
                                .with(request -> { request.setServerName("attacker.example"); return request; })
                                .queryParam("response_type", "code")
                                .queryParam("client_id", "mnema-web")
                                .queryParam("redirect_uri", "https://mnema.app/auth/callback")
                                .queryParam("scope", "openid account.read").queryParam("code_challenge", challenge)
                                .queryParam("code_challenge_method", "S256")).andExpect(status().is3xxRedirection())
                .andReturn();
        Cookie cookie = initial.getResponse().getCookie("SESSION");
        mvc.perform(get("/login").secure(true).cookie(cookie)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/login/continue")));
        var logged = mvc.perform(
                post("/api/accounts/login").secure(true).cookie(cookie).with(csrf()).contentType("application/json")
                        .content(body(Map.of("login", accounts.get(a.accountId(), false).email(), "password",
                                password)))).andExpect(status().isOk()).andReturn();
        Cookie next = logged.getResponse().getCookie("SESSION");
        var resumed = mvc.perform(get("/login/continue").secure(true).cookie(next))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(resumed.getResponse().getRedirectedUrl()).startsWith("/oauth2/authorize?");
        mvc.perform(get(URI.create(resumed.getResponse().getRedirectedUrl())).secure(true).cookie(next))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("https://mnema.app/auth/callback?code=*"));
    }

    @Test
    void oidcLogoutRevokesOutstandingCodeAndAccess() throws Exception {
        var a = account();
        var cookie = login(a);
        String verifier = "synthetic-oidc-logout-verifier-0123456789-abcdefghijklmnop";
        String code = authorize(cookie, verifier);
        var result = mvc.perform(
                post("/oauth2/token").param("grant_type", "authorization_code").param("client_id", "mnema-web")
                        .param("redirect_uri", "https://mnema.app/auth/callback").param("code", code)
                        .param("code_verifier", verifier)).andExpect(status().isOk()).andReturn();
        var tokens = json.readTree(result.getResponse().getContentAsString());
        String outstanding = authorize(cookie, verifier);
        mvc.perform(post("/connect/logout").secure(true).cookie(cookie).with(csrf())
                        .param("id_token_hint", tokens.get("id_token").asText()).param("client_id", "mnema-web"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/accounts/me").header("Authorization", "Bearer " + tokens.get("access_token").asText()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/oauth2/token").param("grant_type", "authorization_code").param("client_id", "mnema-web")
                .param("redirect_uri", "https://mnema.app/auth/callback").param("code", outstanding)
                .param("code_verifier", verifier)).andExpect(status().isBadRequest());
    }

    @Test
    void publicControllersValidateAndApplyAccountMutations() throws Exception {
        String key = UUID.randomUUID().toString();
        var registered = mvc.perform(post("/api/accounts/register").with(csrf()).contentType("application/json")
                .content(body(Map.of("email", key + "@example.test", "loginName", key, "password", password,
                        "profileUsername", key)))).andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(
                json.readTree(registered.getResponse().getContentAsString()).get("accountId").asText());
        var access = accounts.get(id, false).access();
        var cookie = login(access);
        mvc.perform(post("/api/accounts/register").with(csrf()).contentType("application/json")
                        .content(body(Map.of("email", key + "@example.test", "loginName", key, "password", password))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("account_conflict"));
        mvc.perform(post("/api/accounts/register").with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/accounts/me").secure(true).cookie(cookie).with(csrf()).contentType("application/json")
                        .content(body(Map.of("profileUsername", "edited-" + key.substring(0, 8), "displayName", "Edited", "bio",
                                "A short bio")))).andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Edited"));
        mvc.perform(put("/api/accounts/me").secure(true).cookie(cookie).with(csrf()).contentType("application/json")
                        .content(body(Map.of("displayName", "Would erase omitted fields"))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/accounts/me").secure(true).cookie(cookie))
                .andExpect(jsonPath("$.profileUsername").value("edited-" + key.substring(0, 8)))
                .andExpect(jsonPath("$.bio").value("A short bio"));
        mvc.perform(patch("/api/accounts/me").secure(true).cookie(cookie).with(csrf()).contentType("application/json")
                        .content(body(Map.of("profileUsername", "wrong-method", "displayName", "Wrong", "bio", "Wrong"))))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(
                        post("/api/accounts/me/proofs").secure(true).cookie(cookie).with(csrf()).contentType("application/json")
                                .content(body(Map.of("password", password, "purpose", "DELETE_ACCOUNT"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expiresAt").exists());
        // Reauthentication rotates the session: acquire a fresh authenticated cookie.
        cookie = login(access);
        mvc.perform(post("/api/accounts/me/password").secure(true).cookie(cookie).with(csrf())
                        .contentType("application/json")
                        .content(body(Map.of("currentPassword", password, "newPassword", "updated-password-555"))))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/accounts/password-reset/confirm").with(csrf()).contentType("application/json")
                .content(body(Map.of("token", "unknown", "newPassword", password)))).andExpect(status().isBadRequest());
    }

    @Test
    void publicProfileAndAvatarDoNotRevealHiddenAccountExistence() throws Exception {
        var hidden = account();
        jdbc.sql("UPDATE app_identity.account SET status='BANNED',banned_at=statement_timestamp() WHERE account_id=:id")
                .param("id", hidden.accountId()).update();
        UUID absent = UUID.randomUUID();

        for (UUID id : List.of(hidden.accountId(), absent)) {
            mvc.perform(get("/api/accounts/profiles/" + id)).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("profile_not_found"));
            mvc.perform(get("/api/accounts/profiles/" + id + "/avatar")).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("avatar_not_found"));
        }
    }

    @Test
    void confidentialRefreshRotationAndRevocationAreAtomic() throws Exception {
        String clientId = "confidential-fixture-" + UUID.randomUUID();
        clients.save(org.springframework.security.oauth2.server.authorization.client.RegisteredClient.withId(clientId)
                .clientId(clientId).clientSecret(passwords.encode("fixture-secret"))
                .clientAuthenticationMethod(
                        org.springframework.security.oauth2.core.ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(
                        org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(org.springframework.security.oauth2.core.AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("https://mnema.app/auth/callback").scope("openid").scope("profile").scope("account.read")
                .scope("account.write")
                .clientSettings(
                        org.springframework.security.oauth2.server.authorization.settings.ClientSettings.builder()
                                .requireProofKey(true).build())
                .tokenSettings(org.springframework.security.oauth2.server.authorization.settings.TokenSettings.builder()
                        .reuseRefreshTokens(false).build()).build());
        var a = account();
        var cookie = login(a);
        String verifier = "synthetic-refresh-verifier-0123456789-abcdefghijklmnop";
        String code = authorize(cookie, verifier, clientId);
        var result = mvc.perform(post("/oauth2/token").with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic(
                                clientId, "fixture-secret")).param("grant_type", "authorization_code")
                .param("redirect_uri", "https://mnema.app/auth/callback").param("code", code)
                .param("code_verifier", verifier)).andExpect(status().isOk()).andReturn();
        String refresh = json.readTree(result.getResponse().getContentAsString()).get("refresh_token").asText();
        var rotated = mvc.perform(post("/oauth2/token").with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic(
                                clientId, "fixture-secret")).param("grant_type", "refresh_token")
                .param("refresh_token", refresh)).andExpect(status().isOk()).andReturn();
        String next = json.readTree(rotated.getResponse().getContentAsString()).get("refresh_token").asText();
        assertThat(next).isNotEqualTo(refresh);
        mvc.perform(post("/oauth2/token").with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic(
                                clientId, "fixture-secret")).param("grant_type", "refresh_token")
                .param("refresh_token", refresh)).andExpect(status().isBadRequest());
        tx.executeWithoutResult(status -> {
            accounts.get(a.accountId(), true);
            accounts.revoke(a.accountId());
        });
        mvc.perform(post("/oauth2/token").with(
                        org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic(
                                clientId, "fixture-secret")).param("grant_type", "refresh_token").param("refresh_token", next))
                .andExpect(status().isBadRequest());
    }

    String authorize(Cookie cookie, String verifier) throws Exception {
        return authorize(cookie, verifier, "mnema-web");
    }

    String authorize(Cookie cookie, String verifier, String clientId) throws Exception {
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        var result = mvc.perform(
                        get("/oauth2/authorize").secure(true).cookie(cookie).queryParam("response_type", "code")
                                .queryParam("client_id", clientId).queryParam("redirect_uri", "https://mnema.app/auth/callback")
                                .queryParam("scope", "openid profile account.read account.write")
                                .queryParam("state", "fixture-state").queryParam("code_challenge", challenge)
                                .queryParam("code_challenge_method", "S256")).andExpect(status().is3xxRedirection())
                .andReturn();
        URI redirect = URI.create(result.getResponse().getRedirectedUrl());
        return Arrays.stream(redirect.getRawQuery().split("&")).filter(v -> v.startsWith("code="))
                .map(v -> URLDecoder.decode(v.substring(5), StandardCharsets.UTF_8)).findFirst().orElseThrow();
    }

    String token(AccountAccess a, String audience, String issuer, String type, Instant expiry) throws Exception {
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(SIGNING_KEY.getKeyID()).type(new JOSEObjectType(type))
                        .build(),
                new JWTClaimsSet.Builder().subject(a.accountId().toString()).issuer(issuer).audience(audience)
                        .issueTime(Date.from(Instant.now().minusSeconds(5))).expirationTime(Date.from(expiry))
                        .claim("generation", Long.toString(a.generation())).claim("scope", "account.read account.write")
                        .build());
        jwt.sign(new RSASSASigner(SIGNING_KEY));
        String value = jwt.serialize();
        if (a.generation() == accounts.get(a.accountId(), false).securityGeneration())
            authorizations.save(
                    org.springframework.security.oauth2.server.authorization.OAuth2Authorization.withRegisteredClient(
                                    clients.findByClientId("mnema-web"))
                            .principalName(a.accountId().toString()).authorizationGrantType(
                                    org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                            .attribute("generation", Long.toString(a.generation()))
                            .accessToken(new org.springframework.security.oauth2.core.OAuth2AccessToken(
                                    org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType.BEARER, value,
                                    Instant.now().minusSeconds(200), expiry, Set.of("account.read", "account.write")))
                            .build());
        return value;
    }
}
