package app.mnema.core.deck.repository;

import app.mnema.core.deck.domain.entity.PublicCardEntity;
import app.mnema.core.deck.domain.entity.PublicDeckEntity;
import app.mnema.core.deck.domain.type.LanguageTag;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
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
class PublicCardRepositoryDataJpaTest {

    @Autowired
    private PublicCardRepository publicCardRepository;

    @Autowired
    private PublicDeckRepository publicDeckRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Возвращает любой валидный template_id из card_templates.
     * Если записей нет – создаёт минимально валидную запись.
     */
    private UUID anyTemplateId() {
        UUID existing = null;
        try {
            existing = jdbcTemplate.query(
                    "select template_id from card_templates limit 1",
                    rs -> rs.next() ? (UUID) rs.getObject(1) : null
            );
        } catch (DataAccessException ignored) {
            // если таблицы нет/другая ошибка – создадим запись
        }
        if (existing != null) {
            return existing;
        }

        UUID id = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        jdbcTemplate.update(
                "insert into card_templates (template_id, owner_id) values (?, ?)",
                id, ownerId
        );

        return id;
    }

    private PublicDeckEntity createDeck() {
        PublicDeckEntity deck = new PublicDeckEntity(
                1,
                UUID.randomUUID(),
                "Test deck",
                "Desc",
                anyTemplateId(),
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

    @Test
    void findByDeckIdAndDeckVersion_returnsAllCardsForDeckAndVersion() {
        PublicDeckEntity deck = createDeck();

        ObjectNode content1 = objectMapper.createObjectNode();
        content1.put("front", "Q1");
        content1.put("back", "A1");

        ObjectNode content2 = objectMapper.createObjectNode();
        content2.put("front", "Q2");
        content2.put("back", "A2");

        PublicCardEntity card1 = new PublicCardEntity(
                deck.getDeckId(),
                deck.getVersion(),
                deck,
                content1,
                1,
                new String[]{"tag1"},
                Instant.now(),
                null,
                true,
                "checksum-1"
        );

        PublicCardEntity card2 = new PublicCardEntity(
                deck.getDeckId(),
                deck.getVersion(),
                deck,
                content2,
                2,
                new String[]{"tag2"},
                Instant.now(),
                null,
                true,
                "checksum-2"
        );

        publicCardRepository.saveAll(List.of(card1, card2));

        List<PublicCardEntity> result = publicCardRepository
                .findByDeckIdAndDeckVersion(deck.getDeckId(), deck.getVersion());

        assertThat(result)
                .hasSize(2)
                .allMatch(c -> c.getDeckId().equals(deck.getDeckId()))
                .allMatch(c -> c.getDeckVersion().equals(deck.getVersion()));
    }

    @Test
    void findByDeckIdAndDeckVersionOrderByOrderIndex_returnsCardsSortedByOrderIndex() {
        PublicDeckEntity deck = createDeck();

        ObjectNode content = objectMapper.createObjectNode();
        content.put("front", "Q");
        content.put("back", "A");

        PublicCardEntity card1 = new PublicCardEntity(
                deck.getDeckId(),
                deck.getVersion(),
                deck,
                content,
                3,
                new String[]{"tag"},
                Instant.now(),
                null,
                true,
                "c1"
        );
        PublicCardEntity card2 = new PublicCardEntity(
                deck.getDeckId(),
                deck.getVersion(),
                deck,
                content,
                1,
                new String[]{"tag"},
                Instant.now(),
                null,
                true,
                "c2"
        );
        PublicCardEntity card3 = new PublicCardEntity(
                deck.getDeckId(),
                deck.getVersion(),
                deck,
                content,
                2,
                new String[]{"tag"},
                Instant.now(),
                null,
                true,
                "c3"
        );

        publicCardRepository.saveAll(List.of(card1, card2, card3));

        Page<PublicCardEntity> page = publicCardRepository
                .findByDeckIdAndDeckVersionOrderByOrderIndex(
                        deck.getDeckId(),
                        deck.getVersion(),
                        PageRequest.of(0, 10)
                );

        List<PublicCardEntity> result = page.getContent();
        assertThat(result).hasSize(3);
        assertThat(result.get(0).getOrderIndex()).isEqualTo(1);
        assertThat(result.get(1).getOrderIndex()).isEqualTo(2);
        assertThat(result.get(2).getOrderIndex()).isEqualTo(3);
    }

    @Test
    void findByCardId_findsCardByGeneratedId() {
        PublicDeckEntity deck = createDeck();

        ObjectNode content = objectMapper.createObjectNode();
        content.put("front", "Q");
        content.put("back", "A");

        PublicCardEntity card = new PublicCardEntity(
                deck.getDeckId(),
                deck.getVersion(),
                deck,
                content,
                1,
                new String[]{"tag"},
                Instant.now(),
                null,
                true,
                "checksum"
        );

        PublicCardEntity saved = publicCardRepository.save(card);

        var found = publicCardRepository.findByCardId(saved.getCardId());

        assertThat(found).isPresent();
        assertThat(found.get().getCardId()).isEqualTo(saved.getCardId());
    }
}
