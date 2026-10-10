package app.mnema.identityaccount.profile;

import app.mnema.identityaccount.avatar.AvatarStorage;
import app.mnema.identityaccount.contract.AccountAccess;
import app.mnema.identityaccount.local.LocalAccounts;
import app.mnema.identityaccount.security.Secrets;
import app.mnema.identityaccount.support.PostgresIntegrationTest;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class PublicProfileIntegrationTest extends PostgresIntegrationTest {
    private static final String PASSWORD = "correct-horse-battery-42";
    private static final String VERSION = PublicProfiles.TEXT_VERSION;

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    LocalAccounts local;
    @Autowired
    Profiles profiles;
    @Autowired
    PublicProfiles publicProfiles;
    @Autowired
    RegisteredClientRepository clients;
    @Autowired
    OAuth2AuthorizationService authorizations;
    @MockitoBean
    AvatarStorage storage;

    // ---- own consent ----------------------------------------------------------------------------------------

    @Test
    void consentStartsAllFalseAndRoundTripsThroughTheFixtureShapes() throws Exception {
        JsonNode fixture = fixture();
        assertThat(fixture.get("textVersion").stringValue()).isEqualTo(VERSION);
        assertThat(fixture.get("batchMaxIds").intValue()).isEqualTo(PublicProfiles.MAX_BATCH);
        var account = account(true);
        String token = bearer(account);

        JsonNode initial = json.readTree(body(get("/api/accounts/me/public-profile")
                .header("Authorization", "Bearer " + token), 200));
        assertSameShape(fixture.get("consentDefault"), initial);
        assertThat(initial).isEqualTo(fixture.get("consentDefault"));

        JsonNode granted = json.readTree(body(put("/api/accounts/me/public-profile")
                .header("Authorization", "Bearer " + token).contentType("application/json")
                .content(json.writeValueAsString(fixture.get("consentUpdate"))), 200));
        assertSameShape(fixture.get("consentGranted"), granted);
        assertThat(granted.get("enabled").booleanValue()).isTrue();
        assertThat(granted.get("showDisplayName").booleanValue()).isTrue();
        assertThat(granted.get("showAvatar").booleanValue()).isFalse();
        assertThat(granted.get("showBio").booleanValue()).isTrue();
        assertThat(granted.get("textVersion").stringValue()).isEqualTo(VERSION);
        assertThat(granted.get("publishReady").booleanValue()).isTrue();
        assertThat(Instant.parse(granted.get("updatedAt").stringValue())).isAfter(Instant.now().minusSeconds(300));
        assertThat(json.readTree(body(get("/api/accounts/me/public-profile")
                .header("Authorization", "Bearer " + token), 200))).isEqualTo(granted);
    }

    @Test
    void publishReadyNeedsEnabledConsentAndAProfileUsername() throws Exception {
        var account = account(true);
        String token = bearer(account);
        assertThat(consent(token).get("publishReady").booleanValue()).isFalse();
        putConsent(token, true, false, false, false);
        assertThat(consent(token).get("publishReady").booleanValue()).isTrue();
        putConsent(token, false, false, false, false);
        assertThat(consent(token).get("publishReady").booleanValue()).isFalse();
    }

    @Test
    void enablingWithoutProfileUsernameIsRefusedAndWritesNothing() throws Exception {
        var account = account(false);
        String token = bearer(account);
        putConsent(token, true, true, true, true, VERSION, 409, "profile_username_required");
        assertThat(count("public_profile_consent", account)).isZero();
        assertThat(count("public_profile_consent_event", account)).isZero();
        // Withdrawing nothing is not an error and still writes nothing.
        putConsent(token, false, false, false, false);
        assertThat(count("public_profile_consent", account)).isZero();
    }

    @Test
    void outdatedTextVersionIsRefusedWithoutChangingTheState() throws Exception {
        var account = account(true);
        String token = bearer(account);
        putConsent(token, true, true, false, false);
        putConsent(token, true, true, true, true, "2026-10-09", 409, "consent_text_outdated");
        var stored = consent(token);
        assertThat(stored.get("enabled").booleanValue()).isTrue();
        assertThat(stored.get("showAvatar").booleanValue()).isFalse();
        assertThat(count("public_profile_consent_event", account)).isOne();
    }

    @Test
    void withdrawalNeverDependsOnTheTextVersion() throws Exception {
        var account = account(true);
        String token = bearer(account);
        putConsent(token, true, true, true, true);
        var withdrawn = putConsent(token, false, true, true, true, "2030-01-01", 200, null);
        assertThat(withdrawn.get("enabled").booleanValue()).isFalse();
        assertThat(withdrawn.get("textVersion").stringValue()).isEqualTo(VERSION);
        assertThat(status(get("/api/accounts/profiles/" + account.accountId()))).isEqualTo(404);
        assertThat(actions(account)).containsExactly("GRANT", "WITHDRAW");
        assertThat(jdbc.sql("SELECT text_version FROM app_identity.public_profile_consent_event WHERE action='WITHDRAW' AND account_id=:id")
                .param("id", account.accountId()).query(String.class).single()).isEqualTo(VERSION);
        // A stale grant is still refused.
        putConsent(token, true, true, false, false, "2030-01-01", 409, "consent_text_outdated");
    }

    @Test
    void updateRequiresExactlyTheFiveFieldsWithTheirTypes() throws Exception {
        var account = account(true);
        String token = bearer(account);
        List<String> invalid = List.of(
                "{}",
                "{\"enabled\":true,\"showDisplayName\":true,\"showAvatar\":false,\"showBio\":true}",
                "{\"showDisplayName\":true,\"showAvatar\":false,\"showBio\":true,\"textVersion\":\"" + VERSION + "\"}",
                "{\"enabled\":\"true\",\"showDisplayName\":true,\"showAvatar\":false,\"showBio\":true,\"textVersion\":\"" + VERSION + "\"}",
                "{\"enabled\":1,\"showDisplayName\":true,\"showAvatar\":false,\"showBio\":true,\"textVersion\":\"" + VERSION + "\"}",
                "{\"enabled\":null,\"showDisplayName\":true,\"showAvatar\":false,\"showBio\":true,\"textVersion\":\"" + VERSION + "\"}",
                "{\"enabled\":true,\"showDisplayName\":true,\"showAvatar\":false,\"showBio\":true,\"textVersion\":20261010}",
                "{\"enabled\":true,\"showDisplayName\":true,\"showAvatar\":false,\"showBio\":true,\"textVersion\":\"" + VERSION + "\",\"extra\":1}",
                "[]", "\"x\"", "null", "{\"enabled\":", "");
        for (String content : invalid) {
            var result = mvc.perform(put("/api/accounts/me/public-profile").header("Authorization", "Bearer " + token)
                    .contentType("application/json").content(content)).andReturn().getResponse();
            assertThat(result.getStatus()).as(content).isEqualTo(400);
            assertThat(json.readTree(result.getContentAsString()).get("code").stringValue()).as(content)
                    .isEqualTo("invalid_request");
        }
        assertThat(count("public_profile_consent", account)).isZero();
    }

    @Test
    void withdrawalClearsEveryFieldHidesTheCardAtOnceAndKeepsTheJournal() throws Exception {
        var account = account(true);
        String token = bearer(account);
        putConsent(token, true, true, true, true);
        assertThat(status(get("/api/accounts/profiles/" + account.accountId()))).isEqualTo(200);

        var withdrawn = putConsent(token, false, true, true, true);
        assertThat(withdrawn.get("enabled").booleanValue()).isFalse();
        assertThat(withdrawn.get("showDisplayName").booleanValue()).isFalse();
        assertThat(withdrawn.get("showAvatar").booleanValue()).isFalse();
        assertThat(withdrawn.get("showBio").booleanValue()).isFalse();
        assertThat(withdrawn.get("publishReady").booleanValue()).isFalse();
        assertThat(status(get("/api/accounts/profiles/" + account.accountId()))).isEqualTo(404);
        assertThat(status(get("/api/accounts/profiles/" + account.accountId() + "/avatar"))).isEqualTo(404);
        assertThat(jdbc.sql("""
                        SELECT show_display_name OR show_avatar OR show_bio FROM app_identity.public_profile_consent
                        WHERE account_id=:id
                        """).param("id", account.accountId()).query(Boolean.class).single()).isFalse();
        assertThat(actions(account)).containsExactly("GRANT", "WITHDRAW");

        // Granting again is a new GRANT, not a CHANGE.
        putConsent(token, true, false, false, true);
        assertThat(actions(account)).containsExactly("GRANT", "WITHDRAW", "GRANT");
    }

    @Test
    void journalHasOneRowPerEffectiveChangeAndNoneForIdempotentPuts() throws Exception {
        var account = account(true);
        String token = bearer(account);
        putConsent(token, false, false, false, false);
        assertThat(actions(account)).isEmpty();
        putConsent(token, true, true, false, true);
        var first = consent(token);
        putConsent(token, true, true, false, true);
        putConsent(token, true, true, false, true);
        assertThat(actions(account)).containsExactly("GRANT");
        assertThat(consent(token).get("updatedAt")).as("an idempotent PUT does not touch updated_at")
                .isEqualTo(first.get("updatedAt"));
        putConsent(token, true, true, true, true);
        putConsent(token, true, false, false, false);
        putConsent(token, false, false, false, false);
        putConsent(token, false, true, true, true);
        assertThat(actions(account)).containsExactly("GRANT", "CHANGE", "CHANGE", "WITHDRAW");
        var rows = jdbc.sql("""
                        SELECT action,enabled,show_display_name,show_avatar,show_bio,text_version
                        FROM app_identity.public_profile_consent_event WHERE account_id=:id ORDER BY event_id
                        """).param("id", account.accountId())
                .query((row, n) -> row.getString(1) + ":" + row.getBoolean(2) + row.getBoolean(3) +
                        row.getBoolean(4) + row.getBoolean(5) + ":" + row.getString(6)).list();
        assertThat(rows).containsExactly("GRANT:truetruefalsetrue:" + VERSION,
                "CHANGE:truetruetruetrue:" + VERSION, "CHANGE:truefalsefalsefalse:" + VERSION,
                "WITHDRAW:falsefalsefalsefalse:" + VERSION);
    }

    @Test
    void reconsentToANewTextVersionIsAnEffectiveChange() throws Exception {
        var account = account(true);
        String token = bearer(account);
        putConsent(token, true, true, false, false);
        jdbc.sql("UPDATE app_identity.public_profile_consent SET text_version='2026-01-01' WHERE account_id=:id")
                .param("id", account.accountId()).update();
        putConsent(token, true, true, false, false);
        assertThat(consent(token).get("textVersion").stringValue()).isEqualTo(VERSION);
        assertThat(actions(account)).containsExactly("GRANT", "CHANGE");
    }

    @Test
    void journalIsAppendOnlyAndConsentRowsCannotKeepFieldsAfterWithdrawal() throws Exception {
        var account = account(true);
        putConsent(bearer(account), true, true, true, true);
        for (String statement : List.of(
                "UPDATE app_identity.public_profile_consent_event SET action='CHANGE' WHERE account_id=:id",
                "DELETE FROM app_identity.public_profile_consent_event WHERE account_id=:id")) {
            assertThatThrownBy(() -> jdbc.sql(statement).param("id", account.accountId()).update())
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE app_identity.public_profile_consent_event").update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("public_profile_consent_event", account)).isOne();
        assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE app_identity.public_profile_consent SET enabled=false WHERE account_id=:id
                        """).param("id", account.accountId()).update())
                .as("not enabled implies every field flag is false").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("""
                        UPDATE app_identity.public_profile_consent SET text_version='today' WHERE account_id=:id
                        """).param("id", account.accountId()).update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- the gate on public reads -----------------------------------------------------------------------------

    @Test
    void everyNonPublicStateAnswersExactlyLikeAnUnknownAccount() throws Exception {
        var noConsent = account(true);
        var disabled = account(true);
        putConsent(bearer(disabled), true, true, true, true);
        putConsent(bearer(disabled), false, false, false, false);
        var banned = publicAccount(true, true, true);
        jdbc.sql("UPDATE app_identity.account SET status='BANNED',banned_at=statement_timestamp() WHERE account_id=:id")
                .param("id", banned.accountId()).update();
        var deleting = publicAccount(true, true, true);
        jdbc.sql("UPDATE app_identity.account SET deletion_state='PENDING_DELETION' WHERE account_id=:id")
                .param("id", deleting.accountId()).update();
        var noUsername = account(false);
        jdbc.sql("""
                        INSERT INTO app_identity.public_profile_consent(account_id,enabled,show_display_name,show_avatar,
                            show_bio,text_version) VALUES(:id,true,true,true,true,:version)
                        """).param("id", noUsername.accountId()).param("version", VERSION).update();
        var photoHidden = publicAccount(true, false, true);
        withAvatar(photoHidden, new byte[]{1, 2, 3});

        UUID unknown = UUID.randomUUID();
        String reference = normalized("/api/accounts/profiles/" + unknown, unknown.toString());
        String avatarReference = normalized("/api/accounts/profiles/" + unknown + "/avatar", unknown.toString());
        assertThat(reference).contains("profile_not_found");
        assertThat(avatarReference).contains("avatar_not_found");
        for (AccountAccess hidden : List.of(noConsent, disabled, banned, deleting, noUsername)) {
            String id = hidden.accountId().toString();
            assertThat(normalized("/api/accounts/profiles/" + id, id)).isEqualTo(reference);
            assertThat(normalized("/api/accounts/profiles/" + id + "/avatar", id)).isEqualTo(avatarReference);
        }
        // A public account whose photo is hidden gives the same avatar answer, and the card says no photo.
        String id = photoHidden.accountId().toString();
        assertThat(normalized("/api/accounts/profiles/" + id + "/avatar", id)).isEqualTo(avatarReference);
        assertThat(json.readTree(body(get("/api/accounts/profiles/" + id), 200)).get("avatarPresent").booleanValue())
                .isFalse();

        // The same accounts are omitted from a batch and unreachable by username.
        var ids = new ArrayList<>(List.of(noConsent, disabled, banned, deleting, noUsername).stream()
                .map(a -> a.accountId().toString()).toList());
        ids.add(unknown.toString());
        assertThat(json.readTree(body(get("/api/accounts/profiles").queryParam("ids", String.join(",", ids))
                .header("X-Forwarded-For", ip()), 200)).get("profiles")).isEmpty();
        String missing = "nobody-" + UUID.randomUUID().toString().substring(0, 8);
        String usernameReference = normalized("/api/accounts/profiles/by-username/" + missing, missing,
                ip());
        for (AccountAccess hidden : List.of(noConsent, disabled, banned, deleting)) {
            String username = username(hidden);
            assertThat(normalized("/api/accounts/profiles/by-username/" + username, username, ip()))
                    .isEqualTo(usernameReference);
        }
        assertThat(usernameReference).contains("profile_not_found");
    }

    @Test
    void cardShowsOnlyTheFieldsTheOwnerChose() throws Exception {
        JsonNode fixture = fixture();
        var account = publicAccount(false, false, false);
        String username = username(account);
        JsonNode card = json.readTree(body(get("/api/accounts/profiles/" + account.accountId()), 200));
        assertThat(card.get("accountId").stringValue()).isEqualTo(account.accountId().toString());
        assertThat(card.get("profileUsername").stringValue()).isEqualTo(username);
        assertThat(card.get("displayName").isNull()).isTrue();
        assertThat(card.get("bio").isNull()).isTrue();
        assertThat(card.get("avatarPresent").booleanValue()).isFalse();
        assertThat(card.propertyNames()).containsExactlyInAnyOrderElementsOf(
                fixture.get("card").propertyNames());

        var tokenOwner = bearer(account);
        putConsent(tokenOwner, true, true, false, false);
        card = json.readTree(body(get("/api/accounts/profiles/" + account.accountId()), 200));
        assertThat(card.get("displayName").stringValue()).isEqualTo("Анна");
        assertThat(card.get("bio").isNull()).isTrue();
        assertSameShape(fixture.get("card"), card);

        putConsent(tokenOwner, true, false, false, true);
        card = json.readTree(body(get("/api/accounts/profiles/" + account.accountId()), 200));
        assertThat(card.get("displayName").isNull()).isTrue();
        assertThat(card.get("bio").stringValue()).isEqualTo("О себе");
    }

    @Test
    void blankDisplayNameAndBioAreNullEvenWhenShown() throws Exception {
        var account = account(true);
        putConsent(bearer(account), true, true, false, true);
        JsonNode card = json.readTree(body(get("/api/accounts/profiles/" + account.accountId()), 200));
        assertThat(card.get("displayName").isNull()).isTrue();
        assertThat(card.get("bio").isNull()).isTrue();
    }

    @Test
    void usernameChangesAreVisibleAndPublicReadsAreCachedForAtMostAMinute() throws Exception {
        var account = publicAccount(true, true, true);
        var response = mvc.perform(get("/api/accounts/profiles/" + account.accountId())).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("public, max-age=60");
        profiles.update(account, "renamed-" + UUID.randomUUID().toString().substring(0, 8), "Новое", "Био");
        JsonNode card = json.readTree(body(get("/api/accounts/profiles/" + account.accountId()), 200));
        assertThat(card.get("profileUsername").stringValue()).startsWith("renamed-");
        assertThat(card.get("displayName").stringValue()).isEqualTo("Новое");
        assertThat(card.get("bio").stringValue()).isEqualTo("Био");
        var notFound = mvc.perform(get("/api/accounts/profiles/" + UUID.randomUUID())).andReturn().getResponse();
        assertThat(notFound.getHeader("Cache-Control")).doesNotContain("public");
    }

    @Test
    void bannedAccountReappearsAfterUnbanWithItsOriginalConsent() throws Exception {
        var account = publicAccount(true, true, true);
        jdbc.sql("UPDATE app_identity.account SET status='BANNED',banned_at=statement_timestamp() WHERE account_id=:id")
                .param("id", account.accountId()).update();
        assertThat(status(get("/api/accounts/profiles/" + account.accountId()))).isEqualTo(404);
        jdbc.sql("UPDATE app_identity.account SET status='ACTIVE',banned_at=NULL WHERE account_id=:id")
                .param("id", account.accountId()).update();
        assertThat(status(get("/api/accounts/profiles/" + account.accountId()))).isEqualTo(200);
    }

    // ---- avatars ---------------------------------------------------------------------------------------------

    @Test
    void publicAvatarNeedsTheCardAndThePhotoFlagAndTheOwnerAlwaysSeesTheirOwn() throws Exception {
        byte[] bytes = {9, 8, 7, 6};
        var account = publicAccount(true, false, true);
        String token = bearer(account);
        withAvatar(account, bytes);
        String id = account.accountId().toString();

        // Photo flag off: public 404, owner still sees the photo.
        assertThat(status(get("/api/accounts/profiles/" + id + "/avatar"))).isEqualTo(404);
        var own = mvc.perform(get("/api/accounts/me/avatar").header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        assertThat(own.getStatus()).isEqualTo(200);
        assertThat(own.getContentAsByteArray()).isEqualTo(bytes);
        assertThat(own.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(own.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(own.getContentType()).isEqualTo("image/png");

        putConsent(token, true, true, true, true);
        var publicRead = mvc.perform(get("/api/accounts/profiles/" + id + "/avatar")).andReturn().getResponse();
        assertThat(publicRead.getStatus()).isEqualTo(200);
        assertThat(publicRead.getContentAsByteArray()).isEqualTo(bytes);
        assertThat(publicRead.getHeader("Cache-Control")).isEqualTo("public, max-age=60");
        assertThat(publicRead.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        String etag = publicRead.getHeader("ETag");
        assertThat(etag).isEqualTo("\"" + java.util.HexFormat.of().formatHex(Secrets.digest(bytes)) + "\"");

        // Revalidation answers 304 from the database row without touching object storage.
        Mockito.clearInvocations(storage);
        for (String match : List.of(etag, "W/" + etag, "\"other\", " + etag, "*")) {
            var revalidated = mvc.perform(get("/api/accounts/profiles/" + id + "/avatar")
                    .header("If-None-Match", match)).andReturn().getResponse();
            assertThat(revalidated.getStatus()).as(match).isEqualTo(304);
            assertThat(revalidated.getHeader("ETag")).isEqualTo(etag);
            assertThat(revalidated.getHeader("Cache-Control")).isEqualTo("public, max-age=60");
            assertThat(revalidated.getContentAsByteArray()).isEmpty();
        }
        Mockito.verifyNoInteractions(storage);
        assertThat(mvc.perform(get("/api/accounts/profiles/" + id + "/avatar").header("If-None-Match", "\"stale\""))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(body(get("/api/accounts/profiles/" + id), 200)).get("avatarPresent").booleanValue())
                .isTrue();

        // Withdrawal hides the photo immediately, even for a client holding a validator.
        putConsent(token, false, false, false, false);
        assertThat(status(get("/api/accounts/profiles/" + id + "/avatar"))).isEqualTo(404);
        assertThat(status(get("/api/accounts/profiles/" + id + "/avatar").header("If-None-Match", etag)))
                .isEqualTo(404);
        assertThat(mvc.perform(get("/api/accounts/me/avatar").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void sharedPhotoFlagWithoutAPhotoIsAnAbsentAvatarNotAnError() throws Exception {
        var account = publicAccount(true, true, true);
        assertThat(json.readTree(body(get("/api/accounts/profiles/" + account.accountId()), 200))
                .get("avatarPresent").booleanValue()).isFalse();
        assertThat(status(get("/api/accounts/profiles/" + account.accountId() + "/avatar"))).isEqualTo(404);
        var own = mvc.perform(get("/api/accounts/me/avatar").header("Authorization", "Bearer " + bearer(account)))
                .andReturn().getResponse();
        assertThat(own.getStatus()).isEqualTo(404);
        assertThat(json.readTree(own.getContentAsString()).get("code").stringValue()).isEqualTo("avatar_not_found");
    }

    // ---- batch -----------------------------------------------------------------------------------------------

    @Test
    void batchKeepsRequestOrderOmitsNonPublicIdsAndIsCachedBriefly() throws Exception {
        JsonNode fixture = fixture();
        var first = publicAccount(true, false, false);
        var second = publicAccount(false, false, true);
        var hidden = account(true);
        var third = publicAccount(true, false, true);
        String ids = String.join(",", third.accountId().toString(), hidden.accountId().toString(),
                UUID.randomUUID().toString(), first.accountId().toString(), second.accountId().toString());
        var response = mvc.perform(get("/api/accounts/profiles").queryParam("ids", ids)
                .header("X-Forwarded-For", ip())).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("public, max-age=60");
        JsonNode result = json.readTree(response.getContentAsString());
        assertThat(result.propertyNames()).containsExactly("profiles");
        var accountIds = new ArrayList<String>();
        result.get("profiles").forEach(card -> accountIds.add(card.get("accountId").stringValue()));
        assertThat(accountIds).containsExactly(third.accountId().toString(), first.accountId().toString(),
                second.accountId().toString());
        for (JsonNode card : result.get("profiles")) assertSameShapeIgnoringNulls(fixture.get("card"), card);
        // Same cards as the single reads.
        for (JsonNode card : result.get("profiles"))
            assertThat(card).isEqualTo(json.readTree(body(get("/api/accounts/profiles/" + card.get("accountId")
                    .stringValue()), 200)));
        // Upper-case hex is the same identifier.
        assertThat(json.readTree(body(get("/api/accounts/profiles")
                .queryParam("ids", first.accountId().toString().toUpperCase())
                .header("X-Forwarded-For", ip()), 200)).get("profiles")).hasSize(1);
    }

    @Test
    void batchAcceptsOneToFiftyDistinctCanonicalIdsOnly() throws Exception {
        var account = publicAccount(true, false, false);
        String one = account.accountId().toString();
        String address = ip();
        List<String> fifty = new ArrayList<>();
        for (int i = 0; i < 50; i++) fifty.add(UUID.randomUUID().toString());
        assertThat(batch(address, String.join(",", fifty))).isEqualTo(200);
        assertThat(batch(address, one)).isEqualTo(200);

        List<String> fiftyOne = new ArrayList<>(fifty);
        fiftyOne.add(UUID.randomUUID().toString());
        List<String> invalid = List.of(
                String.join(",", fiftyOne), one + "," + one, one + "," + one.toUpperCase(), "", ",", one + ",",
                "," + one, one + ",,", "not-a-uuid", one + ",x", one.replace("-", ""), " " + one,
                one + " ", "00000000-0000-0000-0000-00000000000g",
                "1-1-1-1-1");
        for (String ids : invalid) assertThat(batch(address, ids)).as(ids).isEqualTo(400);
        var missing = mvc.perform(get("/api/accounts/profiles").header("X-Forwarded-For", address))
                .andReturn().getResponse();
        assertThat(missing.getStatus()).isEqualTo(400);
        assertThat(json.readTree(missing.getContentAsString()).get("code").stringValue()).isEqualTo("invalid_request");
        // Malformed requests are not counted: only the two valid calls used the budget.
        assertThat(attempts("profile-batch", address)).isEqualTo(2);
    }

    @Test
    void batchIsRateLimitedPerClientAddress() throws Exception {
        String address = ip();
        String ids = UUID.randomUUID().toString();
        for (int i = 0; i < PublicProfileController.BATCH_LIMIT; i++)
            assertThat(batch(address, ids)).as("request %d", i).isEqualTo(200);
        var limited = mvc.perform(get("/api/accounts/profiles").queryParam("ids", ids)
                .header("X-Forwarded-For", address)).andReturn().getResponse();
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(json.readTree(limited.getContentAsString()).get("code").stringValue()).isEqualTo("try_later");
        // Another address is unaffected.
        assertThat(batch(ip(), ids)).isEqualTo(200);
    }

    // ---- by username -----------------------------------------------------------------------------------------

    @Test
    void byUsernameFindsPublicCardsCaseInsensitivelyAndMatchesTheIdRead() throws Exception {
        var account = publicAccount(true, false, true);
        String username = "Anna.K-" + UUID.randomUUID().toString().substring(0, 8);
        profiles.update(account, username, "Анна", "О себе");
        String address = ip();
        for (String candidate : List.of(username, username.toLowerCase(), username.toUpperCase())) {
            var response = mvc.perform(get("/api/accounts/profiles/by-username/" + candidate)
                    .header("X-Forwarded-For", address)).andReturn().getResponse();
            assertThat(response.getStatus()).as(candidate).isEqualTo(200);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("public, max-age=60");
            assertThat(json.readTree(response.getContentAsString())).isEqualTo(
                    json.readTree(body(get("/api/accounts/profiles/" + account.accountId()), 200)));
        }
        assertThat(json.readTree(body(get("/api/accounts/profiles/by-username/" + username)
                .header("X-Forwarded-For", address), 200)).get("profileUsername").stringValue()).isEqualTo(username);
    }

    @Test
    void byUsernameDoesNotCollideWithTheAvatarRoute() throws Exception {
        var account = account(true);
        profiles.update(account, "avatar", "", "");
        publicProfiles.update(account, new PublicProfiles.Update(true, false, false, false, VERSION));
        JsonNode card = json.readTree(body(get("/api/accounts/profiles/by-username/avatar")
                .header("X-Forwarded-For", ip()), 200));
        assertThat(card.get("accountId").stringValue()).isEqualTo(account.accountId().toString());
    }

    @Test
    void byUsernameMalformedNamesAreTheSame404AsUnknownOnes() throws Exception {
        String address = ip();
        String reference = normalized("/api/accounts/profiles/by-username/ghost-user", "ghost-user", address);
        assertThat(reference).contains("profile_not_found");
        for (String malformed : List.of("a", "ab", "x".repeat(51), "bad!name", "a$b")) {
            assertThat(normalized("/api/accounts/profiles/by-username/" + malformed, malformed, address))
                    .as(malformed).isEqualTo(reference);
        }
    }

    @Test
    void byUsernameNetworkLimitAlwaysAppliesAndSignedInAccountsAddAnAccountLimit() throws Exception {
        String address = ip();
        for (int i = 0; i < PublicProfileController.USERNAME_LIMIT; i++)
            assertThat(status(get("/api/accounts/profiles/by-username/ghost-user").header("X-Forwarded-For", address)))
                    .as("request %d", i).isEqualTo(404);
        var limited = mvc.perform(get("/api/accounts/profiles/by-username/ghost-user")
                .header("X-Forwarded-For", address)).andReturn().getResponse();
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(json.readTree(limited.getContentAsString()).get("code").stringValue()).isEqualTo("try_later");
        // Signing in does not buy a second budget on the same network.
        String token = bearer(account(true));
        assertThat(status(get("/api/accounts/profiles/by-username/ghost-user")
                .header("Authorization", "Bearer " + token).header("X-Forwarded-For", address))).isEqualTo(429);
        // On a fresh network the account spends both buckets.
        String second = ip();
        for (int i = 0; i < PublicProfileController.USERNAME_LIMIT; i++)
            assertThat(status(get("/api/accounts/profiles/by-username/ghost-user")
                    .header("Authorization", "Bearer " + token).header("X-Forwarded-For", second)))
                    .as("signed in request %d", i).isEqualTo(404);
        assertThat(status(get("/api/accounts/profiles/by-username/ghost-user")
                .header("Authorization", "Bearer " + token).header("X-Forwarded-For", second))).isEqualTo(429);
        // Changing network does not reset the account budget ...
        assertThat(status(get("/api/accounts/profiles/by-username/ghost-user")
                .header("Authorization", "Bearer " + token).header("X-Forwarded-For", ip()))).isEqualTo(429);
        // ... and does not touch anonymous callers elsewhere.
        assertThat(status(get("/api/accounts/profiles/by-username/ghost-user").header("X-Forwarded-For", ip())))
                .isEqualTo(404);
    }

    @Test
    void ipv6ClientsShareOneBudgetPerSlash64() throws Exception {
        var random = ThreadLocalRandom.current();
        String network = "2001:db8:" + Integer.toHexString(random.nextInt(1, 65535)) + ":" +
                Integer.toHexString(random.nextInt(1, 65535));
        for (int i = 0; i < PublicProfileController.USERNAME_LIMIT; i++)
            assertThat(status(get("/api/accounts/profiles/by-username/ghost-user")
                    .header("X-Forwarded-For", network + "::" + (i + 1)))).as("request %d", i).isEqualTo(404);
        // Rotating the interface identifier inside the same /64 stays limited.
        assertThat(status(get("/api/accounts/profiles/by-username/ghost-user")
                .header("X-Forwarded-For", network + ":abcd:1234:5678:9abc"))).isEqualTo(429);
        // Another /64 and an IPv4 client are unaffected.
        assertThat(status(get("/api/accounts/profiles/by-username/ghost-user")
                .header("X-Forwarded-For", "2001:db8:" + Integer.toHexString(random.nextInt(1, 65535)) + ":ffff::1")))
                .isEqualTo(404);
        assertThat(status(get("/api/accounts/profiles/by-username/ghost-user").header("X-Forwarded-For", ip())))
                .isEqualTo(404);
    }

    // ---- authentication boundary -----------------------------------------------------------------------------

    @Test
    void publicReadsNeedNoAuthenticationAndOwnRoutesNeedABearer() throws Exception {
        var account = publicAccount(true, true, true);
        String id = account.accountId().toString();
        assertThat(status(get("/api/accounts/profiles/" + id))).isEqualTo(200);
        assertThat(status(get("/api/accounts/profiles").queryParam("ids", id).header("X-Forwarded-For", ip())))
                .isEqualTo(200);
        assertThat(status(get("/api/accounts/profiles/by-username/" + username(account))
                .header("X-Forwarded-For", ip()))).isEqualTo(200);
        assertThat(status(get("/api/accounts/profiles/" + id + "/avatar"))).isEqualTo(404);
        assertThat(status(get("/api/accounts/profiles/" + UUID.randomUUID()))).isEqualTo(404);

        for (MockHttpServletRequestBuilder request : List.of(get("/api/accounts/me/public-profile"),
                get("/api/accounts/me/avatar"),
                put("/api/accounts/me/public-profile").with(csrf()).contentType("application/json")
                        .content("{}"))) {
            assertThat(status(request)).isEqualTo(401);
        }
        assertThat(status(get("/api/accounts/me/public-profile").header("Authorization", "Bearer invalid")))
                .isEqualTo(401);
    }

    @Test
    void bannedOwnerLosesBearerAccessAndTheirPublicCard() throws Exception {
        var owner = publicAccount(true, true, true);
        String token = bearer(owner);
        jdbc.sql("UPDATE app_identity.account SET status='BANNED',banned_at=statement_timestamp() WHERE account_id=:id")
                .param("id", owner.accountId()).update();
        assertThat(status(get("/api/accounts/me/public-profile").header("Authorization", "Bearer " + token)))
                .isEqualTo(401);
        assertThat(status(get("/api/accounts/profiles/" + owner.accountId()))).isEqualTo(404);
    }

    // ---- fixture ---------------------------------------------------------------------------------------------

    @Test
    void fixtureNotFoundAndBatchShapesMatchTheRealResponses() throws Exception {
        JsonNode fixture = fixture();
        var account = publicAccount(true, false, false);
        var notFound = mvc.perform(get("/api/accounts/profiles/" + UUID.randomUUID())).andReturn().getResponse();
        assertThat(notFound.getStatus()).isEqualTo(fixture.get("notFound").get("status").intValue());
        assertThat(json.readTree(notFound.getContentAsString()).get("code").stringValue())
                .isEqualTo(fixture.get("notFound").get("code").stringValue());
        JsonNode batch = json.readTree(body(get("/api/accounts/profiles")
                .queryParam("ids", account.accountId() + "," + UUID.randomUUID())
                .header("X-Forwarded-For", ip()), 200));
        assertSameShape(fixture.get("batch").get("response"), batch);
        assertSameShape(fixture.get("batch").get("response").get("profiles").get(0), batch.get("profiles").get(0));
        assertThat(fixture.get("batch").get("request")).hasSize(2);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private JsonNode fixture() throws Exception {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("contracts/identity/public-profile.json")))
            directory = directory.getParent();
        assertThat(directory).as("repository root with contracts/identity").isNotNull();
        return json.readTree(Files.readString(directory.resolve("contracts/identity/public-profile.json"),
                StandardCharsets.UTF_8));
    }

    /** Same property names and JSON types, recursively; the first element represents arrays. */
    private static void assertSameShape(JsonNode expected, JsonNode actual) {
        assertThat(actual.getNodeType()).as("type of %s", actual).isEqualTo(expected.getNodeType());
        if (expected.isObject()) {
            assertThat(new TreeSet<>(actual.propertyNames())).isEqualTo(new TreeSet<>(expected.propertyNames()));
            expected.properties().forEach(entry -> assertSameShape(entry.getValue(), actual.get(entry.getKey())));
        } else if (expected.isArray() && !expected.isEmpty() && !actual.isEmpty()) {
            assertSameShape(expected.get(0), actual.get(0));
        }
    }

    /** Card nullable fields may differ in type between examples; the property set and scalar types of the rest hold. */
    private static void assertSameShapeIgnoringNulls(JsonNode expected, JsonNode actual) {
        assertThat(new TreeSet<>(actual.propertyNames())).isEqualTo(new TreeSet<>(expected.propertyNames()));
        expected.properties().forEach(entry -> {
            JsonNode value = actual.get(entry.getKey());
            if (!entry.getValue().isNull() && !value.isNull())
                assertThat(value.getNodeType()).isEqualTo(entry.getValue().getNodeType());
        });
    }

    private AccountAccess account(boolean withUsername) {
        String key = UUID.randomUUID().toString();
        return local.register(key + "@example.test", key, PASSWORD,
                withUsername ? "u" + key.substring(0, 12) : null, key);
    }

    /** An account with a username, the name "Анна", the bio "О себе" and the given consent flags. */
    private AccountAccess publicAccount(boolean name, boolean avatar, boolean bio) {
        var account = account(true);
        profiles.update(account, username(account), "Анна", "О себе");
        publicProfiles.update(account, new PublicProfiles.Update(true, name, avatar, bio, VERSION));
        return account;
    }

    private String username(AccountAccess account) {
        return jdbc.sql("SELECT profile_username FROM app_identity.account WHERE account_id=:id")
                .param("id", account.accountId()).query(String.class).single();
    }

    private void withAvatar(AccountAccess account, byte[] bytes) {
        UUID asset = UUID.randomUUID();
        String key = "account-avatar/" + account.accountId() + "/" + asset;
        jdbc.sql("""
                        INSERT INTO app_identity.account_avatar(account_id,asset_id,storage_key,content_type,byte_size,
                            content_sha256,created_at,storage_version)
                        VALUES(:id,:asset,:key,'image/png',:size,:hash,statement_timestamp(),'v1')
                        """).param("id", account.accountId()).param("asset", asset).param("key", key)
                .param("size", bytes.length).param("hash", Secrets.digest(bytes)).update();
        Mockito.when(storage.get(key)).thenReturn(bytes);
    }

    private static String ip() {
        var random = ThreadLocalRandom.current();
        return "10." + random.nextInt(1, 255) + "." + random.nextInt(1, 255) + "." + random.nextInt(1, 255);
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private String body(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString())
                .isEqualTo(expectedStatus);
        return result.getResponse().getContentAsString();
    }

    /** The problem body with the requested identifier removed, so unknown and hidden accounts compare equal. */
    private String normalized(String url, String identifier) throws Exception {
        return normalized(url, identifier, null);
    }

    private String normalized(String url, String identifier, String address) throws Exception {
        var request = get(url);
        if (address != null) request.header("X-Forwarded-For", address);
        var response = mvc.perform(request).andReturn().getResponse();
        return response.getStatus() + "|" + response.getContentType() + "|" + response.getHeader("Cache-Control") +
                "|" + response.getContentAsString().replace(url, "{url}");
    }

    private int batch(String address, String ids) throws Exception {
        return status(get("/api/accounts/profiles").queryParam("ids", ids).header("X-Forwarded-For", address));
    }

    private int attempts(String action, String address) {
        return jdbc.sql("SELECT attempts FROM app_identity.rate_limit WHERE bucket=:bucket")
                .param("bucket", Secrets.hash(action + ":" + address)).query(Integer.class).optional().orElse(0);
    }

    private JsonNode consent(String token) throws Exception {
        return json.readTree(body(get("/api/accounts/me/public-profile").header("Authorization", "Bearer " + token),
                200));
    }

    private JsonNode putConsent(String token, boolean enabled, boolean name, boolean avatar, boolean bio) throws Exception {
        return putConsent(token, enabled, name, avatar, bio, VERSION, 200, null);
    }

    private JsonNode putConsent(String token, boolean enabled, boolean name, boolean avatar, boolean bio, String version,
                         int expectedStatus, String expectedCode) throws Exception {
        var content = new LinkedHashMap<String, Object>();
        content.put("enabled", enabled);
        content.put("showDisplayName", name);
        content.put("showAvatar", avatar);
        content.put("showBio", bio);
        content.put("textVersion", version);
        var response = mvc.perform(put("/api/accounts/me/public-profile").header("Authorization", "Bearer " + token)
                .contentType("application/json").content(json.writeValueAsString(content))).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expectedStatus);
        JsonNode result = json.readTree(response.getContentAsString());
        if (expectedCode != null) assertThat(result.get("code").stringValue()).isEqualTo(expectedCode);
        return result;
    }

    private List<String> actions(AccountAccess account) {
        return jdbc.sql("SELECT action FROM app_identity.public_profile_consent_event WHERE account_id=:id ORDER BY event_id")
                .param("id", account.accountId()).query(String.class).list();
    }

    private long count(String table, AccountAccess account) {
        return jdbc.sql("SELECT count(*) FROM app_identity." + table + " WHERE account_id=:id")
                .param("id", account.accountId()).query(Long.class).single();
    }

    private String bearer(AccountAccess access) throws Exception {
        Instant expires = Instant.now().plusSeconds(300);
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(SIGNING_KEY.getKeyID())
                .type(new JOSEObjectType("at+jwt")).build(),
                new JWTClaimsSet.Builder().subject(access.accountId().toString())
                        .issuer("https://identity.mnema.test").audience("mnema-api")
                        .issueTime(Date.from(Instant.now().minusSeconds(5))).expirationTime(Date.from(expires))
                        .claim("generation", Long.toString(access.generation()))
                        .claim("scope", "account.read account.write").build());
        jwt.sign(new RSASSASigner(SIGNING_KEY));
        String token = jwt.serialize();
        authorizations.save(OAuth2Authorization.withRegisteredClient(clients.findByClientId("mnema-web"))
                .principalName(access.accountId().toString())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .attribute("generation", Long.toString(access.generation()))
                .accessToken(new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, token,
                        Instant.now().minusSeconds(5), expires, Set.of("account.read", "account.write")))
                .build());
        return token;
    }
}
