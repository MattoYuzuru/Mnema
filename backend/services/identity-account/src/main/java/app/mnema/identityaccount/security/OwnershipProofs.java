package app.mnema.identityaccount.security;

import app.mnema.identityaccount.account.AccountStore;
import app.mnema.identityaccount.contract.AccountAccess;
import app.mnema.identityaccount.contract.AccountFailure;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class OwnershipProofs {
    public enum Purpose {RESET_PASSWORD, VERIFY_EMAIL, LINK_IDENTITY, UNLINK_IDENTITY, DELETE_ACCOUNT}

    public record Proof(String token, Instant expiresAt) {
    }

    public record Confirmation(String secretHash, OffsetDateTime expiresAt) {
    }

    private final JdbcClient jdbcClient;
    private final AccountStore accounts;
    private final Clock clock;

    public OwnershipProofs(JdbcClient jdbcClient, AccountStore accounts, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.accounts = accounts;
        this.clock = clock;
    }

    /**
     * Caller holds account lock in its transaction. Plaintext only leaves via authorized proof response or mail transport.
     */
    public Proof issue(AccountAccess access, Purpose purpose) {
        accounts.require(access, true);
        return insert(access, purpose);
    }

    public Proof issueDeletion(AccountAccess access) {
        accounts.requireDeletionOwner(access, true);
        return insert(access, Purpose.DELETE_ACCOUNT);
    }

    private Proof insert(AccountAccess access, Purpose purpose) {
        String token = Secrets.random();
        Instant expiry = clock.instant().plusSeconds(600);
        jdbcClient.sql(
                        "INSERT INTO app_identity.ownership_challenge(secret_hash,account_id,purpose,generation,expires_at) VALUES(:hash,:id,:purpose,:generation,:expires)")
                .param("hash", Secrets.hash(token)).param("id", access.accountId()).param("purpose", purpose.name())
                .param("generation", access.generation()).param("expires", expiry.atOffset(ZoneOffset.UTC)).update();
        return new Proof(token, expiry);
    }

    public AccountAccess identify(String token) {
        return jdbcClient.sql("SELECT account_id,generation FROM app_identity.ownership_challenge WHERE secret_hash=:hash")
                .param("hash", Secrets.hash(token))
                .query((r, n) -> new AccountAccess(r.getObject(1, UUID.class), r.getLong(2))).optional()
                .orElseThrow(() -> new AccountFailure(400, "invalid_proof"));
    }

    public void consume(AccountAccess access, String token, Purpose purpose) {
        accounts.require(access, true);
        int count = jdbcClient.sql(
                        "DELETE FROM app_identity.ownership_challenge WHERE secret_hash=:hash AND account_id=:id AND purpose=:purpose AND generation=:gen AND expires_at>:now")
                .param("hash", Secrets.hash(token)).param("id", access.accountId()).param("purpose", purpose.name())
                .param("gen", access.generation()).param("now", OffsetDateTime.now(clock)).update();
        if (count != 1) throw new AccountFailure(400, "invalid_proof");
    }

    public Confirmation consumeDeletion(AccountAccess access, String token) {
        accounts.requireDeletionOwner(access, true);
        String hash = Secrets.hash(token);
        OffsetDateTime expiry = jdbcClient.sql("""
                        DELETE FROM app_identity.ownership_challenge
                        WHERE secret_hash=:hash AND account_id=:id AND purpose='DELETE_ACCOUNT'
                          AND generation=:generation AND expires_at>transaction_timestamp()
                        RETURNING expires_at
                        """).param("hash", hash).param("id", access.accountId())
                .param("generation", access.generation())
                .query(OffsetDateTime.class).optional()
                .orElseThrow(() -> new AccountFailure(400, "invalid_proof"));
        return new Confirmation(hash, expiry);
    }

    public void invalidate(String token) {
        jdbcClient.sql("DELETE FROM app_identity.ownership_challenge WHERE secret_hash=:hash")
                .param("hash", Secrets.hash(token)).update();
    }
}
