package app.mnema.core.security;

import app.mnema.core.user.client.InternalUserModerationState;
import app.mnema.core.user.client.UserApiClient;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentAdminAccessServiceTest {

    private final UserApiClient userApiClient = mock(UserApiClient.class);
    private final ContentAdminAccessService service = new ContentAdminAccessService(userApiClient);

    @Test
    void canManageOwnedContent_returnsTrueForOwnerWithoutModerationLookup() {
        UUID userId = UUID.randomUUID();

        assertThat(service.canManageOwnedContent(userId, userId)).isTrue();
    }

    @Test
    void canManageOwnedContent_requiresActiveAdminAndNonAdminOwner() {
        UUID actorId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        when(userApiClient.getModerationState(actorId))
                .thenReturn(new InternalUserModerationState(actorId, true, false));
        when(userApiClient.getModerationState(ownerId))
                .thenReturn(new InternalUserModerationState(ownerId, false, false));

        assertThat(service.canManageOwnedContent(actorId, ownerId)).isTrue();
        verify(userApiClient).getModerationState(actorId);
        verify(userApiClient).getModerationState(ownerId);
    }

    @Test
    void canManageOwnedContent_rejectsBannedOrNonAdminActorAndAdminOwner() {
        UUID actorId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        when(userApiClient.getModerationState(actorId))
                .thenReturn(new InternalUserModerationState(actorId, false, false));

        assertThat(service.canManageOwnedContent(actorId, ownerId)).isFalse();

        when(userApiClient.getModerationState(actorId))
                .thenReturn(new InternalUserModerationState(actorId, true, false));
        when(userApiClient.getModerationState(ownerId))
                .thenReturn(new InternalUserModerationState(ownerId, true, false));

        assertThat(service.canManageOwnedContent(actorId, ownerId)).isFalse();
    }

    @Test
    void requireActiveAdminAndAssertCanManageThrowOnDeniedAccess() {
        UUID actorId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        when(userApiClient.getModerationState(actorId))
                .thenReturn(new InternalUserModerationState(actorId, true, true));

        assertThatThrownBy(() -> service.requireActiveAdmin(actorId))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Admin access denied");

        when(userApiClient.getModerationState(actorId))
                .thenReturn(new InternalUserModerationState(actorId, true, false));
        when(userApiClient.getModerationState(ownerId))
                .thenReturn(new InternalUserModerationState(ownerId, true, false));

        assertThatThrownBy(() -> service.assertCanManageOwnedContent(actorId, ownerId, "deck", resourceId))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("deck")
                .hasMessageContaining(resourceId.toString());
    }
}
