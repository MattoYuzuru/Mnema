package app.mnema.core.deck.repository;

import app.mnema.core.deck.domain.entity.PublicCardEntity;
import app.mnema.core.deck.domain.entity.PublicDeckEntity;
import app.mnema.core.deck.domain.entity.UserCardEntity;
import app.mnema.core.deck.domain.entity.UserDeckEntity;
import app.mnema.core.deck.domain.type.LanguageTag;
import app.mnema.core.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserCardRepositorySearchDataJpaTest extends PostgresIntegrationTest {

    @Autowired
    private UserCardRepository userCardRepository;

    @Autowired
    private UserDeckRepository userDeckRepository;

    @Autowired
    private PublicDeckRepository publicDeckRepository;

    @Autowired
    private PublicCardRepository publicCardRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void searchUserCards_matchesContentAndNotes_andSkipsDeleted() {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();

        PublicDeckEntity publicDeck = createDeck();
        UserDeckEntity userDeck = createUserDeck(userId, publicDeck);

        PublicCardEntity publicCard1 = createPublicCard(publicDeck, "Cat", new String[]{"animal"});
        PublicCardEntity publicCard2 = createPublicCard(publicDeck, "Run", new String[]{"verb"});

        UserCardEntity cardByNote = new UserCardEntity(
                userId,
                userDeck.getUserDeckId(),
                publicCard1.getCardId(),
                false,
                false,
                "feline note",
                null,
                now,
                null
        );

        ObjectNode override = objectMapper.createObjectNode();
        override.put("front", "Sprint");
        UserCardEntity cardByOverride = new UserCardEntity(
                userId,
                userDeck.getUserDeckId(),
                publicCard2.getCardId(),
                false,
                false,
                null,
                override,
                now.plusSeconds(1),
                null
        );

        UserCardEntity deleted = new UserCardEntity(
                userId,
                userDeck.getUserDeckId(),
                publicCard2.getCardId(),
                false,
                true,
                "Sprint",
                null,
                now.plusSeconds(2),
                null
        );

        userCardRepository.saveAll(List.of(cardByNote, cardByOverride, deleted));
        userCardRepository.flush();

        var result = userCardRepository.searchUserCards(
                userId,
                userDeck.getUserDeckId(),
                "sprint",
                null,
                PageRequest.of(0, 10)
        );

        assertThat(result.getContent())
                .hasSize(1)
                .allMatch(card -> card.getUserCardId().equals(cardByOverride.getUserCardId()));
    }

    @Test
    void searchUserCardsByTags_filtersByPublicCardTags() {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();

        PublicDeckEntity publicDeck = createDeck();
        UserDeckEntity userDeck = createUserDeck(userId, publicDeck);

        PublicCardEntity publicCard1 = createPublicCard(publicDeck, "Cat", new String[]{"animal"});
        PublicCardEntity publicCard2 = createPublicCard(publicDeck, "Run", new String[]{"verb"});

        UserCardEntity card1 = new UserCardEntity(
                userId,
                userDeck.getUserDeckId(),
                publicCard1.getCardId(),
                false,
                false,
                null,
                null,
                now,
                null
        );

        UserCardEntity card2 = new UserCardEntity(
                userId,
                userDeck.getUserDeckId(),
                publicCard2.getCardId(),
                false,
                false,
                null,
                null,
                now.plusSeconds(1),
                null
        );

        userCardRepository.saveAll(List.of(card1, card2));
        userCardRepository.flush();

        var result = userCardRepository.searchUserCardsByTags(
                userId,
                userDeck.getUserDeckId(),
                "verb",
                PageRequest.of(0, 10)
        );

        assertThat(result.getContent())
                .hasSize(1)
                .allMatch(card -> card.getUserCardId().equals(card2.getUserCardId()));
    }

    private PublicDeckEntity createDeck() {
        UUID templateId = anyTemplateId();
        PublicDeckEntity deck = new PublicDeckEntity(
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                "Public deck",
                "Desc",
                null,
                templateId,
                true,
                true,
                LanguageTag.en,
                new String[]{"tag"},
                Instant.now(),
                null,
                null,
                null
        );
        return publicDeckRepository.save(deck);
    }

    private UserDeckEntity createUserDeck(UUID userId, PublicDeckEntity publicDeck) {
        UserDeckEntity deck = new UserDeckEntity();
        deck.setUserId(userId);
        deck.setPublicDeckId(publicDeck.getDeckId());
        deck.setSubscribedVersion(publicDeck.getVersion());
        deck.setCurrentVersion(publicDeck.getVersion());
        deck.setAutoUpdate(true);
        deck.setDisplayName("User deck");
        deck.setCreatedAt(Instant.now());
        deck.setArchived(false);
        return userDeckRepository.save(deck);
    }

    private PublicCardEntity createPublicCard(PublicDeckEntity deck, String front, String[] tags) {
        ObjectNode content = objectMapper.createObjectNode();
        content.put("front", front);
        content.put("back", "back");

        PublicCardEntity card = new PublicCardEntity(
                deck.getDeckId(),
                deck.getVersion(),
                deck,
                content,
                1,
                tags,
                Instant.now(),
                null,
                true,
                UUID.randomUUID().toString()
        );

        return publicCardRepository.save(card);
    }

    private UUID anyTemplateId() {
        UUID existing = null;
        try {
            existing = jdbcTemplate.query(
                    "select template_id from card_templates limit 1",
                    rs -> rs.next() ? (UUID) rs.getObject(1) : null
            );
        } catch (DataAccessException ignored) {
            // If table is empty, fall through and create a minimal template.
        }
        if (existing != null) {
            return existing;
        }

        UUID id = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        String name = "Test template";

        jdbcTemplate.update(
                "insert into card_templates (template_id, owner_id, name) values (?, ?, ?)",
                id, ownerId, name
        );

        return id;
    }
}
