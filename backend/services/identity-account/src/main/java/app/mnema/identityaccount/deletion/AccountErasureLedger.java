package app.mnema.identityaccount.deletion;

import app.mnema.identityaccount.contract.AccountFailure;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class AccountErasureLedger {
    public static final String IDENTITY_SCOPE = "identity-account";

    private final JdbcClient jdbcClient;

    public AccountErasureLedger(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    void recordIdentity(UUID operationId, UUID accountId, long generation, int avatarCount, byte[] manifestHash) {
        int handoff = jdbcClient.sql("""
                        INSERT INTO app_identity.account_erasure_handoff(
                            operation_id,account_id,generation,avatar_object_count,avatar_manifest_sha256,created_at)
                        VALUES(:operation,:account,:generation,:count,:hash,transaction_timestamp())
                        ON CONFLICT(operation_id) DO UPDATE SET operation_id=account_erasure_handoff.operation_id
                        WHERE account_erasure_handoff.account_id=excluded.account_id
                          AND account_erasure_handoff.generation=excluded.generation
                          AND account_erasure_handoff.avatar_object_count=excluded.avatar_object_count
                          AND account_erasure_handoff.avatar_manifest_sha256=excluded.avatar_manifest_sha256
                        """).param("operation", operationId).param("account", accountId)
                .param("generation", generation).param("count", avatarCount).param("hash", manifestHash).update();
        if (handoff != 1) throw new AccountFailure(409, "erasure_handoff_conflict");
        UUID receipt = UUID.randomUUID();
        int inserted = jdbcClient.sql("""
                        INSERT INTO app_identity.account_erasure_receipt(operation_id,scope,receipt_id,recorded_at)
                        VALUES(:operation,:scope,:receipt,transaction_timestamp())
                        ON CONFLICT(operation_id,scope) DO NOTHING
                        """).param("operation", operationId).param("scope", IDENTITY_SCOPE).param("receipt", receipt)
                .update();
        if (inserted == 0 && !receipts(operationId).contains(IDENTITY_SCOPE))
            throw new AccountFailure(409, "erasure_receipt_conflict");
    }

    /**
     * Future domain-owned cleaners acknowledge only their own completed scope. The operation/generation fence makes
     * replay idempotent and prevents a receipt from being attached to another deletion generation.
     */
    public void acknowledge(UUID operationId, long generation, String scope, UUID receiptId) {
        if (scope == null || !scope.matches("[a-z][a-z0-9-]{0,63}") || IDENTITY_SCOPE.equals(scope))
            throw new AccountFailure(400, "invalid_erasure_scope");
        try {
            int updated = jdbcClient.sql("""
                            INSERT INTO app_identity.account_erasure_receipt(operation_id,scope,receipt_id,recorded_at)
                            SELECT operation_id,:scope,:receipt,transaction_timestamp()
                            FROM app_identity.account_erasure_handoff
                            WHERE operation_id=:operation AND generation=:generation
                            ON CONFLICT(operation_id,scope) DO UPDATE SET receipt_id=account_erasure_receipt.receipt_id
                            WHERE account_erasure_receipt.receipt_id=excluded.receipt_id
                            """).param("scope", scope).param("receipt", receiptId).param("operation", operationId)
                    .param("generation", generation).update();
            if (updated != 1) throw new AccountFailure(409, "erasure_receipt_conflict");
        } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
            throw new AccountFailure(409, "erasure_receipt_conflict");
        }
    }

    public List<String> receipts(UUID operationId) {
        return jdbcClient.sql("""
                        SELECT scope FROM app_identity.account_erasure_receipt
                        WHERE operation_id=:operation ORDER BY scope
                        """).param("operation", operationId).query(String.class).list();
    }
}
