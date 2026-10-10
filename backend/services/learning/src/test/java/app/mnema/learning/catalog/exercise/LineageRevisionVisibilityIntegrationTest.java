package app.mnema.learning.catalog.exercise;

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

/** Share/4 (#426): exercise binding reads a revision through its deck (head or journal), never through the revision's origin deck. */
@SpringBootTest
class LineageRevisionVisibilityIntegrationTest extends PostgresIntegrationTest {
    @Autowired private ExerciseRepository repository;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void anExerciseBindsOnlyToTheCurrentHeadOfItsOwnDeck() {
        SharedScopeFixture fixture = new SharedScopeFixture(decks, items, storage, jdbc, transactions);
        Scenario s = fixture.fork();
        UUID member = s.material().member();
        UUID fork = s.material().revision();
        // the inherited head of the copy is bindable by the copy owner; afterwards only the copy's own head is
        assertThat(repository.itemRevision(s.copyOwner(), s.copyDeck(), member, fork)).isPresent();
        UUID copyRevision = fixture.editCopy(s, "copy edit");
        UUID sourceRevision = fixture.editSource(s, "author edit");
        assertThat(repository.itemRevision(s.copyOwner(), s.copyDeck(), member, copyRevision)).isPresent();
        assertThat(repository.itemRevision(s.copyOwner(), s.copyDeck(), member, fork)).isEmpty();
        assertThat(repository.itemRevision(s.copyOwner(), s.copyDeck(), member, sourceRevision)).isEmpty();
        assertThat(repository.itemRevision(s.author(), s.source(), member, sourceRevision)).isPresent();
        assertThat(repository.itemRevision(s.author(), s.source(), member, copyRevision)).isEmpty();
        assertThat(repository.itemRevision(s.author(), s.copyDeck(), member, copyRevision)).isEmpty();
    }
}
