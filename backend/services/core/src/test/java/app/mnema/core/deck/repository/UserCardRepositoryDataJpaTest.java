package app.mnema.core.deck.repository;

import app.mnema.core.deck.domain.entity.UserCardEntity;
import app.mnema.core.deck.domain.entity.UserDeckEntity;
import app.mnema.core.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserCardRepositoryDataJpaTest extends PostgresIntegrationTest {

    @Autowired
    private UserCardRepository userCardRepository;

    @Autowired
    private UserDeckRepository userDeckRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void findByUserDeckIdAndDeletedFalseOrderByCreatedAtAsc_filtersAndSortsCorrectly() {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();

        // сначала создаём user_deck, чтобы пройти FK
        UserDeckEntity deck = new UserDeckEntity();
        deck.setUserId(userId);
        deck.setArchived(false);
        deck.setDisplayName("Test deck");
        deck.setAutoUpdate(true);
        deck.setCreatedAt(now);
        deck.setTemplateVersion(1);
        deck.setSubscribedTemplateVersion(1);

        deck = userDeckRepository.save(deck);
        UUID deckId = deck.getUserDeckId();

        ObjectNode content = objectMapper.createObjectNode();
        content.put("front", "Q");
        content.put("back", "A");

        // active older
        UserCardEntity activeOlder = new UserCardEntity(
                userId,
                deckId,
                null,
                true,
                false,
                "older",
                content,
                now.minusSeconds(60),
                null
        );

        // active newer
        UserCardEntity activeNewer = new UserCardEntity(
                userId,
                deckId,
                null,
                true,
                false,
                "newer",
                content,
                now,
                null
        );

        // deleted
        UserCardEntity deleted = new UserCardEntity(
                userId,
                deckId,
                null,
                true,
                true,
                "deleted",
                content,
                now.minusSeconds(30),
                null
        );

        userCardRepository.save(activeOlder);
        userCardRepository.save(activeNewer);
        userCardRepository.save(deleted);

        Page<UserCardEntity> page = userCardRepository
                .findByUserDeckIdAndDeletedFalseOrderByCreatedAtAsc(
                        deckId,
                        PageRequest.of(0, 10)
                );

        assertThat(page.getContent())
                .hasSize(2)
                .extracting(UserCardEntity::getPersonalNote)
                .containsExactly("older", "newer");
    }
}
