package app.mnema.identityaccount.recovery;

import app.mnema.identityaccount.account.AccountStore;
import app.mnema.identityaccount.contract.IssuerContract;
import app.mnema.identityaccount.local.LocalAccounts;
import app.mnema.identityaccount.mail.PostboxMail;
import app.mnema.identityaccount.security.OwnershipProofs;
import app.mnema.identityaccount.security.RateLimits;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.util.Locale;
import java.util.UUID;

@Service
public class PasswordRecovery {
    private final JdbcClient jdbcClient;
    private final AccountStore accounts;
    private final OwnershipProofs proofs;
    private final LocalAccounts local;
    private final RateLimits limits;
    private final PostboxMail mail;
    private final TransactionTemplate transactions;
    private final String origin;

    public PasswordRecovery(JdbcClient jdbcClient, AccountStore accounts, OwnershipProofs proofs, LocalAccounts local,
                            RateLimits limits, PostboxMail mail, TransactionTemplate transactions,
                            @Value("${identity.frontend-origin}") String origin) {
        this.jdbcClient = jdbcClient;
        this.accounts = accounts;
        this.proofs = proofs;
        this.local = local;
        this.limits = limits;
        this.mail = mail;
        this.transactions = transactions;
        this.origin = new IssuerContract(URI.create(origin)).issuer();
    }

    public void request(String email, String remote) {
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        if (!(limits.allow("reset-address", remote, 20) & limits.allow("reset", normalized, 3))) return;
        var id = jdbcClient.sql(
                        "SELECT account_id FROM app_identity.account WHERE normalized_email=:email AND email_verified AND status='ACTIVE' AND deletion_state='ACTIVE' AND EXISTS(SELECT 1 FROM app_identity.local_credential l WHERE l.account_id=account.account_id)")
                .param("email", normalized).query(UUID.class).optional();
        if (id.isEmpty()) return;
        var proof = transactions.execute(s -> {
            var a = accounts.get(id.get(), true);
            if (!a.status().equals("ACTIVE") || !a.emailVerified()) return null;
            return proofs.issue(a.access(), OwnershipProofs.Purpose.RESET_PASSWORD);
        });
        // Delivery is after commit, outside account locks. Never retry an ambiguous timeout with this secret.
        if (proof != null && !mail.reset(normalized, origin + "/reset-password#token=" + proof.token()))
            proofs.invalidate(proof.token());
    }

    public void requestVerification(String email, String remote) {
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        if (!(limits.allow("verify-address", remote, 20) & limits.allow("verify", normalized, 3))) return;
        var accountId = jdbcClient.sql(
                        "SELECT account_id FROM app_identity.account WHERE normalized_email=:email AND NOT email_verified AND status='ACTIVE' AND deletion_state='ACTIVE'")
                .param("email", normalized).query(UUID.class).optional();
        if (accountId.isEmpty()) return;
        var proof = transactions.execute(status -> {
            var account = accounts.get(accountId.get(), true);
            if (!account.status().equals("ACTIVE") || account.emailVerified()) return null;
            return proofs.issue(account.access(), OwnershipProofs.Purpose.VERIFY_EMAIL);
        });
        if (proof != null && !mail.verification(normalized, origin + "/verify-email#token=" + proof.token())) {
            proofs.invalidate(proof.token());
        }
    }

    public void confirmVerification(String token) {
        transactions.executeWithoutResult(status -> {
            var access = proofs.identify(token);
            proofs.consume(access, token, OwnershipProofs.Purpose.VERIFY_EMAIL);
            jdbcClient.sql(
                            "UPDATE app_identity.account SET email_verified=true,updated_at=statement_timestamp() WHERE account_id=:id")
                    .param("id", access.accountId()).update();
        });
    }

    public void confirm(String token, String password) {
        LocalAccounts.validatePassword(password);
        transactions.executeWithoutResult(s -> {
            var access = proofs.identify(token);
            proofs.consume(access, token, OwnershipProofs.Purpose.RESET_PASSWORD);
            local.replacePassword(access.accountId(), password);
        });
    }
}
