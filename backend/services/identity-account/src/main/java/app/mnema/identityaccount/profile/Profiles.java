package app.mnema.identityaccount.profile;

import app.mnema.identityaccount.account.AccountStore;
import app.mnema.identityaccount.contract.AccountAccess;
import app.mnema.identityaccount.contract.AccountFailure;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class Profiles {
    public record Profile(UUID accountId, String email, boolean emailVerified, String profileUsername,
                          String displayName, String bio, boolean admin, String status, boolean avatarPresent,
                          boolean hasPassword) {
    }

    public record PublicProfile(UUID accountId, String profileUsername, String displayName, String bio,
                                boolean avatarPresent) {
    }

    private final AccountStore accounts;
    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactions;

    public Profiles(AccountStore accounts, JdbcClient jdbcClient, TransactionTemplate transactions) {
        this.accounts = accounts;
        this.jdbcClient = jdbcClient;
        this.transactions = transactions;
    }

    public Profile get(AccountAccess access) {
        var a = accounts.require(access, false);
        return new Profile(a.accountId(), a.email(), a.emailVerified(), a.profileUsername(), a.displayName(), a.bio(),
                a.isAdmin(), a.status(), exists("account_avatar", a.accountId()),
                exists("local_credential", a.accountId()));
    }

    public PublicProfile publicProfile(UUID id) {
        var a = accounts.find(id, false).filter(account -> account.status().equals("ACTIVE") &&
                        account.deletionState().equals("ACTIVE"))
                .orElseThrow(() -> new AccountFailure(404, "profile_not_found"));
        return new PublicProfile(id, a.profileUsername(), a.displayName(), a.bio(), exists("account_avatar", id));
    }

    public Profile update(AccountAccess access, String username, String displayName, String bio) {
        return transactions.execute(s -> {
            accounts.require(access, true);
            jdbcClient.sql(
                            "UPDATE app_identity.account SET profile_username=:username,display_name=:name,bio=:bio,profile_created_at=coalesce(profile_created_at,statement_timestamp()),updated_at=statement_timestamp(),row_version=row_version+1 WHERE account_id=:id")
                    .param("username", username)
                    .param("name", displayName == null || displayName.isBlank() ? null : displayName.strip())
                    .param("bio", bio).param("id", access.accountId()).update();
            return get(access);
        });
    }

    private boolean exists(String table, UUID id) {
        return jdbcClient.sql("SELECT EXISTS(SELECT 1 FROM app_identity." + table + " WHERE account_id=:id)").param("id", id)
                .query(Boolean.class).single();
    }
}
