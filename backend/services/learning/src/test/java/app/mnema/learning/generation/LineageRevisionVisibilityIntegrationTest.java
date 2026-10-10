package app.mnema.learning.generation;

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

/** Share/4 (#426): generation admission reads a revision through its deck (head or journal), never through the revision's origin deck. */
@SpringBootTest
class LineageRevisionVisibilityIntegrationTest extends PostgresIntegrationTest {
    @Autowired private GenerationRepository repository;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void generationAdmissionKnowsOnlyRevisionsVisibleToTheDeck() {
        SharedScopeFixture fixture = new SharedScopeFixture(decks, items, storage, jdbc, transactions);
        Scenario s = fixture.fork();
        UUID member = s.material().member();
        UUID fork = s.material().revision();
        // a copy's inherited head is known to the copy; the source's later revision and the author's history are not
        assertThat(repository.itemRevisionExists(s.copyOwner(), s.copyDeck(), member, fork)).isTrue();
        UUID copyRevision = fixture.editCopy(s, "copy edit");
        UUID sourceRevision = fixture.editSource(s, "author edit");
        assertThat(repository.itemRevisionExists(s.copyOwner(), s.copyDeck(), member, copyRevision)).isTrue();
        assertThat(repository.itemRevisionExists(s.copyOwner(), s.copyDeck(), member, sourceRevision)).isFalse();
        assertThat(repository.itemRevisionExists(s.copyOwner(), s.copyDeck(), member, fork)).isTrue();   // replaced by its own change
        assertThat(repository.itemRevisionExists(s.author(), s.source(), member, sourceRevision)).isTrue();
        assertThat(repository.itemRevisionExists(s.author(), s.source(), member, fork)).isTrue();
        assertThat(repository.itemRevisionExists(s.author(), s.source(), member, copyRevision)).isFalse();
        // ownership of the deck is part of the answer
        assertThat(repository.itemRevisionExists(s.author(), s.copyDeck(), member, copyRevision)).isFalse();
    }
}
