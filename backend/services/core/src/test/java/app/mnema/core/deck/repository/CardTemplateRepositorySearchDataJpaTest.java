package app.mnema.core.deck.repository;

import app.mnema.core.deck.domain.entity.CardTemplateEntity;
import app.mnema.core.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CardTemplateRepositorySearchDataJpaTest extends PostgresIntegrationTest {

    @Autowired
    private CardTemplateRepository cardTemplateRepository;

    @Test
    void searchPublicTemplates_matchesByNameOrDescription() {
        UUID ownerId = UUID.randomUUID();

        CardTemplateEntity publicMatch = saveTemplate(ownerId, "Basic French", "Common verbs", true);
        saveTemplate(ownerId, "Advanced Math", "Equations", true);
        saveTemplate(ownerId, "Basic Private", "Private notes", false);

        var result = cardTemplateRepository.searchPublicTemplates("basic", PageRequest.of(0, 10));

        assertThat(result.getContent())
                .hasSize(1)
                .extracting(CardTemplateEntity::getTemplateId)
                .containsExactly(publicMatch.getTemplateId());
    }

    @Test
    void searchUserTemplates_limitsToOwner() {
        UUID ownerId = UUID.randomUUID();
        UUID otherOwnerId = UUID.randomUUID();

        CardTemplateEntity mine = saveTemplate(ownerId, "Private Basics", "My notes", false);
        saveTemplate(otherOwnerId, "Private Basics", "Other notes", false);

        var result = cardTemplateRepository.searchUserTemplates(ownerId, "private", PageRequest.of(0, 10));

        assertThat(result.getContent())
                .hasSize(1)
                .extracting(CardTemplateEntity::getTemplateId)
                .containsExactly(mine.getTemplateId());
    }

    @Test
    void searchUserAndPublicTemplates_returnsBothScopes() {
        UUID ownerId = UUID.randomUUID();

        CardTemplateEntity publicMatch = saveTemplate(UUID.randomUUID(), "Basic Public", "Shared", true);
        CardTemplateEntity mine = saveTemplate(ownerId, "Basic Private", "Personal", false);

        var result = cardTemplateRepository.searchUserAndPublicTemplates(ownerId, "basic", PageRequest.of(0, 10));

        assertThat(result.getContent())
                .extracting(CardTemplateEntity::getTemplateId)
                .contains(publicMatch.getTemplateId(), mine.getTemplateId());
    }

    private CardTemplateEntity saveTemplate(UUID ownerId, String name, String description, boolean isPublic) {
        CardTemplateEntity template = new CardTemplateEntity(
                null,
                ownerId,
                name,
                description,
                isPublic,
                Instant.now(),
                null,
                null,
                null,
                null,
                1
        );

        return cardTemplateRepository.save(template);
    }
}
