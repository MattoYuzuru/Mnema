package app.mnema.identityaccount.deletion;

import app.mnema.identityaccount.avatar.OwnedAvatarEraser;
import app.mnema.identityaccount.contract.AccountFailure;
import app.mnema.identityaccount.security.Secrets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class AccountPurgeWorker {
    record Lease(UUID accountId, UUID operationId, long generation, UUID owner, long epoch,
                 List<OwnedAvatarEraser.Manifest> avatars) {
    }

    private static final Logger LOG = LoggerFactory.getLogger(AccountPurgeWorker.class);

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactions;
    private final AccountDeletionPolicy policy;
    private final OwnedAvatarEraser avatars;
    private final AccountErasureLedger ledger;
    private final UUID owner = UUID.randomUUID();

    public AccountPurgeWorker(JdbcClient jdbcClient, TransactionTemplate transactions, AccountDeletionPolicy policy,
                              OwnedAvatarEraser avatars, AccountErasureLedger ledger) {
        this.jdbcClient = jdbcClient;
        this.transactions = transactions;
        this.policy = policy;
        this.avatars = avatars;
        this.ledger = ledger;
    }

    @Scheduled(fixedDelayString = "${identity.deletion.scan-delay:PT1M}",
            initialDelayString = "${identity.deletion.scan-delay:PT1M}")
    public void scan() {
        if (!policy.enabled()) return;
        for (int index = 0; index < policy.batchSize(); index++) {
            Lease lease = claim();
            if (lease == null) return;
            process(lease);
        }
    }

    Lease claim() {
        if (!policy.enabled()) return null;
        return transactions.execute(status -> {
            var accountId = jdbcClient.sql("""
                            SELECT account.account_id
                            FROM app_identity.account account
                            JOIN app_identity.account_deletion deletion USING(account_id)
                            WHERE deletion.completed_at IS NULL
                              AND account.deletion_generation=deletion.generation
                              AND deletion.next_attempt_at <= transaction_timestamp()
                              AND (
                                (account.deletion_state='PENDING_DELETION'
                                  AND deletion.purge_after <= transaction_timestamp())
                                OR
                                (account.deletion_state='PURGING'
                                  AND (deletion.lease_owner IS NULL
                                    OR deletion.lease_expires_at <= transaction_timestamp()))
                              )
                            ORDER BY deletion.purge_after,deletion.operation_id
                            LIMIT 1 FOR UPDATE OF account SKIP LOCKED
                            """).query(UUID.class).optional();
            if (accountId.isEmpty()) return null;
            jdbcClient.sql("""
                            UPDATE app_identity.account SET deletion_state='PURGING',updated_at=transaction_timestamp(),
                                row_version=row_version+1
                            WHERE account_id=:account AND deletion_state IN ('PENDING_DELETION','PURGING')
                            """).param("account", accountId.get()).update();
            var claimed = jdbcClient.sql("""
                            UPDATE app_identity.account_deletion
                            SET attempt_count=attempt_count+1,lease_owner=:owner,lease_epoch=lease_epoch+1,
                                lease_expires_at=transaction_timestamp() + make_interval(secs => :lease),
                                last_error_code=NULL
                            WHERE account_id=:account
                            RETURNING operation_id,generation,lease_epoch
                            """).param("owner", owner).param("lease", policy.leasePeriod().toSeconds())
                    .param("account", accountId.get())
                    .query((row, number) -> new Claimed(row.getObject("operation_id", UUID.class),
                            row.getLong("generation"), row.getLong("lease_epoch"))).single();
            return new Lease(accountId.get(), claimed.operationId(), claimed.generation(), owner, claimed.epoch(),
                    manifest(claimed.operationId()));
        });
    }

    boolean heartbeat(Lease lease) {
        return transactions.execute(status -> jdbcClient.sql("""
                        UPDATE app_identity.account_deletion
                        SET lease_expires_at=transaction_timestamp() + make_interval(secs => :lease)
                        WHERE operation_id=:operation AND generation=:generation
                          AND lease_owner=:owner AND lease_epoch=:epoch
                          AND lease_expires_at > transaction_timestamp() AND completed_at IS NULL
                        """).param("lease", policy.leasePeriod().toSeconds()).param("operation", lease.operationId())
                .param("generation", lease.generation()).param("owner", lease.owner()).param("epoch", lease.epoch())
                .update() == 1);
    }

    boolean complete(Lease lease) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            lockAccount(lease.accountId());
            if (!ownsCurrentLease(lease)) return false;
            boolean unexpectedAvatar = jdbcClient.sql("""
                            SELECT EXISTS(
                                SELECT 1 FROM (
                                    SELECT account_id,asset_id,storage_key,storage_version
                                    FROM app_identity.account_avatar
                                    UNION ALL
                                    SELECT account_id,asset_id,storage_key,storage_version
                                    FROM app_identity.avatar_cleanup
                                ) avatar
                                WHERE avatar.account_id=:account AND NOT EXISTS(
                                    SELECT 1 FROM app_identity.account_deletion_avatar manifest
                                    WHERE manifest.operation_id=:operation
                                      AND manifest.account_id=avatar.account_id
                                      AND manifest.asset_id=avatar.asset_id
                                      AND manifest.storage_key=avatar.storage_key
                                      AND manifest.storage_version IS NOT DISTINCT FROM avatar.storage_version
                                )
                            )
                            """).param("account", lease.accountId()).param("operation", lease.operationId())
                    .query(Boolean.class).single();
            if (unexpectedAvatar) throw new AccountFailure(409, "avatar_manifest_mismatch");
            jdbcClient.sql("DELETE FROM app_identity.local_credential WHERE account_id=:account")
                    .param("account", lease.accountId()).update();
            jdbcClient.sql("DELETE FROM app_identity.external_identity WHERE account_id=:account")
                    .param("account", lease.accountId()).update();
            jdbcClient.sql("DELETE FROM app_identity.account_avatar WHERE account_id=:account")
                    .param("account", lease.accountId()).update();
            jdbcClient.sql("DELETE FROM app_identity.avatar_cleanup WHERE account_id=:account")
                    .param("account", lease.accountId()).update();
            jdbcClient.sql("DELETE FROM app_identity.ownership_challenge WHERE account_id=:account")
                    .param("account", lease.accountId()).update();
            jdbcClient.sql("DELETE FROM app_identity.spring_session WHERE principal_name=:account")
                    .param("account", lease.accountId().toString()).update();
            jdbcClient.sql("DELETE FROM app_identity.oauth2_authorization WHERE principal_name=:account")
                    .param("account", lease.accountId().toString()).update();
            jdbcClient.sql("DELETE FROM app_identity.oauth2_authorization_consent WHERE principal_name=:account")
                    .param("account", lease.accountId().toString()).update();
            ledger.recordIdentity(lease.operationId(), lease.accountId(), lease.generation(), lease.avatars().size(),
                    manifestHash(lease.avatars()));
            jdbcClient.sql("DELETE FROM app_identity.account_deletion_avatar WHERE operation_id=:operation")
                    .param("operation", lease.operationId()).update();
            int scrubbed = jdbcClient.sql("""
                            UPDATE app_identity.account
                            SET email=NULL,email_verified=false,profile_username=NULL,display_name=NULL,bio=NULL,
                                is_admin=false,admin_granted_by=NULL,admin_granted_at=NULL,ban_reason=NULL,
                                profile_created_at=NULL,last_login_at=NULL,deletion_state='PURGED',
                                security_generation=security_generation+1,updated_at=transaction_timestamp(),
                                row_version=row_version+1
                            WHERE account_id=:account AND deletion_state='PURGING'
                            """).param("account", lease.accountId()).update();
            if (scrubbed != 1) throw new AccountFailure(409, "stale_purge_lease");
            int completed = jdbcClient.sql("""
                            UPDATE app_identity.account_deletion
                            SET completed_at=transaction_timestamp(),lease_owner=NULL,lease_expires_at=NULL,
                                next_attempt_at=transaction_timestamp(),last_error_code=NULL
                            WHERE operation_id=:operation AND generation=:generation
                              AND lease_owner=:owner AND lease_epoch=:epoch
                            """).param("operation", lease.operationId()).param("generation", lease.generation())
                    .param("owner", lease.owner()).param("epoch", lease.epoch()).update();
            if (completed != 1) throw new AccountFailure(409, "stale_purge_lease");
            return true;
        }));
    }

    void fail(Lease lease, String errorCode) {
        long delay = Math.min(3600, 1L << Math.min(10, Math.max(1, attemptCount(lease)))) +
                Math.floorMod(lease.operationId().hashCode(), 31);
        transactions.executeWithoutResult(status -> jdbcClient.sql("""
                        UPDATE app_identity.account_deletion
                        SET lease_owner=NULL,lease_expires_at=NULL,last_error_code=:error,
                            next_attempt_at=transaction_timestamp() + make_interval(secs => :delay)
                        WHERE operation_id=:operation AND generation=:generation
                          AND lease_owner=:owner AND lease_epoch=:epoch AND completed_at IS NULL
                        """).param("error", errorCode).param("delay", delay).param("operation", lease.operationId())
                .param("generation", lease.generation()).param("owner", lease.owner()).param("epoch", lease.epoch())
                .update());
    }

    private void process(Lease lease) {
        try {
            for (OwnedAvatarEraser.Manifest avatar : lease.avatars()) {
                if (!heartbeat(lease)) return;
                avatars.deleteOwned(avatar);
            }
            if (!heartbeat(lease) || !complete(lease)) return;
            LOG.info("account_purge_completed operation_id={} lease_epoch={}", lease.operationId(), lease.epoch());
        } catch (AccountFailure failure) {
            fail(lease, failure.code());
            LOG.warn("account_purge_retry operation_id={} lease_epoch={} error_code={}", lease.operationId(),
                    lease.epoch(), failure.code());
        } catch (RuntimeException failure) {
            fail(lease, "purge_failed");
            LOG.warn("account_purge_retry operation_id={} lease_epoch={} error_code=purge_failed",
                    lease.operationId(), lease.epoch());
        }
    }

    private List<OwnedAvatarEraser.Manifest> manifest(UUID operationId) {
        return jdbcClient.sql("""
                        SELECT account_id,asset_id,storage_key,storage_version,content_sha256
                        FROM app_identity.account_deletion_avatar
                        WHERE operation_id=:operation ORDER BY storage_key
                        """).param("operation", operationId)
                .query((row, number) -> new OwnedAvatarEraser.Manifest(row.getObject("account_id", UUID.class),
                        row.getObject("asset_id", UUID.class), row.getString("storage_key"),
                        row.getString("storage_version"), row.getBytes("content_sha256"))).list();
    }

    private void lockAccount(UUID accountId) {
        jdbcClient.sql("SELECT account_id FROM app_identity.account WHERE account_id=:account FOR UPDATE")
                .param("account", accountId).query(UUID.class).single();
    }

    private boolean ownsCurrentLease(Lease lease) {
        return jdbcClient.sql("""
                        SELECT EXISTS(
                            SELECT 1 FROM app_identity.account_deletion deletion
                            JOIN app_identity.account account USING(account_id)
                            WHERE deletion.operation_id=:operation AND deletion.generation=:generation
                              AND deletion.lease_owner=:owner AND deletion.lease_epoch=:epoch
                              AND deletion.lease_expires_at > transaction_timestamp()
                              AND deletion.completed_at IS NULL AND account.deletion_state='PURGING'
                              AND account.deletion_generation=deletion.generation
                        )
                        """).param("operation", lease.operationId()).param("generation", lease.generation())
                .param("owner", lease.owner()).param("epoch", lease.epoch()).query(Boolean.class).single();
    }

    private int attemptCount(Lease lease) {
        return jdbcClient.sql("""
                        SELECT attempt_count FROM app_identity.account_deletion
                        WHERE operation_id=:operation AND generation=:generation
                        """).param("operation", lease.operationId()).param("generation", lease.generation())
                .query(Integer.class).optional().orElse(1);
    }

    private static byte[] manifestHash(List<OwnedAvatarEraser.Manifest> manifests) {
        var lines = new ArrayList<String>();
        manifests.stream().sorted(Comparator.comparing(OwnedAvatarEraser.Manifest::storageKey)).forEach(manifest ->
                lines.add(manifest.accountId() + "\u0000" + manifest.assetId() + "\u0000" + manifest.storageKey() +
                        "\u0000" + (manifest.storageVersion() == null ? "" : manifest.storageVersion()) + "\u0000" +
                        (manifest.contentSha256() == null ? "" : HexFormat.of().formatHex(manifest.contentSha256()))));
        return Secrets.digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    private record Claimed(UUID operationId, long generation, long epoch) {
    }
}
