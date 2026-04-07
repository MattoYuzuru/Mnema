package app.mnema.core.deck.controller;

import app.mnema.core.deck.domain.dto.DuplicateGroupDTO;
import app.mnema.core.deck.domain.dto.DuplicateResolveResultDTO;
import app.mnema.core.deck.domain.dto.MissingFieldStatDTO;
import app.mnema.core.deck.domain.dto.MissingFieldSummaryDTO;
import app.mnema.core.deck.domain.dto.UserCardDTO;
import app.mnema.core.deck.domain.request.CreateCardRequest;
import app.mnema.core.deck.domain.request.DuplicateResolveRequest;
import app.mnema.core.deck.domain.request.DuplicateSearchRequest;
import app.mnema.core.deck.domain.request.MissingFieldCardsRequest;
import app.mnema.core.deck.domain.request.MissingFieldSummaryRequest;
import app.mnema.core.deck.service.CardService;
import app.mnema.core.security.CurrentUserProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserCardControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    CurrentUserProvider currentUserProvider;

    @Mock
    CardService cardService;

    @Test
    void endpointsDelegateToCardServiceAndResolveScope() {
        UserCardController controller = new UserCardController(currentUserProvider, cardService);
        UUID userId = UUID.randomUUID();
        UUID userDeckId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        Jwt jwt = jwt();
        UserCardDTO card = new UserCardDTO(cardId, null, true, false, "note", new String[]{"tag"}, MAPPER.createObjectNode());
        Page<UserCardDTO> page = new PageImpl<>(List.of(card));
        MissingFieldSummaryRequest summaryRequest = new MissingFieldSummaryRequest(List.of("front"), 5);
        MissingFieldCardsRequest cardsRequest = new MissingFieldCardsRequest(List.of("front"), 10, List.of(new MissingFieldCardsRequest.FieldLimit("front", 3)));
        DuplicateSearchRequest duplicateSearchRequest = new DuplicateSearchRequest(List.of("front"), 3, 10, true, 0.8);
        DuplicateResolveRequest duplicateResolveRequest = new DuplicateResolveRequest(List.of("front"), "global", operationId);
        CreateCardRequest createCardRequest = new CreateCardRequest(MAPPER.createObjectNode(), 1, new String[]{"tag"}, "note", null, "checksum");
        MissingFieldSummaryDTO missingSummary = new MissingFieldSummaryDTO(List.of(new MissingFieldStatDTO("front", 1, List.of(card))), 5);
        DuplicateResolveResultDTO resolveResult = new DuplicateResolveResultDTO(1, 1, 0, true);

        when(currentUserProvider.getUserId(jwt)).thenReturn(userId);
        when(cardService.getUserCardsByDeck(userId, userDeckId, 1, 50)).thenReturn(page);
        when(cardService.getUserCard(userId, userDeckId, cardId)).thenReturn(card);
        when(cardService.getMissingFieldSummary(userId, userDeckId, summaryRequest)).thenReturn(missingSummary);
        when(cardService.getMissingFieldCards(userId, userDeckId, cardsRequest)).thenReturn(List.of(card));
        when(cardService.getDuplicateGroups(userId, userDeckId, duplicateSearchRequest))
                .thenReturn(List.of(new DuplicateGroupDTO("exact", 1.0, 1, List.of(card))));
        when(cardService.resolveDuplicateGroups(userId, userDeckId, duplicateResolveRequest)).thenReturn(resolveResult);
        when(cardService.addNewCardToDeck(userId, userDeckId, createCardRequest)).thenReturn(card);
        when(cardService.addNewCardsToDeckBatch(userId, userDeckId, List.of(createCardRequest), operationId)).thenReturn(List.of(card));
        when(cardService.updateUserCard(userId, userDeckId, cardId, card, true, operationId)).thenReturn(card);

        assertThat(controller.getCards(jwt, userDeckId, 1, 50)).isEqualTo(page);
        assertThat(controller.getCard(jwt, userDeckId, cardId)).isEqualTo(card);
        assertThat(controller.getMissingFields(jwt, userDeckId, summaryRequest)).isEqualTo(missingSummary);
        assertThat(controller.getMissingFieldCards(jwt, userDeckId, cardsRequest)).containsExactly(card);
        assertThat(controller.getDuplicateGroups(jwt, userDeckId, duplicateSearchRequest)).hasSize(1);
        assertThat(controller.resolveDuplicateGroups(jwt, userDeckId, duplicateResolveRequest)).isEqualTo(resolveResult);
        assertThat(controller.addCard(jwt, userDeckId, createCardRequest)).isEqualTo(card);
        assertThat(controller.addCardsBatch(jwt, userDeckId, List.of(createCardRequest), operationId)).containsExactly(card);
        assertThat(controller.updateCard(jwt, userDeckId, cardId, card, "global", operationId)).isEqualTo(card);

        controller.deleteCard(jwt, userDeckId, cardId, "local", operationId);
        verify(cardService).deleteUserCard(userId, userDeckId, cardId, false, operationId);
    }

    @Test
    void updateAndDeleteRejectUnknownScopes() {
        UserCardController controller = new UserCardController(currentUserProvider, cardService);
        Jwt jwt = jwt();

        assertThatThrownBy(() -> controller.updateCard(jwt, UUID.randomUUID(), UUID.randomUUID(), null, "bad", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown scope");
        assertThatThrownBy(() -> controller.deleteCard(jwt, UUID.randomUUID(), UUID.randomUUID(), "bad", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown delete scope");
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("token").header("alg", "none").claim("user_id", UUID.randomUUID().toString()).build();
    }
}
