package app.mnema.learning.catalog.content.pages;

import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.storage.StorageTypes.*;
import app.mnema.learning.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static app.mnema.learning.catalog.content.pages.CountedPageTypes.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "learning.storage.orphan-grace=PT0.001S")
class CountedPagesIntegrationTest extends PostgresIntegrationTest {
    @Autowired ImmutableStorage storage;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    private final UUID scope = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();

    @Test
    void sharedRootBranchesProtectNormalizedContentEdgesAfterSourcePinRemoval() {
        var content = new NewObject(UUID.randomUUID(), ObjectKind.PAGE, (short) 1, (short) 8,
                JsonNodeFactory.instance.objectNode().put("fixture", "content-root"), List.of());
        var descriptor = new NewObject(UUID.randomUUID(), ObjectKind.PAGE, (short) 1, (short) 9,
                JsonNodeFactory.instance.objectNode().put("fixture", "member"),
                List.of(new NewEdge(0, null, new ObjectRef(scope, content.objectId()))));
        List<StagedRoot> preparation = stage(List.of(content, descriptor));
        var pages = new CountedPages(Profile.members(100_000), ref -> storage.readBatch(scope, List.of(ref.objectId())).getFirst().value());
        var entries = new ArrayList<Entry>();
        for (int i = 0; i < 1100; i++) entries.add(new Entry(UUID.randomUUID(), new ObjectRef(scope, descriptor.objectId())));
        var initial = pages.build(scope, entries);
        preparation.addAll(stage(initial.additions()));
        StagedRoot source = preparation.getLast();
        var tx = new TransactionTemplate(transactions);
        long objectsBeforeFork = count("storage_object");
        UUID firstPin = tx.execute(status -> storage.retain(source, new PinOwner("pages.fixture", UUID.randomUUID(), actor)));
        UUID forkPin = tx.execute(status -> storage.retain(source, new PinOwner("pages.fixture", UUID.randomUUID(), actor)));
        assertThat(count("storage_pin")).isEqualTo(preparation.size() + 2L);
        assertThat(count("storage_object")).isEqualTo(objectsBeforeFork);
        var changed = pages.move(initial.root(), 0, 1099, entries.getFirst().key());
        assertThat(changed.additions()).hasSizeLessThan(15);
        List<StagedRoot> nextPreparation = stage(changed.additions());
        tx.executeWithoutResult(status -> storage.retain(nextPreparation.getLast(), new PinOwner("pages.fixture", UUID.randomUUID(), actor)));
        preparation.addAll(nextPreparation);
        preparation.forEach(pin -> tx.executeWithoutResult(status -> storage.release(scope, pin.stagingPinId())));
        tx.executeWithoutResult(status -> storage.release(scope, firstPin));
        collect();
        assertThat(pages.read(initial.root(), 0, 100)).containsExactlyElementsOf(entries.subList(0, 100));
        assertThat(pages.read(changed.root(), 1099, 1)).containsExactly(entries.getFirst());
        tx.executeWithoutResult(status -> storage.release(scope, forkPin));
        collect();
        assertThat(pages.read(changed.root(), 0, 100)).containsExactlyElementsOf(entries.subList(1, 101));
        assertThat(storage.readBatch(scope, List.of(content.objectId()))).hasSize(1);
        assertThat(count("storage_pin")).isOne();
        assertThat(jdbc.sql("SELECT max(dag_rank) FROM app_learning.storage_object WHERE reuse_scope_id=:scope")
                .param("scope", scope).query(Integer.class).single()).isLessThanOrEqualTo(13);
        assertThat(jdbc.sql("SELECT max(octet_length(payload::text)) FROM app_learning.storage_object WHERE reuse_scope_id=:scope")
                .param("scope", scope).query(Integer.class).single()).isLessThan(16384);
    }

    private List<StagedRoot> stage(List<NewObject> objects) {
        List<StagedRoot> pins = new ArrayList<>();
        for (int offset = 0; offset < objects.size(); offset += 32) {
            List<NewObject> batch = objects.subList(offset, Math.min(offset + 32, objects.size()));
            pins.addAll(storage.stageBatch(new StageBatch(scope, actor, batch, batch.stream().map(NewObject::objectId).toList()), Duration.ofMinutes(5)));
            // Every newly staged frontier object remains pinned across subsequent batches.
            ready();
            assertThat(storage.collectBatch(scope, Instant.MAX, 8).deleted()).isZero();
        }
        return pins;
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM app_learning." + table + " WHERE reuse_scope_id=:scope")
                .param("scope", scope).query(Long.class).single();
    }

    private void ready() {
        jdbc.sql("UPDATE app_learning.storage_gc_candidate SET not_before=clock_timestamp()-interval '1 second' WHERE reuse_scope_id=:scope")
                .param("scope", scope).update();
    }

    private void collect() {
        for (int i = 0; i < 20; i++) { ready(); storage.collectBatch(scope, Instant.MAX, 8); }
    }
}
