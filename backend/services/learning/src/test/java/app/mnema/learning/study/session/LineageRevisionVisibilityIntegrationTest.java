package app.mnema.learning.study.session;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.SharedScopeFixture;
import app.mnema.learning.support.SharedScopeFixture.Scenario;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Share/4 (#426): the Study snapshot reads a revision through its deck (head or journal), never through the revision's origin deck. */
@SpringBootTest
class LineageRevisionVisibilityIntegrationTest extends PostgresIntegrationTest {
    @Autowired private StudySessionRepository repository;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void aStudyMaterialIsPinnedOnlyToRevisionsVisibleToTheSessionsDeck() {
        SharedScopeFixture fixture = new SharedScopeFixture(decks, items, storage, jdbc, transactions);
        Scenario s = fixture.fork();
        UUID member = s.material().member();
        UUID fork = s.material().revision();
        assertThat(repository.material(s.copyDeck(), member, fork)).isPresent();
        UUID copyRevision = fixture.editCopy(s, "copy edit");
        UUID sourceRevision = fixture.editSource(s, "author edit");
        assertThat(repository.material(s.copyDeck(), member, copyRevision)).isPresent();
        assertThat(repository.material(s.copyDeck(), member, fork)).isPresent();   // the base its own change replaced
        assertThat(repository.material(s.copyDeck(), member, sourceRevision)).isEmpty();
        assertThat(repository.material(s.source(), member, sourceRevision)).isPresent();
        assertThat(repository.material(s.source(), member, fork)).isPresent();
        assertThat(repository.material(s.source(), member, copyRevision)).isEmpty();
        assertThat(repository.material(UUID.randomUUID(), member, fork)).isEmpty();
    }
}
