package app.mnema.core.deck.integration;

import app.mnema.core.deck.domain.dto.PublicDeckDTO;
import app.mnema.core.deck.domain.dto.UserCardDTO;
import app.mnema.core.deck.domain.dto.UserDeckDTO;
import app.mnema.core.deck.domain.request.CreateCardRequest;
import app.mnema.core.deck.domain.type.LanguageTag;
import app.mnema.core.deck.domain.type.SrAlgorithm;
import app.mnema.core.deck.repository.PublicDeckRepository;
import app.mnema.core.deck.repository.UserDeckRepository;
import app.mnema.core.deck.service.DeckService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional
class DeckFlowIT {

    @Autowired
    DeckService deckService;

    @Autowired
    UserDeckRepository userDeckRepository;

    @Autowired
    PublicDeckRepository publicDeckRepository;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void createDeckAndAddCard_persistsPublicAndUserState() {
        UUID userId = UUID.randomUUID();

        PublicDeckDTO deckRequest = new PublicDeckDTO(
                null,
                null,
                null,
                "Integration deck",
                "Integration description",
                UUID.randomUUID(),
                true,
                true,
                LanguageTag.en,
                new String[]{"integration"},
                null,
                null,
                null,
                null
        );

        UserDeckDTO createdDeck = deckService.createNewDeck(userId, deckRequest);

        assertThat(createdDeck.userDeckId()).isNotNull();
        assertThat(createdDeck.userId()).isEqualTo(userId);
        assertThat(createdDeck.publicDeckId()).isNotNull();
        assertThat(createdDeck.algorithmId()).isEqualTo(SrAlgorithm.sm2.name());
        assertThat(createdDeck.autoUpdate()).isTrue();

        var userDeckFromDb = userDeckRepository
                .findById(createdDeck.userDeckId())
                .orElseThrow();
        assertThat(userDeckFromDb.getUserId()).isEqualTo(userId);

        var publicDeckFromDb = publicDeckRepository
                .findByDeckIdAndVersion(createdDeck.publicDeckId(), createdDeck.currentVersion())
                .orElseThrow();
        assertThat(publicDeckFromDb.getAuthorId()).isEqualTo(userId);

        ObjectNode content = objectMapper.createObjectNode();
        content.put("front", "Q");
        content.put("back", "A");

        CreateCardRequest cardRequest = new CreateCardRequest(
                content,
                1,
                new String[]{"tag"},
                "note",
                null,
                "checksum-123"
        );

        UserCardDTO createdCard = deckService.addNewCardToDeck(
                userId,
                createdDeck.userDeckId(),
                cardRequest
        );

        assertThat(createdCard.userCardId()).isNotNull();
        assertThat(createdCard.isDeleted()).isFalse();
        assertThat(createdCard.isSuspended()).isFalse();
        assertThat(createdCard.personalNote()).isEqualTo("note");
        assertThat(createdCard.reviewCount()).isZero();

        var page = deckService.getUserCardsByDeck(userId, createdDeck.userDeckId(), 1, 50);
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().getFirst().userCardId()).isEqualTo(createdCard.userCardId());
    }
}
