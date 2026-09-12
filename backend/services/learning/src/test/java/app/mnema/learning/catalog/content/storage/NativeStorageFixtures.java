package app.mnema.learning.catalog.content.storage;

import app.mnema.learning.catalog.content.NativeDocument;
import app.mnema.learning.catalog.content.NativeDocumentReader;
import app.mnema.learning.storage.StorageTypes.StoredObject;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.UUID;

final class NativeStorageFixtures {
    static ObjectNode node(int id, String type, ObjectNode... children) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("id", new UUID(0xabcdefab00004000L, 0x8000000000000000L + id).toString());
        node.put("type", type).put("version", 1).putObject("attrs");
        var content = node.putArray("content");
        for (ObjectNode child : children) content.add(child);
        return node;
    }

    static ObjectNode text(int id, String text) {
        ObjectNode result = node(id, "text");
        result.withObject("attrs").put("text", text);
        return result;
    }

    static ObjectNode document(ObjectNode... children) {
        ObjectNode result = JsonNodeFactory.instance.objectNode().put("formatVersion", 1);
        result.set("root", node(1, "doc", children));
        return result;
    }

    static NativeDocument read(ObjectNode value) {
        return new NativeDocumentReader().read(NativeStorageFormat.JSON.canonicalBytes(value));
    }

    static NativeSnapshot decode(NativeEncodingPlan plan) {
        var cursor = new NativeSnapshotDecoder(plan.snapshot().root());
        while (!cursor.isComplete()) {
            cursor.accept(cursor.requestedIds().stream().map(id -> new StoredObject(
                    new app.mnema.learning.storage.StorageTypes.ObjectRef(plan.snapshot().root().reuseScopeId(), id),
                    plan.snapshot().objects().get(id))).toList());
        }
        return cursor.snapshot();
    }
}
