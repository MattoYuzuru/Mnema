package app.mnema.learning.catalog.content.storage;

import app.mnema.learning.storage.StorageTypes.NewEdge;
import app.mnema.learning.storage.StorageTypes.NewObject;
import app.mnema.learning.storage.StorageTypes.ObjectKind;
import app.mnema.learning.storage.StorageTypes.ObjectRef;
import app.mnema.learning.storage.StorageTypes.StoredObject;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static app.mnema.learning.catalog.content.storage.NativeStorageFixtures.*;
import static app.mnema.learning.catalog.content.storage.NativeStorageFormat.*;
import static org.assertj.core.api.Assertions.*;

class NativeSnapshotDecoderTest {
    private final UUID scope = UUID.randomUUID();
    private final NativeEncodingPlan plan = new NativeSnapshotCodec().encode(scope,
            read(document(node(2, "paragraph", text(3, "Private fixture 🌿")))));

    @Test
    void incompleteUnrequestedCrossScopeAndDuplicateResponsesFailClosed() {
        var decoder = new NativeSnapshotDecoder(plan.snapshot().root());
        assertThatThrownBy(decoder::snapshot).isInstanceOf(NativeStorageFailure.class);
        NewObject root = plan.snapshot().objects().get(plan.snapshot().root().objectId());
        assertThatThrownBy(() -> decoder.accept(List.of(new StoredObject(new ObjectRef(UUID.randomUUID(), root.objectId()), root))))
                .isInstanceOf(NativeStorageFailure.class);
        assertThatThrownBy(decoder::requestedIds).isInstanceOf(NativeStorageFailure.class);
        assertThatThrownBy(decoder::snapshot).isInstanceOf(NativeStorageFailure.class);
        var empty = new NativeSnapshotDecoder(plan.snapshot().root());
        assertThatThrownBy(() -> empty.accept(List.of())).isInstanceOf(NativeStorageFailure.class);
        var duplicate = new NativeSnapshotDecoder(plan.snapshot().root());
        var stored = new StoredObject(plan.snapshot().root(), root);
        assertThatThrownBy(() -> duplicate.accept(List.of(stored, stored))).isInstanceOf(NativeStorageFailure.class);
    }

    @Test
    void malformedDocumentVersionsCountsRolesAndExtraFieldsAreRejected() {
        for (Consumer<ObjectNode> mutation : List.<Consumer<ObjectNode>>of(
                json -> json.put("codec", 2), json -> json.put("role", "unknown"), json -> json.put("nodeCount", 10_001),
                json -> json.put("nodeCount", 4), json -> json.put("recordBytes", 1),
                json -> json.put("extra", "private"), json -> json.put("formatVersion", 2))) {
            assertInvalid(plan.snapshot().root().objectId(), mutation);
        }
    }

    @Test
    void wrongRankCrossScopeEdgesAndStoredIdMismatchCannotComplete() {
        NewObject root = plan.snapshot().objects().get(plan.snapshot().root().objectId());
        var objects = new HashMap<>(plan.snapshot().objects());
        objects.put(root.objectId(), new NewObject(root.objectId(), root.kind(), (short) 1, (short) 7, root.payload(), root.edges()));
        assertThatThrownBy(() -> finish(objects)).isInstanceOf(NativeStorageFailure.class);
        objects.put(root.objectId(), new NewObject(root.objectId(), root.kind(), (short) 1, (short) 8, root.payload(),
                List.of(new NewEdge(0, null, new ObjectRef(UUID.randomUUID(), root.edges().getFirst().child().objectId())))));
        assertThatThrownBy(() -> finish(objects)).isInstanceOf(NativeStorageFailure.class);
        objects.put(root.objectId(), new NewObject(UUID.randomUUID(), root.kind(), (short) 1, (short) 8, root.payload(), root.edges()));
        assertThatThrownBy(() -> finish(objects)).isInstanceOf(NativeStorageFailure.class);
    }

