package app.mnema.identityaccount.moderation;

import app.mnema.identityaccount.account.AccountStore;
import app.mnema.identityaccount.contract.AccountAccess;
import app.mnema.identityaccount.contract.AccountFailure;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class Moderation {
    public enum Action {BAN, UNBAN, GRANT_ADMIN, REVOKE_ADMIN}

    private final JdbcClient jdbcClient;
    private final AccountStore accounts;
    private final TransactionTemplate transactions;

    public Moderation(JdbcClient jdbcClient, AccountStore accounts, TransactionTemplate transactions) {
        this.jdbcClient = jdbcClient;
        this.accounts = accounts;
        this.transactions = transactions;
    }

    public void apply(AccountAccess actor, UUID target, Action action, String reason) {
        transactions.executeWithoutResult(s -> {
            // Serializes the admin graph before account locks, avoiding grant/revoke and subordinate races.
            jdbcClient.sql("SELECT pg_advisory_xact_lock(142001)").query(rs -> {
                rs.next();
                return 0;
            });
            var admin = accounts.require(actor, true);
            if (!admin.isAdmin() || actor.accountId().equals(target)) throw AccountFailure.forbidden();
            var account = accounts.get(target, true);
            switch (action) {
                case GRANT_ADMIN -> {
                    if (account.isAdmin() || !account.status().equals("ACTIVE")) throw AccountFailure.forbidden();
                    jdbcClient.sql(
                                    "UPDATE app_identity.account SET is_admin=true,admin_granted_by=:actor,admin_granted_at=statement_timestamp() WHERE account_id=:id")
                            .param("actor", actor.accountId()).param("id", target).update();
                }
                case REVOKE_ADMIN -> {
                    if (!account.isAdmin() || !actor.accountId().equals(account.adminGrantedBy()) || jdbcClient.sql(
                                    "SELECT EXISTS(SELECT 1 FROM app_identity.account WHERE is_admin AND admin_granted_by=:id)")
                            .param("id", target).query(Boolean.class).single())
                        throw AccountFailure.forbidden();
                    jdbcClient.sql(
                                    "UPDATE app_identity.account SET is_admin=false,admin_granted_by=NULL,admin_granted_at=NULL WHERE account_id=:id")
                            .param("id", target).update();
                    accounts.revoke(target);
                }
                case BAN -> {
                    if (account.isAdmin() || !account.status().equals("ACTIVE")) throw AccountFailure.forbidden();
                    jdbcClient.sql(
                                    "UPDATE app_identity.account SET status='BANNED',banned_by=:actor,banned_at=statement_timestamp(),ban_reason=:reason WHERE account_id=:id")
                            .param("actor", actor.accountId()).param("id", target).param("reason", reason).update();
                    accounts.revoke(target);
                }
                case UNBAN -> {
                    if (!account.status().equals("BANNED")) throw AccountFailure.forbidden();
                    jdbcClient.sql(
                                    "UPDATE app_identity.account SET status='ACTIVE',banned_by=NULL,banned_at=NULL,ban_reason=NULL WHERE account_id=:id")
                            .param("id", target).update();
                }
            }
        });
    }
}
