package app.mnema.learning.catalog.content.storage;

import app.mnema.learning.catalog.content.NativeDocument;
import app.mnema.learning.catalog.content.NativeDocumentReader;
import app.mnema.learning.platform.json.ContentJsonReader;
import app.mnema.learning.storage.StorageTypes.NewObject;
import app.mnema.learning.storage.StorageTypes.ObjectKind;
import app.mnema.learning.storage.StorageTypes.ObjectRef;
import app.mnema.learning.storage.StorageTypes.StoredObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static app.mnema.learning.catalog.content.storage.NativeStorageFormat.*;

/**
 * Caller-owned, single-threaded bounded cursor. The caller must authorize and keep the root pinned
 * throughout all reads. No content is exposed until the complete graph and native document validate.
 */
public final class NativeSnapshotDecoder {
    private final ObjectRef root;
    private final LinkedHashSet<UUID> pending = new LinkedHashSet<>();
    private final Map<UUID, NewObject> objects = new LinkedHashMap<>();
    private int payloadBytes;
    private int edges;
    private int expanded;
    private boolean failed;
    private NativeSnapshot result;

    public NativeSnapshotDecoder(ObjectRef root) {
        this.root = java.util.Objects.requireNonNull(root);
        pending.add(root.objectId());
    }

    public ObjectRef root() { return root; }

    /** At most 32 objects: safe even when each K1 object approaches its own physical limit. */
    public List<UUID> requestedIds() {
        require(!failed);
        return pending.stream().limit(BATCH_SIZE).toList();
    }

    public boolean isComplete() { return !failed && pending.isEmpty(); }

    public void accept(List<StoredObject> batch) {
        try {
            List<UUID> requested = requestedIds();
            require(!requested.isEmpty() && batch.size() == requested.size());
            for (int i = 0; i < batch.size(); i++) {
                StoredObject stored = batch.get(i);
                NewObject value = stored.value();
                require(stored.ref().equals(new ObjectRef(root.reuseScopeId(), requested.get(i)))
                        && value.objectId().equals(stored.ref().objectId()) && !objects.containsKey(value.objectId()));
                validateObject(value);
                pending.remove(value.objectId());
                objects.put(value.objectId(), value);
                for (var edge : value.edges()) {
                    require(edge.child().reuseScopeId().equals(root.reuseScopeId()));
                    if (!objects.containsKey(edge.child().objectId())) pending.add(edge.child().objectId());
                }
                budget(objects.size() + pending.size() <= MAX_OBJECTS);
            }
        } catch (RuntimeException exception) {
            failed = true;
            throw sanitized(exception);
        }
    }

    public NativeSnapshot snapshot() {
        if (!isComplete()) throw new NativeStorageFailure(NativeStorageFailure.Code.INCOMPLETE);
        if (result != null) return result;
        try {
            NewObject envelope = object(root.objectId());
            require(role(envelope).equals("document"));
            expanded = 1;
            List<Leaf> records = tree(envelope.edges().getFirst().child().objectId(), "nodes", -1, true);
            require(records.size() == envelope.payload().path("nodeCount").intValue());
            List<ObjectNode> nodes = new ArrayList<>();
            List<Integer> childCounts = new ArrayList<>();
            List<List<String>> fragments = new ArrayList<>();
            int recordBytes = 0;
            var reader = new ContentJsonReader(MAX_RECORD_BYTES, 128, 250_010);
            for (Leaf record : records) {
                List<String> parts = record(record.id());
                String text = String.join("", parts);
                recordBytes += bytes(text);
                budget(recordBytes <= MAX_RECORD_BYTES);
                JsonNode decoded = reader.read(text.getBytes(StandardCharsets.UTF_8));
                fields(decoded, "c", "n");
                require(canonical(decoded).equals(text));
                int count = integer(decoded.path("c"), 0, NativeDocumentReader.MAX_NODES);
                require(decoded.path("n").isObject() && !decoded.path("n").has("content")
                        && decoded.path("n").path("id").isTextual()
                        && UUID.fromString(decoded.path("n").path("id").textValue()).equals(record.key()));
                nodes.add((ObjectNode) decoded.path("n"));
                childCounts.add(count);
                fragments.add(parts);
            }
            require(recordBytes == envelope.payload().path("recordBytes").intValue());
            int[] next = {0};
            ObjectNode nativeRoot = assemble(nodes, childCounts, next, 1);
            require(next[0] == nodes.size());
            ObjectNode nativeJson = JsonNodeFactory.instance.objectNode().put("formatVersion", 1);
            nativeJson.set("root", nativeRoot);
            NativeDocument document = new NativeDocumentReader().read(JSON.canonicalBytes(nativeJson));
            require(document.nodeCount() == records.size());
            result = new NativeSnapshot(root, document, objects, fragments);
            return result;
        } catch (RuntimeException exception) {
            failed = true;
            throw sanitized(exception);
        }
    }