    @Test
    void malformedPageCountsHeightAndLeafKeysAreRejected() {
        NewObject page = plan.snapshot().objects().values().stream().filter(value -> value.payload().path("role").asText().equals("nodes")).findFirst().orElseThrow();
        assertInvalid(page.objectId(), json -> json.put("treeHeight", 1));
        assertInvalid(page.objectId(), json -> json.putArray("counts").add(1));
        assertInvalid(page.objectId(), json -> json.withArray("counts").set(0, com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.numberNode(2)));
        var objects = new HashMap<>(plan.snapshot().objects());
        List<NewEdge> edges = page.edges().stream().map(edge -> new NewEdge(edge.ordinal(), null, edge.child())).toList();
        objects.put(page.objectId(), new NewObject(page.objectId(), page.kind(), (short) 1, page.dagRank(), page.payload(), edges));
        assertThatThrownBy(() -> finish(objects)).isInstanceOf(NativeStorageFailure.class);
    }

    @Test
    void malformedRecordJsonUnicodeLengthIdentityAndPreorderAreRejectedWithoutPayloadLeak() {
        NewObject record = plan.snapshot().objects().values().stream().filter(value -> value.kind() == ObjectKind.BLOCK
                && value.payload().path("data").asText().contains("Private fixture")).findFirst().orElseThrow();
        for (String invalid : List.of("{bad Private fixture}", "\ud800", "a".repeat(2049),
                record.payload().path("data").asText() + " ",
                record.payload().path("data").asText().replace("\"c\":0", "\"c\":1"),
                record.payload().path("data").asText().replace("Private fixture", ""))) {
            assertInvalid(record.objectId(), json -> json.put("data", invalid));
        }
        String oldId = node(3, "text").path("id").asText();
        assertInvalid(record.objectId(), json -> json.put("data", json.path("data").asText().replace(oldId, node(4, "text").path("id").asText())));
    }

    @Test
    void adversarialFanoutIsStoppedBeforeUnboundedFetchOrAllocation() {
        var decoder = new NativeSnapshotDecoder(new ObjectRef(scope, UUID.randomUUID()));
        assertThatThrownBy(() -> {
            for (int step = 0; step < 100; step++) {
                List<StoredObject> response = new ArrayList<>();
                for (UUID id : decoder.requestedIds()) {
                    ObjectNode page = payload("nodes").put("treeHeight", 0);
                    var counts = page.putArray("counts");
                    List<NewEdge> edges = new ArrayList<>();
                    for (int i = 0; i < 32; i++) { counts.add(1); edges.add(new NewEdge(i, UUID.randomUUID(), new ObjectRef(scope, UUID.randomUUID()))); }
                    response.add(new StoredObject(new ObjectRef(scope, id), new NewObject(id, ObjectKind.PAGE, (short) 1, (short) 4, page, edges)));
                }
                decoder.accept(response);
            }
        }).isInstanceOfSatisfying(NativeStorageFailure.class, failure -> assertThat(failure.code()).isEqualTo(NativeStorageFailure.Code.BUDGET_EXCEEDED));
        assertThat(decoder.isComplete()).isFalse();
    }

    private void assertInvalid(UUID id, Consumer<ObjectNode> mutation) {
        var objects = new HashMap<>(plan.snapshot().objects());
        NewObject before = objects.get(id);
        ObjectNode payload = (ObjectNode) before.payload();
        mutation.accept(payload);
        objects.put(id, new NewObject(id, before.kind(), before.encodingVersion(), before.dagRank(), payload, before.edges()));
        assertThatThrownBy(() -> finish(objects)).isInstanceOf(NativeStorageFailure.class)
                .hasMessageNotContaining("Private fixture").hasNoCause();
    }

    private NativeSnapshot finish(java.util.Map<UUID, NewObject> objects) {
        var decoder = new NativeSnapshotDecoder(plan.snapshot().root());
        while (!decoder.isComplete()) decoder.accept(decoder.requestedIds().stream()
                .map(id -> new StoredObject(new ObjectRef(scope, id), objects.get(id))).toList());
        NativeSnapshot snapshot = decoder.snapshot();
        assertThat(decoder.snapshot()).isSameAs(snapshot);
        return snapshot;
    }
}
