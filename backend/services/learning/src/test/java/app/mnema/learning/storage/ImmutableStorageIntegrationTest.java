package app.mnema.learning.storage;

import app.mnema.learning.platform.concurrency.CompareAndSetExecutor;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import app.mnema.learning.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static app.mnema.learning.storage.StorageTypes.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"learning.storage.orphan-grace=PT0.001S", "learning.storage.lock-timeout=PT2S"})
class ImmutableStorageIntegrationTest extends PostgresIntegrationTest {
    @Autowired ImmutableStorage storage;
    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager transactions;
    @Autowired CommandReceiptService receipts;
    @Autowired CompareAndSetExecutor cas;
    private final UUID scope = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private TransactionTemplate tx;

    @BeforeEach
    void fixtures() {
        tx = new TransactionTemplate(transactions);
        jdbc.sql("CREATE TABLE IF NOT EXISTS app_learning.storage_publication_fixture (id UUID PRIMARY KEY, version BIGINT NOT NULL, root_id UUID)").update();
        jdbc.sql("CREATE TABLE IF NOT EXISTS app_learning.storage_projection_fixture (id UUID PRIMARY KEY)").update();
        // Explicit complete graph in this disposable test database; never cascade into unknown tables.
        jdbc.sql("TRUNCATE app_learning.study_presentation, app_learning.study_session, "
                + "app_learning.study_candidate, app_learning.study_candidate_generation, "
                + "app_learning.capture_note, app_learning.editing_draft, "
                + "app_learning.exercise_content_binding, app_learning.deck_head_exercise, "
                + "app_learning.deck_exercise_change, app_learning.exercise_revision, "
                + "app_learning.exercise_definition, app_learning.objective_head, "
                + "app_learning.objective_revision, app_learning.memory_objective, app_learning.deck_head_item, "
                + "app_learning.deck_item_change, app_learning.item_revision, "
                + "app_learning.learning_item, app_learning.deck, app_learning.deck_revision, app_learning.storage_edge, "
                + "app_learning.storage_pin, app_learning.storage_gc_candidate, app_learning.storage_object, "
                + "app_learning.storage_publication_fixture, app_learning.storage_projection_fixture, "
                + "app_learning.command_receipt").update();
    }

    @Test
    void postorderGraphRoundTripsAndReusesOnlyCompletelyEqualObjects() {
        NewObject child = leaf("Привет مرحبا 🌿");
        NewObject page = page(child);
        var first = stage(List.of(child, page), page.objectId());
        var second = stage(List.of(child, page), page.objectId());
        assertThat(first.stagingPinId()).isNotEqualTo(second.stagingPinId());
        assertThat(count("storage_object")).isEqualTo(2);
        assertThat(storage.readBatch(scope, List.of(page.objectId(), child.objectId())))
                .extracting(it -> it.value().objectId()).containsExactly(page.objectId(), child.objectId());
        assertThat(storage.readBatch(scope, List.of(child.objectId())).getFirst().value().payload()).isEqualTo(child.payload());
        NewObject changed = new NewObject(child.objectId(), child.kind(), (short) 1, (short) 0,
                mapper.createObjectNode().put("text", "different"), List.of());
        assertThatThrownBy(() -> stage(List.of(changed), child.objectId())).isInstanceOf(StorageFailure.class);
        NewObject otherKey = new NewObject(page.objectId(), ObjectKind.PAGE, (short) 1, (short) 1,
                page.payload(), List.of(new NewEdge(0, UUID.randomUUID(), new ObjectRef(scope, child.objectId()))));
        assertThatThrownBy(() -> stage(List.of(otherKey), page.objectId())).isInstanceOf(StorageFailure.class);
        assertThat(count("storage_pin")).isEqualTo(2);
    }

