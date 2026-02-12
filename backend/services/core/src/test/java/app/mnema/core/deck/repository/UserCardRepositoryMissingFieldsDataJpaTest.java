package app.mnema.core.deck.repository;

import app.mnema.core.deck.domain.entity.CardTemplateEntity;
import app.mnema.core.deck.domain.entity.CardTemplateVersionEntity;
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
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserCardRepositoryMissingFieldsDataJpaTest extends PostgresIntegrationTest {

    @Autowired
    private UserCardRepository userCardRepository;

    @Autowired
    private UserDeckRepository userDeckRepository;

    @Autowired
    private PublicDeckRepository publicDeckRepository;

    @Autowired
    private PublicCardRepository publicCardRepository;

    @Autowired
    private CardTemplateRepository cardTemplateRepository;

    @Autowired
    private CardTemplateVersionRepository cardTemplateVersionRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void missingFieldQueries_countAndSampleCorrectly() {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();

        UserDeckEntity deck = new UserDeckEntity();
        deck.setUserId(userId);
        deck.setArchived(false);
        deck.setDisplayName("Test deck");
        deck.setAutoUpdate(true);
        deck.setCreatedAt(now);
        deck.setTemplateVersion(1);
        deck.setSubscribedTemplateVersion(1);
        deck = userDeckRepository.save(deck);
        UUID userDeckId = deck.getUserDeckId();

        CardTemplateEntity template = new CardTemplateEntity(
                null,
                userId,
                "Template",
                null,
                false,
                now,
                null,
                objectMapper.createObjectNode(),
                null,
                null,
                1
        );
        template = cardTemplateRepository.save(template);

        CardTemplateVersionEntity version = new CardTemplateVersionEntity(
                template.getTemplateId(),
                1,
                objectMapper.createObjectNode(),
                null,
                null,
                now,
                userId
        );
        cardTemplateVersionRepository.save(version);

        UUID templateId = template.getTemplateId();
        PublicDeckEntity publicDeck = new PublicDeckEntity(
                UUID.randomUUID(),
                1,
                userId,
                "Public deck",
                "desc",
                null,
                templateId,
                1,
                true,
                true,
                LanguageTag.en,
                null,
                now,
                null,
                now,
                null
        );
        publicDeckRepository.save(publicDeck);

        ObjectNode publicContent = objectMapper.createObjectNode();
        publicContent.put("front", "Public front");
        publicContent.put("back", "");
        PublicCardEntity publicCard = new PublicCardEntity(
                publicDeck.getDeckId(),
                publicDeck.getVersion(),
                publicDeck,
                publicContent,
                1,
                null,
                now,
                null,
                true,
                "checksum-1"
        );
        publicCardRepository.save(publicCard);

        ObjectNode customContent = objectMapper.createObjectNode();
        customContent.put("front", "Custom front");

        UserCardEntity missingCustom = new UserCardEntity(
                userId,
                userDeckId,
                null,
                true,
                false,
                null,
                null,
                customContent,
                now.minusSeconds(10),
                null
        );

        UserCardEntity missingFromPublic = new UserCardEntity(
                userId,
                userDeckId,
                publicCard.getCardId(),
                false,
                false,
                null,
                null,
                null,
                now.minusSeconds(5),
                null
        );

        UserCardEntity deletedMissing = new UserCardEntity(
                userId,
                userDeckId,
                null,
                true,
                true,
                null,
                null,
                customContent,
                now.minusSeconds(1),
                null
        );

        userCardRepository.save(missingCustom);
        userCardRepository.save(missingFromPublic);
        userCardRepository.save(deletedMissing);

        long count = userCardRepository.countMissingField(userId, userDeckId, "back");
        assertThat(count).isEqualTo(2);

        List<UUID> ids = userCardRepository.findMissingFieldCardIds(userId, userDeckId, "back", 10);
        assertThat(ids).hasSize(2);
        assertThat(ids.get(0)).isEqualTo(missingFromPublic.getUserCardId());
        assertThat(ids.get(1)).isEqualTo(missingCustom.getUserCardId());
    }
}
