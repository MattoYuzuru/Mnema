package app.mnema.core.deck.integration;

import app.mnema.core.deck.domain.dto.PublicDeckDTO;
import app.mnema.core.deck.domain.dto.UserCardDTO;
import app.mnema.core.deck.domain.dto.UserDeckDTO;
import app.mnema.core.deck.domain.request.CreateCardRequest;
import app.mnema.core.deck.domain.type.LanguageTag;
import app.mnema.core.deck.domain.type.SrAlgorithm;
import app.mnema.core.deck.repository.PublicDeckRepository;
import app.mnema.core.deck.repository.UserCardRepository;
import app.mnema.core.deck.repository.UserDeckRepository;
import app.mnema.core.deck.service.CardService;
import app.mnema.core.deck.service.DeckService;
import app.mnema.core.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional
class DeckFlowIT extends PostgresIntegrationTest {

    @Autowired
    DeckService deckService;

    @Autowired
    CardService cardService;

    @Autowired
    UserDeckRepository userDeckRepository;

    @Autowired
    UserCardRepository userCardRepository;

    @Autowired
    PublicDeckRepository publicDeckRepository;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

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
            // если таблицы ещё нет или другая ошибка – создадим запись ниже
        }
        if (existing != null) {
            return existing;
        }

        UUID id = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        String name = "Integration template";

        jdbcTemplate.update(
                "insert into card_templates (template_id, owner_id, name) values (?, ?, ?)",
                id, ownerId, name
        );

        return id;
    }

    /**
     * Гарантирует, что в sr_algorithms есть запись для SrAlgorithm.sm2.
     * Нужна из-за FK user_decks.algorithm_id -> sr_algorithms.algorithm_id.
     */
    private void ensureSm2AlgorithmExists() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "select count(*) from sr_algorithms where algorithm_id = ?",
                    Integer.class,
                    SrAlgorithm.sm2.name()
            );
            if (count != null && count > 0) {
                return;
            }
        } catch (DataAccessException ignored) {
            // если таблицы нет или другая ошибка — тест всё равно упадёт раньше,
            // но для нормального случая sr_algorithms уже создан миграциями
        }

        jdbcTemplate.update(
                "insert into sr_algorithms (algorithm_id, name) values (?, ?)",
                SrAlgorithm.sm2.name(),
                "SM-2 default"
        );
    }

    @Test
    @WithMockUser(authorities = {"SCOPE_user.read", "SCOPE_user.write"})
    void createDeckAndAddCard_persistsPublicAndUserState() {
        // обеспечиваем наличие необходимых справочников
        ensureSm2AlgorithmExists();
        UUID userId = UUID.randomUUID();
        UUID templateId = anyTemplateId();

        // 1. Создаём публичную + пользовательскую деку через сервис
        PublicDeckDTO deckRequest = new PublicDeckDTO(
                null,                 // deckId (генерится)
                null,                 // version (будет 1)
                null,                 // authorId (в сервисе равно currentUserId)
                "Integration deck",
                "Integration description",
                null,
                templateId,           // валидный FK в card_templates
                true,                 // isPublic
                true,                 // isListed
                LanguageTag.en,
                new String[]{"integration"},
                null,                 // createdAt
                null,                 // updatedAt
                null,                 // publishedAt
                null                  // forkedFromDeck
        );

        UserDeckDTO createdDeck = deckService.createNewDeck(userId, deckRequest);

        // Проверяем DTO
        assertThat(createdDeck.userDeckId()).isNotNull();
        assertThat(createdDeck.userId()).isEqualTo(userId);
        assertThat(createdDeck.publicDeckId()).isNotNull();
        assertThat(createdDeck.algorithmId()).isEqualTo(SrAlgorithm.sm2.name());
        assertThat(createdDeck.autoUpdate()).isTrue();

        // Проверяем, что user_deck сохранён
        var userDeckFromDb = userDeckRepository
                .findById(createdDeck.userDeckId())
                .orElseThrow();
        assertThat(userDeckFromDb.getUserId()).isEqualTo(userId);
        assertThat(userDeckFromDb.getPublicDeckId()).isEqualTo(createdDeck.publicDeckId());

        // Проверяем, что public_deck сохранён и привязан к тому же автору и шаблону
        var publicDeckFromDb = publicDeckRepository
                .findByDeckIdAndVersion(createdDeck.publicDeckId(), createdDeck.currentVersion())
                .orElseThrow();
        assertThat(publicDeckFromDb.getAuthorId()).isEqualTo(userId);
        assertThat(publicDeckFromDb.getTemplateId()).isEqualTo(templateId);

        // 2. Добавляем карту в эту колоду
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

        UserCardDTO createdCard = cardService.addNewCardToDeck(
                userId,
                createdDeck.userDeckId(),
                cardRequest
        );

        assertThat(createdCard.userCardId()).isNotNull();
        assertThat(createdCard.isDeleted()).isFalse();
        assertThat(createdCard.personalNote()).isEqualTo("note");

        // Проверяем, что карта реально лежит в БД
        var storedCard = userCardRepository
                .findById(createdCard.userCardId())
                .orElseThrow();
        assertThat(storedCard.getUserId()).isEqualTo(userId);
        assertThat(storedCard.getUserDeckId()).isEqualTo(createdDeck.userDeckId());

        // 3. Через сервис достаём пагинированный список карт и убеждаемся, что она там
        var page = cardService.getUserCardsByDeck(userId, createdDeck.userDeckId(), 1, 50);
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().getFirst().userCardId()).isEqualTo(createdCard.userCardId());
        assertThat(page.getContent().getFirst().personalNote()).isEqualTo("note");
    }
}