    @Test
    void mismatchRollsBackEarlierInsertAndPreparedPins() {
        NewObject existing = leaf("original");
        stage(List.of(existing), existing.objectId());
        NewObject fresh = leaf("fresh");
        NewObject changed = new NewObject(existing.objectId(), existing.kind(), (short) 2, (short) 0, existing.payload(), List.of());
        assertThatThrownBy(() -> stage(List.of(fresh, changed), fresh.objectId())).isInstanceOf(StorageFailure.class);
        assertThat(count("storage_object")).isOne();
        assertThat(count("storage_gc_candidate")).isOne();
        assertThat(count("storage_pin")).isOne();
    }

    @Test
    void scopesAndTopologicalOrderAreEnforcedWithoutPartialWrites() {
        NewObject leaf = leaf("leaf");
        NewObject page = page(leaf);
        assertThatThrownBy(() -> stage(List.of(page, leaf), page.objectId())).isInstanceOf(StorageFailure.class);
        assertThat(count("storage_object")).isZero();
        stage(List.of(leaf), leaf.objectId());
        assertThatThrownBy(() -> storage.readBatch(UUID.randomUUID(), List.of(leaf.objectId()))).isInstanceOf(StorageFailure.class);
        assertThatThrownBy(() -> new StageBatch(UUID.randomUUID(), actor, List.of(page), List.of(page.objectId())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exactJsonbTextByteBoundaryAndAggregateBudget() {
        // PostgreSQL prints {"text": "..."}: twelve bytes apart from text.
        NewObject exact = leaf("я".repeat(8186));
        stage(List.of(exact), exact.objectId());
        assertThat(jdbc.sql("SELECT octet_length(payload::text) FROM app_learning.storage_object").query(Integer.class).single()).isEqualTo(16384);
        NewObject over = leaf("я".repeat(8186) + "a");
        assertThatThrownBy(() -> stage(List.of(over), over.objectId())).isInstanceOf(IllegalArgumentException.class);
        List<NewObject> batch = IntStream.range(0, 64).mapToObj(i -> leaf("x".repeat(16372))).toList();
        assertThatThrownBy(() -> stage(batch, batch.getFirst().objectId())).isInstanceOf(StorageFailure.class);
        assertThat(count("storage_object")).isOne();
        assertThatThrownBy(() -> storage.readBatch(scope, IntStream.range(0, 65).mapToObj(i -> UUID.randomUUID()).toList()))
                .isInstanceOf(StorageFailure.class);
    }

    @Test
    void readAggregateBudgetAppliesAcrossSeparatelyStagedObjects() {
        List<NewObject> batch = IntStream.range(0, 64).mapToObj(i -> leaf("x".repeat(16372))).toList();
        batch.forEach(value -> stage(List.of(value), value.objectId()));
        assertThatThrownBy(() -> storage.readBatch(scope, batch.stream().map(NewObject::objectId).toList()))
                .isInstanceOf(StorageFailure.class);
        assertThat(storage.readBatch(scope, batch.subList(0, 63).stream().map(NewObject::objectId).toList())).hasSize(63);
    }

    @Test
    void directSqlCannotRewriteSealedObjectOrEdgesOrPinTarget() {
        NewObject leaf = leaf("leaf");
        NewObject page = page(leaf);
        stage(List.of(leaf, page), page.objectId());
        for (String sql : List.of(
                "UPDATE app_learning.storage_object SET payload = '{}'::jsonb",
                "UPDATE app_learning.storage_object SET sealed = false",
                "UPDATE app_learning.storage_edge SET logical_key = NULL",
                "DELETE FROM app_learning.storage_edge",
                "UPDATE app_learning.storage_pin SET owner_id = '" + UUID.randomUUID() + "'")) {
            assertThatThrownBy(() -> jdbc.sql(sql).update()).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM app_learning.storage_object WHERE object_id = :id")
                .param("id", leaf.objectId()).update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void incompleteOrGappedObjectsCannotCommitAndSealedParentsCannotAppend() throws Exception {
        NewObject leaf = leaf("leaf");
        stage(List.of(leaf), leaf.objectId());
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            rawObject(connection, UUID.randomUUID(), 0, 0, false);
            assertThatThrownBy(connection::commit).isInstanceOf(SQLException.class);
            connection.rollback();
            UUID gapped = UUID.randomUUID();
            rawObject(connection, gapped, 1, 1, false);
            rawEdge(connection, gapped, 1, leaf.objectId(), 0, 1);
            connection.createStatement().executeUpdate("UPDATE app_learning.storage_object SET sealed=true WHERE object_id='" + gapped + "'");
            assertThatThrownBy(connection::commit).isInstanceOf(SQLException.class);
            connection.rollback();
        }
        NewObject page = page(leaf);
        stage(List.of(page), page.objectId());
        try (Connection connection = dataSource.getConnection()) {
            assertThatThrownBy(() -> rawEdge(connection, page.objectId(), 1, leaf.objectId(), 0, 1)).isInstanceOf(SQLException.class);
        }
        assertThat(count("storage_object")).isEqualTo(2);
    }

    @Test
    void copiedRanksAndScopeForeignKeysPreventCyclesAndCrossScopeLinks() throws Exception {
        NewObject leaf = leaf("leaf");
        stage(List.of(leaf), leaf.objectId());
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            UUID page = UUID.randomUUID();
            rawObject(connection, page, 1, 1, false);
            assertThatThrownBy(() -> rawEdge(connection, page, 1, leaf.objectId(), 1, 0)).isInstanceOf(SQLException.class);
            connection.rollback();
            rawObject(connection, page, 2, 1, false);
            assertThatThrownBy(() -> rawEdge(connection, page, 1, leaf.objectId(), 0, 0)).isInstanceOf(SQLException.class);
            connection.rollback();
        }
        assertThat(count("storage_object")).isOne();
    }

    @Test
    void retainAndReleaseRequireCallerTransactionAndRollbackTogether() {
        NewObject leaf = leaf("leaf");
        var staged = stage(List.of(leaf), leaf.objectId());
        assertThatThrownBy(() -> storage.retain(staged, owner())).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> storage.release(scope, staged.stagingPinId())).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> tx.execute(status -> {
            storage.retain(staged, owner());
            storage.release(scope, staged.stagingPinId());
            throw new IllegalStateException("fixture rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("storage_pin")).isOne();
        UUID durable = tx.execute(status -> storage.retain(staged, owner()));
        tx.executeWithoutResult(status -> storage.release(scope, staged.stagingPinId()));
        assertThat(jdbc.sql("SELECT expires_at IS NULL FROM app_learning.storage_pin WHERE pin_id=:id").param("id", durable).query(Boolean.class).single()).isTrue();
        assertThat(storage.expireStaging(scope, Instant.MAX, 8)).isZero();
        assertThatThrownBy(() -> tx.execute(status -> storage.collectBatch(scope, Instant.now(), 8))).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void expiredAndWrongActorPreparationsCannotPublishAndRenewUsesCas() {
        NewObject leaf = leaf("leaf");
        var staged = stage(List.of(leaf), leaf.objectId());
        assertThatThrownBy(() -> tx.execute(status -> storage.retain(staged,
                new PinOwner("revision", UUID.randomUUID(), UUID.randomUUID())))).isInstanceOf(StorageFailure.class);
        assertThat(storage.renewStaging(scope, staged.stagingPinId(), 0, Duration.ofMinutes(2))).isOne();
        assertThatThrownBy(() -> storage.renewStaging(scope, staged.stagingPinId(), 0, Duration.ofMinutes(2))).isInstanceOf(VersionConflictException.class);
        expirePins();
        assertThatThrownBy(() -> tx.execute(status -> storage.retain(staged, owner()))).isInstanceOf(StorageFailure.class);
        assertThatThrownBy(() -> storage.renewStaging(scope, staged.stagingPinId(), 2, Duration.ofMinutes(2))).isInstanceOf(StorageFailure.class);
    }

    @Test
    void expiryAndCollectorAreBoundedAndRestartableAcrossPageToFragmentGraph() {
        NewObject fragment = new NewObject(UUID.randomUUID(), ObjectKind.FRAGMENT, (short) 1, (short) 0,
                mapper.createObjectNode().put("text", "physical part"), List.of());
        NewObject manifest = page(fragment);
        NewObject root = new NewObject(UUID.randomUUID(), ObjectKind.PAGE, (short) 1, (short) 2,
                mapper.createObjectNode(), List.of(new NewEdge(0, null, new ObjectRef(scope, manifest.objectId()))));
        var prepared = stage(List.of(fragment, manifest, root), root.objectId());
        UUID durable = tx.execute(status -> storage.retain(prepared, owner()));
        expirePins();
        assertThat(storage.expireStaging(scope, Instant.MAX, 8)).isOne();
        readyCandidates();
        assertThat(storage.collectBatch(scope, Instant.MAX, 8).deleted()).isZero();
        assertThat(count("storage_object")).isEqualTo(3);
        tx.executeWithoutResult(status -> storage.release(scope, durable));
        for (int i = 0; i < 3; i++) {
            readyCandidates();
            assertThat(storage.collectBatch(scope, Instant.MAX, 1).deleted()).isOne();
        }
        assertThat(count("storage_object")).isZero();
        assertThat(count("storage_edge")).isZero();
        assertThat(count("storage_gc_candidate")).isZero();
        assertThat(storage.collectBatch(scope, Instant.MAX, 8).inspected()).isZero();
    }

    @Test
    void parentRemovalPostponesChildAlreadySelectedInSameCollectionBatch() {
        NewObject child = leaf("grace child");
        NewObject parent = page(child);
        var staged = stage(List.of(child, parent), parent.objectId());
        tx.executeWithoutResult(status -> storage.release(scope, staged.stagingPinId()));
        // Both are ready, but the parent is deterministically first in the selected batch.
        jdbc.sql("UPDATE app_learning.storage_gc_candidate SET not_before=clock_timestamp()-interval '2 seconds' WHERE object_id=:id")
                .param("id", parent.objectId()).update();
        jdbc.sql("UPDATE app_learning.storage_gc_candidate SET not_before=clock_timestamp()-interval '1 second' WHERE object_id=:id")
                .param("id", child.objectId()).update();
        CollectionResult result = storage.collectBatch(scope, Instant.MAX, 2);
        assertThat(result.inspected()).isEqualTo(2);
        assertThat(result.deleted()).isOne();
        assertThat(result.deferred()).isOne();
        assertThat(storage.readBatch(scope, List.of(child.objectId()))).hasSize(1);
        assertThat(count("storage_gc_candidate")).isOne();
        readyCandidates();
        assertThat(storage.collectBatch(scope, Instant.MAX, 2).deleted()).isOne();
    }

    @Test
    void expiryAndGarbageNeverExceedEightCandidates() {
        for (int i = 0; i < 11; i++) { NewObject value = leaf("x"); stage(List.of(value), value.objectId()); }
        expirePins();
        assertThat(storage.expireStaging(scope, Instant.MAX, 8)).isEqualTo(8);
        assertThat(storage.expireStaging(scope, Instant.MAX, 8)).isEqualTo(3);
        readyCandidates();
        assertThat(storage.collectBatch(scope, Instant.MAX, 8).deleted()).isEqualTo(8);
        assertThat(storage.collectBatch(scope, Instant.MAX, 8).deleted()).isEqualTo(3);
        assertThatThrownBy(() -> storage.collectBatch(scope, Instant.now(), 9)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publicationReceiptPinHeadAndProjectionCommitOnceUnderSameCommandRace() throws Exception {
        NewObject value = leaf("published");
        var staged = stage(List.of(value), value.objectId());
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_learning.storage_publication_fixture VALUES (:id,0,NULL)").param("id", id).update();
        var identity = new CommandIdentity(UUID.randomUUID(), actor, "storage.fixture", "publish");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var backend = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> receipts.execute(identity, mapper.createObjectNode(), () -> {
                backend.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                publish(staged, id); calls.incrementAndGet(); entered.countDown(); await(release);
                return mapper.createObjectNode().put("version", 1);
            }));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> receipts.execute(identity, mapper.createObjectNode(), () -> {
                    calls.incrementAndGet(); return mapper.createObjectNode().put("wrong", true);
                }));
                awaitBlockedBy(backend.get());
                release.countDown();
                assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
            } finally { release.countDown(); }
        }
        assertThat(calls).hasValue(1);
        assertThat(count("storage_pin")).isOne();
        assertThat(count("command_receipt")).isOne();
        assertThat(count("storage_projection_fixture")).isOne();
        assertThat(jdbc.sql("SELECT version FROM app_learning.storage_publication_fixture").query(Long.class).single()).isOne();
    }

    @Test
    void publicationFailureAndCasLossRollBackReceiptPinsProjectionAndHead() {
        NewObject value = leaf("published");
        var staged = stage(List.of(value), value.objectId());
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_learning.storage_publication_fixture VALUES (:id,0,NULL)").param("id", id).update();
        var identity = new CommandIdentity(UUID.randomUUID(), actor, "storage.fixture", "publish");
        assertThatThrownBy(() -> receipts.execute(identity, mapper.createObjectNode(), () -> {
            publish(staged, id); throw new IllegalStateException("crash fixture");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("storage_pin")).isOne();
        assertThat(count("command_receipt")).isZero();
        assertThat(count("storage_projection_fixture")).isZero();
        assertThat(jdbc.sql("SELECT version FROM app_learning.storage_publication_fixture").query(Long.class).single()).isZero();
        jdbc.sql("UPDATE app_learning.storage_publication_fixture SET version=1").update();
        assertThatThrownBy(() -> receipts.execute(identity, mapper.createObjectNode(), () -> {
            publish(staged, id); return mapper.createObjectNode();
        })).isInstanceOf(VersionConflictException.class);
        assertThat(count("storage_pin")).isOne();
        assertThat(count("command_receipt")).isZero();
        assertThat(count("storage_projection_fixture")).isZero();
    }

    @Test
    void collectorWaitsForReaderAndPreservesCompleteEdgesUntilReadCompletes() throws Exception {
        NewObject child = leaf("child");
        NewObject parent = page(child);
        var staged = stage(List.of(child, parent), parent.objectId());
        tx.executeWithoutResult(status -> storage.release(scope, staged.stagingPinId()));
        // Isolate the parent candidate so the race has a single deterministic lock target.
        jdbc.sql("DELETE FROM app_learning.storage_gc_candidate WHERE object_id=:id").param("id", child.objectId()).update();
        readyCandidates();
        try (Connection reader = dataSource.getConnection(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            reader.setAutoCommit(false);
            rawLock(reader, parent.objectId(), "FOR KEY SHARE");
            int pid = pid(reader);
            var collection = executor.submit(() -> storage.collectBatch(scope, Instant.MAX, 1));
            try {
                awaitBlockedBy(pid);
                assertThat(storage.readBatch(scope, List.of(parent.objectId())).getFirst().value().edges()).hasSize(1);
            } finally { reader.commit(); }
            assertThat(collection.get(10, TimeUnit.SECONDS).deleted()).isOne();
        }
        assertThat(count("storage_edge")).isZero();
        assertThat(count("storage_object")).isOne();
    }

    @Test
    void differentCommandsRacingOnHeadLeaveOnlyWinningDurablePinAndReceipt() throws Exception {
        NewObject value = leaf("cas race");
        var firstRoot = stage(List.of(value), value.objectId());
        var secondRoot = stage(List.of(), value.objectId());
        UUID head = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_learning.storage_publication_fixture VALUES (:id,0,NULL)").param("id", head).update();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var backend = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> receipts.execute(new CommandIdentity(UUID.randomUUID(), actor, "storage.fixture", "publish"),
                    mapper.createObjectNode(), () -> {
                        backend.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                        publish(firstRoot, head); entered.countDown(); await(release); return mapper.createObjectNode();
                    }));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> receipts.execute(new CommandIdentity(UUID.randomUUID(), actor, "storage.fixture", "publish"),
                        mapper.createObjectNode(), () -> { publish(secondRoot, head); return mapper.createObjectNode(); }));
                awaitBlockedBy(backend.get());
                release.countDown();
                first.get(10, TimeUnit.SECONDS);
                assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(VersionConflictException.class);
            } finally { release.countDown(); }
        }
        assertThat(count("command_receipt")).isOne();
        assertThat(count("storage_projection_fixture")).isOne();
        assertThat(count("storage_pin")).isEqualTo(2); // winner durable plus losing preparation, restored by rollback
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.storage_pin WHERE pin_kind='durable'").query(Long.class).single()).isOne();
    }

    @Test
    void competingAppendWaitsOnParentLockThenRejectsSealedState() throws Exception {
        NewObject child = leaf("append race");
        NewObject parent = page(child);
        stage(List.of(child, parent), parent.objectId());
        try (Connection holder = dataSource.getConnection(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            holder.setAutoCommit(false);
            rawLock(holder, parent.objectId(), "FOR UPDATE");
            var append = executor.submit(() -> {
                try (Connection writer = dataSource.getConnection()) { rawEdge(writer, parent.objectId(), 1, child.objectId(), 0, 1); }
                return true;
            });
            try { awaitBlockedBy(pid(holder)); }
            finally { holder.commit(); }
            assertThatThrownBy(() -> append.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(SQLException.class);
        }
        assertThat(storage.readBatch(scope, List.of(parent.objectId())).getFirst().value().edges()).hasSize(1);
    }

    @Test
    void newPinWinsAgainstCollectorAndPreventsDeletion() throws Exception {
        NewObject object = leaf("pin race");
        var staged = stage(List.of(object), object.objectId());
        tx.executeWithoutResult(status -> storage.release(scope, staged.stagingPinId()));
        readyCandidates();
        try (Connection pinner = dataSource.getConnection(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            pinner.setAutoCommit(false);
            rawDurablePin(pinner, object.objectId());
            var collection = executor.submit(() -> storage.collectBatch(scope, Instant.MAX, 1));
            try { awaitBlockedBy(pid(pinner)); }
            finally { pinner.commit(); }
            assertThat(collection.get(10, TimeUnit.SECONDS).deferred()).isOne();
        }
        assertThat(count("storage_object")).isOne();
        assertThat(count("storage_pin")).isOne();
    }

    @Test
    void collectorWinningLockMakesConcurrentPreparationFailWithoutDanglingPin() throws Exception {
        NewObject object = leaf("delete race");
        var staged = stage(List.of(object), object.objectId());
        tx.executeWithoutResult(status -> storage.release(scope, staged.stagingPinId()));
        try (Connection collector = dataSource.getConnection(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            collector.setAutoCommit(false);
            rawLock(collector, object.objectId(), "FOR UPDATE");
            var preparation = executor.submit(() -> stage(List.of(), object.objectId()));
            try {
                awaitBlockedBy(pid(collector));
                collector.createStatement().executeUpdate("DELETE FROM app_learning.storage_object WHERE object_id='" + object.objectId() + "'");
            } finally { collector.commit(); }
            assertThatThrownBy(() -> preparation.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(StorageFailure.class);
        }
        assertThat(count("storage_pin")).isZero();
        assertThat(count("storage_object")).isZero();
    }

    @Test
    void concurrentEqualStageWaitsThenComparesWholeCommittedObject() throws Exception {
        NewObject object = leaf("same object");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var backend = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> tx.execute(status -> {
                backend.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                var result = stage(List.of(object), object.objectId());
                entered.countDown(); await(release); return result;
            }));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> stage(List.of(object), object.objectId()));
                awaitBlockedBy(backend.get());
                release.countDown();
                assertThat(first.get(10, TimeUnit.SECONDS).root()).isEqualTo(second.get(10, TimeUnit.SECONDS).root());
            } finally { release.countDown(); }
        }
        assertThat(count("storage_object")).isOne();
        assertThat(count("storage_pin")).isEqualTo(2);
    }

    @Test
    void concurrentCollectorsSkipLockedCandidatesAndDoNotDuplicateDeletion() throws Exception {
        for (int i = 0; i < 2; i++) {
            NewObject object = leaf("candidate");
            var staged = stage(List.of(object), object.objectId());
            tx.executeWithoutResult(status -> storage.release(scope, staged.stagingPinId()));
        }
        readyCandidates();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            holder.createStatement().executeQuery("SELECT object_id FROM app_learning.storage_gc_candidate ORDER BY object_id LIMIT 1 FOR UPDATE").close();
            assertThat(storage.collectBatch(scope, Instant.MAX, 8).deleted()).isOne();
            holder.commit();
        }
        assertThat(storage.collectBatch(scope, Instant.MAX, 8).deleted()).isOne();
        assertThat(storage.collectBatch(scope, Instant.MAX, 8).inspected()).isZero();
    }

    @Test
    void expirySkipsPublicationLockAndDurablePinSurvivesStagingRemoval() throws Exception {
        NewObject object = leaf("lease");
        var staged = stage(List.of(object), object.objectId());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var publication = executor.submit(() -> tx.execute(status -> {
                storage.retain(staged, owner());
                // Synthetic elapsed lease, inside the same locked publication transaction.
                expirePins(); entered.countDown(); await(release); return true;
            }));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(storage.expireStaging(scope, Instant.MAX, 8)).isZero();
            } finally { release.countDown(); }
            assertThat(publication.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(storage.expireStaging(scope, Instant.MAX, 8)).isOne();
        readyCandidates();
        assertThat(storage.collectBatch(scope, Instant.MAX, 8).deleted()).isZero();
        assertThat(count("storage_pin")).isOne();
    }

    @Test
    void scopedExpiryAndCandidateQueriesUseTheirMatchingRangeIndexes() {
        NewObject object = leaf("index fixture");
        stage(List.of(object), object.objectId());
        // Many expired pins in another scope must not be scanned to find this scope's eight.
        UUID otherScope = UUID.randomUUID();
        storage.stageBatch(new StageBatch(otherScope, actor, List.of(object), List.of(object.objectId())), Duration.ofMinutes(1));
        jdbc.sql("""
                INSERT INTO app_learning.storage_pin
                SELECT :scope,gen_random_uuid(),:root,'staging','fixture',gen_random_uuid(),:actor,
                    clock_timestamp()-interval '1 day',0 FROM generate_series(1,4000)
                """).param("scope", otherScope).param("root", object.objectId()).param("actor", actor).update();
        jdbc.sql("ANALYZE app_learning.storage_pin").update();
        String plan = String.join("\n", jdbc.sql("""
                EXPLAIN (ANALYZE, BUFFERS) SELECT pin_id FROM app_learning.storage_pin
                WHERE reuse_scope_id=:scope AND expires_at<=clock_timestamp()
                ORDER BY expires_at,pin_id LIMIT 8 FOR UPDATE SKIP LOCKED
                """).param("scope", scope).query(String.class).list());
        assertThat(plan).contains("storage_pin_expiry", "reuse_scope_id");
        // Parameterized production cutoff is stable, unlike clock_timestamp() in this diagnostic.
        String stable = String.join("\n", jdbc.sql("""
                EXPLAIN SELECT pin_id FROM app_learning.storage_pin
                WHERE reuse_scope_id=:scope AND expires_at<=:cutoff
                ORDER BY expires_at,pin_id LIMIT 8 FOR UPDATE SKIP LOCKED
                """).param("scope", scope).param("cutoff", StorageRepository.at(Instant.now())).query(String.class).list());
        assertThat(stable).contains("Index Cond:", "expires_at <=");
        assertThat(jdbc.sql("SELECT indexdef FROM pg_indexes WHERE schemaname='app_learning' AND indexname='storage_gc_ready'")
                .query(String.class).single()).contains("reuse_scope_id, not_before, object_id");
    }

    private void publish(StagedRoot staged, UUID id) {
        storage.retain(staged, owner());
        storage.release(scope, staged.stagingPinId());
        jdbc.sql("INSERT INTO app_learning.storage_projection_fixture VALUES (:id)").param("id", UUID.randomUUID()).update();
        cas.updateOne(0, () -> jdbc.sql("UPDATE app_learning.storage_publication_fixture SET version=version+1,root_id=:root WHERE id=:id AND version=0")
                .param("root", staged.root().objectId()).param("id", id).update());
    }

    private NewObject leaf(String text) {
        return new NewObject(UUID.randomUUID(), ObjectKind.BLOCK, (short) 1, (short) 0, mapper.createObjectNode().put("text", text), List.of());
    }
    private NewObject page(NewObject child) {
        return new NewObject(UUID.randomUUID(), ObjectKind.PAGE, (short) 1, (short) 1, mapper.createObjectNode(),
                List.of(new NewEdge(0, UUID.randomUUID(), new ObjectRef(scope, child.objectId()))));
    }
    private StagedRoot stage(List<NewObject> objects, UUID root) {
        return storage.stageBatch(new StageBatch(scope, actor, objects, List.of(root)), Duration.ofMinutes(1)).getFirst();
    }
    private PinOwner owner() { return new PinOwner("revision", UUID.randomUUID(), actor); }
    private long count(String table) { return jdbc.sql("SELECT count(*) FROM app_learning." + table).query(Long.class).single(); }
    private void expirePins() { jdbc.sql("UPDATE app_learning.storage_pin SET expires_at=clock_timestamp()-interval '1 second',row_version=row_version+1 WHERE pin_kind='staging'").update(); }
    private void readyCandidates() { jdbc.sql("UPDATE app_learning.storage_gc_candidate SET not_before=clock_timestamp()-interval '1 second'").update(); }
    private void rawObject(Connection c, UUID id, int rank, int edges, boolean sealed) throws SQLException {
        c.createStatement().executeUpdate("INSERT INTO app_learning.storage_object VALUES ('" + scope + "','" + id + "','" + (rank == 0 ? "block" : "page") + "',1," + rank + "," + edges + ",'{}',decode(repeat('00',32),'hex')," + sealed + ",default)");
    }
    private void rawEdge(Connection c, UUID parent, int rank, UUID child, int childRank, int ordinal) throws SQLException {
        c.createStatement().executeUpdate("INSERT INTO app_learning.storage_edge VALUES ('" + scope + "','" + parent + "'," + ordinal + "," + rank + ",'" + child + "'," + childRank + ",NULL)");
    }
    private void rawDurablePin(Connection c, UUID root) throws SQLException {
        c.createStatement().executeUpdate("INSERT INTO app_learning.storage_pin VALUES ('" + scope + "','" + UUID.randomUUID() + "','" + root + "','durable','fixture','" + UUID.randomUUID() + "','" + actor + "',NULL,0)");
    }
    private void rawLock(Connection c, UUID object, String lock) throws SQLException {
        c.createStatement().executeQuery("SELECT object_id FROM app_learning.storage_object WHERE reuse_scope_id='" + scope + "' AND object_id='" + object + "' " + lock).close();
    }
    private int pid(Connection c) throws SQLException {
        try (var statement = c.createStatement(); var rows = statement.executeQuery("SELECT pg_backend_pid()")) { rows.next(); return rows.getInt(1); }
    }
    private void awaitBlockedBy(int blocker) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (jdbc.sql("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE :pid = ANY(pg_blocking_pids(pid)))")
                    .param("pid", blocker).query(Boolean.class).single()) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Expected observed PostgreSQL lock contention");
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("fixture latch timeout"); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
    }
}
