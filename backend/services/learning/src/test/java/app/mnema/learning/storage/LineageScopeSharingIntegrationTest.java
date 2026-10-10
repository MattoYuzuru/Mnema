package app.mnema.learning.storage;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.item.ItemPublicationCommand;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.support.PostgresIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static app.mnema.learning.storage.StorageTypes.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Share/3 (#425) prototype evidence for ADR CD-1/CD-2: two decks of one lineage share a reuse scope. A copy is a deck row and a
 * revision pinned on the source's published roots (O(1)); each side then edits by path-copy inside the same scope. The kernel
 * reachability (an incoming edge or any pin of the scope) must keep everything either deck reaches through a source tombstone,
 * while still reclaiming abandoned staging garbage of that scope. (A source's durable pins cannot be released while its revision
 * rows exist: the revision FKs to its pins and roots hold them, so a future purge must remove revisions first.)
 */
@SpringBootTest(properties = {"learning.storage.orphan-grace=PT0.001S", "learning.storage.lock-timeout=PT2S"})
class LineageScopeSharingIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static JsonNode document;

    @Autowired ImmutableStorage storage;
    @Autowired StorageGcRepository repository;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DeckService decks;
    @Autowired ItemService items;
    private TransactionTemplate tx;
    private final UUID author = UUID.randomUUID();
    private final UUID learner = UUID.randomUUID();

    @BeforeAll
    static void fixture() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("contracts/content/native-v1/valid/mixed.json"))) root = root.getParent();
        document = JSON.readTree(Files.readString(root.resolve("contracts/content/native-v1/valid/mixed.json")));
    }

    @BeforeEach
    void drainLeftoversOfOtherTests() {
        tx = new TransactionTemplate(transactions);
        readyCandidates();
        drain(gc());
    }

    @Test
    void aCopyInTheSourceScopeKeepsItsGraphThroughSourceEditsAndTombstone() {
        // The source: two materials, then one more edit after the copy is taken.
        UUID source = UUID.fromString(decks.create(author, new DeckCommand(UUID.randomUUID(), "Источник", "Линия"))
                .acknowledgement().path("deck").path("deckId").stringValue(null));
        var first = items.publish(author, source, 0, publication("createItem", decks.read(author, source), null, null, document));
        items.publish(author, source, 1, publication("createItem", decks.read(author, source), null, null, document));
        UUID member = UUID.fromString(first.acknowledgement().path("changes").get(0).path("memberKey").stringValue(null));
        UUID firstRevision = UUID.fromString(first.acknowledgement().path("changes").get(0).path("itemRevisionId").stringValue(null));
        Revision published = head(source);

        // The copy: O(1) rows in the same scope, its own durable pins on the published roots.
        UUID copy = UUID.randomUUID();
        UUID copyRevision = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            UUID membersPin = durablePin(published.scope(), published.membersRoot(), copyRevision);
            UUID exercisesPin = durablePin(published.scope(), published.exercisesRoot(), copyRevision);
            jdbc.sql("INSERT INTO app_learning.deck(deck_id,owner_id,reuse_scope_id,head_revision_id,row_version,created_at) "
                            + "VALUES (:deck,:owner,:scope,:revision,0,clock_timestamp())")
                    .param("deck", copy).param("owner", learner).param("scope", published.scope())
                    .param("revision", copyRevision).update();
            jdbc.sql("INSERT INTO app_learning.deck_revision(deck_id,revision_id,reuse_scope_id,owner_id,sequence,command_id,"
                            + "title,description,created_at,members_root_id,exercises_root_id,members_pin_id,exercises_pin_id,"
                            + "member_count,exercise_count) SELECT :deck,:revision,reuse_scope_id,:owner,0,:command,title,"
                            + "description,clock_timestamp(),members_root_id,exercises_root_id,:membersPin,:exercisesPin,"
                            + "member_count,exercise_count FROM app_learning.deck_revision WHERE deck_id=:source AND revision_id=:published")
                    .param("deck", copy).param("revision", copyRevision).param("owner", learner)
                    .param("command", UUID.randomUUID()).param("membersPin", membersPin).param("exercisesPin", exercisesPin)
                    .param("source", source).param("published", published.revisionId()).update();
        });
        Set<UUID> copyGraph = reachable(published.scope(), List.of(published.membersRoot(), published.exercisesRoot()));
        assertThat(copyGraph).hasSizeGreaterThan(2);

        // The source edits a shared material (path-copy in the shared scope); the copy keeps the published graph.
        ObjectNode changed = (ObjectNode) document.deepCopy();
        ((ObjectNode) changed.path("root").path("content").get(0).path("content").get(0).path("attrs")).put("text", "Правка автора");
        items.publish(author, source, 2, publication("saveItem", decks.read(author, source), member, firstRevision, changed));
        assertThat(head(source).membersRoot()).isNotEqualTo(published.membersRoot());

        // The copy's own edit: a new page in the same scope that reuses a subtree of the source.
        UUID shared = copyGraph.stream().filter(id -> !id.equals(published.membersRoot()) && !id.equals(published.exercisesRoot()))
                .findFirst().orElseThrow();
        NewObject ownPage = new NewObject(UUID.randomUUID(), ObjectKind.PAGE, (short) 1, (short) (rank(published.scope(), shared) + 1),
                mapper.createObjectNode().put("copyEdit", true),
                List.of(new NewEdge(0, UUID.randomUUID(), new ObjectRef(published.scope(), shared))));
        StagedRoot ownStaged = storage.stageBatch(new StageBatch(published.scope(), learner, List.of(ownPage),
                List.of(ownPage.objectId())), Duration.ofMinutes(5)).getFirst();
        tx.executeWithoutResult(status -> {
            storage.retain(ownStaged, new PinOwner("deck.revision", UUID.randomUUID(), learner));
            storage.release(published.scope(), ownStaged.stagingPinId());
        });

        // Tombstone the source and leave abandoned staging garbage in the scope.
        jdbc.sql("UPDATE app_learning.deck SET deleted_at=clock_timestamp() WHERE deck_id=:deck").param("deck", source).update();
        List<UUID> orphans = abandon(published.scope(), 3);
        List<StorageGc.Pass> passes = new ArrayList<>();
        for (int round = 0; round < 3; round++) { readyCandidates(); passes.addAll(drain(gc())); }

        assertThat(passes.stream().mapToInt(StorageGc.Pass::errors).sum()).isZero();
        for (UUID orphan : orphans) assertThat(exists(published.scope(), orphan)).isFalse();
        for (UUID object : copyGraph) assertThat(exists(published.scope(), object)).isTrue();
        assertThat(exists(published.scope(), ownPage.objectId())).isTrue();
        List<UUID> graph = List.copyOf(copyGraph);
        for (int from = 0; from < graph.size(); from += 64) {
            List<UUID> page = graph.subList(from, Math.min(graph.size(), from + 64));
            assertThat(storage.readBatch(published.scope(), page)).hasSize(page.size());
        }

        assertThat(unreachable(published.scope())).isZero();
    }

    private record Revision(UUID scope, UUID revisionId, UUID membersRoot, UUID exercisesRoot) {
    }

    private Revision head(UUID deck) {
        return jdbc.sql("""
                        SELECT r.reuse_scope_id,r.revision_id,r.members_root_id,r.exercises_root_id
                          FROM app_learning.deck d JOIN app_learning.deck_revision r
                            ON r.deck_id=d.deck_id AND r.revision_id=d.head_revision_id WHERE d.deck_id=:deck
                        """).param("deck", deck)
                .query((row, ignored) -> new Revision(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                        row.getObject(3, UUID.class), row.getObject(4, UUID.class))).single();
    }

    /** The O(1) fork primitive: a durable pin of the copy's revision on an already sealed root of the shared scope. */
    private UUID durablePin(UUID scope, UUID root, UUID revision) {
        StagedRoot staged = storage.stageBatch(new StageBatch(scope, learner, List.of(), List.of(root)), Duration.ofMinutes(5)).getFirst();
        UUID pin = storage.retain(staged, new PinOwner("deck.revision", revision, learner));
        storage.release(scope, staged.stagingPinId());
        return pin;
    }

    private short rank(UUID scope, UUID object) {
        return jdbc.sql("SELECT dag_rank FROM app_learning.storage_object WHERE reuse_scope_id=:scope AND object_id=:id")
                .param("scope", scope).param("id", object).query(Short.class).single();
    }

    private Set<UUID> reachable(UUID scope, List<UUID> roots) {
        return new java.util.HashSet<>(jdbc.sql("""
                        WITH RECURSIVE reach(id) AS (
                            SELECT unnest(CAST(:roots AS uuid[]))
                            UNION
                            SELECT e.child_id FROM app_learning.storage_edge e JOIN reach r ON e.parent_id=r.id WHERE e.reuse_scope_id=:scope)
                        SELECT id FROM reach
                        """).param("scope", scope).param("roots", roots.toArray(UUID[]::new)).query(UUID.class).list());
    }

    private long unreachable(UUID scope) {
        return jdbc.sql("""
                WITH RECURSIVE reach(id) AS (
                    SELECT root_id FROM app_learning.storage_pin WHERE reuse_scope_id=:scope
                    UNION
                    SELECT e.child_id FROM app_learning.storage_edge e JOIN reach r ON e.parent_id=r.id WHERE e.reuse_scope_id=:scope)
                SELECT count(*) FROM app_learning.storage_object WHERE reuse_scope_id=:scope AND object_id NOT IN (SELECT id FROM reach)
                """).param("scope", scope).query(Long.class).single();
    }

    private boolean exists(UUID scope, UUID object) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_learning.storage_object WHERE reuse_scope_id=:scope AND object_id=:id)")
                .param("scope", scope).param("id", object).query(Boolean.class).single();
    }

    private List<UUID> abandon(UUID scope, int chains) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < chains; i++) {
            NewObject leaf = new NewObject(UUID.randomUUID(), ObjectKind.BLOCK, (short) 1, (short) 0,
                    mapper.createObjectNode().put("text", UUID.randomUUID().toString()), List.of());
            NewObject page = new NewObject(UUID.randomUUID(), ObjectKind.PAGE, (short) 1, (short) 1, mapper.createObjectNode(),
                    List.of(new NewEdge(0, UUID.randomUUID(), new ObjectRef(scope, leaf.objectId()))));
            StagedRoot staged = storage.stageBatch(new StageBatch(scope, author, List.of(leaf, page), List.of(page.objectId())),
                    Duration.ofMinutes(1)).getFirst();
            tx.executeWithoutResult(status -> storage.release(scope, staged.stagingPinId()));
            ids.add(leaf.objectId());
            ids.add(page.objectId());
        }
        return ids;
    }

    private StorageGc gc() {
        return new StorageGc(storage, repository, new StorageGcSettings(true, Duration.ZERO, 64, 256, Duration.ofSeconds(30)),
                new SimpleMeterRegistry());
    }

    private List<StorageGc.Pass> drain(StorageGc gc) {
        List<StorageGc.Pass> passes = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            var pass = gc.runOnce();
            if (pass.scopes() == 0) return passes;
            passes.add(pass);
        }
        throw new AssertionError("reclamation did not settle");
    }

    private void readyCandidates() {
        jdbc.sql("UPDATE app_learning.storage_gc_candidate SET not_before=clock_timestamp()-interval '1 second'").update();
    }

    private static ItemPublicationCommand publication(String kind, JsonNode deck, UUID member, UUID revision, JsonNode value) {
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", deck.path("revisionId").stringValue(null));
        body.set("document", value.deepCopy());
        try {
            if (kind.equals("createItem")) return ItemPublicationCommand.readCreate(new ByteArrayInputStream(JSON.writeValueAsBytes(body)));
            body.put("expectedItemRevisionId", revision.toString()).put("expectedOrdinal", 0);
            return ItemPublicationCommand.readSave(new ByteArrayInputStream(JSON.writeValueAsBytes(body)), member);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
