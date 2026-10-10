package app.mnema.identityaccount.admin;

import app.mnema.identityaccount.account.AccountStore;
import app.mnema.identityaccount.contract.AccountAccess;
import app.mnema.identityaccount.contract.AccountFailure;
import app.mnema.identityaccount.security.BrowserSessions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Owner-console authorization. The console needs both the configured owner account and an access token issued to the
 * dedicated {@code mnema-admin-web} client, so a learner-web token of the same account cannot reach it. An empty owner
 * configuration keeps the console closed.
 */
@Component
public final class AdminOwnerAccess {
    /** Public client registered only while {@code MNEMA_IDENTITY_ADMIN_ORIGIN} is configured. */
    public static final String ADMIN_CLIENT = "mnema-admin-web";
    private final UUID owner;
    private final AccountStore accounts;

    public AdminOwnerAccess(AccountStore accounts, @Value("${identity.admin.owner-id:}") String configuredOwner) {
        this.accounts = accounts;
        if (configuredOwner.isEmpty()) owner = null;
        else {
            owner = UUID.fromString(configuredOwner);
            if (!owner.toString().equals(configuredOwner) || owner.equals(new UUID(0, 0)) || owner.variant() != 2 || owner.version() < 1 || owner.version() > 8)
                throw new IllegalArgumentException("Admin owner must be a canonical entity UUID");
        }
    }

    /** Directory and journal reads: the exact owner through the admin client; closed while no owner is configured. */
    public void requireConsole(Authentication actor) {
        AccountAccess access = BrowserSessions.access(actor);
        accounts.require(access, false);
        if (owner == null || !owner.equals(access.accountId()) || !adminClient(actor)) throw AccountFailure.forbidden();
    }

    /**
     * Moderation keeps its Identity administrator authority unchanged while the console is not configured. Once an owner
     * is configured it additionally requires the console owner through the admin client.
     */
    public void requireModeration(Authentication actor) {
        if (owner != null) requireConsole(actor);
    }

    private static boolean adminClient(Authentication actor) {
        return actor instanceof JwtAuthenticationToken token && ADMIN_CLIENT.equals(token.getToken().getClaimAsString("client_id"));
    }
}
