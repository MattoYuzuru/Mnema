package app.mnema.core.security;

import app.mnema.core.user.client.InternalUserModerationState;
import app.mnema.core.user.client.UserApiClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ContentAdminAccessService {

    private final UserApiClient userApiClient;

    public ContentAdminAccessService(UserApiClient userApiClient) {
        this.userApiClient = userApiClient;
    }

    public boolean canManageOwnedContent(UUID actorUserId, UUID ownerUserId) {
        if (actorUserId.equals(ownerUserId)) {
            return true;
        }

        InternalUserModerationState actor = userApiClient.getModerationState(actorUserId);
        if (actor.banned() || !actor.admin()) {
            return false;
        }
        InternalUserModerationState owner = userApiClient.getModerationState(ownerUserId);
        return !owner.admin();
    }

    public InternalUserModerationState requireActiveAdmin(UUID actorUserId) {
        InternalUserModerationState actor = userApiClient.getModerationState(actorUserId);
        if (actor.banned() || !actor.admin()) {
            throw new SecurityException("Admin access denied for " + actorUserId);
        }
        return actor;
    }

    public void assertCanManageOwnedContent(UUID actorUserId, UUID ownerUserId, String resourceKind, UUID resourceId) {
        if (!canManageOwnedContent(actorUserId, ownerUserId)) {
            throw new SecurityException("Access denied to " + resourceKind + " " + resourceId);
        }
    }
}
