package app.mnema.core.security;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentAdminAccessServiceTest {

    private final ContentAdminAccessService service = new ContentAdminAccessService();

    @Test
    void canManageOwnedContent_returnsTrueOnlyForOwner() {
        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();

        assertThat(service.canManageOwnedContent(userId, userId)).isTrue();
        assertThat(service.canManageOwnedContent(userId, otherUserId)).isFalse();
    }

    @Test
    void requireActiveAdminAndAssertCanManageThrowOnDeniedAccess() {
        UUID actorId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        assertThatThrownBy(() -> service.requireActiveAdmin(actorId))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("unavailable");

        assertThatThrownBy(() -> service.assertCanManageOwnedContent(actorId, ownerId, "deck", resourceId))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("deck")
                .hasMessageContaining(resourceId.toString());
    }
}
