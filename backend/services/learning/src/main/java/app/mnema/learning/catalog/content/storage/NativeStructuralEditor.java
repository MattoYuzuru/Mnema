package app.mnema.learning.catalog.content.storage;

import app.mnema.learning.catalog.content.NativeDocument;
import app.mnema.learning.catalog.content.pages.CountedPages;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.*;
import app.mnema.learning.storage.StorageTypes.NewEdge;
import app.mnema.learning.storage.StorageTypes.NewObject;
import app.mnema.learning.storage.StorageTypes.ObjectKind;
import app.mnema.learning.storage.StorageTypes.ObjectRef;
import app.mnema.learning.storage.StorageTypes.StoredObject;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static app.mnema.learning.catalog.content.storage.NativeStorageFormat.*;

/** Native intent validation plus local page edits; publication/ACL/leases remain caller-owned. */
public final class NativeStructuralEditor {
    public static final int MAX_EDITS = 100;

    // Native-v1's editable container capabilities, not acceptance of opaque payloads.
    // Keep aligned with contracts/content/native-v1/README.md; schema validation remains in its reader.
    private static final Set<String> EDITABLE_CONTAINERS = Set.of("doc", "paragraph", "heading", "blockquote",
            "bullet_list", "ordered_list", "list_item", "link");

    public NativeEncodingPlan apply(NativeSnapshot previous, NativeDocument resulting,
                                    List<NativeStructuralEdit> edits) {
        Objects.requireNonNull(previous); Objects.requireNonNull(resulting); Objects.requireNonNull(edits);
        if (edits.isEmpty() || edits.size() > MAX_EDITS || edits.stream().anyMatch(Objects::isNull)) {
            throw new NativeStorageFailure(NativeStorageFailure.Code.INVALID_GRAPH);
        }
        List<Intent> intents = verify(previous.document(), resulting, List.copyOf(edits));
        if (resulting.toJson().equals(previous.document().toJson())) return new NativeEncodingPlan(previous, List.of(), previous.root());
        UUID scope = previous.root().reuseScopeId();
        Map<UUID, NewObject> created = new LinkedHashMap<>();
        ObjectSource source = ref -> {
            require(ref.reuseScopeId().equals(scope));
            return created.containsKey(ref.objectId()) ? created.get(ref.objectId()) : previous.objects().get(ref.objectId());
        };
        CountedPages pages = new CountedPages(Profile.nativeNodes(), source);
        NewObject oldEnvelope = previous.objects().get(previous.root().objectId());
        ObjectRef oldManifest = oldEnvelope.edges().getFirst().child();
        NewObject manifest = previous.objects().get(oldManifest.objectId());
        TreeRoot current = new TreeRoot(oldManifest, manifest.payload().path("treeHeight").intValue(), previous.document().nodeCount());
        List<Entry> oldEntries = new ArrayList<>();
        for (int start = 0; start < current.count(); start += 100) oldEntries.addAll(pages.read(current, start, 100));
        List<NativeSnapshotCodec.NodeRecord> oldRecords = NativeSnapshotCodec.flatten(previous.document());
        List<NativeSnapshotCodec.NodeRecord> nextRecords = NativeSnapshotCodec.flatten(resulting);
        Map<UUID, Integer> oldIndices = new HashMap<>();
        for (int i = 0; i < oldRecords.size(); i++) oldIndices.put(oldRecords.get(i).id(), i);
        RecordWriter writer = new RecordWriter(scope, previous, created, source);
        List<Entry> desired = new ArrayList<>();
        Map<UUID, Entry> desiredById = new HashMap<>();
        int recordBytes = 0;
        for (var record : nextRecords) {
            String text = canonical(record.value());
            recordBytes += bytes(text);
            budget(recordBytes <= MAX_RECORD_BYTES);
            Integer oldIndex = oldIndices.get(record.id());
            if (oldIndex != null && text.equals(String.join("", previous.fragments().get(oldIndex)))) {
                desired.add(oldEntries.get(oldIndex));
            } else {
                List<String> fragments = oldIndex == null ? split(text)
                        : NativeSnapshotCodec.replaceFragments(previous.fragments().get(oldIndex), text);
                desired.add(new Entry(record.id(), writer.record(fragments)));
            }
            desiredById.put(record.id(), desired.getLast());
        }
        List<Entry> order = new ArrayList<>(oldEntries);
        for (Intent intent : intents) {
            for (UUID removed : intent.removed()) {
                require(intent.from() < order.size() && order.get(intent.from()).key().equals(removed));
                current = keep(pages.delete(current, intent.from(), removed), created);
                order.remove(intent.from());
            }
            for (int i = 0; i < intent.inserted().size(); i++) {
                Entry entry = desiredById.get(intent.inserted().get(i));
                require(entry != null && intent.to() + i <= order.size());
                current = keep(pages.insert(current, intent.to() + i, entry), created);
                order.add(intent.to() + i, entry);
            }
        }
        require(order.size() == desired.size());
        for (int i = 0; i < desired.size(); i++) {
            require(order.get(i).key().equals(desired.get(i).key()));
            if (!order.get(i).equals(desired.get(i))) current = keep(pages.replace(current, i, order.get(i).key(), desired.get(i)), created);
        }
        NewObject root = new NewObject(UUID.randomUUID(), ObjectKind.PAGE, (short) 1, (short) 8,
                payload("document").put("formatVersion", 1).put("nodeCount", resulting.nodeCount()).put("recordBytes", recordBytes),
                List.of(new NewEdge(0, null, current.ref())));
        created.put(root.objectId(), root);
        ObjectRef rootRef = new ObjectRef(scope, root.objectId());
        var decoder = new NativeSnapshotDecoder(rootRef);
        while (!decoder.isComplete()) decoder.accept(decoder.requestedIds().stream()
                .map(id -> new StoredObject(new ObjectRef(scope, id), source.read(new ObjectRef(scope, id)))).toList());
        NativeSnapshot verified = decoder.snapshot();
        require(verified.document().toJson().equals(resulting.toJson()));
        List<NewObject> additions = new ArrayList<>();
        postorder(rootRef, created, new HashSet<>(), additions);
        return new NativeEncodingPlan(verified, additions, previous.root());
    }

