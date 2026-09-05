package app.mnema.identityaccount.deletion;

import app.mnema.identityaccount.account.AccountStore;
import app.mnema.identityaccount.contract.AccountAccess;
import app.mnema.identityaccount.contract.AccountFailure;
import app.mnema.identityaccount.local.LocalAccounts;
import app.mnema.identityaccount.security.OwnershipProofs;
import app.mnema.identityaccount.security.Secrets;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AccountDeletions {
    public record Operation(UUID accountId, UUID operationId, long generation, OffsetDateTime deletionRequestedAt,
                            OffsetDateTime recoverableUntil, OffsetDateTime purgeAfter) {
    }

    private final JdbcClient jdbcClient;
    private final AccountStore accounts;
    private final OwnershipProofs proofs;
    private final TransactionTemplate transactions;
    private final AccountDeletionPolicy policy;
    private final LocalAccounts local;

    public AccountDeletions(JdbcClient jdbcClient, AccountStore accounts, OwnershipProofs proofs,
                            TransactionTemplate transactions, AccountDeletionPolicy policy, LocalAccounts local) {
        this.jdbcClient = jdbcClient;
        this.accounts = accounts;
        this.proofs = proofs;
        this.transactions = transactions;
        this.policy = policy;
        this.local = local;
    }

    public AccountDeletionView request(AccountAccess access, String proof) {
        return requestConfirmed(access, proof);
    }

    public AccountDeletionView requestConfirmed(String proof) {
        policy.requireEnabled();
        Optional<Operation> retry = confirmedOperation(proof);
        if (retry.isPresent()) return AccountDeletionView.pending(retry.get());
        try {
            return requestConfirmed(proofs.identify(proof), proof);
        } catch (AccountFailure failure) {
            if (!failure.code().equals("invalid_proof")) throw failure;
            // The first command may commit between the lookup and challenge identification.
            return AccountDeletionView.pending(confirmedOperation(proof).orElseThrow(() -> failure));
        }
    }

    public OwnershipProofs.Proof passwordProof(String login, String password, String remote) {
        policy.requireEnabled();
        AccountAccess access = local.proveDeletionOwner(login, password, remote);
        return transactions.execute(status -> proofs.issueDeletion(access));
    }

    private AccountDeletionView requestConfirmed(AccountAccess access, String proof) {
        policy.requireEnabled();
        Operation operation = transactions.execute(status -> {
            lockAdministrationGraph();
            var account = accounts.get(access.accountId(), true);
            if (account.deletionState().equals("PENDING_DELETION") &&
                    account.securityGeneration() == access.generation() + 1)
                return confirmedOperation(account.accountId(), proof);
            accounts.requireDeletionOwner(access, false);
            var confirmation = proofs.consumeDeletion(access, proof);
            long generation = account.deletionGeneration() + 1;
            UUID operationId = UUID.randomUUID();
            jdbcClient.sql("""
                            INSERT INTO app_identity.account_deletion(
                                account_id,operation_id,generation,deletion_requested_at,recoverable_until,purge_after,
                                next_attempt_at,confirmation_hash,confirmation_expires_at)
                            VALUES(:account,:operation,:generation,transaction_timestamp(),
                                transaction_timestamp() + make_interval(secs => :recovery),
                                transaction_timestamp() + make_interval(secs => :recovery),
                                transaction_timestamp() + make_interval(secs => :recovery),:confirmation,:confirmationExpiry)
                            """)
                    .param("account", account.accountId()).param("operation", operationId)
                    .param("generation", generation).param("recovery", policy.recoveryPeriod().toSeconds())
                    .param("confirmation", confirmation.secretHash())
                    .param("confirmationExpiry", confirmation.expiresAt()).update();
            captureAvatarManifest(account.accountId(), operationId, generation);
            revokeAdministrativeLineage(account.accountId());
            int updated = jdbcClient.sql("""
                            UPDATE app_identity.account
                            SET deletion_state='PENDING_DELETION',deletion_generation=:generation,
                                is_admin=false,admin_granted_by=NULL,admin_granted_at=NULL,
                                updated_at=transaction_timestamp(),row_version=row_version+1
                            WHERE account_id=:account
                            """).param("generation", generation).param("account", account.accountId()).update();
            if (updated != 1) throw new AccountFailure(409, "deletion_state_conflict");
            accounts.revoke(account.accountId());
            return operation(account.accountId());
        });
        return AccountDeletionView.pending(operation);
    }

    public AccountDeletionView recovery(AccountAccess access, UUID operationId) {
        policy.requireEnabled();
        return transactions.execute(status -> AccountDeletionView.pending(requireRecoverable(access, operationId)));
    }

    public AccountAccess cancel(AccountAccess access, UUID operationId) {
        policy.requireEnabled();
        return transactions.execute(status -> {
            accounts.requireRecovery(access, true);
            requireRecoverable(access, operationId);
            int deleted = jdbcClient.sql("""
                            DELETE FROM app_identity.account_deletion
                            WHERE account_id=:account AND operation_id=:operation
                              AND recoverable_until > transaction_timestamp()
                            """).param("account", access.accountId()).param("operation", operationId).update();
            if (deleted != 1) throw AccountFailure.forbidden();
            int restored = jdbcClient.sql("""
                            UPDATE app_identity.account
                            SET deletion_state='ACTIVE',updated_at=transaction_timestamp(),row_version=row_version+1
                            WHERE account_id=:account AND deletion_state='PENDING_DELETION'
                            """).param("account", access.accountId()).update();
            if (restored != 1) throw AccountFailure.forbidden();
            accounts.revoke(access.accountId());
            return accounts.get(access.accountId(), true).access();
        });
    }

    public AccountAccess passwordRecovery(AccountAccess access) {
        policy.requireEnabled();
        transactions.executeWithoutResult(status -> requireRecoverable(access, null));
        return access;
    }

    public long recoverySessionSeconds() {
        return policy.recoverySessionPeriod().toSeconds();
    }

    private Operation requireRecoverable(AccountAccess access, UUID operationId) {
        var account = accounts.requireRecovery(access, true);
        String operationClause = operationId == null ? "" : " AND operation_id=:operation";
        var query = jdbcClient.sql("""
                        SELECT account_id,operation_id,generation,deletion_requested_at,recoverable_until,purge_after
                        FROM app_identity.account_deletion
                        WHERE account_id=:account AND generation=:generation
                          AND recoverable_until > transaction_timestamp()
                        """ + operationClause).param("account", access.accountId())
                .param("generation", account.deletionGeneration());
        if (operationId != null) query = query.param("operation", operationId);
        return query.query(Operation.class).optional().orElseThrow(AccountFailure::forbidden);
    }

    private Operation operation(UUID accountId) {
        return jdbcClient.sql("""
                        SELECT account_id,operation_id,generation,deletion_requested_at,recoverable_until,purge_after
                        FROM app_identity.account_deletion WHERE account_id=:account
                """).param("account", accountId).query(Operation.class).single();
    }

    private Operation confirmedOperation(UUID accountId, String proof) {
        return jdbcClient.sql("""
                        SELECT account_id,operation_id,generation,deletion_requested_at,recoverable_until,purge_after
                        FROM app_identity.account_deletion
                        WHERE account_id=:account AND confirmation_hash=:confirmation
                          AND confirmation_expires_at > transaction_timestamp()
                        """).param("account", accountId).param("confirmation", Secrets.hash(proof))
                .query(Operation.class).optional().orElseThrow(() -> new AccountFailure(400, "invalid_proof"));
    }

    private Optional<Operation> confirmedOperation(String proof) {
        return transactions.execute(status -> jdbcClient.sql("""
                        SELECT deletion.account_id,deletion.operation_id,deletion.generation,
                               deletion.deletion_requested_at,deletion.recoverable_until,deletion.purge_after
                        FROM app_identity.account_deletion deletion
                        JOIN app_identity.account account
                          ON account.account_id=deletion.account_id
                         AND account.deletion_generation=deletion.generation
                        WHERE deletion.confirmation_hash=:confirmation
                          AND deletion.confirmation_expires_at > transaction_timestamp()
                          AND deletion.completed_at IS NULL
                          AND account.deletion_state='PENDING_DELETION'
                        """).param("confirmation", Secrets.hash(proof)).query(Operation.class).optional());
    }

    private void captureAvatarManifest(UUID accountId, UUID operationId, long generation) {
        jdbcClient.sql("""
                        INSERT INTO app_identity.account_deletion_avatar(
                            operation_id,account_id,generation,asset_id,storage_key,storage_version,content_sha256,source)
                        SELECT :operation,account_id,:generation,asset_id,storage_key,storage_version,content_sha256,'CURRENT'
                        FROM app_identity.account_avatar WHERE account_id=:account
                        ON CONFLICT(operation_id,storage_key) DO NOTHING
                        """).param("operation", operationId).param("generation", generation)
                .param("account", accountId).update();
        jdbcClient.sql("""
                        INSERT INTO app_identity.account_deletion_avatar(
                            operation_id,account_id,generation,asset_id,storage_key,storage_version,content_sha256,source)
                        SELECT :operation,account_id,:generation,asset_id,storage_key,storage_version,content_sha256,'CLEANUP'
                        FROM app_identity.avatar_cleanup WHERE account_id=:account
                        ON CONFLICT(operation_id,storage_key) DO NOTHING
                        """).param("operation", operationId).param("generation", generation)
                .param("account", accountId).update();
    }

    private void lockAdministrationGraph() {
        jdbcClient.sql("SELECT pg_advisory_xact_lock(142001)").query(result -> {
            result.next();
            return 0;
        });
    }

    private void revokeAdministrativeLineage(UUID accountId) {
        List<UUID> descendants = jdbcClient.sql("""
                        WITH RECURSIVE descendants(account_id) AS (
                            SELECT account_id FROM app_identity.account WHERE is_admin AND admin_granted_by=:account
                            UNION
                            SELECT child.account_id FROM app_identity.account child
                            JOIN descendants parent ON child.admin_granted_by=parent.account_id
                            WHERE child.is_admin AND child.account_id<>:account
                        )
                        SELECT account_id FROM descendants ORDER BY account_id
                        """).param("account", accountId).query(UUID.class).list();
        if (!descendants.isEmpty()) {
            for (UUID descendant : descendants) {
                jdbcClient.sql("""
                                UPDATE app_identity.account
                                SET is_admin=false,admin_granted_by=NULL,admin_granted_at=NULL
                                WHERE account_id=:account
                                """).param("account", descendant).update();
                accounts.revoke(descendant);
            }
        }
    }
}
