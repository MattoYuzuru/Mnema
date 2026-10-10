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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static app.mnema.learning.storage.StorageTypes.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scheduled reclamation against a real PostgreSQL: it removes only what no pin and no edge reaches, never what a live deck
 * head, a retained revision or an unexpired staging pin holds, and it stays safe under overlapping passes and a new-root race.
 * The kernel's own lock-order races are in {@link ImmutableStorageIntegrationTest}.
 */
@SpringBootTest(properties = {"learning.storage.orphan-grace=PT0.001S", "learning.storage.lock-timeout=PT2S"})
class StorageGcIntegrationTest extends PostgresIntegrationTest {
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
    private final UUID actor = UUID.randomUUID();

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
        drain(gc(64, 256));
    }

    @Test
    void liveHeadAndRetainedRevisionsSurviveWhileUnpublishedOrphansAreReclaimed() {
        UUID deck = UUID.fromString(decks.create(actor, new DeckCommand(UUID.randomUUID(), "Deck", "GC"))
                .acknowledgement().path("deck").path("deckId").stringValue(null));
        var created = items.publish(actor, deck, 0, publication("createItem", decks.read(actor, deck), null, null, document));
        UUID member = UUID.fromString(created.acknowledgement().path("changes").get(0).path("memberKey").stringValue(null));
        UUID firstRevision = UUID.fromString(created.acknowledgement().path("changes").get(0).path("itemRevisionId").stringValue(null));
        ObjectNode changed = (ObjectNode) document.deepCopy();
        ((ObjectNode) changed.path("root").path("content").get(0).path("content").get(0).path("attrs")).put("text", "Второе издание");
        items.publish(actor, deck, 1, publication("saveItem", decks.read(actor, deck), member, firstRevision, changed));
        UUID scope = jdbc.sql("SELECT reuse_scope_id FROM app_learning.deck WHERE deck_id=:deck").param("deck", deck)
                .query(UUID.class).single();
        long published = count("storage_object", scope);
        assertThat(published).isPositive();

        // An abandoned publication: staged objects whose preparation was released without ever being retained.
        List<UUID> orphans = abandon(scope, 5);
        var passes = new ArrayList<StorageGc.Pass>();
        // A page goes first; its leaf is released to the queue only then, so the chain settles over a few rounds.
        for (int round = 0; round < 3; round++) { readyCandidates(); passes.addAll(drain(gc(8, 16))); }

        assertThat(passes.stream().mapToInt(StorageGc.Pass::deleted).sum()).isGreaterThanOrEqualTo(orphans.size());
        assertThat(passes.stream().mapToInt(StorageGc.Pass::errors).sum()).isZero();
        assertThat(count("storage_object", scope)).isEqualTo(published);
        assertThat(unreachable(scope)).isZero();
        for (UUID orphan : orphans) assertThat(exists(scope, orphan)).isFalse();
        // Both revisions still read byte for byte, and the head is intact.
        assertThat(items.read(actor, deck, member, firstRevision).path("document")).isNotNull();
        assertThat(items.read(actor, deck, member, null).path("document").toString()).contains("Второе издание");
    }

    @Test
    void anUnexpiredStagingPinProtectsItsRootAndLapsedOnesAreReclaimedWithDescendants() {
        UUID scope = UUID.randomUUID();
        NewObject leaf = leaf(scope);
        NewObject page = page(scope, leaf);
        stage(scope, List.of(leaf, page), page.objectId(), Duration.ofMinutes(5));
        readyCandidates();

        var protectedRun = drain(gc(8, 16));

        assertThat(protectedRun.stream().mapToInt(StorageGc.Pass::deleted).sum()).isZero();
        assertThat(count("storage_object", scope)).isEqualTo(2);
        assertThat(count("storage_pin", scope)).isOne();

        jdbc.sql("UPDATE app_learning.storage_pin SET expires_at=clock_timestamp()-interval '1 second',row_version=row_version+1 WHERE reuse_scope_id=:scope")
                .param("scope", scope).update();
        var reclaimed = new ArrayList<StorageGc.Pass>();
        for (int i = 0; i < 4; i++) { readyCandidates(); reclaimed.addAll(drain(gc(8, 16))); }

        assertThat(reclaimed.stream().mapToInt(StorageGc.Pass::stagingExpired).sum()).isOne();
        assertThat(count("storage_object", scope)).isZero();
        assertThat(count("storage_pin", scope)).isZero();
        assertThat(count("storage_gc_candidate", scope)).isZero();
    }

    @Test
    void aDurablePinKeepsItsWholeGraphAndReleasingItMakesTheGraphReclaimable() {
        UUID scope = UUID.randomUUID();
        NewObject leaf = leaf(scope);
        NewObject page = page(scope, leaf);
        StagedRoot staged = stage(scope, List.of(leaf, page), page.objectId(), Duration.ofMinutes(5));
        UUID durable = tx.execute(status -> storage.retain(staged, new PinOwner("deck.revision", UUID.randomUUID(), actor)));
        tx.executeWithoutResult(status -> storage.release(scope, staged.stagingPinId()));
        readyCandidates();

        drain(gc(8, 16));
        assertThat(count("storage_object", scope)).isEqualTo(2);

        tx.executeWithoutResult(status -> storage.release(scope, durable));
        for (int i = 0; i < 3; i++) { readyCandidates(); drain(gc(8, 16)); }
        assertThat(count("storage_object", scope)).isZero();
    }

    @Test
    void passesAreBoundedAndEveryScopeIsServedRoundRobin() {
        List<UUID> scopes = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        scopes.forEach(scope -> abandon(scope, 20));
        readyCandidates();
        StorageGc bounded = gc(2, 1);

        var first = bounded.runOnce();
        assertThat(first.scopes()).isEqualTo(2);
        assertThat(first.inspected()).isLessThanOrEqualTo(16);
        assertThat(first.deleted()).isPositive().isLessThanOrEqualTo(16);
        assertThat(scopes.stream().filter(scope -> count("storage_object", scope) == 40).count()).isOne();

        var second = bounded.runOnce();
        assertThat(second.scopes()).isEqualTo(2);
        assertThat(scopes.stream().filter(scope -> count("storage_object", scope) == 40).count()).isZero();

        for (int i = 0; i < 30; i++) { readyCandidates(); bounded.runOnce(); }
        scopes.forEach(scope -> assertThat(count("storage_object", scope)).isZero());
    }

    @Test
    void overlappingPassesTakeDisjointCandidatesAndNeverCountAnObjectTwice() throws Exception {
        UUID scope = UUID.randomUUID();
        abandon(scope, 60);
        readyCandidates();
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var one = executor.submit(() -> { start.await(); return drain(gc(8, 16)); });
            var two = executor.submit(() -> { start.await(); return drain(gc(8, 16)); });
            start.countDown();
            List<StorageGc.Pass> passes = new ArrayList<>(one.get(60, TimeUnit.SECONDS));
            passes.addAll(two.get(60, TimeUnit.SECONDS));
            assertThat(passes.stream().mapToInt(StorageGc.Pass::errors).sum()).isZero();
            // Leaves and pages were staged as one chain per abandon(): each object is deleted by exactly one pass, a parent before its child.
            long remaining = count("storage_object", scope);
            for (int i = 0; remaining > 0 && i < 4; i++) { readyCandidates(); drain(gc(8, 16)); remaining = count("storage_object", scope); }
            assertThat(remaining).isZero();
            assertThat(passes.stream().mapToInt(StorageGc.Pass::deleted).sum()).isLessThanOrEqualTo(120);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void aRootReusedWhileItsObjectIsBeingCollectedEitherWinsIntactOrFailsWithoutADanglingPin() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 25; round++) {
                UUID scope = UUID.randomUUID();
                NewObject shared = leaf(scope);
                StagedRoot first = stage(scope, List.of(shared), shared.objectId(), Duration.ofMinutes(5));
                tx.executeWithoutResult(status -> storage.release(scope, first.stagingPinId()));
                readyCandidates();
                CountDownLatch start = new CountDownLatch(1);
                var collector = executor.submit(() -> { start.await(); return gc(8, 16).runOnce(); });
                var publisher = executor.submit(() -> {
                    start.await();
                    try {
                        StagedRoot again = stage(scope, List.of(shared), shared.objectId(), Duration.ofMinutes(5));
                        return tx.execute(status -> storage.retain(again, new PinOwner("deck.revision", UUID.randomUUID(), actor)));
                    } catch (StorageFailure expected) {
                        return null;
                    }
                });
                start.countDown();
                var pass = collector.get(30, TimeUnit.SECONDS);
                UUID durable = publisher.get(30, TimeUnit.SECONDS);
                assertThat(pass.errors()).isZero();
                if (durable != null) {
                    assertThat(exists(scope, shared.objectId())).isTrue();
                    assertThat(jdbc.sql("SELECT count(*) FROM app_learning.storage_pin WHERE reuse_scope_id=:scope AND pin_id=:pin AND pin_kind='durable'")
                            .param("scope", scope).param("pin", durable).query(Long.class).single()).isOne();
                }
                assertThat(jdbc.sql("""
                        SELECT count(*) FROM app_learning.storage_pin p WHERE p.reuse_scope_id=:scope AND NOT EXISTS (
                            SELECT 1 FROM app_learning.storage_object o WHERE o.reuse_scope_id=p.reuse_scope_id AND o.object_id=p.root_id)
                        """).param("scope", scope).query(Long.class).single()).isZero();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private StorageGc gc(int scopes, int batches) {
        return new StorageGc(storage, repository, new StorageGcSettings(true, Duration.ZERO, scopes, batches, Duration.ofSeconds(30)),
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

    private List<UUID> abandon(UUID scope, int chains) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < chains; i++) {
            NewObject leaf = leaf(scope);
            NewObject page = page(scope, leaf);
            StagedRoot staged = stage(scope, List.of(leaf, page), page.objectId(), Duration.ofMinutes(1));
            tx.executeWithoutResult(status -> storage.release(scope, staged.stagingPinId()));
            ids.add(leaf.objectId());
            ids.add(page.objectId());
        }
        return ids;
    }

    private NewObject leaf(UUID scope) {
        return new NewObject(UUID.randomUUID(), ObjectKind.BLOCK, (short) 1, (short) 0,
                mapper.createObjectNode().put("text", UUID.randomUUID().toString()), List.of());
    }

    private NewObject page(UUID scope, NewObject child) {
        return new NewObject(UUID.randomUUID(), ObjectKind.PAGE, (short) 1, (short) 1, mapper.createObjectNode(),
                List.of(new NewEdge(0, UUID.randomUUID(), new ObjectRef(scope, child.objectId()))));
    }

    private StagedRoot stage(UUID scope, List<NewObject> objects, UUID root, Duration lease) {
        return storage.stageBatch(new StageBatch(scope, actor, objects, List.of(root)), lease).getFirst();
    }

    private long count(String table, UUID scope) {
        return jdbc.sql("SELECT count(*) FROM app_learning." + table + " WHERE reuse_scope_id=:scope").param("scope", scope)
                .query(Long.class).single();
    }

    private boolean exists(UUID scope, UUID object) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_learning.storage_object WHERE reuse_scope_id=:scope AND object_id=:id)")
                .param("scope", scope).param("id", object).query(Boolean.class).single();
    }

    /** Objects of the scope that no pin reaches through the edge graph: after a settled reclamation there must be none. */
    private long unreachable(UUID scope) {
        return jdbc.sql("""
                WITH RECURSIVE reach(id) AS (
                    SELECT root_id FROM app_learning.storage_pin WHERE reuse_scope_id=:scope
                    UNION
                    SELECT e.child_id FROM app_learning.storage_edge e JOIN reach r ON e.parent_id=r.id WHERE e.reuse_scope_id=:scope)
                SELECT count(*) FROM app_learning.storage_object WHERE reuse_scope_id=:scope AND object_id NOT IN (SELECT id FROM reach)
                """).param("scope", scope).query(Long.class).single();
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
