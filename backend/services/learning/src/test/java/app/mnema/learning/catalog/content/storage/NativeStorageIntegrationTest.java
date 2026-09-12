package app.mnema.learning.catalog.content.storage;

import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.storage.StorageTypes.PinOwner;
import app.mnema.learning.storage.StorageTypes.StagedRoot;
import app.mnema.learning.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static app.mnema.learning.catalog.content.storage.NativeStorageFixtures.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "learning.storage.orphan-grace=PT0.001S")
class NativeStorageIntegrationTest extends PostgresIntegrationTest {
    @Autowired ImmutableStorage storage;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    private final UUID scope = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final NativeSnapshotCodec codec = new NativeSnapshotCodec();

    @Test
    void multipleBatchesKeepEveryFrontierObjectPinnedAgainstCollection() {
        ObjectNode doc = document();
        for (int i = 2; i <= 100; i++) ((ObjectNode) doc.path("root")).withArray("content").add(node(i, "future"));
        var plan = codec.encode(scope, read(doc));
        var adapter = new NativeStorageBatches(storage);
        var preparation = adapter.begin(plan, actor, Duration.ofMinutes(5), null);
        int batches = 0;
        while (!preparation.complete()) {
            long before = count("storage_object");
            adapter.stageNext(preparation);
            assertThat(count("storage_object") - before).isBetween(1L, 32L);
            assertThat(count("storage_pin")).isEqualTo(count("storage_object"));
            readyCandidates();
            assertThat(storage.collectBatch(scope, Instant.MAX, 8).deleted()).isZero();
            batches++;
        }
        assertThat(batches).isGreaterThan(1);
        assertThat(readStored(preparation.root()).document().toJson()).isEqualTo(read(doc).toJson());
        assertThat(jdbc.sql("SELECT max(octet_length(payload::text)) FROM app_learning.storage_object WHERE reuse_scope_id=:scope")
                .param("scope", scope).query(Integer.class).single()).isLessThanOrEqualTo(16384);
        assertThat(jdbc.sql("SELECT max(dag_rank) FROM app_learning.storage_object WHERE reuse_scope_id=:scope")
                .param("scope", scope).query(Integer.class).single()).isEqualTo(8);
    }

    @Test
    void longOpaqueScalarsKeysAndHistoriesRoundTripThroughJsonbAndGc() {
        ObjectNode future = node(2, "future");
        future.put("я".repeat(16_384), "\u0001".repeat(32_768));
        future.withObject("attrs").put("text", "Я🌿".repeat(4000));
        ObjectNode doc = document(future);
        var adapter = new NativeStorageBatches(storage);
        var firstPlan = codec.encode(scope, read(doc));
        var first = prepare(adapter, firstPlan, null);
        var old = readStored(first.root());
        future.withObject("attrs").put("text", "Я🌿".repeat(2000) + " Ω " + "Я🌿".repeat(2000));
        var nextPlan = codec.replace(old, read(doc));
        var next = prepare(adapter, nextPlan, first.root());
        assertThat(nextPlan.additions().size()).isLessThan(10);
        var tx = new TransactionTemplate(transactions);
        UUID oldPin = tx.execute(status -> storage.retain(first.root(), new PinOwner("codec.fixture", UUID.randomUUID(), actor)));
        UUID newPin = tx.execute(status -> storage.retain(next.root(), new PinOwner("codec.fixture", UUID.randomUUID(), actor)));
        for (StagedRoot pin : first.pins()) tx.executeWithoutResult(status -> storage.release(scope, pin.stagingPinId()));
        for (StagedRoot pin : next.pins()) tx.executeWithoutResult(status -> storage.release(scope, pin.stagingPinId()));
        assertThat(readStored(first.root()).document().toJson()).isEqualTo(old.document().toJson());
        assertThat(readStored(next.root()).document().toJson()).isEqualTo(read(doc).toJson());
        tx.executeWithoutResult(status -> storage.release(scope, oldPin));
        for (int i = 0; i < 20; i++) { readyCandidates(); storage.collectBatch(scope, Instant.MAX, 8); }
        assertThat(readStored(next.root()).document().toJson()).isEqualTo(read(doc).toJson());
        assertThat(count("storage_pin")).isOne();
        assertThat(newPin).isNotNull();
        int maximum = jdbc.sql("SELECT max(octet_length(payload::text)) FROM app_learning.storage_object WHERE reuse_scope_id=:scope")
                .param("scope", scope).query(Integer.class).single();
        assertThat(maximum).isLessThanOrEqualTo(16384);
        System.out.println("K2 PostgreSQL: initialObjects=" + firstPlan.snapshot().objectCount()
                + ", changedObjects=" + nextPlan.additions().size() + ", maxJsonbPayloadBytes=" + maximum);
    }

    private NativeStorageBatches.Preparation prepare(NativeStorageBatches adapter, NativeEncodingPlan plan, StagedRoot source) {
        var cursor = adapter.begin(plan, actor, Duration.ofMinutes(5), source);
        while (!cursor.complete()) adapter.stageNext(cursor);
        return cursor;
    }

    private NativeSnapshot readStored(StagedRoot root) {
        var adapter = new NativeStorageBatches(storage);
        var decoder = new NativeSnapshotDecoder(root.root());
        int batches = 0;
        while (!decoder.isComplete()) { adapter.readNext(decoder); batches++; }
        assertThatThrownBy(() -> adapter.readNext(decoder)).isInstanceOf(IllegalStateException.class);
        NativeSnapshot snapshot = decoder.snapshot();
        assertThat(batches).isLessThanOrEqualTo(8 + (snapshot.objectCount() + 31) / 32);
        assertThat(decoder.snapshot()).isSameAs(snapshot);
        return snapshot;
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM app_learning." + table + " WHERE reuse_scope_id=:scope")
                .param("scope", scope).query(Long.class).single();
    }

    private void readyCandidates() {
        jdbc.sql("UPDATE app_learning.storage_gc_candidate SET not_before=clock_timestamp()-interval '1 second' WHERE reuse_scope_id=:scope")
                .param("scope", scope).update();
    }
}
