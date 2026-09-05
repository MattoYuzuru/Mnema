package app.mnema.identityaccount.federation;

import app.mnema.identityaccount.account.AccountStore;
import app.mnema.identityaccount.contract.AccountAccess;
import app.mnema.identityaccount.contract.AccountFailure;
import app.mnema.identityaccount.security.OwnershipProofs;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class FederatedAccounts {
    public record Identity(UUID identityId, String provider) {
    }

    public record External(String provider, String subject, String email, boolean emailVerified) {
    }

    private final JdbcClient jdbcClient;
    private final AccountStore accounts;
    private final OwnershipProofs proofs;
    private final TransactionTemplate transactions;

    public FederatedAccounts(JdbcClient jdbcClient, AccountStore accounts, OwnershipProofs proofs, TransactionTemplate transactions) {
        this.jdbcClient = jdbcClient;
        this.accounts = accounts;
        this.proofs = proofs;
        this.transactions = transactions;
    }

    public List<Identity> identities(AccountAccess access) {
        accounts.require(access, false);
        return jdbcClient.sql(
                        "SELECT identity_id,provider FROM app_identity.external_identity WHERE account_id=:id ORDER BY linked_at,identity_id")
                .param("id", access.accountId()).query(Identity.class).list();
    }

    public AccountAccess complete(External external, AccountAccess linking) {
        validate(external);
        return transactions.execute(s -> {
            // Global binding lock also orders link/unlink and account creation against duplicate subject races.
            jdbcClient.sql("SELECT pg_advisory_xact_lock(142002)").query(rs -> {
                rs.next();
                return 0;
            });
            var binding = jdbcClient.sql(
                            "SELECT account_id FROM app_identity.external_identity WHERE provider=:provider AND provider_subject=:subject")
                    .param("provider", external.provider()).param("subject", external.subject()).query(UUID.class)
                    .optional();
            UUID id;
            if (linking != null) {
                accounts.require(linking, true);
                if (binding.isPresent()) throw new AccountFailure(409, "identity_conflict");
                id = linking.accountId();
            } else if (binding.isPresent()) {
                id = binding.get();
                var a = accounts.get(id, true);
                accounts.require(a.access(), true);
            } else {
                if (external.email() == null || !external.email().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") ||
                        external.email().length() > 320)
                    throw new AccountFailure(400, "provider_email_required");
                // Unique normalized email fails safely; it is never an account lookup or an auto-link key.
                id = jdbcClient.sql(
                                "INSERT INTO app_identity.account(email,email_verified) VALUES(:email,:verified) RETURNING account_id")
                        .param("email", external.email()).param("verified", external.emailVerified()).query(UUID.class)
                        .single();
            }
            if (binding.isEmpty())
                jdbcClient.sql(
                                "INSERT INTO app_identity.external_identity(account_id,provider,provider_subject) VALUES(:id,:provider,:subject)")
                        .param("id", id).param("provider", external.provider()).param("subject", external.subject())
                        .update();
            jdbcClient.sql(
                            "UPDATE app_identity.external_identity SET last_login_at=statement_timestamp() WHERE provider=:provider AND provider_subject=:subject")
                    .param("provider", external.provider()).param("subject", external.subject()).update();
            jdbcClient.sql("UPDATE app_identity.account SET last_login_at=statement_timestamp() WHERE account_id=:id")
                    .param("id", id).update();
            return accounts.get(id, true).access();
        });
    }

    public AccountAccess recovery(External external) {
        validate(external);
        return transactions.execute(status -> {
            jdbcClient.sql("SELECT pg_advisory_xact_lock(142002)").query(result -> {
                result.next();
                return 0;
            });
            UUID accountId = jdbcClient.sql("""
                            SELECT account_id FROM app_identity.external_identity
                            WHERE provider=:provider AND provider_subject=:subject
                            """).param("provider", external.provider()).param("subject", external.subject())
                    .query(UUID.class).optional().orElseThrow(AccountFailure::denied);
            var access = accounts.get(accountId, true).access();
            accounts.requireRecovery(access, false);
            return access;
        });
    }

    public OwnershipProofs.Proof deletionProof(External external) {
        validate(external);
        return transactions.execute(status -> {
            jdbcClient.sql("SELECT pg_advisory_xact_lock(142002)").query(result -> {
                result.next();
                return 0;
            });
            UUID accountId = jdbcClient.sql("""
                            SELECT account_id FROM app_identity.external_identity
                            WHERE provider=:provider AND provider_subject=:subject
                            """).param("provider", external.provider()).param("subject", external.subject())
                    .query(UUID.class).optional().orElseThrow(AccountFailure::denied);
            var access = accounts.get(accountId, true).access();
            accounts.requireDeletionOwner(access, false);
            return proofs.issueDeletion(access);
        });
    }

    public OwnershipProofs.Proof reauthenticate(External external, AccountAccess access,
                                                OwnershipProofs.Purpose purpose) {
        return transactions.execute(s -> {
            accounts.require(access, true);
            boolean bound = jdbcClient.sql(
                            "SELECT EXISTS(SELECT 1 FROM app_identity.external_identity WHERE account_id=:id AND provider=:provider AND provider_subject=:subject)")
                    .param("id", access.accountId()).param("provider", external.provider())
                    .param("subject", external.subject()).query(Boolean.class).single();
            if (!bound || (purpose == OwnershipProofs.Purpose.RESET_PASSWORD ||
                    purpose == OwnershipProofs.Purpose.VERIFY_EMAIL)) throw AccountFailure.forbidden();
            return proofs.issue(access, purpose);
        });
    }

    public void authorizeLink(AccountAccess access, String token) {
        transactions.executeWithoutResult(s -> proofs.consume(access, token, OwnershipProofs.Purpose.LINK_IDENTITY));
    }

    public void unlink(AccountAccess access, UUID identity, String token) {
        transactions.executeWithoutResult(s -> {
            jdbcClient.sql("SELECT pg_advisory_xact_lock(142002)").query(rs -> {
                rs.next();
                return 0;
            });
            accounts.require(access, true);
            proofs.consume(access, token, OwnershipProofs.Purpose.UNLINK_IDENTITY);
            boolean owned = jdbcClient.sql(
                            "SELECT EXISTS(SELECT 1 FROM app_identity.external_identity WHERE identity_id=:identity AND account_id=:id)")
                    .param("identity", identity).param("id", access.accountId()).query(Boolean.class).single();
            long factors = jdbcClient.sql(
                            "SELECT (SELECT count(*) FROM app_identity.local_credential WHERE account_id=:id)+(SELECT count(*) FROM app_identity.external_identity WHERE account_id=:id)")
                    .param("id", access.accountId()).query(Long.class).single();
            if (!owned || factors <= 1) throw AccountFailure.forbidden();
            jdbcClient.sql("DELETE FROM app_identity.external_identity WHERE identity_id=:identity AND account_id=:id")
                    .param("identity", identity).param("id", access.accountId()).update();
            accounts.revoke(access.accountId());
        });
    }

    private static void validate(External external) {
        if (!Set.of("google", "github", "yandex").contains(external.provider()) || external.subject() == null ||
                external.subject().isBlank() || external.subject().length() > 255)
            throw AccountFailure.denied();
    }
}
