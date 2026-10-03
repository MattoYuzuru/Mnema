package app.mnema.learning.catalog.content.storage;

import app.mnema.learning.catalog.content.NativeDocument;
import app.mnema.learning.catalog.content.NativeDocumentReader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Plans the save of a <em>revised</em> document of an existing material (REVISE_ITEM, #294): the structural edits that turn the stored
 * document into the revised one, which {@link NativeStructuralEditor} wants whenever the shape of the tree changes (a plain replace keeps
 * every node and child count). A rewrite by a model keeps the node id of a block whose type is unchanged and gives every other node a new
 * one, so the plan has three parts:
 * <ol>
 *   <li><b>Identity.</b> Children are matched by position and type: a new child at the position of an old child of the same type, whose old
 *       id the revised tree no longer uses, takes the old id (and so on down), so an inline node that was merely rewritten (a paragraph's
 *       text) is not a structural change at all and the nodes keep the identity that exercises and drafts may point at.</li>
 *   <li><b>Edits.</b> What is left is one {@code Delete} per old child the revised tree lacks and one {@code Insert} per child it adds, at its
 *       final index, under a container that native-v1 lets be edited; children that stay keep their order.</li>
 *   <li><b>Fallback.</b> A top-level block whose change cannot be expressed (a table row, a reordering) is replaced as a whole: the old block is
 *       deleted and the revised one, with all-new identifiers, inserted at its place.</li>
 * </ol>
 * The result is the document to publish (the revised one with the identifiers of 1.) and the edits; both are verified by the storage editor
 * when the save is prepared.
 */
public final class NativeRevisionPlanner {
    private static final Set<String> EDITABLE = Set.of("doc", "paragraph", "heading", "blockquote", "bullet_list", "ordered_list", "list_item", "link");

    private NativeRevisionPlanner() { }

    /**
     * @param document the revised document to save (identifiers harmonized with the stored one)
     * @param edits the structural edits from the stored document to {@code document}; empty when only content changed
     */
    public record Plan(NativeDocument document, List<NativeStructuralEdit> edits) { }

    public static Plan plan(NativeDocument before, NativeDocument after) {
        ObjectNode oldRoot = (ObjectNode) before.toJson().path("root").deepCopy();
        ObjectNode revised = (ObjectNode) after.toJson().deepCopy();
        ObjectNode newRoot = (ObjectNode) revised.path("root");
        Set<UUID> oldIds = new HashSet<>();
        ids(oldRoot, oldIds);
        Set<UUID> newIds = new HashSet<>();
        ids(newRoot, newIds);
        harmonize(oldRoot, newRoot, oldIds, newIds);

        List<NativeStructuralEdit> edits = new ArrayList<>();
        diffRoot(oldRoot, newRoot, edits);
        NativeDocument document = new NativeDocumentReader().read(revised.toString().getBytes(StandardCharsets.UTF_8));
        return new Plan(document, List.copyOf(edits));
    }

    // ---------------------------------------------------------------------- identity

    /**
     * Gives the new children the ids of the old ones they stand in for: first the children the revised tree kept by id are matched, then
     * each child with an id the stored tree never had takes, in order, the first old child of its type that the revised tree no longer uses.
     */
    private static void harmonize(ObjectNode old, ObjectNode revised, Set<UUID> oldIds, Set<UUID> newIds) {
        ArrayNode before = (ArrayNode) old.path("content");
        ArrayNode after = (ArrayNode) revised.path("content");
        Map<UUID, ObjectNode> was = new HashMap<>();
        before.forEach(child -> was.put(id((ObjectNode) child), (ObjectNode) child));
        for (JsonNode child : after) {
            ObjectNode now = (ObjectNode) child;
            ObjectNode previous = was.get(id(now));
            if (previous != null && type(previous).equals(type(now))) harmonize(previous, now, oldIds, newIds);
        }
        List<ObjectNode> free = new ArrayList<>();
        before.forEach(child -> {
            if (!newIds.contains(id((ObjectNode) child))) free.add((ObjectNode) child);
        });
        for (JsonNode child : after) {
            ObjectNode now = (ObjectNode) child;
            UUID newId = id(now);
            // a child whose id the stored tree has is not new, even when it moved or changed type
            if (oldIds.contains(newId)) continue;
            for (int index = 0; index < free.size(); index++) {
                ObjectNode candidate = free.get(index);
                if (!type(candidate).equals(type(now))) continue;
                free.remove(index);
                newIds.remove(newId);
                newIds.add(id(candidate));
                now.put("id", candidate.path("id").stringValue(""));
                harmonize(candidate, now, oldIds, newIds);
                break;
            }
        }
    }

    // ------------------------------------------------------------------------ edits

    /** The root is a container whose children are the blocks; a block that cannot be diffed in place is replaced as a whole. */
    private static void diffRoot(ObjectNode old, ObjectNode revised, List<NativeStructuralEdit> edits) {
        ArrayNode before = (ArrayNode) old.path("content");
        ArrayNode after = (ArrayNode) revised.path("content");
        Map<UUID, ObjectNode> kept = new HashMap<>();
        before.forEach(child -> kept.put(id((ObjectNode) child), (ObjectNode) child));
        // blocks that kept their id but not their order cannot be moved by an edit of this slice: they are replaced as wholes
        List<UUID> orderBefore = new ArrayList<>();
        before.forEach(child -> {
            if (contains(after, id((ObjectNode) child))) orderBefore.add(id((ObjectNode) child));
        });
        List<UUID> orderAfter = new ArrayList<>();
        after.forEach(child -> {
            if (kept.containsKey(id((ObjectNode) child))) orderAfter.add(id((ObjectNode) child));
        });
        if (!orderBefore.equals(orderAfter)) {
            for (int index = 0; index < orderAfter.size(); index++) {
                if (orderAfter.get(index).equals(orderBefore.get(index))) continue;
                UUID moved = orderAfter.get(index);
                for (JsonNode child : after) {
                    if (id((ObjectNode) child).equals(moved)) renew((ObjectNode) child);
                }
            }
            kept.clear();
            before.forEach(child -> kept.put(id((ObjectNode) child), (ObjectNode) child));
        }
        List<NativeStructuralEdit> inPlace = new ArrayList<>();
        for (int index = 0; index < after.size(); index++) {
            ObjectNode now = (ObjectNode) after.get(index);
            ObjectNode was = kept.get(id(now));
            if (was == null) continue;
            List<NativeStructuralEdit> scratch = new ArrayList<>();
            if (diff(was, now, scratch)) {
                inPlace.addAll(scratch);
            } else {
                // not expressible in place: the block gets all-new identifiers, so it is the old one deleted and a new one inserted
                renew(now);
                after.set(index, now);
            }
        }
        edits.addAll(inPlace);
        childEdits(old, revised, edits);
    }

    /** True when {@code revised} follows from {@code old} by edits inside editable containers; the edits are appended. */
    private static boolean diff(ObjectNode old, ObjectNode revised, List<NativeStructuralEdit> edits) {
        ArrayNode before = (ArrayNode) old.path("content");
        ArrayNode after = (ArrayNode) revised.path("content");
        Set<UUID> present = new HashSet<>();
        before.forEach(child -> present.add(id((ObjectNode) child)));
        Set<UUID> wanted = new HashSet<>();
        after.forEach(child -> wanted.add(id((ObjectNode) child)));
        List<UUID> keptBefore = new ArrayList<>();
        before.forEach(child -> {
            if (wanted.contains(id((ObjectNode) child))) keptBefore.add(id((ObjectNode) child));
        });
        List<UUID> keptAfter = new ArrayList<>();
        after.forEach(child -> {
            if (present.contains(id((ObjectNode) child))) keptAfter.add(id((ObjectNode) child));
        });
        if (!keptBefore.equals(keptAfter)) return false;
        if (!(present.equals(wanted)) && !editable(old)) return false;
        Map<UUID, ObjectNode> was = new HashMap<>();
        before.forEach(child -> was.put(id((ObjectNode) child), (ObjectNode) child));
        List<NativeStructuralEdit> inner = new ArrayList<>();
        for (JsonNode child : after) {
            ObjectNode now = (ObjectNode) child;
            ObjectNode previous = was.get(id(now));
            if (previous != null && !diff(previous, now, inner)) return false;
        }
        // an edit below a container that native-v1 does not let be edited is not an edit: the block is replaced instead
        if (!inner.isEmpty() && !editable(old)) return false;
        edits.addAll(inner);
        childEdits(old, revised, edits);
        return true;
    }

    /** The deletes of the old children the revised tree lacks, then the inserts of the ones it adds (each at its final index). */
    private static void childEdits(ObjectNode old, ObjectNode revised, List<NativeStructuralEdit> edits) {
        Set<UUID> wanted = new HashSet<>();
        revised.path("content").forEach(child -> wanted.add(id((ObjectNode) child)));
        Set<UUID> present = new HashSet<>();
        old.path("content").forEach(child -> present.add(id((ObjectNode) child)));
        for (JsonNode child : old.path("content")) {
            UUID id = id((ObjectNode) child);
            if (!wanted.contains(id)) edits.add(new NativeStructuralEdit.Delete(id));
        }
        for (int index = 0; index < revised.path("content").size(); index++) {
            UUID id = id((ObjectNode) revised.path("content").get(index));
            if (!present.contains(id)) edits.add(new NativeStructuralEdit.Insert(id, id(old), index));
        }
    }

    private static boolean contains(ArrayNode children, UUID id) {
        for (JsonNode child : children) if (id((ObjectNode) child).equals(id)) return true;
        return false;
    }

    /** All-new identifiers for a node and everything below it. */
    private static void renew(ObjectNode node) {
        node.put("id", UUID.randomUUID().toString());
        node.path("content").forEach(child -> renew((ObjectNode) child));
    }

    // ---------------------------------------------------------------------- helpers

    private static boolean editable(ObjectNode node) {
        return node.path("version").intValue(0) == 1 && EDITABLE.contains(type(node));
    }

    private static String type(ObjectNode node) {
        return node.path("type").stringValue("");
    }

    private static UUID id(ObjectNode node) {
        return UUID.fromString(node.path("id").stringValue(""));
    }

    private static void ids(ObjectNode node, Set<UUID> into) {
        into.add(id(node));
        node.path("content").forEach(child -> ids((ObjectNode) child, into));
    }
}
