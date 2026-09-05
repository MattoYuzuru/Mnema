package app.mnema.identityaccount.deletion;

import app.mnema.identityaccount.account.AccountStore;
import app.mnema.identityaccount.avatar.OwnedAvatarEraser;
import app.mnema.identityaccount.contract.AccountAccess;
import app.mnema.identityaccount.contract.AccountFailure;
import app.mnema.identityaccount.federation.FederatedAccounts;
import app.mnema.identityaccount.local.LocalAccounts;
import app.mnema.identityaccount.moderation.Moderation;
import app.mnema.identityaccount.profile.Profiles;
import app.mnema.identityaccount.security.OwnershipProofs;
import app.mnema.identityaccount.support.PostgresIntegrationTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "identity.deletion.enabled=true",
        "identity.deletion.recovery-period=PT1H",
        "identity.deletion.recovery-session-period=PT5M",
        "identity.deletion.lease-period=PT2M",
        "identity.deletion.scan-delay=PT24H",
        "identity.deletion.batch-size=10"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(AccountDeletionIntegrationTest.StorageFixture.class)
class AccountDeletionIntegrationTest extends PostgresIntegrationTest {
    private static final String PASSWORD = "correct-horse-battery-42";
    private static final Map<String, StoredAvatar> OBJECTS = new ConcurrentHashMap<>();
    private static final Set<String> FAILURES = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean FAIL_IDENTITY_RECEIPT = new AtomicBoolean();

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    TransactionTemplate transactions;
    @Autowired
    LocalAccounts local;
    @Autowired
    AccountStore accounts;
    @Autowired
    OwnershipProofs proofs;
    @Autowired
    Profiles profiles;
    @Autowired
    Moderation moderation;
    @Autowired
    AccountDeletions deletions;
    @Autowired
    AccountPurgeWorker worker;
    @Autowired
    AccountDeletionPolicy policy;
    @Autowired
    AccountErasureLedger ledger;
    @Autowired
    FederatedAccounts federation;
    @Autowired
    OwnedAvatarEraser avatarEraser;
    @Autowired
    org.springframework.session.jdbc.JdbcIndexedSessionRepository sessionRepository;
    @Autowired
    org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository clients;
    @Autowired
    org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService authorizations;

    @BeforeEach
    void clearStorageFixture() {
        OBJECTS.clear();
        FAILURES.clear();
        FAIL_IDENTITY_RECEIPT.set(false);
    }

    @Test
    void concurrentRequestsCreateOneFixedOperationAndImmediatelyRevokeAndHide() throws Exception {
        AccountAccess account = account();
        var confirmedProof = proof(account);
        var differentProof = proof(account);
        Cookie session = login(account);
        String bearer = bearer(account);
        mvc.perform(get("/api/accounts/me").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk());

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var first = executor.submit(() -> {
                start.await();
                return deletions.request(account, confirmedProof.token());
            });
            var second = executor.submit(() -> {
                start.await();
                return deletions.request(account, confirmedProof.token());
            });
            start.countDown();
            var firstView = first.get(10, TimeUnit.SECONDS);
            var secondView = second.get(10, TimeUnit.SECONDS);
            assertThat(secondView).isEqualTo(firstView);
        }

