package app.mnema.core.deck.controller;

import app.mnema.core.deck.domain.dto.DeckSizeDTO;
import app.mnema.core.deck.domain.dto.PublicDeckDTO;
import app.mnema.core.deck.domain.dto.UserDeckDTO;
import app.mnema.core.deck.domain.type.LanguageTag;
import app.mnema.core.deck.service.DeckService;
import app.mnema.core.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDeckControllerTest {

    @Mock
    DeckService deckService;

    @Mock
    CurrentUserProvider currentUserProvider;

    @Test
    void endpointsDelegateToDeckServiceWithResolvedUserId() {
        UserDeckController controller = new UserDeckController(deckService, currentUserProvider);
        UUID userId = UUID.randomUUID();
        UUID userDeckId = UUID.randomUUID();
        Jwt jwt = jwt();
        UserDeckDTO deck = deck(userId, userDeckId);
        Page<UserDeckDTO> page = new PageImpl<>(List.of(deck));
        PublicDeckDTO publicDeck = new PublicDeckDTO(
                userDeckId,
                1,
                UUID.randomUUID(),
                "Name",
                "Description",
                null,
                null,
                UUID.randomUUID(),
                1,
                true,
                true,
                LanguageTag.en,
                new String[]{"tag"},
                Instant.now(),
                Instant.now(),
                null,
                null
        );
        when(currentUserProvider.getUserId(jwt)).thenReturn(userId);
        when(deckService.getUserDecksByPage(userId, 1, 10)).thenReturn(page);
        when(deckService.getUserPublicDeckIds(userId)).thenReturn(List.of(UUID.randomUUID()));
        when(deckService.getUserDeck(userId, userDeckId)).thenReturn(deck);
        when(deckService.getDeletedUserDecks(userId)).thenReturn(List.of(deck));
        when(deckService.createNewDeck(userId, publicDeck)).thenReturn(deck);
        when(deckService.updateUserDeckMeta(userId, userDeckId, deck)).thenReturn(deck);
        when(deckService.syncUserDeckToLatestVersion(userId, userDeckId)).thenReturn(deck);
        when(deckService.syncUserDeckTemplate(userId, userDeckId)).thenReturn(deck);
        when(deckService.getUserDeckSize(userId, userDeckId)).thenReturn(new DeckSizeDTO(userDeckId, 42));

        assertThat(controller.getAllUserDecksByPage(jwt, 1, 10)).isEqualTo(page);
        assertThat(controller.getPublicDeckIds(jwt).publicDeckIds()).hasSize(1);
        assertThat(controller.getDeckById(jwt, userDeckId)).isEqualTo(deck);
        assertThat(controller.getDeletedDecks(jwt)).containsExactly(deck);
        assertThat(controller.createDeck(jwt, publicDeck)).isEqualTo(deck);
        assertThat(controller.updateDeck(jwt, userDeckId, deck)).isEqualTo(deck);
        assertThat(controller.syncDeck(jwt, userDeckId)).isEqualTo(deck);
        assertThat(controller.syncTemplate(jwt, userDeckId)).isEqualTo(deck);
        assertThat(controller.getDeckSize(jwt, userDeckId).cardsQty()).isEqualTo(42);

        controller.deleteDeck(jwt, userDeckId);
        controller.hardDeleteDeck(jwt, userDeckId);

        verify(deckService).deleteUserDeck(userId, userDeckId);
        verify(deckService).hardDeleteUserDeck(userId, userDeckId);
    }

    private static UserDeckDTO deck(UUID userId, UUID userDeckId) {
        return new UserDeckDTO(
                userDeckId,
                userId,
                UUID.randomUUID(),
                1,
                1,
                1,
                1,
                true,
                null,
                null,
                "Deck",
                "Description",
                Instant.now(),
                Instant.now(),
                false
        );
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("token").header("alg", "none").claim("user_id", UUID.randomUUID().toString()).build();
    }
}
