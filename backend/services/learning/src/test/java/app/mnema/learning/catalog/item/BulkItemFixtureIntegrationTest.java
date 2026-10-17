package app.mnema.learning.catalog.item;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.support.MediaFixtures;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static app.mnema.learning.support.ContractFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class BulkItemFixtureIntegrationTest extends PostgresIntegrationTest {
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;

    private UUID deck(UUID owner) {
        return UUID.fromString(decks.create(owner, new DeckCommand(UUID.randomUUID(), "Fixture", "Parity"))
                .acknowledgement().path("deck").path("deckId").stringValue(null));
    }

    @Test
    void multiPageFixtureReadsLikeAuthoredContentAndRealEditingPreservesItsHistoricalSnapshot() {
        UUID owner = UUID.randomUUID(), deck = deck(owner);
        var documents = IntStream.range(0, 130).mapToObj(index -> MediaFixtures.imageDocument()).toList();
        var created = new BulkItemFixture(storage, jdbc, transactions).append(owner, deck, documents);
        assertThat(created).hasSize(130);
        for (int index : new int[] {0, 31, 32, 63, 64, 129}) {
            var value = created.get(index);
            var read = items.read(owner, deck, value.member(), null);
            var canonical = new app.mnema.learning.platform.json.CanonicalJsonHasher();
            assertThat(canonical.canonicalBytes(read.path("document"))).isEqualTo(canonical.canonicalBytes(documents.get(index)));
            assertThat(read.path("ordinal").intValue()).isEqualTo(index);
        }
        UUID authored = deck(owner);
        var authorHead = decks.read(owner, authored);
        var create = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("commandId", UUID.randomUUID().toString()).put("expectedDeckRevisionId", authorHead.path("revisionId").stringValue(null));
        create.set("document", documents.getFirst());
        var acknowledgement = items.publish(owner, authored, Long.parseLong(authorHead.path("rowVersion").stringValue(null)),
                ItemPublicationCommand.readCreate(bytes(create))).acknowledgement().path("changes").get(0);
        UUID authoredMember = UUID.fromString(acknowledgement.path("memberKey").stringValue(null));
        assertThat(items.read(owner, authored, authoredMember, null).path("document"))
                .isEqualTo(items.read(owner, deck, created.getFirst().member(), null).path("document"));
        var first = created.getFirst();
        var old = items.read(owner, deck, first.member(), first.revision());
        var head = decks.read(owner, deck);
        var body = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", head.path("revisionId").stringValue(null))
                .put("expectedItemRevisionId", first.revision().toString()).put("expectedOrdinal", 0);
        var edited = documents.getFirst().deepCopy();
        ((tools.jackson.databind.node.ObjectNode) edited.path("root").path("content").get(0).path("content").get(0).path("attrs"))
                .put("text", "Edited through the real publication command");
        body.set("document", edited);
        items.publish(owner, deck, Long.parseLong(head.path("rowVersion").stringValue(null)),
                ItemPublicationCommand.readSave(bytes(body), first.member()));
        assertThat(items.read(owner, deck, first.member(), first.revision())).isEqualTo(old);
        assertThat(items.read(owner, deck, first.member(), null).path("itemRevisionId").stringValue(null)).isNotEqualTo(first.revision().toString());
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_item_change WHERE deck_id=:deck AND change_kind='create'")
                .param("deck", deck).query(Integer.class).single()).isEqualTo(130);
        UUID scope = jdbc.sql("SELECT reuse_scope_id FROM app_learning.deck WHERE deck_id=:deck").param("deck", deck).query(UUID.class).single();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.storage_pin WHERE reuse_scope_id=:scope AND pin_kind='staging'")
                .param("scope", scope).query(Integer.class).single()).isZero();
    }

    @Test
    void fixtureRollsBackItsDagHeadsHistoryAndPinsWithTheCallingTransaction() {
        UUID owner = UUID.randomUUID(), deck = deck(owner);
        var before = decks.read(owner, deck);
        var fixture = new BulkItemFixture(storage, jdbc, transactions);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            fixture.append(owner, deck, List.of(MediaFixtures.imageDocument()));
            throw new IllegalStateException("abort fixture");
        })).isInstanceOf(IllegalStateException.class).hasMessage("abort fixture");
        assertThat(decks.read(owner, deck)).isEqualTo(before);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_head_item WHERE deck_id=:deck")
                .param("deck", deck).query(Integer.class).single()).isZero();
    }
}
