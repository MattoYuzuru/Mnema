package app.mnema.learning.events;

import app.mnema.learning.platform.api.AccessForbiddenException;
import app.mnema.learning.platform.id.UuidPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Exact account identity, never a username or a delegable administrator claim. Empty configuration denies every editor. */
@Component
public final class EventAdminAccess {
    private final UUID owner;

    public EventAdminAccess(@Value("${learning.events.owner-id:}") String configuredOwner) {
        if (configuredOwner.isEmpty()) {
            owner = null;
        } else {
            UUID parsed = UuidPolicy.requireEntityId(UUID.fromString(configuredOwner), "events owner");
            if (!parsed.toString().equals(configuredOwner)) throw new IllegalArgumentException("Events owner must be a canonical UUID");
            owner = parsed;
        }
    }

    void require(UUID actor) {
        if (owner == null || !owner.equals(actor)) throw new AccessForbiddenException();
    }
}
