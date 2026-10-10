package app.mnema.learning.support;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.item.ItemPublicationCommand;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.storage.StorageTypes.PinOwner;
import app.mnema.learning.storage.StorageTypes.StageBatch;
import app.mnema.learning.storage.StorageTypes.StagedRoot;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Two decks in ONE storage lineage (Share/4, #426), built without the future copy job: a copy is a deck row and a revision 0
 * pinned on the source's published roots (O(1)), and its heads are one INSERT...SELECT from the source's heads, exactly what
 * the Share/3 prototype (LineageScopeSharingIntegrationTest) simulates. Everything afterwards goes through the real
 * services, so an edit on either side is the production code path.
 */
public final class SharedScopeFixture {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A copy of a source deck: its owner and ids. */
    public record Copy(UUID deckId, UUID owner, UUID genesisRevisionId, UUID scope) { }

    /** A published material revision. */
    public record Material(UUID member, UUID revision) { }

    /** An author's deck with one material, and a copy of it owned by another account (nothing edited yet). */
    public record Scenario(UUID author, UUID source, Material material, Copy copy) {
        public UUID copyOwner() { return copy.owner(); }
        public UUID copyDeck() { return copy.deckId(); }
    }

    private final DeckService decks;
    private final ItemService items;
    private final ImmutableStorage storage;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final JsonNode document;

    public SharedScopeFixture(DeckService decks, ItemService items, ImmutableStorage storage, JdbcClient jdbc,
                              PlatformTransactionManager transactions) {
        this.decks = decks;
        this.items = items;
        this.storage = storage;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.document = readDocument();
    }

    /** A native document whose first text is {@code text}. */
    public JsonNode document(String text) {
        ObjectNode copy = (ObjectNode) document.deepCopy();
        ((ObjectNode) copy.path("root").path("content").get(0).path("content").get(0).path("attrs")).put("text", text);
        return copy;
    }

    public UUID deck(UUID owner, String title) {
        return UUID.fromString(decks.create(owner, new DeckCommand(UUID.randomUUID(), title, "Линия"))
                .acknowledgement().path("deck").path("deckId").stringValue(null));
    }

    public Material create(UUID owner, UUID deck, JsonNode value) {
        JsonNode head = decks.read(owner, deck);
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", head.path("revisionId").stringValue(null));
        body.set("document", value.deepCopy());
        JsonNode change = items.publish(owner, deck, Long.parseLong(head.path("rowVersion").stringValue(null)),
                ItemPublicationCommand.readCreate(bytes(body))).acknowledgement().path("changes").get(0);
        return new Material(UUID.fromString(change.path("memberKey").stringValue(null)),
                UUID.fromString(change.path("itemRevisionId").stringValue(null)));
    }

    /** Saves a new revision of a material this deck currently has at {@code expected}; returns the new revision id. */
    public UUID save(UUID owner, UUID deck, UUID member, UUID expected, JsonNode value) {
        JsonNode head = decks.read(owner, deck);
        int ordinal = items.read(owner, deck, member, null).path("ordinal").intValue();
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", head.path("revisionId").stringValue(null))
                .put("expectedItemRevisionId", expected.toString()).put("expectedOrdinal", ordinal);
        body.set("document", value.deepCopy());
        JsonNode change = items.publish(owner, deck, Long.parseLong(head.path("rowVersion").stringValue(null)),
                ItemPublicationCommand.readSave(bytes(body), member)).acknowledgement().path("changes").get(0);
        return UUID.fromString(change.path("itemRevisionId").stringValue(null));
    }

    /** Author deck with one material "one", and its copy for a second account. */
    public Scenario fork() {
        UUID author = UUID.randomUUID();
        UUID source = deck(author, "Источник");
        Material material = create(author, source, document("one"));
        return new Scenario(author, source, material, copy(source, UUID.randomUUID()));
    }

    /** The copy's owner edits the inherited material; returns the new revision. */
    public UUID editCopy(Scenario scenario, String text) {
        return save(scenario.copyOwner(), scenario.copyDeck(), scenario.material().member(), scenario.material().revision(),
                document(text));
    }

    /** The author edits the same material in the source deck, after the fork; returns the new revision. */
    public UUID editSource(Scenario scenario, String text) {
        return save(scenario.author(), scenario.source(), scenario.material().member(), scenario.material().revision(),
                document(text));
    }

    /** The O(1) fork: deck + revision 0 on the source's published roots, heads copied from the source's heads. */
    public Copy copy(UUID source, UUID owner) {
        record Published(UUID scope, UUID revision, UUID membersRoot, UUID exercisesRoot) { }
        Published published = jdbc.sql("""
                SELECT r.reuse_scope_id,r.revision_id,r.members_root_id,r.exercises_root_id
                  FROM app_learning.deck d JOIN app_learning.deck_revision r
                    ON r.deck_id=d.deck_id AND r.revision_id=d.head_revision_id WHERE d.deck_id=:deck
                """).param("deck", source).query((row, ignored) -> new Published(row.getObject(1, UUID.class),
                row.getObject(2, UUID.class), row.getObject(3, UUID.class), row.getObject(4, UUID.class))).single();
        UUID copy = UUID.randomUUID();
        UUID genesis = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            UUID membersPin = durablePin(published.scope(), published.membersRoot(), genesis, owner);
            UUID exercisesPin = durablePin(published.scope(), published.exercisesRoot(), genesis, owner);
            jdbc.sql("INSERT INTO app_learning.deck(deck_id,owner_id,reuse_scope_id,head_revision_id,row_version,created_at) "
                            + "VALUES (:deck,:owner,:scope,:revision,0,clock_timestamp())")
                    .param("deck", copy).param("owner", owner).param("scope", published.scope())
                    .param("revision", genesis).update();
            jdbc.sql("INSERT INTO app_learning.deck_revision(deck_id,revision_id,reuse_scope_id,owner_id,sequence,command_id,"
                            + "title,description,created_at,members_root_id,exercises_root_id,members_pin_id,exercises_pin_id,"
                            + "member_count,exercise_count) SELECT :deck,:revision,reuse_scope_id,:owner,0,:command,title,"
                            + "description,clock_timestamp(),members_root_id,exercises_root_id,:membersPin,:exercisesPin,"
                            + "member_count,exercise_count FROM app_learning.deck_revision WHERE deck_id=:source AND revision_id=:published")
                    .param("deck", copy).param("revision", genesis).param("owner", owner).param("command", UUID.randomUUID())
                    .param("membersPin", membersPin).param("exercisesPin", exercisesPin).param("source", source)
                    .param("published", published.revision()).update();
            jdbc.sql("INSERT INTO app_learning.deck_head_item(deck_id,reuse_scope_id,member_key,revision_id,item_sequence,updated_at) "
                            + "SELECT :copy,reuse_scope_id,member_key,revision_id,item_sequence,clock_timestamp() "
                            + "FROM app_learning.deck_head_item WHERE deck_id=:source")
                    .param("copy", copy).param("source", source).update();
        });
        return new Copy(copy, owner, genesis, published.scope());
    }

    private UUID durablePin(UUID scope, UUID root, UUID revision, UUID owner) {
        StagedRoot staged = storage.stageBatch(new StageBatch(scope, owner, List.of(), List.of(root)), Duration.ofMinutes(5)).getFirst();
        UUID pin = storage.retain(staged, new PinOwner("deck.revision", revision, owner));
        storage.release(scope, staged.stagingPinId());
        return pin;
    }

    private static ByteArrayInputStream bytes(JsonNode value) {
        try { return new ByteArrayInputStream(JSON.writeValueAsBytes(value)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static JsonNode readDocument() {
        try {
            Path root = Path.of("").toAbsolutePath();
            while (!Files.exists(root.resolve("contracts/content/native-v1/valid/mixed.json"))) root = root.getParent();
            return JSON.readTree(Files.readString(root.resolve("contracts/content/native-v1/valid/mixed.json")));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
