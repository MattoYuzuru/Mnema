package app.mnema.identityaccount.account;

import app.mnema.identityaccount.contract.AccountAccess;
import app.mnema.identityaccount.contract.AccountFailure;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class AccountStore {
    public record Account(UUID accountId, String email, boolean emailVerified, String profileUsername,
                          String displayName,
                          String bio, String status, boolean isAdmin, UUID adminGrantedBy, long securityGeneration,
                          String deletionState, long deletionGeneration) {
        public AccountAccess access() {
            return new AccountAccess(accountId, securityGeneration);
        }
    }

    private final JdbcClient jdbcClient;

    public AccountStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Account get(UUID id, boolean lock) {
        return find(id, lock).orElseThrow(AccountFailure::denied);
    }

    public Optional<Account> find(UUID id, boolean lock) {
        return jdbcClient.sql(
                        "SELECT account_id,email,email_verified,profile_username,display_name,bio,status,is_admin,admin_granted_by,security_generation,deletion_state,deletion_generation FROM app_identity.account WHERE account_id=:id" +
                                (lock ? " FOR UPDATE" : ""))
                .param("id", id).query(Account.class).optional();
    }

    public Account require(AccountAccess access, boolean lock) {
        var a = get(access.accountId(), lock);
        if (!a.status().equals("ACTIVE") || !a.deletionState().equals("ACTIVE") ||
                a.securityGeneration() != access.generation())
            throw AccountFailure.denied();
        return a;
    }

    public Account requireRecovery(AccountAccess access, boolean lock) {
        var account = get(access.accountId(), lock);
        if (!account.deletionState().equals("PENDING_DELETION") ||
                account.securityGeneration() != access.generation()) throw AccountFailure.denied();
        return account;
    }

    public Account requireDeletionOwner(AccountAccess access, boolean lock) {
        var account = get(access.accountId(), lock);
        if (!account.deletionState().equals("ACTIVE") || account.securityGeneration() != access.generation())
            throw AccountFailure.denied();
        return account;
    }

    /**
     * Caller holds the account row lock and transaction. Every generation-bound capability becomes unusable.
     */
    public void revoke(UUID id) {
        jdbcClient.sql("UPDATE app_identity.account SET security_generation=security_generation+1 WHERE account_id=:id")
                .param("id", id).update();
        jdbcClient.sql("DELETE FROM app_identity.ownership_challenge WHERE account_id=:id").param("id", id).update();
        jdbcClient.sql("DELETE FROM app_identity.spring_session WHERE principal_name=:id").param("id", id.toString())
                .update();
        jdbcClient.sql("DELETE FROM app_identity.oauth2_authorization WHERE principal_name=:id").param("id", id.toString())
                .update();
    }
}