    private static TreeRoot keep(PageEdit edit, Map<UUID, NewObject> created) {
        edit.additions().forEach(object -> created.put(object.objectId(), object));
        return edit.root();
    }

    private static void postorder(ObjectRef ref, Map<UUID, NewObject> created, Set<UUID> visited, List<NewObject> additions) {
        NewObject object = created.get(ref.objectId());
        if (object == null || !visited.add(ref.objectId())) return;
        object.edges().forEach(edge -> postorder(edge.child(), created, visited, additions));
        additions.add(object);
    }

    private static List<Intent> verify(NativeDocument before, NativeDocument after,
                                       List<NativeStructuralEdit> edits) {
        ObjectNode expected = (ObjectNode) before.toJson();
        ObjectNode root = (ObjectNode) expected.path("root");
        ObjectNode actual = (ObjectNode) after.toJson().path("root");
        require(editableContainer(root) && editableContainer(actual));
        Set<UUID> currentIds = new HashSet<>(preorderIds(root));
        Set<UUID> editedRoots = new HashSet<>();
        List<Intent> result = new ArrayList<>();
        for (NativeStructuralEdit edit : edits) {
            UUID editedRoot = switch (edit) {
                case NativeStructuralEdit.Insert insert -> insert.nodeId();
                case NativeStructuralEdit.Delete delete -> delete.nodeId();
                case NativeStructuralEdit.Move move -> move.nodeId();
            };
            require(editedRoots.add(editedRoot));
            switch (edit) {
                case NativeStructuralEdit.Insert insert -> {
                    Located parent = locate(root, insert.parentId());
                    Located finalNode = locate(actual, insert.nodeId());
                    require(parent != null && parent.editableAncestry() && editableContainer(parent.node())
                            && finalNode != null && insert.childIndex() <= parent.node().path("content").size());
                    ObjectNode inserted = finalNode.node().deepCopy();
                    List<UUID> insertedIds = preorderIds(inserted);
                    require(insertedIds.stream().noneMatch(currentIds::contains));
                    parent.node().withArray("content").insert(insert.childIndex(), inserted);
                    currentIds.addAll(insertedIds);
                    result.add(new Intent(0, ordinal(root, insert.nodeId()), List.of(), insertedIds));
                }
                case NativeStructuralEdit.Delete delete -> {
                    Located selected = locate(root, delete.nodeId());
                    require(selected != null && selected.parent() != null && selected.editableAncestry());
                    int from = ordinal(root, delete.nodeId());
                    List<UUID> removedIds = preorderIds(selected.node());
                    selected.parent().withArray("content").remove(selected.index());
                    currentIds.removeAll(removedIds);
                    result.add(new Intent(from, 0, removedIds, List.of()));
                }
                case NativeStructuralEdit.Move move -> {
                    Located selected = locate(root, move.nodeId());
                    require(selected != null && selected.parent() != null && selected.editableAncestry()
                            && locate(selected.node(), move.parentId()) == null);
                    int from = ordinal(root, move.nodeId());
                    List<UUID> movedIds = preorderIds(selected.node());
                    ObjectNode moved = selected.node();
                    selected.parent().withArray("content").remove(selected.index());
                    Located parent = locate(root, move.parentId());
                    require(parent != null && parent.editableAncestry() && editableContainer(parent.node())
                            && move.childIndexAfterRemoval() <= parent.node().path("content").size());
                    parent.node().withArray("content").insert(move.childIndexAfterRemoval(), moved);
                    result.add(new Intent(from, ordinal(root, move.nodeId()), movedIds, movedIds));
                }
            }
        }
        require(sameTopology(root, actual));
        return List.copyOf(result);
    }

    private static int ordinal(ObjectNode root, UUID id) {
        List<UUID> ids = preorderIds(root);
        for (int i = 0; i < ids.size(); i++) if (ids.get(i).equals(id)) return i;
        throw new NativeStorageFailure(NativeStorageFailure.Code.INVALID_GRAPH);
    }

