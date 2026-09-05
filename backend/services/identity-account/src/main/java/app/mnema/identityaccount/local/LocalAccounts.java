package app.mnema.identityaccount.local;

import app.mnema.identityaccount.account.AccountStore;
import app.mnema.identityaccount.contract.AccountAccess;
import app.mnema.identityaccount.contract.AccountFailure;
import app.mnema.identityaccount.security.RateLimits;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

@Service
public class LocalAccounts {
    private final JdbcClient jdbcClient;
    private final AccountStore accounts;
    private final TransactionTemplate transactions;
    private final PasswordEncoder passwords;
    private final RateLimits limits;
    private final Clock clock;
    private final String dummyHash;

    public LocalAccounts(JdbcClient jdbcClient, AccountStore accounts, TransactionTemplate transactions, PasswordEncoder passwords,
                         RateLimits limits, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.accounts = accounts;
        this.transactions = transactions;
        this.passwords = passwords;
        this.limits = limits;
        this.clock = clock;
        dummyHash = passwords.encode("synthetic-timing-equalizer-password");
    }

    public AccountAccess register(String email, String login, String password, String username, String remote) {
        validatePassword(password);
        if (!limits.allow("register", remote, 10)) throw new AccountFailure(429, "try_later");
        String hash = passwords.encode(password);
        return transactions.execute(s -> {
            UUID id = jdbcClient.sql(
                            "INSERT INTO app_identity.account(email,profile_username,profile_created_at) VALUES(:email,:username,statement_timestamp()) RETURNING account_id")
                    .param("email", email.strip()).param("username", username).query(UUID.class).single();
            jdbcClient.sql(
                            "INSERT INTO app_identity.local_credential(account_id,login_name,password_hash) VALUES(:id,:login,:hash)")
                    .param("id", id).param("login", login).param("hash", hash).update();
            return accounts.get(id, true).access();
        });
    }

    public AccountAccess login(String login, String password, String remote) {
        String normalized = login.strip().toLowerCase(Locale.ROOT);
        boolean allowed = limits.allow("login-address", remote, 100) & limits.allow("login", normalized, 20);
        var ids = jdbcClient.sql("""
                SELECT a.account_id FROM app_identity.account a JOIN app_identity.local_credential l USING(account_id)
                WHERE a.normalized_email=:login OR l.normalized_login_name=:login
                """).param("login", normalized).query(UUID.class).list();
        if (!allowed || ids.size() != 1) {
            passwords.matches(password, dummyHash);
            throw AccountFailure.denied();
        }
        // Return a rejected result, then throw after commit: failure counters must remain durable.
        AccountAccess result = transactions.execute(s -> {
            var a = accounts.get(ids.getFirst(), true);
            var credential = jdbcClient.sql(
                            "SELECT password_hash,locked_until FROM app_identity.local_credential WHERE account_id=:id")
                    .param("id", a.accountId())
                    .query((r, n) -> new Credential(r.getString(1), r.getObject(2, OffsetDateTime.class))).single();
            boolean matches = passwords.matches(password, credential.hash());
            if (!matches || !a.status().equals("ACTIVE") ||
                    credential.lockedUntil() != null && credential.lockedUntil().isAfter(OffsetDateTime.now(clock))) {
                jdbcClient.sql("""
                                UPDATE app_identity.local_credential SET failed_login_attempts=LEAST(failed_login_attempts+1,1000000),
                                 locked_until=CASE WHEN failed_login_attempts>=4 THEN :until ELSE locked_until END WHERE account_id=:id
                                """).param("until", OffsetDateTime.now(clock).plusMinutes(15)).param("id", a.accountId())
                        .update();
                return null;
            }
            jdbcClient.sql(
                            "UPDATE app_identity.local_credential SET failed_login_attempts=0,locked_until=NULL WHERE account_id=:id")
                    .param("id", a.accountId()).update();
            jdbcClient.sql("UPDATE app_identity.account SET last_login_at=statement_timestamp() WHERE account_id=:id")
                    .param("id", a.accountId()).update();
            return a.access();
        });
        if (result == null) throw AccountFailure.denied();
        return result;
    }

    public void verifyPassword(AccountAccess access, String password) {
        accounts.require(access, true);
        String hash = jdbcClient.sql("SELECT password_hash FROM app_identity.local_credential WHERE account_id=:id")
                .param("id", access.accountId()).query(String.class).optional().orElse(dummyHash);
        if (!passwords.matches(password, hash) || hash.equals(dummyHash)) throw AccountFailure.denied();
    }

    public void changePassword(AccountAccess access, String current, String next) {
        validatePassword(next);
        if (!limits.allow("password", access.accountId().toString(), 10)) throw new AccountFailure(429, "try_later");
        transactions.executeWithoutResult(s -> {
            verifyPassword(access, current);
            replacePassword(access.accountId(), next);
        });
    }

    public void replacePassword(UUID id, String next) {
        validatePassword(next);
        accounts.get(id, true);
        jdbcClient.sql(
                        "UPDATE app_identity.local_credential SET password_hash=:hash,failed_login_attempts=0,locked_until=NULL,updated_at=statement_timestamp() WHERE account_id=:id")
                .param("hash", passwords.encode(next)).param("id", id).update();
        accounts.revoke(id);
    }

    public static void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 128 ||
                password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new AccountFailure(400, "invalid_password");
    }

    private record Credential(String hash, OffsetDateTime lockedUntil) {
    }
}
