package app.mnema.core.deck.repository;

import app.mnema.core.deck.domain.entity.PublicDeckEntity;
import app.mnema.core.deck.domain.type.LanguageTag;
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
class PublicDeckRepositoryDataJpaTest {

    @Autowired
    private PublicDeckRepository publicDeckRepository;

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

    @Test
    void findByAuthorId_returnsOnlyDecksForAuthor() {
        UUID author1 = UUID.randomUUID();
        UUID author2 = UUID.randomUUID();
        UUID templateId = anyTemplateId();

        PublicDeckEntity deck1 = new PublicDeckEntity(
                1,
                author1,
                "Deck 1",
                "Desc 1",
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

        PublicDeckEntity deck2 = new PublicDeckEntity(
                1,
                author2,
                "Deck 2",
                "Desc 2",
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

        publicDeckRepository.saveAll(List.of(deck1, deck2));

        List<PublicDeckEntity> result = publicDeckRepository.findByAuthorId(author1);

        assertThat(result)
                .hasSize(1)
                .allMatch(d -> d.getAuthorId().equals(author1));
    }

    @Test
    void findByDeckIdAndVersion_returnsExactVersion() {
        UUID author = UUID.randomUUID();
        UUID templateId = anyTemplateId();

        PublicDeckEntity v1 = new PublicDeckEntity(
                1,
                author,
                "Deck v1",
                "Desc v1",
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
                2,
                author,
                "Deck v2",
                "Desc v2",
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
        // та же deck_id, другая версия
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
        UUID templateId = anyTemplateId();

        PublicDeckEntity v1 = new PublicDeckEntity(
                1,
                author,
                "Deck v1",
                "Desc v1",
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
                2,
                author,
                "Deck v2",
                "Desc v2",
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
    void findByPublicFlagTrueAndListedTrue_returnsOnlyPublicAndListedDecks() {
        UUID author = UUID.randomUUID();
        UUID templateId = anyTemplateId();

        PublicDeckEntity publicAndListed = new PublicDeckEntity(
                1,
                author,
                "Public listed",
                "Desc",
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
                1,
                author,
                "Not public",
                "Desc",
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
                1,
                author,
                "Not listed",
                "Desc",
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
                .findByPublicFlagTrueAndListedTrue(PageRequest.of(0, 10));

        assertThat(page.getContent())
                .hasSize(1)
                .allMatch(d -> d.isPublicFlag() && d.isListed());
    }
}
