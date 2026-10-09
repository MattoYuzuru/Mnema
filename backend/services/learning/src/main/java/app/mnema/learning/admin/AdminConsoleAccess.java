package app.mnema.learning.admin;

import app.mnema.learning.platform.api.AccessForbiddenException;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Owner operations require the configured owner account and an access token issued to the dedicated admin client
 * ({@code client_id} claim, RFC 9068), so a learner-web token of the same account cannot reach the console. Read access is
 * independent of editorial access and delegable admin grants. Empty configuration closes the console.
 */
@Component
public final class AdminConsoleAccess {
    /** The public client Identity registers only while its admin origin is configured. */
    public static final String ADMIN_CLIENT = "mnema-admin-web";
    private final UUID owner;

    public AdminConsoleAccess(@Value("${learning.admin.owner-id:}") String configuredOwner) {
        if (configuredOwner.isEmpty()) owner = null;
        else {
            owner = UuidPolicy.requireEntityId(UUID.fromString(configuredOwner), "admin owner");
            if (!owner.toString().equals(configuredOwner)) throw new IllegalArgumentException("Admin owner must be a canonical UUID");
        }
    }

    /** @return the verified actor; @throws AccessForbiddenException for another account, another client or an unconfigured console */
    public UUID require(Jwt token) {
        if (token == null) throw new InvalidRequestException();
        UUID actor = UuidPolicy.requireEntityId(UUID.fromString(token.getSubject()), "actor");
        if (owner == null || !owner.equals(actor) || !ADMIN_CLIENT.equals(token.getClaimAsString("client_id")))
            throw new AccessForbiddenException();
        return actor;
    }
}