    private static List<UUID> preorderIds(ObjectNode root) {
        List<UUID> result = new ArrayList<>();
        var pending = new ArrayDeque<ObjectNode>();
        pending.push(root);
        while (!pending.isEmpty()) {
            ObjectNode node = pending.pop();
            result.add(id(node));
            for (int i = node.path("content").size() - 1; i >= 0; i--) {
                pending.push((ObjectNode) node.path("content").get(i));
            }
        }
        return List.copyOf(result);
    }

    private static boolean sameTopology(ObjectNode expected, ObjectNode actual) {
        var pending = new ArrayDeque<ObjectNode[]>();
        pending.push(new ObjectNode[]{expected, actual});
        while (!pending.isEmpty()) {
            ObjectNode[] pair = pending.pop();
            if (!id(pair[0]).equals(id(pair[1]))
                    || pair[0].path("content").size() != pair[1].path("content").size()) return false;
            for (int i = 0; i < pair[0].path("content").size(); i++) {
                pending.push(new ObjectNode[]{(ObjectNode) pair[0].path("content").get(i),
                        (ObjectNode) pair[1].path("content").get(i)});
            }
        }
        return true;
    }

    private static Located locate(ObjectNode root, UUID id) {
        var pending = new ArrayDeque<Located>();
        pending.add(new Located(root, null, 0, true));
        while (!pending.isEmpty()) {
            Located current = pending.pop();
            if (id(current.node()).equals(id)) return current;
            for (int i = 0; i < current.node().path("content").size(); i++) {
                pending.push(new Located((ObjectNode) current.node().path("content").get(i), current.node(), i,
                        current.editableAncestry() && editableContainer(current.node())));
            }
        }
        return null;
    }

    private static UUID id(ObjectNode node) { return UUID.fromString(node.path("id").textValue()); }
    private static boolean editableContainer(ObjectNode node) {
        return node.path("version").intValue() == 1 && EDITABLE_CONTAINERS.contains(node.path("type").textValue());
    }
    private record Intent(int from, int to, List<UUID> removed, List<UUID> inserted) { }
    private record Located(ObjectNode node, ObjectNode parent, int index, boolean editableAncestry) { }

    private static final class RecordWriter {
        private final UUID scope;
        private final Map<UUID, NewObject> created;
        private final ObjectSource source;
        private final Map<String, NewObject> fragments = new HashMap<>();
        private final Map<PageValue, NewObject> fragmentPages = new HashMap<>();

        RecordWriter(UUID scope, NativeSnapshot previous, Map<UUID, NewObject> created, ObjectSource source) {
            this.scope = scope; this.created = created; this.source = source;
            previous.objects().values().stream().filter(object -> object.kind() == ObjectKind.FRAGMENT)
                    .forEach(object -> fragments.put(object.payload().path("data").textValue(), object));
            previous.objects().values().stream().filter(object -> object.payload().path("role").asText().equals("fragments"))
                    .forEach(object -> fragmentPages.put(PageValue.of(object), object));
        }

        ObjectRef record(List<String> pieces) {
            if (pieces.size() == 1) {
                NewObject block = new NewObject(UUID.randomUUID(), ObjectKind.BLOCK, (short) 1, (short) 0,
                        payload("record").put("data", pieces.getFirst()), List.of());
                created.put(block.objectId(), block);
                return new ObjectRef(scope, block.objectId());
            }
            List<Entry> entries = pieces.stream().map(piece -> {
                NewObject fragment = fragments.get(piece);
                if (fragment == null) {
                    fragment = new NewObject(UUID.randomUUID(), ObjectKind.FRAGMENT, (short) 1, (short) 0,
                            payload("fragment").put("data", piece), List.of());
                    fragments.put(piece, fragment);
                    created.put(fragment.objectId(), fragment);
                }
                return new Entry(null, new ObjectRef(scope, fragment.objectId()));
            }).toList();
            CountedPages pages = new CountedPages(new Profile("fragments", 1, MAX_RECORD_BYTES / MIN_FRAGMENT_BYTES, false, false), source);
            PageEdit tree = pages.build(scope, entries);
            Map<UUID, ObjectRef> reused = new HashMap<>();
            for (NewObject page : tree.additions()) {
                List<NewEdge> edges = page.edges().stream().map(edge -> new NewEdge(edge.ordinal(), edge.logicalKey(),
                        reused.getOrDefault(edge.child().objectId(), edge.child()))).toList();
                NewObject candidate = new NewObject(page.objectId(), page.kind(), page.encodingVersion(), page.dagRank(), page.payload(), edges);
                PageValue key = PageValue.of(candidate);
                NewObject equal = fragmentPages.get(key);
                if (equal == null) {
                    equal = candidate;
                    fragmentPages.put(key, equal);
                    created.put(equal.objectId(), equal);
                }
                reused.put(page.objectId(), new ObjectRef(scope, equal.objectId()));
            }
            return reused.get(tree.root().ref().objectId());
        }
        private record PageValue(short rank, String payload, List<NewEdge> edges) {
            static PageValue of(NewObject object) { return new PageValue(object.dagRank(), canonical(object.payload()), object.edges()); }
        }
    }
}
