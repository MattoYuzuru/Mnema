package app.mnema.learning.catalog.content.pages;

import app.mnema.learning.catalog.content.pages.CountedPageFixture.Tree;
import app.mnema.learning.library.ManifestDiff;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.storage.StorageFailure;
import app.mnema.learning.storage.StorageTypes.NewObject;
import app.mnema.learning.storage.StorageTypes.StoredObject;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static app.mnema.learning.catalog.content.pages.CountedPageFixture.assertMatches;
import static app.mnema.learning.catalog.content.pages.CountedPageTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The frontier diff over real PostgreSQL storage: a published tree and trees edited from it are stored as sealed immutable objects, then
 * {@link ManifestDiff} (the production adapter) must return exactly what the in-memory diff and a full comparison return, with every level
 * read by {@link ImmutableStorage#readMany} calls of two data statements each. Lives in the pages package to share the in-memory fixture.
 */
@SpringBootTest
class ManifestDiffIntegrationTest extends PostgresIntegrationTest {
    @Autowired ImmutableStorage storage;
    @Autowired ManifestDiff manifestDiff;
    @Autowired JdbcTemplate template;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void tenThousandEntriesAreDiffedWithTheChangedPathsOnly() {
        measure(10_000, "10 000");
    }

    @Test
    void oneHundredThousandEntriesAreDiffedWithTheChangedPathsOnly() {
        // The production ceiling of a manifest: the edits below never grow it.
        measure(100_000, "100 000");
    }

    private void measure(int size, String label) {
        CountedPageFixture fx = new CountedPageFixture();
        long started = System.nanoTime();
        Tree base = fx.new Tree(fx.sharedEntries(size));
        Map<UUID, Short> staged = new HashMap<>();
        int stagedObjects = load(fx, base.root, staged);
        long stagedMillis = (System.nanoTime() - started) / 1_000_000;
        List<String> rows = new ArrayList<>();
        Random random = new Random(440_000L + size);
        Tree edited = base.copy();
        int done = 0;
        for (int k : new int[] {1, 10, 100}) {
            while (done < k) { edited.randomEdit(random, 100_000); done++; }
            load(fx, edited.root, staged);
            Result expected = fx.diff(base.root, edited.root);
            assertMatches(expected, base.model, edited.model);

            Result actual = null;
            double millis = Double.MAX_VALUE;
            for (int run = 0; run < 3; run++) {
                long begin = System.nanoTime();
                actual = manifestDiff.members(fx.scope, base.root.ref().objectId(), base.root.count(),
                        edited.root.ref().objectId(), edited.root.count());
                millis = Math.min(millis, (System.nanoTime() - begin) / 1e6);
            }
            assertThat(actual).isEqualTo(expected);

            // The same walk through a counting source built exactly like the production adapter's: one readMany per 128 pages of a level.
            int[] readMany = {0};
            Result counted = new CountedPageDiff(Profile.members(100_000), (scope, ids) -> {
                Map<UUID, NewObject> found = new HashMap<>();
                List<UUID> all = List.copyOf(ids);
                for (int from = 0; from < all.size(); from += ImmutableStorage.MAX_READ_MANY) {
                    readMany[0]++;
                    for (StoredObject stored : storage.readMany(scope, all.subList(from, Math.min(from + ImmutableStorage.MAX_READ_MANY, all.size())))) {
                        found.put(stored.ref().objectId(), stored.value());
                    }
                }
                return found;
            }).diff(base.root, edited.root);
            assertThat(counted).isEqualTo(expected);
            // Production reads the two roots in one call first and reuses them for the first level.
            int calls = readMany[0];
            rows.add(String.format("| %s | %d | %d | %d | %d | %d | %.1f |", label, k, edited.maxHeight, actual.stats().pagesRead(), calls, 2 * calls, millis));
            assertThat(actual.stats().pagesRead()).isLessThanOrEqualTo(12 * k * (edited.maxHeight + 2));
            // One call per tree level while a level has at most 128 pages, so the statements follow the height, not the size.
            assertThat(calls).isLessThanOrEqualTo(actual.stats().levels() + actual.stats().pagesRead() / ImmutableStorage.MAX_READ_MANY + 1);
            if (k <= 10) assertThat(calls).isLessThanOrEqualTo(edited.maxHeight + 1);
        }
        System.out.println("ManifestDiff PostgreSQL N=" + label + ": loaded " + stagedObjects + " objects of the base tree in " + stagedMillis + " ms");
        System.out.println("| N | edits k | tallest H | pages read | readMany calls | data statements | ms |");
        System.out.println("|---|---|---|---|---|---|---|");
        rows.forEach(System.out::println);
    }

    @Test
    void readManyHasTheSemanticsOfReadBatchAndItsBudgets() {
        CountedPageFixture fx = new CountedPageFixture();
        Tree tree = fx.new Tree(fx.sharedEntries(6_000));
        load(fx, tree.root, new HashMap<>());
        List<UUID> leaves = fx.objects.values().stream().filter(page -> page.dagRank() == 10 && !page.edges().isEmpty())
                .map(NewObject::objectId).toList();
        assertThat(leaves.size()).isGreaterThan(ImmutableStorage.MAX_READ_MANY);
        List<UUID> chunk = leaves.subList(0, ImmutableStorage.MAX_READ_MANY);
        // Full pages (up to 32 edges) times the chunk size stay within the 1 MiB batch budget, and the values are those of the one-by-one read.
        List<StoredObject> many = storage.readMany(fx.scope, chunk);
        assertThat(many).hasSize(chunk.size());
        assertThat(many).extracting(stored -> stored.ref().objectId()).containsExactlyElementsOf(chunk);
        assertThat(many.stream().mapToInt(stored -> stored.value().edges().size()).max().orElseThrow()).isGreaterThanOrEqualTo(31);
        List<UUID> head = chunk.subList(0, 64);
        assertThat(storage.readMany(fx.scope, head)).containsExactlyElementsOf(storage.readBatch(fx.scope, head));
        assertThat(storage.readMany(fx.scope, List.of(chunk.get(1), chunk.get(0))).getFirst().value()).isEqualTo(many.get(1).value());
        assertThat(storage.readMany(fx.scope, List.of())).isEmpty();
        // Rejections are the batch read's: a missing id (or one of another scope), too many ids, duplicates.
        assertThatThrownBy(() -> storage.readMany(fx.scope, List.of(chunk.getFirst(), UUID.randomUUID()))).isInstanceOfSatisfying(StorageFailure.class,
                failure -> assertThat(failure.code()).isEqualTo(StorageFailure.Code.OBJECT_MISSING));
        assertThatThrownBy(() -> storage.readMany(UUID.randomUUID(), chunk.subList(0, 3))).isInstanceOfSatisfying(StorageFailure.class,
                failure -> assertThat(failure.code()).isEqualTo(StorageFailure.Code.OBJECT_MISSING));
        // An object that exists but is not sealed yet (visible only inside its own transaction) is missing for readers, like in readBatch.
        UUID open = UUID.randomUUID();
        UUID rolledBack = new TransactionTemplate(transactions).execute(status -> {
            template.update("""
                    INSERT INTO app_learning.storage_object (reuse_scope_id,object_id,kind,encoding_version,dag_rank,edge_count,payload,fingerprint)
                    VALUES (?,?,'page',1,9,0,'{}'::jsonb,?)
                    """, fx.scope, open, new byte[32]);
            assertThatThrownBy(() -> storage.readMany(fx.scope, List.of(open))).isInstanceOfSatisfying(StorageFailure.class,
                    failure -> assertThat(failure.code()).isEqualTo(StorageFailure.Code.OBJECT_MISSING));
            assertThatThrownBy(() -> storage.readBatch(fx.scope, List.of(open))).isInstanceOfSatisfying(StorageFailure.class,
                    failure -> assertThat(failure.code()).isEqualTo(StorageFailure.Code.OBJECT_MISSING));
            // The unsealed row, a sealed one and a foreign scope's id together: still OBJECT_MISSING, nothing partial.
            assertThatThrownBy(() -> storage.readMany(fx.scope, List.of(chunk.getFirst(), open))).isInstanceOfSatisfying(StorageFailure.class,
                    failure -> assertThat(failure.code()).isEqualTo(StorageFailure.Code.OBJECT_MISSING));
            status.setRollbackOnly();
            return open;
        });
        assertThat(rolledBack).isEqualTo(open);
        // Invalid ids are the same IllegalArgumentException in both reads, before any statement for readMany.
        UUID nil = new UUID(0, 0);
        assertThatThrownBy(() -> storage.readMany(fx.scope, List.of(nil))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.readBatch(fx.scope, List.of(nil))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.readMany(nil, List.of(chunk.getFirst()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.readMany(fx.scope, leaves.subList(0, ImmutableStorage.MAX_READ_MANY + 1))).isInstanceOfSatisfying(StorageFailure.class,
                failure -> assertThat(failure.code()).isEqualTo(StorageFailure.Code.BUDGET_EXCEEDED));
        assertThatThrownBy(() -> storage.readMany(fx.scope, List.of(chunk.getFirst(), chunk.getFirst()))).isInstanceOfSatisfying(StorageFailure.class,
                failure -> assertThat(failure.code()).isEqualTo(StorageFailure.Code.BUDGET_EXCEEDED));
    }

    @Test
    void manifestDiffRejectsTheWrongRoleAndAnswersIdenticalRootsWithoutReading() {
        CountedPageFixture fx = new CountedPageFixture();
        Tree one = fx.new Tree(fx.sharedEntries(400));
        Tree two = one.copy();
        two.insert(7, two.entry());
        Map<UUID, Short> loaded = new HashMap<>();
        load(fx, one.root, loaded);
        load(fx, two.root, loaded);
        UUID a = one.root.ref().objectId();
        UUID b = two.root.ref().objectId();
        assertThatThrownBy(() -> manifestDiff.exercises(fx.scope, a, one.root.count(), b, two.root.count()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(manifestDiff.members(fx.scope, a, one.root.count(), a, one.root.count())).isEqualTo(Result.unchanged());
        // One page cannot have two counts: the same failure as the diff itself, before any read.
        assertThatThrownBy(() -> manifestDiff.members(fx.scope, a, one.root.count(), a, one.root.count() + 1)).isInstanceOfSatisfying(
                CountedPageFailure.class, failure -> assertThat(failure.code()).isEqualTo(CountedPageFailure.Code.INVALID_PAGE));
        assertThat(manifestDiff.members(fx.scope, a, one.root.count(), b, two.root.count()).changes()).hasSize(1);
        // A count that does not match the page is refused like any inconsistent tree.
        assertThatThrownBy(() -> manifestDiff.members(fx.scope, a, one.root.count() + 1, b, two.root.count()))
                .isInstanceOf(CountedPageFailure.class);
        assertThatThrownBy(() -> manifestDiff.members(fx.scope, a, one.root.count(), UUID.randomUUID(), 1))
                .isInstanceOf(StorageFailure.class);
    }

    /**
     * Writes everything reachable from the root that is not stored yet straight into the storage tables, rank by rank (children sealed before
     * the edges that point at them), in one transaction with batched statements. The staging path itself is covered by
     * {@code CountedPagesIntegrationTest}; this fixture only makes a 100 000-entry published tree affordable (staging it takes about a minute).
     */
    private int load(CountedPageFixture fx, TreeRoot root, Map<UUID, Short> loaded) {
        List<NewObject> order = new ArrayList<>();
        collect(fx, root.ref().objectId(), loaded, new HashSet<>(), order);
        Map<Short, List<NewObject>> byRank = new java.util.TreeMap<>();
        order.forEach(object -> byRank.computeIfAbsent(object.dagRank(), rank -> new ArrayList<>()).add(object));
        Map<UUID, Short> rank = loaded;
        order.forEach(object -> rank.put(object.objectId(), object.dagRank()));
        Random bytes = new Random(order.size());
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            for (List<NewObject> level : byRank.values()) {
                template.batchUpdate("""
                        INSERT INTO app_learning.storage_object
                            (reuse_scope_id,object_id,kind,encoding_version,dag_rank,edge_count,payload,fingerprint)
                        VALUES (?,?,?,?,?,?,CAST(? AS jsonb),?)
                        """, level, 500, (statement, object) -> {
                    byte[] fingerprint = new byte[32];
                    bytes.nextBytes(fingerprint);
                    statement.setObject(1, fx.scope);
                    statement.setObject(2, object.objectId());
                    statement.setString(3, object.kind().name().toLowerCase(java.util.Locale.ROOT));
                    statement.setShort(4, object.encodingVersion());
                    statement.setShort(5, object.dagRank());
                    statement.setShort(6, (short) object.edges().size());
                    statement.setString(7, object.payload().toString());
                    statement.setBytes(8, fingerprint);
                });
                List<Object[]> edges = new ArrayList<>();
                for (NewObject object : level) {
                    for (var edge : object.edges()) {
                        edges.add(new Object[] {fx.scope, object.objectId(), edge.ordinal(), object.dagRank(), edge.child().objectId(),
                                rank.get(edge.child().objectId()), edge.logicalKey()});
                    }
                }
                template.batchUpdate("""
                        INSERT INTO app_learning.storage_edge (reuse_scope_id,parent_id,ordinal,parent_rank,child_id,child_rank,logical_key)
                        VALUES (?,?,?,?,?,?,?)
                        """, edges, 500, (statement, values) -> {
                    for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
                });
                template.batchUpdate("UPDATE app_learning.storage_object SET sealed=true WHERE reuse_scope_id=? AND object_id=?", level, 500,
                        (statement, object) -> { statement.setObject(1, fx.scope); statement.setObject(2, object.objectId()); });
            }
        });
        return order.size();
    }

    private static void collect(CountedPageFixture fx, UUID id, Map<UUID, Short> loaded, Set<UUID> seen, List<NewObject> order) {
        if (loaded.containsKey(id) || !seen.add(id)) return;
        NewObject object = fx.objects.get(id);
        object.edges().forEach(edge -> collect(fx, edge.child().objectId(), loaded, seen, order));
        order.add(object);
    }
}
