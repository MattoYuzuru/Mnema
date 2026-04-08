package app.mnema.core.user.client;

import java.util.UUID;

public record InternalUserModerationState(
        UUID id,
        boolean admin,
        boolean banned
) {
}
