package app.mnema.core.deck.repository;

import app.mnema.core.deck.domain.entity.UserDeckEntity;
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
class UserDeckRepositoryDataJpaTest {

    @Autowired
    private UserDeckRepository userDeckRepository;

    @Test
    void findByUserIdAndArchivedFalse_returnsOnlyActiveDecks() {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();

        UserDeckEntity active = new UserDeckEntity();
        active.setUserId(userId);
        active.setArchived(false);
        active.setDisplayName("My active deck");
        active.setAutoUpdate(true);
        active.setCreatedAt(now);
        userDeckRepository.save(active);

        UserDeckEntity archived = new UserDeckEntity();
        archived.setUserId(userId);
        archived.setArchived(true);
        archived.setDisplayName("Archived deck");
        archived.setAutoUpdate(true);
        archived.setCreatedAt(now);
        userDeckRepository.save(archived);

        Page<UserDeckEntity> page = userDeckRepository
                .findByUserIdAndArchivedFalse(userId, PageRequest.of(0, 10));

        assertThat(page.getContent())
                .hasSize(1)
                .allMatch(d -> !d.isArchived())
                .allMatch(d -> d.getUserId().equals(userId));
    }

    @Test
    void findByUserIdAndPublicDeckId_returnsDeckIfExists() {
        UUID userId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();
        Instant now = Instant.now();

        UserDeckEntity deck = new UserDeckEntity();
        deck.setUserId(userId);
        deck.setPublicDeckId(publicDeckId);
        deck.setArchived(false);
        deck.setDisplayName("My active deck");
        deck.setAutoUpdate(true);
        deck.setCreatedAt(now);

        userDeckRepository.save(deck);

        var result = userDeckRepository.findByUserIdAndPublicDeckId(userId, publicDeckId);

        assertThat(result).isPresent();
        assertThat(result.get().getUserId()).isEqualTo(userId);
        assertThat(result.get().getPublicDeckId()).isEqualTo(publicDeckId);
    }

}