        assertThat(jdbc.sql("SELECT count(*) FROM app_identity.account_deletion WHERE account_id=:account")
                .param("account", account.accountId()).query(Long.class).single()).isOne();
        assertThatThrownBy(() -> deletions.request(account, differentProof.token()))
                .isInstanceOf(AccountFailure.class).hasMessage("invalid_proof");
        jdbc.sql("""
                        UPDATE app_identity.account_deletion
                        SET deletion_requested_at=transaction_timestamp()-interval '2 seconds',
                            confirmation_expires_at=transaction_timestamp()-interval '1 second'
                        WHERE account_id=:account
                        """).param("account", account.accountId()).update();
        assertThatThrownBy(() -> deletions.request(account, confirmedProof.token()))
                .isInstanceOf(AccountFailure.class).hasMessage("invalid_proof");
        mvc.perform(post("/api/accounts/deletion/confirmed").secure(true).with(csrf())
                        .contentType("application/json").content(body(Map.of("proof", confirmedProof.token()))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_proof"));
        assertThat(jdbc.sql("SELECT count(*) FROM app_identity.oauth2_authorization WHERE principal_name=:account")
                .param("account", account.accountId().toString()).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_identity.ownership_challenge WHERE account_id=:account")
                .param("account", account.accountId()).query(Long.class).single()).isZero();
        assertThatThrownBy(() -> accounts.require(account, false)).isInstanceOf(AccountFailure.class);
        assertThatThrownBy(() -> profiles.publicProfile(account.accountId())).isInstanceOf(AccountFailure.class)
                .hasMessage("profile_not_found");
        mvc.perform(get("/api/accounts/me").secure(true).cookie(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/accounts/me").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passwordProofCreatesOnlyRecoveryContextAndCancelRotatesToFreshOrdinarySession() throws Exception {
        AccountAccess account = account();
        Cookie ordinary = login(account);
        var operation = deletions.request(account, proof(account).token());
        var deadline = operation.recoverableUntil();

        mvc.perform(post("/api/accounts/login").secure(true).with(csrf()).contentType("application/json")
                        .content(body(Map.of("login", email(account), "password", "wrong-password"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("authentication_failed"));
        var recoveryLogin = mvc.perform(post("/api/accounts/login").secure(true).with(csrf())
                        .contentType("application/json").content(body(Map.of("login", email(account),
                                "password", PASSWORD))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.context").value("ACCOUNT_RECOVERY"))
                .andExpect(jsonPath("$.operationId").value(operation.operationId().toString())).andReturn();
        Cookie recovery = session(recoveryLogin.getResponse().getCookies());
        assertThat(deletions.recovery(accounts.get(account.accountId(), false).access(), operation.operationId())
                .recoverableUntil()).isEqualTo(deadline);

        mvc.perform(get("/api/accounts/csrf").secure(true).cookie(recovery)).andExpect(status().isOk());
        mvc.perform(get("/api/accounts/me").secure(true).cookie(recovery)).andExpect(status().isForbidden());
        recovery = recoveryLogin(account);
        mvc.perform(get("/oauth2/authorize").secure(true).cookie(recovery)).andExpect(status().isForbidden());
        recovery = recoveryLogin(account);
        mvc.perform(get("/api/accounts/deletion/recovery/" + UUID.randomUUID()).secure(true).cookie(recovery))
                .andExpect(status().isForbidden());
        recovery = recoveryLogin(account);
        expireRecovery(recovery);
        mvc.perform(get("/api/accounts/deletion/recovery/" + operation.operationId()).secure(true).cookie(recovery))
                .andExpect(status().isUnauthorized());
        recovery = recoveryLogin(account);
        mvc.perform(post("/api/accounts/deletion/recovery/logout").secure(true).cookie(recovery).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/accounts/deletion/recovery/" + operation.operationId()).secure(true).cookie(recovery))
                .andExpect(status().isUnauthorized());
        recovery = recoveryLogin(account);
        mvc.perform(delete("/api/accounts/deletion/recovery/" + operation.operationId()).secure(true)
                        .cookie(recovery)).andExpect(status().isForbidden());
        var cancelled = mvc.perform(delete("/api/accounts/deletion/recovery/" + operation.operationId()).secure(true)
                        .cookie(recovery).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ordinaryAccessRestored").value(true)).andReturn();
        Cookie restored = session(cancelled.getResponse().getCookies());
        assertThat(restored.getValue()).isNotEqualTo(recovery.getValue()).isNotEqualTo(ordinary.getValue());
        mvc.perform(get("/api/accounts/me").secure(true).cookie(restored)).andExpect(status().isOk());
        mvc.perform(delete("/api/accounts/deletion/recovery/" + operation.operationId()).secure(true)
                        .cookie(recovery).with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test
    void cancelPreservesBanAndRemovedAdministration() throws Exception {
        AccountAccess admin = account();
        jdbc.sql("UPDATE app_identity.account SET is_admin=true WHERE account_id=:account")
                .param("account", admin.accountId()).update();
        AccountAccess child = account();
        moderation.apply(admin, child.accountId(), Moderation.Action.GRANT_ADMIN, null);

        var operation = deletions.request(admin, proof(admin).token());
        assertThat(accounts.get(admin.accountId(), false).isAdmin()).isFalse();
        assertThat(accounts.get(child.accountId(), false).isAdmin()).isFalse();
        AccountAccess moderator = account();
        jdbc.sql("UPDATE app_identity.account SET is_admin=true WHERE account_id=:account")
                .param("account", moderator.accountId()).update();
        moderation.apply(moderator, admin.accountId(), Moderation.Action.BAN, "fixture");

        Cookie recovery = recoveryLogin(admin);
        mvc.perform(delete("/api/accounts/deletion/recovery/" + operation.operationId()).secure(true)
                        .cookie(recovery).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ordinaryAccessRestored").value(false));
        var cancelled = accounts.get(admin.accountId(), false);
        assertThat(cancelled.status()).isEqualTo("BANNED");
        assertThat(cancelled.deletionState()).isEqualTo("ACTIVE");
        assertThat(cancelled.isAdmin()).isFalse();
    }

    @Test
    void alreadyBannedPasswordOwnerCanOnlyProveDeleteRecoverAndCancel() throws Exception {
        AccountAccess banned = account();
        AccountAccess moderator = account();
        jdbc.sql("UPDATE app_identity.account SET is_admin=true WHERE account_id=:account")
                .param("account", moderator.accountId()).update();
        moderation.apply(moderator, banned.accountId(), Moderation.Action.BAN, "fixture");

        mvc.perform(post("/api/accounts/login").secure(true).with(csrf()).contentType("application/json")
                        .content(body(Map.of("login", email(banned), "password", PASSWORD))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("authentication_failed"));
        mvc.perform(post("/api/accounts/deletion/proof/password").secure(true).with(csrf())
                        .contentType("application/json")
                        .content(body(Map.of("login", email(banned), "password", "wrong-password"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("authentication_failed"));
        var proofResponse = mvc.perform(post("/api/accounts/deletion/proof/password").secure(true).with(csrf())
                        .contentType("application/json")
                        .content(body(Map.of("login", email(banned), "password", PASSWORD))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.token").isString()).andReturn();
        if (proofResponse.getResponse().getCookie("SESSION") != null)
            mvc.perform(get("/api/accounts/me").secure(true)
                            .cookie(proofResponse.getResponse().getCookie("SESSION")))
                    .andExpect(status().isUnauthorized());
        String proof = json.readTree(proofResponse.getResponse().getContentAsString()).get("token").asText();
        var operation = mvc.perform(post("/api/accounts/deletion/confirmed").secure(true).with(csrf())
                        .contentType("application/json").content(body(Map.of("proof", proof))))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("PENDING_DELETION"))
                .andReturn();
        mvc.perform(post("/api/accounts/deletion/confirmed").secure(true).with(csrf())
                        .contentType("application/json").content(body(Map.of("proof", proof))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operationId").value(json.readTree(operation.getResponse().getContentAsString())
                        .get("operationId").asText()));
        assertThat(accounts.get(banned.accountId(), false).status()).isEqualTo("BANNED");
        Cookie recovery = recoveryLogin(banned);
        mvc.perform(delete("/api/accounts/deletion/recovery/" + json.readTree(
                                operation.getResponse().getContentAsString()).get("operationId").asText())
                        .secure(true).cookie(recovery).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ordinaryAccessRestored").value(false));
        assertThat(accounts.get(banned.accountId(), false).status()).isEqualTo("BANNED");
    }

    @Test
    void boundProviderCanProveRecoveryButOrdinaryFederatedLoginStaysRevoked() {
        AccountAccess account = account();
        var external = new FederatedAccounts.External("github", "subject-" + UUID.randomUUID(), null, false);
        federation.complete(external, account);
        var operation = deletions.request(account, proof(account).token());

        assertThatThrownBy(() -> federation.complete(external, null)).isInstanceOf(AccountFailure.class)
                .hasMessage("authentication_failed");
        AccountAccess recovery = federation.recovery(external);
        assertThat(deletions.passwordRecovery(recovery)).isEqualTo(recovery);
        assertThat(deletions.recovery(recovery, operation.operationId()).context()).isEqualTo("ACCOUNT_RECOVERY");
        assertThatThrownBy(() -> federation.recovery(new FederatedAccounts.External("github", "foreign", null,
                false))).isInstanceOf(AccountFailure.class).hasMessage("authentication_failed");
    }

    @Test
    void zeroRecoveryPolicyMakesDeadlineImmediateWithoutChangingItLater() {
        assertThatThrownBy(() -> new AccountDeletionPolicy(true, "", Duration.ofMinutes(5),
                Duration.ofMinutes(2), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new AccountDeletionPolicy(false, "", Duration.ofMinutes(5), Duration.ofMinutes(2), 1)
                .recoveryPeriod()).isZero();
        var zeroPolicy = new AccountDeletionPolicy(true, "PT0S", Duration.ofMinutes(5),
                Duration.ofMinutes(2), 1);
        var zeroDeletions = new AccountDeletions(jdbc, accounts, proofs, transactions, zeroPolicy, local);
        AccountAccess account = account();
        var operation = zeroDeletions.request(account, proof(account).token());
        AccountAccess recovery = accounts.get(account.accountId(), false).access();

        assertThat(operation.recoverableUntil()).isEqualTo(operation.purgeAfter());
        assertThatThrownBy(() -> zeroDeletions.recovery(recovery, operation.operationId()))
                .isInstanceOf(AccountFailure.class).hasMessage("operation_denied");
        assertThatThrownBy(() -> zeroDeletions.cancel(recovery, operation.operationId()))
                .isInstanceOf(AccountFailure.class).hasMessage("operation_denied");
        assertThat(jdbc.sql("SELECT recoverable_until=purge_after FROM app_identity.account_deletion WHERE account_id=:id")
                .param("id", account.accountId()).query(Boolean.class).single()).isTrue();
        var zeroWorker = new AccountPurgeWorker(jdbc, transactions, zeroPolicy, avatarEraser, ledger);
        zeroWorker.scan();
        assertThat(accounts.get(account.accountId(), false).deletionState()).isEqualTo("PURGED");
    }

    @Test
    void purgeRetriesOwnedAvatarFailuresThenScrubsIdentityAndWritesFencedReceipts() {
        AccountAccess account = account();
        String originalEmail = email(account);
        jdbc.sql("UPDATE app_identity.account SET is_admin=true WHERE account_id=:account")
                .param("account", account.accountId()).update();
        AccountAccess moderated = account();
        moderation.apply(account, moderated.accountId(), Moderation.Action.BAN, "preserved FK fixture");
        var external = new FederatedAccounts.External("github", "purge-" + UUID.randomUUID(), null, false);
        federation.complete(external, account);
        var staleReset = transactions.execute(
                status -> proofs.issue(account, OwnershipProofs.Purpose.RESET_PASSWORD));
        UUID asset = UUID.randomUUID();
        String key = "account-avatar/" + account.accountId() + "/" + asset;
        UUID missingAsset = UUID.randomUUID();
        String missingKey = "account-avatar/" + account.accountId() + "/" + missingAsset;
        byte[] hash = new byte[32];
        jdbc.sql("""
                        INSERT INTO app_identity.account_avatar(
                            account_id,asset_id,storage_key,content_type,byte_size,content_sha256,created_at,storage_version)
                        VALUES(:account,:asset,:key,'image/png',3,:hash,transaction_timestamp(),'version-1')
                        """).param("account", account.accountId()).param("asset", asset).param("key", key)
                .param("hash", hash).update();
        jdbc.sql("""
                        INSERT INTO app_identity.avatar_cleanup(
                            storage_key,account_id,asset_id,storage_version,content_sha256)
                        VALUES(:key,:account,:asset,'missing-version',:hash)
                        """).param("key", missingKey).param("account", account.accountId())
                .param("asset", missingAsset).param("hash", hash).update();
        OBJECTS.put(key, new StoredAvatar(UUID.randomUUID(), asset, "version-1"));
        var operation = deletions.request(account, proof(account).token());
        makeDue(account.accountId());

        worker.scan();
        assertThat(accounts.get(account.accountId(), false).deletionState()).isEqualTo("PURGING");
        assertThat(OBJECTS).containsKey(key);
        assertThat(jdbc.sql("SELECT last_error_code FROM app_identity.account_deletion WHERE account_id=:account")
                .param("account", account.accountId()).query(String.class).single())
                .isEqualTo("avatar_ownership_mismatch");

        OBJECTS.put(key, new StoredAvatar(account.accountId(), asset, "version-1"));
        FAILURES.add(key);
        makeDue(account.accountId());
        worker.scan();
        assertThat(jdbc.sql("SELECT last_error_code FROM app_identity.account_deletion WHERE account_id=:account")
                .param("account", account.accountId()).query(String.class).single())
                .isEqualTo("avatar_storage_unavailable");
        assertThat(OBJECTS).containsKey(key);
        FAILURES.remove(key);
        makeDue(account.accountId());
        worker.scan();

        var tombstone = accounts.get(account.accountId(), false);
        assertThat(tombstone.deletionState()).isEqualTo("PURGED");
        assertThat(tombstone.email()).isNull();
        assertThat(tombstone.profileUsername()).isNull();
        assertThat(tombstone.displayName()).isNull();
        assertThat(tombstone.bio()).isNull();
        assertThat(tombstone.isAdmin()).isFalse();
        assertThat(OBJECTS).doesNotContainKey(key);
        assertThat(count("local_credential", account.accountId())).isZero();
        assertThat(count("external_identity", account.accountId())).isZero();
        assertThat(count("account_avatar", account.accountId())).isZero();
        assertThat(count("account_deletion_avatar", account.accountId())).isZero();
        assertThat(jdbc.sql("SELECT banned_by FROM app_identity.account WHERE account_id=:account")
                .param("account", moderated.accountId()).query(UUID.class).single()).isEqualTo(account.accountId());
        assertThat(jdbc.sql("SELECT avatar_object_count FROM app_identity.account_erasure_handoff WHERE operation_id=:operation")
                .param("operation", operation.operationId()).query(Integer.class).single()).isEqualTo(2);
        assertThat(ledger.receipts(operation.operationId())).containsExactly(AccountErasureLedger.IDENTITY_SCOPE);
        UUID downstreamReceipt = UUID.randomUUID();
        ledger.acknowledge(operation.operationId(), tombstone.deletionGeneration(), "learning-content",
                downstreamReceipt);
        ledger.acknowledge(operation.operationId(), tombstone.deletionGeneration(), "learning-content",
                downstreamReceipt);
        assertThatThrownBy(() -> ledger.acknowledge(operation.operationId(),
                tombstone.deletionGeneration() + 1, "study-state", UUID.randomUUID()))
                .hasMessage("erasure_receipt_conflict");
        assertThatThrownBy(() -> ledger.acknowledge(operation.operationId(), tombstone.deletionGeneration(),
                AccountErasureLedger.IDENTITY_SCOPE, UUID.randomUUID())).hasMessage("invalid_erasure_scope");
        assertThatThrownBy(() -> ledger.acknowledge(operation.operationId(), tombstone.deletionGeneration(),
                "learning-content", UUID.randomUUID())).hasMessage("erasure_receipt_conflict");
        assertThatThrownBy(() -> ledger.acknowledge(operation.operationId(), tombstone.deletionGeneration(),
                "study-state", downstreamReceipt)).hasMessage("erasure_receipt_conflict");
        assertThat(ledger.receipts(operation.operationId()))
                .containsExactly(AccountErasureLedger.IDENTITY_SCOPE, "learning-content");

        AccountAccess replacement = local.register(originalEmail, UUID.randomUUID().toString(), PASSWORD, null,
                "replacement");
        assertThat(replacement.accountId()).isNotEqualTo(account.accountId());
        assertThatThrownBy(() -> proofs.identify(staleReset.token())).hasMessage("invalid_proof");
    }

    @Test
    void staleLeaseCannotHeartbeatOrCompleteAfterAnotherReplicaReclaimsIt() {
        AccountAccess account = account();
        deletions.request(account, proof(account).token());
        makeDue(account.accountId());
        AccountPurgeWorker.Lease stale = worker.claim();
        jdbc.sql("""
                        UPDATE app_identity.account_deletion
                        SET lease_expires_at=transaction_timestamp()-interval '1 second'
                        WHERE account_id=:account
                        """).param("account", account.accountId()).update();
        var replacement = new AccountPurgeWorker(jdbc, transactions, policy, avatarEraser, ledger);
        AccountPurgeWorker.Lease current = replacement.claim();

        assertThat(current).isNotNull();
        assertThat(current.epoch()).isGreaterThan(stale.epoch());
        assertThat(worker.heartbeat(stale)).isFalse();
        assertThat(worker.complete(stale)).isFalse();
        assertThat(replacement.heartbeat(current)).isTrue();
        assertThat(replacement.complete(current)).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM app_identity.account_erasure_receipt WHERE operation_id=:operation")
                .param("operation", current.operationId()).query(Long.class).single()).isOne();
    }

    @Test
    void cancellationHoldingTheAccountLockWinsAgainstADueSkipLockedClaim() throws Exception {
        AccountAccess account = account();
        var operation = deletions.request(account, proof(account).token());
        AccountAccess recovery = accounts.get(account.accountId(), false).access();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var cancellation = executor.submit(() -> transactions.execute(status -> {
                accounts.requireRecovery(recovery, true);
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("claim did not run");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("cancellation interrupted", interrupted);
                }
                return deletions.cancel(recovery, operation.operationId());
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            jdbc.sql("""
                            UPDATE app_identity.account_deletion
                            SET recoverable_until=transaction_timestamp(),purge_after=transaction_timestamp(),
                                next_attempt_at=transaction_timestamp()-interval '1 second'
                            WHERE account_id=:account
                            """).param("account", account.accountId()).update();
            assertThat(worker.claim()).isNull();
            release.countDown();
            assertThat(cancellation.get(10, TimeUnit.SECONDS).accountId()).isEqualTo(account.accountId());
        }

        assertThat(accounts.get(account.accountId(), false).deletionState()).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("SELECT count(*) FROM app_identity.account_deletion WHERE account_id=:account")
                .param("account", account.accountId()).query(Long.class).single()).isZero();
    }

    @Test
    void mandatoryIdentityReceiptFailureRollsBackScrubAndRemainsRetryable() {
        AccountAccess account = account();
        String email = email(account);
        var operation = deletions.request(account, proof(account).token());
        makeDue(account.accountId());
        FAIL_IDENTITY_RECEIPT.set(true);

        worker.scan();

        var retryable = accounts.get(account.accountId(), false);
        assertThat(retryable.deletionState()).isEqualTo("PURGING");
        assertThat(retryable.email()).isEqualTo(email);
        assertThat(count("local_credential", account.accountId())).isOne();
        assertThat(jdbc.sql("SELECT completed_at IS NULL FROM app_identity.account_deletion WHERE account_id=:account")
                .param("account", account.accountId()).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT last_error_code FROM app_identity.account_deletion WHERE account_id=:account")
                .param("account", account.accountId()).query(String.class).single())
                .isEqualTo("erasure_receipt_unavailable");
        assertThat(jdbc.sql("SELECT count(*) FROM app_identity.account_erasure_handoff WHERE operation_id=:operation")
                .param("operation", operation.operationId()).query(Long.class).single()).isZero();

        makeDue(account.accountId());
        worker.scan();
        assertThat(accounts.get(account.accountId(), false).deletionState()).isEqualTo("PURGED");
        assertThat(ledger.receipts(operation.operationId())).containsExactly(AccountErasureLedger.IDENTITY_SCOPE);
    }

    private AccountAccess account() {
        String value = UUID.randomUUID().toString();
        return local.register(value + "@example.test", value, PASSWORD, "profile-" + value.substring(0, 8), value);
    }

    private OwnershipProofs.Proof proof(AccountAccess account) {
        return transactions.execute(status -> proofs.issue(account, OwnershipProofs.Purpose.DELETE_ACCOUNT));
    }

    private String email(AccountAccess account) {
        return accounts.get(account.accountId(), false).email();
    }

    private Cookie login(AccountAccess account) throws Exception {
        var response = mvc.perform(post("/api/accounts/login").secure(true).with(csrf()).contentType("application/json")
                        .content(body(Map.of("login", email(account), "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse();
        return session(response.getCookies());
    }

    private Cookie recoveryLogin(AccountAccess account) throws Exception {
        var response = mvc.perform(post("/api/accounts/login").secure(true).with(csrf()).contentType("application/json")
                        .content(body(Map.of("login", email(account), "password", PASSWORD))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.context").value("ACCOUNT_RECOVERY"))
                .andReturn().getResponse();
        return session(response.getCookies());
    }

    private Cookie session(Cookie[] cookies) {
        return Arrays.stream(cookies).filter(cookie -> cookie.getName().equals("SESSION")).findFirst().orElseThrow();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void expireRecovery(Cookie cookie) {
        String sessionId = new String(Base64.getDecoder().decode(cookie.getValue()), StandardCharsets.UTF_8);
        org.springframework.session.Session session = sessionRepository.findById(sessionId);
        session.setAttribute("identity.recovery-expires", 0L);
        ((org.springframework.session.SessionRepository) sessionRepository).save(session);
    }

    private String body(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    private String bearer(AccountAccess access) throws Exception {
        var expires = java.time.Instant.now().plusSeconds(120);
        var jwt = new com.nimbusds.jwt.SignedJWT(
                new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256)
                        .keyID(SIGNING_KEY.getKeyID()).type(new com.nimbusds.jose.JOSEObjectType("at+jwt")).build(),
                new com.nimbusds.jwt.JWTClaimsSet.Builder().subject(access.accountId().toString())
                        .issuer("https://identity.mnema.test").audience("mnema-api")
                        .issueTime(java.util.Date.from(java.time.Instant.now().minusSeconds(5)))
                        .expirationTime(java.util.Date.from(expires))
                        .claim("generation", Long.toString(access.generation()))
                        .claim("scope", "account.read account.write").build());
        jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(SIGNING_KEY));
        String value = jwt.serialize();
        authorizations.save(org.springframework.security.oauth2.server.authorization.OAuth2Authorization
                .withRegisteredClient(clients.findByClientId("mnema-web"))
                .principalName(access.accountId().toString())
                .authorizationGrantType(
                        org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                .attribute("generation", Long.toString(access.generation()))
                .accessToken(new org.springframework.security.oauth2.core.OAuth2AccessToken(
                        org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType.BEARER, value,
                        java.time.Instant.now().minusSeconds(5), expires, Set.of("account.read", "account.write")))
                .build());
        return value;
    }

    private void makeDue(UUID accountId) {
        jdbc.sql("""
                        UPDATE app_identity.account_deletion
                        SET recoverable_until=deletion_requested_at,
                            purge_after=deletion_requested_at,
                            next_attempt_at=transaction_timestamp()-interval '1 second',
                            lease_owner=NULL,lease_expires_at=NULL
                        WHERE account_id=:account
                        """).param("account", accountId).update();
    }

    private long count(String table, UUID accountId) {
        return jdbc.sql("SELECT count(*) FROM app_identity." + table + " WHERE account_id=:account")
                .param("account", accountId).query(Long.class).single();
    }

    private record StoredAvatar(UUID accountId, UUID assetId, String version) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StorageFixture {
        @Bean
        @Primary
        OwnedAvatarEraser ownedAvatarEraser() {
            return manifest -> {
                if (FAILURES.contains(manifest.storageKey()))
                    throw new AccountFailure(503, "avatar_storage_unavailable");
                StoredAvatar stored = OBJECTS.get(manifest.storageKey());
                if (stored == null) return;
                if (!stored.accountId().equals(manifest.accountId()) || !stored.assetId().equals(manifest.assetId()) ||
                        !java.util.Objects.equals(stored.version(), manifest.storageVersion()))
                    throw new AccountFailure(409, "avatar_ownership_mismatch");
                OBJECTS.remove(manifest.storageKey(), stored);
            };
        }

        @Bean
        @Primary
        AccountErasureLedger accountErasureLedger(JdbcClient jdbcClient) {
            return new AccountErasureLedger(jdbcClient) {
                @Override
                void recordIdentity(UUID operationId, UUID accountId, long generation, int avatarCount,
                                    byte[] manifestHash) {
                    if (FAIL_IDENTITY_RECEIPT.getAndSet(false))
                        throw new AccountFailure(503, "erasure_receipt_unavailable");
                    super.recordIdentity(operationId, accountId, generation, avatarCount, manifestHash);
                }
            };
        }
    }
}
