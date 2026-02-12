package app.mnema.core.deck.repository;

import app.mnema.core.deck.domain.entity.PublicDeckEntity;
import app.mnema.core.deck.domain.type.LanguageTag;
import app.mnema.core.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
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
class PublicDeckRepositoryDataJpaTest extends PostgresIntegrationTest {

    @Autowired
    private PublicDeckRepository publicDeckRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        // Ensure deterministic catalog contents even if the database already has data
        jdbcTemplate.execute("TRUNCATE TABLE app_core.public_cards CASCADE");
        jdbcTemplate.execute("TRUNCATE TABLE app_core.public_decks CASCADE");
    }

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
            ensureTemplateVersion(existing);
            return existing;
        }

        UUID id = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        String name = "Test template";

        // В схеме card_templates колонка "name" NOT NULL,
        // поэтому обязательно задаём её при вставке.
        jdbcTemplate.update(
                "insert into card_templates (template_id, owner_id, name) values (?, ?, ?)",
                id, ownerId, name
        );
        ensureTemplateVersion(id);

        return id;
    }

    private void ensureTemplateVersion(UUID templateId) {
        UUID ownerId = jdbcTemplate.queryForObject(
                "select owner_id from card_templates where template_id = ?",
                UUID.class,
                templateId
        );
        jdbcTemplate.update(
                "insert into card_template_versions (template_id, version, created_by) values (?, 1, ?) on conflict do nothing",
                templateId,
                ownerId
        );
    }

    @Test
    void findByDeckIdAndVersion_returnsExactVersion() {
        UUID author = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();
        UUID templateId = anyTemplateId();

        PublicDeckEntity v1 = new PublicDeckEntity(
                publicDeckId,
                1,
                author,
                "Deck v1",
                "Desc v1",
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
        PublicDeckEntity savedV1 = publicDeckRepository.save(v1);

        PublicDeckEntity v2 = new PublicDeckEntity(
                publicDeckId,
                2,
                author,
                "Deck v2",
                "Desc v2",
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
        // на всякий случай синхронизируем deckId вручную, если JPA что-то прокрутит
        try {
            var deckIdField = PublicDeckEntity.class.getDeclaredField("deckId");
            deckIdField.setAccessible(true);
            deckIdField.set(v2, savedV1.getDeckId());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        publicDeckRepository.save(v2);

        var found = publicDeckRepository
                .findByDeckIdAndVersion(savedV1.getDeckId(), 2);

        assertThat(found).isPresent();
        assertThat(found.get().getVersion()).isEqualTo(2);
        assertThat(found.get().getName()).isEqualTo("Deck v2");
    }

    @Test
    void findTopByDeckIdOrderByVersionDesc_returnsLatestVersion() {
        UUID author = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();
        UUID templateId = anyTemplateId();

        PublicDeckEntity v1 = new PublicDeckEntity(
                publicDeckId,
                1,
                author,
                "Deck v1",
                "Desc v1",
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
        PublicDeckEntity savedV1 = publicDeckRepository.save(v1);

        PublicDeckEntity v2 = new PublicDeckEntity(
                publicDeckId,
                2,
                author,
                "Deck v2",
                "Desc v2",
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
        try {
            var deckIdField = PublicDeckEntity.class.getDeclaredField("deckId");
            deckIdField.setAccessible(true);
            deckIdField.set(v2, savedV1.getDeckId());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        publicDeckRepository.save(v2);

        var latest = publicDeckRepository
                .findTopByDeckIdOrderByVersionDesc(savedV1.getDeckId());

        assertThat(latest).isPresent();
        assertThat(latest.get().getVersion()).isEqualTo(2);
        assertThat(latest.get().getName()).isEqualTo("Deck v2");
    }

    @Test
    void findLatestPublicVisibleDecks_returnsOnlyPublicAndListedDecks_latestVersions() {
        UUID author = UUID.randomUUID();
        UUID templateId = anyTemplateId();

        UUID publicListedDeckId = UUID.randomUUID();
        UUID notPublicDeckId = UUID.randomUUID();
        UUID notListedDeckId = UUID.randomUUID();

        PublicDeckEntity publicAndListed = new PublicDeckEntity(
                publicListedDeckId,
                1,
                author,
                "Public listed",
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

        PublicDeckEntity notPublic = new PublicDeckEntity(
                notPublicDeckId,
                1,
                author,
                "Not public",
                "Desc",
                null,
                templateId,
                false,
                true,
                LanguageTag.en,
                new String[]{"tag"},
                Instant.now(),
                null,
                null,
                null
        );

        PublicDeckEntity notListed = new PublicDeckEntity(
                notListedDeckId,
                1,
                author,
                "Not listed",
                "Desc",
                null,
                templateId,
                true,
                false,
                LanguageTag.en,
                new String[]{"tag"},
                Instant.now(),
                null,
                null,
                null
        );

        publicDeckRepository.saveAll(List.of(publicAndListed, notPublic, notListed));

        Page<PublicDeckEntity> page = publicDeckRepository
                .findLatestPublicVisibleDecks(PageRequest.of(0, 10));

        assertThat(page.getContent())
                .hasSize(1)
                .allMatch(d -> d.isPublicFlag() && d.isListed())
                .allMatch(d -> d.getDeckId().equals(publicListedDeckId))
                .allMatch(d -> d.getName().equals("Public listed"));
    }
}
