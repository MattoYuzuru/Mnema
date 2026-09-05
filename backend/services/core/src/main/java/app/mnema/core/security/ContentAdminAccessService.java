package app.mnema.core.security;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ContentAdminAccessService {
    public boolean canManageOwnedContent(UUID actorUserId, UUID ownerUserId) {
        // The legacy core is not part of the replacement topology. Until #146 removes it,
        // keep owner access functional but fail closed instead of calling the deleted user runtime.
        return actorUserId.equals(ownerUserId);
    }

    public void requireActiveAdmin(UUID actorUserId) {
        throw new SecurityException("Legacy content administration is unavailable for " + actorUserId);
    }

    public void assertCanManageOwnedContent(UUID actorUserId, UUID ownerUserId, String resourceKind, UUID resourceId) {
        if (!canManageOwnedContent(actorUserId, ownerUserId)) {
            throw new SecurityException("Access denied to " + resourceKind + " " + resourceId);
        }
    }
}
