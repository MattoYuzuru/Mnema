package app.mnema.identityaccount.moderation;

import app.mnema.identityaccount.account.AccountStore;
import app.mnema.identityaccount.contract.AccountAccess;
import app.mnema.identityaccount.contract.AccountFailure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class Moderation {
    public enum Action {BAN, UNBAN, GRANT_ADMIN, REVOKE_ADMIN}

    private static final Logger log = LoggerFactory.getLogger(Moderation.class);

    private final JdbcClient jdbcClient;
    private final AccountStore accounts;
    private final TransactionTemplate transactions;

    public Moderation(JdbcClient jdbcClient, AccountStore accounts, TransactionTemplate transactions) {
        this.jdbcClient = jdbcClient;
        this.accounts = accounts;
        this.transactions = transactions;
    }

    /**
     * A refused attempt by a current administrator is journaled as DENIED after the moderation transaction rolled back;
     * other callers leave no row, so an ordinary account cannot grow the journal.
     */
    public void apply(AccountAccess actor, UUID target, Action action, String reason) {
        try {
            transactions.executeWithoutResult(s -> change(actor, target, action, reason));
        } catch (AccountFailure failure) {
            if (failure.status() == 403) recordDenied(actor, target, action);
            throw failure;
        }
    }

    private void recordDenied(AccountAccess actor, UUID target, Action action) {
        try {
            transactions.executeWithoutResult(s -> jdbcClient.sql("""
                            INSERT INTO app_identity.admin_audit(actor_account_id,action,resource_id,outcome)
                            SELECT :actor,:action,:target,'DENIED'
                            WHERE EXISTS (SELECT 1 FROM app_identity.account WHERE account_id=:actor AND is_admin AND status='ACTIVE')""")
                    .param("actor", actor.accountId()).param("action", action.name()).param("target", target).update());
        } catch (DataAccessException failure) {
            log.warn("moderation denial could not be journaled action={}", action);
        }
    }

    private void change(AccountAccess actor, UUID target, Action action, String reason) {
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
                if (account.isAdmin() || !account.status().equals("ACTIVE") ||
                        !account.deletionState().equals("ACTIVE")) throw AccountFailure.forbidden();
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
                if (account.isAdmin() || !account.status().equals("ACTIVE") ||
                        account.deletionState().equals("PURGED")) throw AccountFailure.forbidden();
                jdbcClient.sql(
                                "UPDATE app_identity.account SET status='BANNED',banned_by=:actor,banned_at=statement_timestamp(),ban_reason=:reason WHERE account_id=:id")
                        .param("actor", actor.accountId()).param("id", target).param("reason", reason).update();
                accounts.revoke(target);
            }
            case UNBAN -> {
                if (!account.status().equals("BANNED") || account.deletionState().equals("PURGED"))
                    throw AccountFailure.forbidden();
                jdbcClient.sql(
                                "UPDATE app_identity.account SET status='ACTIVE',banned_by=NULL,banned_at=NULL,ban_reason=NULL WHERE account_id=:id")
                        .param("id", target).update();
            }
        }
        jdbcClient.sql("INSERT INTO app_identity.admin_audit(actor_account_id,action,resource_id,reason) VALUES (:actor,:action,:target,:reason)")
                .param("actor", actor.accountId()).param("action", action.name()).param("target", target)
                .param("reason", action == Action.BAN && reason != null && !reason.isBlank() ? reason : null).update();
    }
}
