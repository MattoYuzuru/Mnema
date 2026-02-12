package app.mnema.core.deck.repository;

import app.mnema.core.deck.domain.entity.PublicDeckEntity;
import app.mnema.core.deck.domain.entity.UserDeckEntity;
import app.mnema.core.deck.domain.type.LanguageTag;
import app.mnema.core.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserDeckRepositorySearchDataJpaTest extends PostgresIntegrationTest {

    @Autowired
    private UserDeckRepository userDeckRepository;

    @Autowired
    private PublicDeckRepository publicDeckRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void searchUserDecks_matchesDisplayText() {
        UUID userId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();
        UUID archivedPublicDeckId = UUID.randomUUID();
        Instant now = Instant.now();

        createPublicDeck(publicDeckId, new String[]{"verbs", "spanish"});
        createPublicDeck(archivedPublicDeckId, new String[]{"verbs", "spanish"});

        UserDeckEntity deck = new UserDeckEntity();
        deck.setUserId(userId);
        deck.setPublicDeckId(publicDeckId);
        deck.setSubscribedVersion(1);
        deck.setCurrentVersion(1);
        deck.setTemplateVersion(1);
        deck.setSubscribedTemplateVersion(1);
        deck.setAutoUpdate(true);
        deck.setDisplayName("Spanish verbs");
        deck.setDisplayDescription("Basics for beginners");
        deck.setCreatedAt(now);
        deck.setArchived(false);
        userDeckRepository.save(deck);

        UserDeckEntity archived = new UserDeckEntity();
        archived.setUserId(userId);
        archived.setPublicDeckId(archivedPublicDeckId);
        archived.setSubscribedVersion(1);
        archived.setCurrentVersion(1);
        archived.setTemplateVersion(1);
        archived.setSubscribedTemplateVersion(1);
        archived.setAutoUpdate(true);
        archived.setDisplayName("Spanish verbs archived");
        archived.setCreatedAt(now);
        archived.setArchived(true);
        userDeckRepository.save(archived);

        var result = userDeckRepository.searchUserDecks(userId, "spanish", null, PageRequest.of(0, 10));

        assertThat(result.getContent())
                .hasSize(1)
                .allMatch(found -> !found.isArchived())
                .allMatch(found -> found.getDisplayName().toLowerCase().contains("spanish"));
    }

    @Test
    void searchUserDecksByTags_matchesPublicDeckTags() {
        UUID userId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();
        Instant now = Instant.now();

        createPublicDeck(publicDeckId, new String[]{"verbs", "latin"});

        UserDeckEntity deck = new UserDeckEntity();
        deck.setUserId(userId);
        deck.setPublicDeckId(publicDeckId);
        deck.setSubscribedVersion(1);
        deck.setCurrentVersion(1);
        deck.setTemplateVersion(1);
        deck.setSubscribedTemplateVersion(1);
        deck.setAutoUpdate(true);
        deck.setDisplayName("Latin verbs");
        deck.setCreatedAt(now);
        deck.setArchived(false);
        userDeckRepository.save(deck);

        var result = userDeckRepository.searchUserDecksByTags(userId, "verbs", PageRequest.of(0, 10));

        assertThat(result.getContent())
                .hasSize(1)
                .allMatch(found -> found.getUserId().equals(userId));
    }

    private void createPublicDeck(UUID deckId, String[] tags) {
        UUID templateId = anyTemplateId();

        PublicDeckEntity deck = new PublicDeckEntity(
                deckId,
                1,
                UUID.randomUUID(),
                "Public deck",
                "Desc",
                null,
                templateId,
                true,
                true,
                LanguageTag.en,
                tags,
                Instant.now(),
                null,
                null,
                null
        );

        publicDeckRepository.save(deck);
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
            ensureTemplateVersion(existing);
            return existing;
        }

        UUID id = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        String name = "Test template";

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
}
