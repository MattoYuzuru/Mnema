package app.mnema.core.deck.controller;

import app.mnema.core.deck.domain.dto.CardTemplateDTO;
import app.mnema.core.deck.domain.dto.UserCardDTO;
import app.mnema.core.deck.domain.dto.UserDeckDTO;
import app.mnema.core.deck.domain.request.CreateCardRequest;
import app.mnema.core.deck.domain.request.MissingFieldCardsRequest;
import app.mnema.core.deck.service.CardService;
import app.mnema.core.deck.service.DeckService;
import app.mnema.core.deck.service.TemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalAiControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    DeckService deckService;

    @Mock
    CardService cardService;

    @Mock
    TemplateService templateService;

    @Test
    void internalEndpointsDelegateToInternalServices() {
        InternalAiController controller = new InternalAiController(deckService, cardService, templateService);
        UUID userDeckId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UserDeckDTO deck = deck(userDeckId);
        UserCardDTO card = new UserCardDTO(cardId, null, true, false, "note", new String[]{"tag"}, MAPPER.createObjectNode());
        CardTemplateDTO template = template(templateId);
        MissingFieldCardsRequest missingRequest = new MissingFieldCardsRequest(List.of("front"), 10, null);
        CreateCardRequest createCardRequest = new CreateCardRequest(MAPPER.createObjectNode(), 1, new String[]{"tag"}, "note", null, "checksum");

        when(deckService.getUserDeckInternal(userDeckId)).thenReturn(deck);
        when(templateService.getCardTemplateByIdInternal(templateId, 2)).thenReturn(template);
        when(cardService.getUserCardsByDeckInternal(userDeckId, 1, 50)).thenReturn(new PageImpl<>(List.of(card)));
        when(cardService.getUserCardInternal(userDeckId, cardId)).thenReturn(card);
        when(cardService.getMissingFieldCardsInternal(userDeckId, missingRequest)).thenReturn(List.of(card));
        when(cardService.addNewCardsToDeckBatchInternal(userDeckId, List.of(createCardRequest), operationId)).thenReturn(List.of(card));
        when(cardService.updateUserCardInternal(userDeckId, cardId, card, true, operationId)).thenReturn(card);

        Page<UserCardDTO> page = controller.getCards(userDeckId, 1, 50);

        assertThat(controller.getUserDeck(userDeckId)).isEqualTo(deck);
        assertThat(controller.getTemplate(templateId, 2)).isEqualTo(template);
        assertThat(page.getContent()).containsExactly(card);
        assertThat(controller.getCard(userDeckId, cardId)).isEqualTo(card);
        assertThat(controller.getMissingFieldCards(userDeckId, missingRequest)).containsExactly(card);
        assertThat(controller.addCardsBatch(userDeckId, List.of(createCardRequest), operationId)).containsExactly(card);
        assertThat(controller.updateCard(userDeckId, cardId, card, "global", operationId)).isEqualTo(card);
    }

    @Test
    void updateCardRejectsUnknownScope() {
        InternalAiController controller = new InternalAiController(deckService, cardService, templateService);

        assertThatThrownBy(() -> controller.updateCard(UUID.randomUUID(), UUID.randomUUID(), null, "bad", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown scope");
    }

    @Test
    void updateCardSupportsLocalScope() {
        InternalAiController controller = new InternalAiController(deckService, cardService, templateService);
        UUID userDeckId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        UserCardDTO card = new UserCardDTO(cardId, null, true, false, "note", new String[0], MAPPER.createObjectNode());
        when(cardService.updateUserCardInternal(userDeckId, cardId, card, false, null)).thenReturn(card);

        assertThat(controller.updateCard(userDeckId, cardId, card, "local", null)).isEqualTo(card);
    }

    private static UserDeckDTO deck(UUID userDeckId) {
        return new UserDeckDTO(
                userDeckId,
                UUID.randomUUID(),
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

    private static CardTemplateDTO template(UUID templateId) {
        return new CardTemplateDTO(
                templateId,
                1,
                1,
                UUID.randomUUID(),
                "Basic",
                "Description",
                true,
                Instant.now(),
                Instant.now(),
                null,
                null,
                null,
                List.of()
        );
    }
}
