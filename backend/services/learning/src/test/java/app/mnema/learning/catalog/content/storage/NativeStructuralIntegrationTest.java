package app.mnema.learning.catalog.content.storage;

import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.storage.StorageTypes.StagedRoot;
import app.mnema.learning.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.UUID;

import static app.mnema.learning.catalog.content.storage.NativeStorageFixtures.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class NativeStructuralIntegrationTest extends PostgresIntegrationTest {
    @Autowired ImmutableStorage storage;

    @Test
    void nativeStructuralPlanUsesExistingBatchesAndDecoderAgainstPostgres() {
        UUID scope = UUID.randomUUID(); UUID actor = UUID.randomUUID();
        ObjectNode doc = document();
        for (int i = 2; i <= 100; i++) ((ObjectNode) doc.path("root")).withArray("content").add(node(i, "future"));
        var adapter = new NativeStorageBatches(storage);
        var initial = new NativeSnapshotCodec().encode(scope, read(doc));
        var first = adapter.begin(initial, actor, Duration.ofMinutes(5), null);
        while (!first.complete()) adapter.stageNext(first);
        ObjectNode next = doc.deepCopy();
        ((ObjectNode) next.path("root")).withArray("content").insert(30, node(101, "paragraph", text(102, "日本語 🌿")));
        var plan = new NativeStructuralEditor().apply(initial.snapshot(), read(next),
                new NativeStructuralEdit.Insert(UUID.fromString(doc.path("root").path("id").asText()), 30));
        var prepared = adapter.begin(plan, actor, Duration.ofMinutes(5), first.root());
        while (!prepared.complete()) adapter.stageNext(prepared);
        assertThat(readStored(adapter, prepared.root()).document().toJson()).isEqualTo(read(next).toJson());
        assertThat(readStored(adapter, first.root()).document().toJson()).isEqualTo(read(doc).toJson());
        assertThat(plan.additions()).hasSizeLessThan(10);
    }

    private NativeSnapshot readStored(NativeStorageBatches adapter, StagedRoot root) {
        var cursor = new NativeSnapshotDecoder(root.root());
        while (!cursor.isComplete()) adapter.readNext(cursor);
        return cursor.snapshot();
    }
}
