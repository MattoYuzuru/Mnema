package app.mnema.core.deck.repository;

import app.mnema.core.deck.domain.entity.UserCardEntity;
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
class UserCardRepositoryDataJpaTest {

    @Autowired
    private UserCardRepository userCardRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void findByUserDeckIdAndDeletedFalseAndSuspendedFalseOrderByCreatedAtAsc_filtersAndSortsCorrectly() {
        UUID userId = UUID.randomUUID();
        UUID deckId = UUID.randomUUID();
        Instant now = Instant.now();

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
                null,
                null,
                null,
                0,
                false
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
                null,
                null,
                null,
                0,
                false
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
                null,
                null,
                null,
                0,
                false
        );

        // suspended
        UserCardEntity suspended = new UserCardEntity(
                userId,
                deckId,
                null,
                true,
                false,
                "suspended",
                content,
                now.minusSeconds(10),
                null,
                null,
                null,
                0,
                true
        );

        userCardRepository.save(activeOlder);
        userCardRepository.save(activeNewer);
        userCardRepository.save(deleted);
        userCardRepository.save(suspended);

        Page<UserCardEntity> page = userCardRepository
                .findByUserDeckIdAndDeletedFalseAndSuspendedFalseOrderByCreatedAtAsc(
                        deckId,
                        PageRequest.of(0, 10)
                );

        assertThat(page.getContent())
                .hasSize(2)
                .extracting(UserCardEntity::getPersonalNote)
                .containsExactly("older", "newer");
    }
}