    private void validateObject(NewObject value) {
        require(value.encodingVersion() == VERSION && value.dagRank() <= 8);
        JsonNode data = value.payload();
        require(integer(data.path("codec"), VERSION, VERSION) == VERSION && data.path("role").isTextual());
        switch (data.path("role").textValue()) {
            case "record", "fragment" -> {
                fields(data, "codec", "role", "data");
                require(value.kind() == (role(value).equals("record") ? ObjectKind.BLOCK : ObjectKind.FRAGMENT)
                        && value.dagRank() == 0 && value.edges().isEmpty() && data.path("data").isTextual());
                String text = data.path("data").textValue();
                require(!text.isEmpty() && bytes(text) <= MAX_FRAGMENT_BYTES);
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    require(c != 0 && !Character.isLowSurrogate(c));
                    if (Character.isHighSurrogate(c)) require(++i < text.length() && Character.isLowSurrogate(text.charAt(i)));
                }
            }
            case "document" -> {
                fields(data, "codec", "role", "formatVersion", "nodeCount", "recordBytes");
                require(value.kind() == ObjectKind.PAGE && value.dagRank() == 8 && value.edges().size() == 1
                        && value.edges().getFirst().logicalKey() == null);
                integer(data.path("formatVersion"), 1, 1);
                integer(data.path("nodeCount"), 1, NativeDocumentReader.MAX_NODES);
                integer(data.path("recordBytes"), 1, MAX_RECORD_BYTES);
            }
            case "nodes", "fragments" -> {
                fields(data, "codec", "role", "treeHeight", "counts");
                int leafRank = role(value).equals("nodes") ? 4 : 1;
                int height = integer(data.path("treeHeight"), 0, leafRank == 4 ? 3 : 2);
                require(value.kind() == ObjectKind.PAGE && value.dagRank() == leafRank + height
                        && !value.edges().isEmpty() && data.path("counts").isArray()
                        && data.path("counts").size() == value.edges().size());
                for (JsonNode count : data.path("counts")) integer(count, 1, MAX_OBJECTS);
            }
            default -> throw new NativeStorageFailure(NativeStorageFailure.Code.INVALID_GRAPH);
        }
        payloadBytes += JSON.canonicalBytes(data).length;
        edges += value.edges().size();
        budget(payloadBytes <= MAX_PAYLOAD_BYTES && edges <= MAX_OBJECTS);
    }

    private List<Leaf> tree(UUID id, String expectedRole, int expectedHeight, boolean rootPage) {
        visit();
        NewObject page = object(id);
        require(role(page).equals(expectedRole));
        int height = page.payload().path("treeHeight").intValue();
        require(expectedHeight == -1 || height == expectedHeight);
        require(rootPage ? height == 0 || page.edges().size() >= 2 : page.edges().size() >= 16);
        List<Leaf> result = new ArrayList<>();
        for (int i = 0; i < page.edges().size(); i++) {
            var edge = page.edges().get(i);
            NewObject child = object(edge.child().objectId());
            require(child.dagRank() < page.dagRank());
            int before = result.size();
            if (height > 0) {
                require(edge.logicalKey() == null);
                result.addAll(tree(child.objectId(), expectedRole, height - 1, false));
            } else {
                require(expectedRole.equals("nodes") ? edge.logicalKey() != null : edge.logicalKey() == null);
                result.add(new Leaf(child.objectId(), edge.logicalKey()));
            }
            require(result.size() - before == page.payload().path("counts").get(i).intValue());
            budget(result.size() <= (expectedRole.equals("nodes") ? NativeDocumentReader.MAX_NODES : MAX_OBJECTS));
        }
        return result;
    }

    private List<String> record(UUID id) {
        NewObject value = object(id);
        if (role(value).equals("record")) {
            visit();
            return List.of(value.payload().path("data").textValue());
        }
        List<Leaf> leaves = tree(id, "fragments", -1, true);
        require(leaves.size() > 1);
        List<String> parts = new ArrayList<>();
        int length = 0;
        for (Leaf leaf : leaves) {
            visit();
            NewObject fragment = object(leaf.id());
            require(role(fragment).equals("fragment"));
            String part = fragment.payload().path("data").textValue();
            require(bytes(part) >= MIN_FRAGMENT_BYTES);
            length += bytes(part);
            budget(length <= MAX_RECORD_BYTES);
            parts.add(part);
        }
        return parts;
    }

    private ObjectNode assemble(List<ObjectNode> nodes, List<Integer> counts, int[] next, int depth) {
        require(depth <= NativeDocumentReader.MAX_DEPTH && next[0] < nodes.size());
        int index = next[0]++;
        ObjectNode node = nodes.get(index);
        var content = node.putArray("content");
        for (int i = 0; i < counts.get(index); i++) content.add(assemble(nodes, counts, next, depth + 1));
        return node;
    }

    private void visit() { budget(++expanded <= MAX_OBJECTS); }
    private NewObject object(UUID id) {
        NewObject result = objects.get(id);
        require(result != null);
        return result;
    }
    private static String role(NewObject object) { return object.payload().path("role").textValue(); }
    private static NativeStorageFailure sanitized(RuntimeException exception) {
        return exception instanceof NativeStorageFailure failure ? failure
                : new NativeStorageFailure(NativeStorageFailure.Code.INVALID_GRAPH);
    }
    private record Leaf(UUID id, UUID key) { }
}
