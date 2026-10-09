package app.mnema.learning.admin;

import app.mnema.learning.platform.api.AccessForbiddenException;
import app.mnema.learning.platform.id.UuidPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Read access to owner operations is independent of editorial access and delegable admin grants. */
@Component
public final class AdminConsoleAccess {
    private final UUID owner;

    public AdminConsoleAccess(@Value("${learning.admin.owner-id:}") String configuredOwner) {
        if (configuredOwner.isEmpty()) owner = null;
        else {
            owner = UuidPolicy.requireEntityId(UUID.fromString(configuredOwner), "admin owner");
            if (!owner.toString().equals(configuredOwner)) throw new IllegalArgumentException("Admin owner must be a canonical UUID");
        }
    }

    public void require(UUID actor) {
        if (owner == null || !owner.equals(actor)) throw new AccessForbiddenException();
    }
}
