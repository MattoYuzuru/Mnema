package app.mnema.learning.catalog.content.storage;

import app.mnema.learning.catalog.content.NativeDocument;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.storage.StorageTypes.NewEdge;
import app.mnema.learning.storage.StorageTypes.NewObject;
import app.mnema.learning.storage.StorageTypes.ObjectKind;
import app.mnema.learning.storage.StorageTypes.ObjectRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static app.mnema.learning.catalog.content.storage.NativeStorageFormat.*;

/** Pure format-v1 codec. Full-document validation/planning is O(bytes + nodes), never a history replay. */
public final class NativeSnapshotCodec {

    public NativeEncodingPlan encode(UUID scope, NativeDocument document) {
        UuidPolicy.requireEntityId(scope, "reuseScopeId");
        return build(scope, Objects.requireNonNull(document), null);
    }

    /** Only existing preorder identities and child counts may be replaced in this slice. */
    public NativeEncodingPlan replace(NativeSnapshot previous, NativeDocument document) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(document);
        List<NodeRecord> old = flatten(previous.document());
        List<NodeRecord> next = flatten(document);
        if (old.size() != next.size()) throw new NativeStorageFailure(NativeStorageFailure.Code.STRUCTURE_CHANGED);
        for (int i = 0; i < old.size(); i++) {
            if (!old.get(i).id().equals(next.get(i).id()) || old.get(i).children() != next.get(i).children()) {
                throw new NativeStorageFailure(NativeStorageFailure.Code.STRUCTURE_CHANGED);
            }
        }
        return build(previous.root().reuseScopeId(), document, previous);
    }

    private NativeEncodingPlan build(UUID scope, NativeDocument document, NativeSnapshot previous) {
        Builder builder = new Builder(scope, previous);
        List<NodeRecord> records = flatten(document);
        List<Entry> entries = new ArrayList<>();
        List<List<String>> fragments = new ArrayList<>();
        int recordBytes = 0;
        for (int i = 0; i < records.size(); i++) {
            NodeRecord record = records.get(i);
            String value = canonical(record.value());
            recordBytes += bytes(value);
            budget(recordBytes <= MAX_RECORD_BYTES);
            List<String> pieces = previous == null ? split(value) : replaceFragments(previous.fragments().get(i), value);
            fragments.add(pieces);
            Entry recordRoot;
            if (pieces.size() == 1) {
                recordRoot = builder.object(ObjectKind.BLOCK, 0, payload("record").put("data", pieces.getFirst()), List.of(), 1);
            } else {
                List<Entry> chunks = pieces.stream().map(piece -> builder.object(ObjectKind.FRAGMENT, 0,
                        payload("fragment").put("data", piece), List.of(), 1)).toList();
                recordRoot = builder.tree(chunks, "fragments", 1);
            }
            entries.add(new Entry(recordRoot.object(), record.id(), 1));
        }
        Entry manifest = builder.tree(entries, "nodes", 4);
        Entry root = builder.object(ObjectKind.PAGE, 8,
                payload("document").put("formatVersion", 1).put("nodeCount", records.size())
                        .put("recordBytes", recordBytes),
                List.of(new NewEdge(0, null, new ObjectRef(scope, manifest.object().objectId()))), records.size());
        NativeSnapshot snapshot = new NativeSnapshot(new ObjectRef(scope, root.object().objectId()),
                document, builder.objects, fragments);
        return new NativeEncodingPlan(snapshot, builder.additions, previous == null ? null : previous.root());
    }

    static List<NodeRecord> flatten(NativeDocument document) {
        List<NodeRecord> result = new ArrayList<>();
        var pending = new ArrayDeque<JsonNode>();
        pending.push(document.toJson().path("root"));
        while (!pending.isEmpty()) {
            JsonNode node = pending.pop();
            ObjectNode metadata = JsonNodeFactory.instance.objectNode();
            node.properties().stream().filter(field -> !field.getKey().equals("content"))
                    .forEach(field -> metadata.set(field.getKey(), field.getValue().deepCopy()));
            ObjectNode record = JsonNodeFactory.instance.objectNode().put("c", node.path("content").size());
            record.set("n", metadata);
            result.add(new NodeRecord(UUID.fromString(node.path("id").textValue()), node.path("content").size(), record));
            for (int i = node.path("content").size() - 1; i >= 0; i--) pending.push(node.path("content").get(i));
        }
        return result;
    }

    /** Keep complete old fragments outside the changed window; insertion does not shift suffix boundaries. */
    static List<String> replaceFragments(List<String> old, String next) {
        String before = String.join("", old);
        if (before.equals(next)) return old;
        int prefix = 0;
        while (prefix < before.length() && prefix < next.length() && before.charAt(prefix) == next.charAt(prefix)) prefix++;
        if (prefix < before.length() && Character.isLowSurrogate(before.charAt(prefix))) prefix--;
        int suffix = 0;
        while (suffix < before.length() - prefix && suffix < next.length() - prefix
                && before.charAt(before.length() - suffix - 1) == next.charAt(next.length() - suffix - 1)) suffix++;
        if (suffix > 0 && Character.isLowSurrogate(before.charAt(before.length() - suffix))) suffix--;
        int head = 0;
        int headChars = 0;
        while (head < old.size() && headChars + old.get(head).length() <= prefix) headChars += old.get(head++).length();
        int tail = old.size();
        int tailChars = 0;
        while (tail > head && tailChars + old.get(tail - 1).length() <= suffix) tailChars += old.get(--tail).length();
        String middle = next.substring(headChars, next.length() - tailChars);
        List<String> result = new ArrayList<>(old.subList(0, head));
        if (!middle.isEmpty()) {
            if (bytes(middle) <= MAX_FRAGMENT_BYTES) result.add(middle);
            else result.addAll(split(middle));
        }
        result.addAll(old.subList(tail, old.size()));
        return repair(result);
    }

    record NodeRecord(UUID id, int children, ObjectNode value) { }
    private record Entry(NewObject object, UUID key, int count) { }
    private record ValueKey(ObjectKind kind, short rank, String payload, List<NewEdge> edges) {
        static ValueKey of(NewObject object) {
            return new ValueKey(object.kind(), object.dagRank(), canonical(object.payload()), object.edges());
        }
    }

    private static final class Builder {
        private final UUID scope;
        private final Map<ValueKey, NewObject> reuse = new HashMap<>();
        private final Map<UUID, NewObject> objects = new LinkedHashMap<>();
        private final List<NewObject> additions = new ArrayList<>();
        private int payloadBytes;

        Builder(UUID scope, NativeSnapshot previous) {
            this.scope = scope;
            if (previous != null) previous.objects().values().forEach(object -> reuse.put(ValueKey.of(object), object));
        }

        Entry object(ObjectKind kind, int rank, ObjectNode payload, List<NewEdge> edges, int count) {
            require(rank <= 8);
            String encoded = canonical(payload);
            // The versioned shapes add less than 256 JSONB whitespace bytes to canonical text.
            budget(bytes(encoded) + 256 <= 16_384);
            ValueKey key = new ValueKey(kind, (short) rank, encoded, edges);
            NewObject object = reuse.get(key);
            if (object == null) {
                object = new NewObject(UUID.randomUUID(), kind, (short) VERSION, (short) rank, payload, edges);
                additions.add(object);
                reuse.put(key, object);
            }
            if (objects.putIfAbsent(object.objectId(), object) == null) {
                payloadBytes += bytes(encoded);
                budget(objects.size() <= MAX_OBJECTS && payloadBytes <= MAX_PAYLOAD_BYTES);
            }
            return new Entry(object, null, count);
        }

        Entry tree(List<Entry> entries, String role, int leafRank) {
            List<Entry> level = entries;
            int height = 0;
            do {
                List<Entry> parents = new ArrayList<>();
                int groups = (level.size() + FANOUT - 1) / FANOUT;
                int start = 0;
                for (int group = 0; group < groups; group++) {
                    int length = level.size() / groups + (group < level.size() % groups ? 1 : 0);
                    ObjectNode data = payload(role).put("treeHeight", height);
                    var counts = data.putArray("counts");
                    List<NewEdge> edges = new ArrayList<>();
                    int count = 0;
                    for (int i = 0; i < length; i++) {
                        Entry entry = level.get(start + i);
                        counts.add(entry.count());
                        count += entry.count();
                        edges.add(new NewEdge(i, entry.key(), new ObjectRef(scope, entry.object().objectId())));
                    }
                    parents.add(object(ObjectKind.PAGE, leafRank + height, data, edges, count));
                    start += length;
                }
                level = parents;
                height++;
            } while (level.size() > 1);
            return level.getFirst();
        }
    }
}
