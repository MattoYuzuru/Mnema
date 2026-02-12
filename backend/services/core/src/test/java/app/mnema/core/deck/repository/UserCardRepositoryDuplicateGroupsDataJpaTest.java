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
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserCardRepositoryDuplicateGroupsDataJpaTest extends PostgresIntegrationTest {

    @Autowired
    private UserCardRepository userCardRepository;

    @Autowired
    private UserDeckRepository userDeckRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void findDuplicateGroups_groupsNormalizedFieldValues() {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();

        UserDeckEntity deck = new UserDeckEntity();
        deck.setUserId(userId);
        deck.setArchived(false);
        deck.setDisplayName("Dup deck");
        deck.setAutoUpdate(true);
        deck.setCreatedAt(now);
        deck.setTemplateVersion(1);
        deck.setSubscribedTemplateVersion(1);
        deck = userDeckRepository.save(deck);
        UUID deckId = deck.getUserDeckId();

        ObjectNode content1 = objectMapper.createObjectNode();
        content1.put("front", "Hello!");
        content1.put("back", "World");

        ObjectNode content2 = objectMapper.createObjectNode();
        content2.put("front", "hello");
        content2.put("back", "world");

        UserCardEntity card1 = new UserCardEntity(
                userId,
                deckId,
                null,
                true,
                false,
                null,
                null,
                content1,
                now.minusSeconds(10),
                null
        );

        UserCardEntity card2 = new UserCardEntity(
                userId,
                deckId,
                null,
                true,
                false,
                null,
                null,
                content2,
                now.minusSeconds(5),
                null
        );

        userCardRepository.save(card1);
        userCardRepository.save(card2);

        List<UserCardRepository.DuplicateGroupProjection> groups = userCardRepository.findDuplicateGroups(
                userId,
                deckId,
                new String[]{"front", "back"},
                10
        );

        assertThat(groups).hasSize(1);
        UserCardRepository.DuplicateGroupProjection group = groups.getFirst();
        assertThat(group.getCnt()).isEqualTo(2);
        assertThat(group.getCardIds()).contains(card1.getUserCardId(), card2.getUserCardId());
    }
}
