package app.mnema.learning.catalog.content.storage;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.*;

import static app.mnema.learning.catalog.content.storage.NativeStorageFixtures.*;
import static org.assertj.core.api.Assertions.*;

class NativeStructuralEditorTest {
    private final NativeSnapshotCodec codec = new NativeSnapshotCodec();
    private final NativeStructuralEditor editor = new NativeStructuralEditor();

    @Test
    void insertDeleteAndMovePreserveOpaqueMetadataSpellingAndDirectOldRoots() {
        ObjectNode opaque = node(4, "future", text(5, "Я🌿"));
        opaque.put("id", opaque.path("id").asText().toUpperCase(Locale.ROOT));
        opaque.put("version", 42).putObject("unknown").put("keep", "exact");
        ObjectNode doc = document(node(2, "paragraph", text(3, "first")), opaque, node(6, "paragraph"));
        var original = codec.encode(UUID.randomUUID(), read(doc));
        ObjectNode inserted = doc.deepCopy();
        ((ObjectNode) inserted.path("root").path("content").get(2)).withArray("content").add(text(7, "new"));
        var first = editor.apply(original.snapshot(), read(inserted),
                List.of(new NativeStructuralEdit.Insert(id(7), id(6), 0)));
        exact(first, inserted);
        ObjectNode moved = inserted.deepCopy();
        var content = ((ObjectNode) moved.path("root")).withArray("content");
        var selected = content.remove(1);
        ((ObjectNode) content.get(0)).withArray("content").insert(0, selected);
        var second = editor.apply(first.snapshot(), read(moved),
                List.of(new NativeStructuralEdit.Move(id(4), id(2), 0)));
        exact(second, moved);
        ObjectNode deleted = moved.deepCopy();
        ((ObjectNode) deleted.path("root").path("content").get(0)).withArray("content").remove(0);
        var third = editor.apply(second.snapshot(), read(deleted),
                List.of(new NativeStructuralEdit.Delete(id(4))));
        exact(third, deleted);
        assertThat(decode(original).document().toJson()).isEqualTo(read(doc).toJson());
        assertThat(second.snapshot().objects().keySet()).containsAll(original.snapshot().objects().values().stream()
                .filter(value -> value.payload().path("role").asText().equals("record") && value.payload().path("data").asText().contains("exact"))
                .map(value -> value.objectId()).toList());
    }

    @Test
    void orderedBatchAppliesAtomicSubtreeInsertDeleteAndMoveWithValueChanges() {
        ObjectNode doc = document(
                node(2, "paragraph", text(3, "first")),
                node(4, "paragraph", text(5, "remove")),
                node(6, "paragraph", text(7, "move")));
        var old = codec.encode(UUID.randomUUID(), read(doc));
        ObjectNode next = doc.deepCopy();
        var content = ((ObjectNode) next.path("root")).withArray("content");
        content.remove(1);
        content.insert(1, node(8, "paragraph", text(9, "inserted subtree")));
        ObjectNode moved = (ObjectNode) content.remove(2);
        content.insert(0, moved);
        ((ObjectNode) content.get(1).path("content").get(0)).withObject("attrs").put("text", "changed too");

        var plan = editor.apply(old.snapshot(), read(next), List.of(
                new NativeStructuralEdit.Delete(id(4)),
                new NativeStructuralEdit.Insert(id(8), id(1), 1),
                new NativeStructuralEdit.Move(id(6), id(1), 0)));

        exact(plan, next);
        assertThat(decode(old).document().toJson()).isEqualTo(read(doc).toJson());
        long reused = old.snapshot().objects().keySet().stream().filter(plan.snapshot().objects()::containsKey).count();
        assertThat(reused).isPositive();
    }

    @Test
    void supportedLinkParentRetainsFragmentsAndNewOpaqueSubtreeReusesEqualFragmentBranches() {
        ObjectNode link = node(3, "link", text(4, "before"));
        link.withObject("attrs").put("href", "https://example.com/" + "x".repeat(1900));
        ObjectNode doc = document(node(2, "paragraph", link));
        var old = codec.encode(UUID.randomUUID(), read(doc));
        link.withArray("content").add(text(5, "after"));
        var next = editor.apply(old.snapshot(), read(doc),
                List.of(new NativeStructuralEdit.Insert(id(5), id(3), 1)));
        exact(next, doc);
        assertThat(next.additions()).hasSizeLessThan(10);
        assertThat(old.snapshot().objects().values().stream().filter(value -> value.kind() == app.mnema.learning.storage.StorageTypes.ObjectKind.FRAGMENT)
                .filter(value -> next.snapshot().objects().containsKey(value.objectId())).count()).isPositive();

        ObjectNode opaque = node(2, "future");
        opaque.put("extension", java.util.stream.IntStream.range(0, 1500).mapToObj(i -> i + " 日本語🌿;")
                .collect(java.util.stream.Collectors.joining()));
        ObjectNode opaqueDoc = document(opaque);
        var before = codec.encode(UUID.randomUUID(), read(opaqueDoc));
        ObjectNode copy = opaque.deepCopy().put("id", id(3).toString());
        ((ObjectNode) opaqueDoc.path("root")).withArray("content").add(copy);
        var inserted = editor.apply(before.snapshot(), read(opaqueDoc),
                List.of(new NativeStructuralEdit.Insert(id(3), id(1), 1)));
        exact(inserted, opaqueDoc);
        assertThat(inserted.additions()).hasSizeLessThan(10);
        assertThat(inserted.additions().stream().filter(value -> value.kind() == app.mnema.learning.storage.StorageTypes.ObjectKind.FRAGMENT)).hasSize(1);
    }

    @Test
    void beginningInsertCopiesPathsNotThePreorderSuffixAndRetainsOpaqueSiblingFragments() {
        ObjectNode doc = document();
        ObjectNode root = (ObjectNode) doc.path("root");
        for (int i = 2; i <= 2000; i++) root.withArray("content").add(node(i, "future"));
        ((ObjectNode) root.path("content").get(0)).put("opaque", java.util.stream.IntStream.range(0, 1500)
                .mapToObj(i -> i + " 日本語🌿;").collect(java.util.stream.Collectors.joining()));
        var before = codec.encode(UUID.randomUUID(), read(doc));
        ObjectNode next = doc.deepCopy();
        ((ObjectNode) next.path("root")).withArray("content").insert(0, node(2001, "future"));
        var plan = editor.apply(before.snapshot(), read(next),
                List.of(new NativeStructuralEdit.Insert(id(2001), id(1), 0)));
        exact(plan, next);
        long reusedFragments = before.snapshot().objects().values().stream().filter(value -> value.kind() == app.mnema.learning.storage.StorageTypes.ObjectKind.FRAGMENT)
                .filter(value -> plan.snapshot().objects().containsKey(value.objectId())).count();
        assertThat(reusedFragments).isGreaterThan(15);
        assertThat(plan.additions().size()).isLessThan(20);
        System.out.println("K3 native nodes=2000 insertObjects=" + plan.additions().size() + " reusedOpaqueSiblingFragments=" + reusedFragments);
    }

    @Test
    void nativeTenThousandEntriesAndDepthThirtyTwoRemainRepresentableAfterStructuralEdits() {
        ObjectNode many = document();
        for (int i = 2; i <= 9999; i++) ((ObjectNode) many.path("root")).withArray("content").add(node(i, "x"));
        var old = codec.encode(UUID.randomUUID(), read(many));
        ObjectNode next = many.deepCopy();
        ((ObjectNode) next.path("root")).withArray("content").insert(0, node(10_000, "x"));
        var plan = editor.apply(old.snapshot(), read(next),
                List.of(new NativeStructuralEdit.Insert(id(10_000), id(1), 0)));
        exact(plan, next);
        assertThat(plan.snapshot().document().nodeCount()).isEqualTo(10_000);
        assertThat(plan.additions()).hasSizeLessThan(15);
        ObjectNode deepest = node(31, "paragraph");
        ObjectNode chain = deepest;
        for (int i = 30; i >= 2; i--) chain = node(i, "blockquote", chain);
        ObjectNode doc = document(chain);
        var deep = codec.encode(UUID.randomUUID(), read(doc));
        deepest.withArray("content").add(text(32, "deep"));
        exact(editor.apply(deep.snapshot(), read(doc),
                List.of(new NativeStructuralEdit.Insert(id(32), id(31), 0))), doc);
    }

    @Test
    void futureDocumentsAndEditingWithinOpaqueAncestryFailClosed() {
        ObjectNode future = document(node(2, "future"), node(3, "future"));
        ((ObjectNode) future.path("root")).put("version", 42);
        var futureSnapshot = codec.encode(UUID.randomUUID(), read(future)).snapshot();
        ObjectNode futureNext = future.deepCopy();
        ((ObjectNode) futureNext.path("root")).withArray("content").remove(0);
        assertThatThrownBy(() -> editor.apply(futureSnapshot, read(futureNext),
                List.of(new NativeStructuralEdit.Delete(id(2)))))
                .isInstanceOf(NativeStorageFailure.class);
        assertThatThrownBy(() -> editor.apply(futureSnapshot, read(future),
                List.of(new NativeStructuralEdit.Move(id(2), id(1), 0))))
                .isInstanceOf(NativeStorageFailure.class);

        ObjectNode doc = document(node(2, "future", node(3, "paragraph", text(4, "inside"))), node(5, "paragraph", text(6, "outside")));
        var old = codec.encode(UUID.randomUUID(), read(doc)).snapshot();
        ObjectNode insert = doc.deepCopy();
        ((ObjectNode) insert.path("root").path("content").get(0).path("content").get(0)).withArray("content").add(text(7, "new"));
        assertThatThrownBy(() -> editor.apply(old, read(insert),
                List.of(new NativeStructuralEdit.Insert(id(7), id(3), 1))))
                .isInstanceOf(NativeStorageFailure.class);
        ObjectNode delete = doc.deepCopy();
        ((ObjectNode) delete.path("root").path("content").get(0).path("content").get(0)).withArray("content").remove(0);
        assertThatThrownBy(() -> editor.apply(old, read(delete),
                List.of(new NativeStructuralEdit.Delete(id(4)))))
                .isInstanceOf(NativeStorageFailure.class);
        ObjectNode moveOut = doc.deepCopy();
        var inside = ((ObjectNode) moveOut.path("root").path("content").get(0).path("content").get(0)).withArray("content").remove(0);
        ((ObjectNode) moveOut.path("root").path("content").get(1)).withArray("content").add(inside);
        assertThatThrownBy(() -> editor.apply(old, read(moveOut),
                List.of(new NativeStructuralEdit.Move(id(4), id(5), 1))))
                .isInstanceOf(NativeStorageFailure.class);
        ObjectNode moveIn = doc.deepCopy();
        var outside = ((ObjectNode) moveIn.path("root").path("content").get(1)).withArray("content").remove(0);
        ((ObjectNode) moveIn.path("root").path("content").get(0).path("content").get(0)).withArray("content").add(outside);
        assertThatThrownBy(() -> editor.apply(old, read(moveIn),
                List.of(new NativeStructuralEdit.Move(id(6), id(3), 1))))
                .isInstanceOf(NativeStorageFailure.class);
        ObjectNode direct = doc.deepCopy();
        ((ObjectNode) direct.path("root").path("content").get(0)).withArray("content").add(node(7, "future"));
        assertThatThrownBy(() -> editor.apply(old, read(direct),
                List.of(new NativeStructuralEdit.Insert(id(7), id(2), 1))))
                .isInstanceOf(NativeStorageFailure.class);
    }

    @Test
    void exactOneMibInputStillFitsAfterDeletingAnotherSubtree() {
        ObjectNode huge = node(2, "future");
        for (int i = 0; i < 31; i++) huge.withObject("attrs").put("field" + i, "x".repeat(32_768));
        huge.withObject("attrs").put("padding", "");
        ObjectNode doc = document(huge, node(3, "future"));
        int remaining = app.mnema.learning.catalog.content.NativeDocumentReader.MAX_BYTES - NativeStorageFormat.JSON.canonicalBytes(doc).length;
        huge.withObject("attrs").put("padding", "x".repeat(remaining));
        var old = codec.encode(UUID.randomUUID(), read(doc));
        ((ObjectNode) doc.path("root")).withArray("content").remove(1);
        var plan = editor.apply(old.snapshot(), read(doc), List.of(new NativeStructuralEdit.Delete(id(3))));
        exact(plan, doc);
        assertThat(plan.additions()).hasSizeLessThan(5);
    }

    @Test
    void invalidIntentRootMovesCyclesAndUnrelatedMetadataChangesFailClosed() {
        ObjectNode doc = document(node(2, "paragraph", text(3, "child")), node(4, "paragraph"));
        var old = codec.encode(UUID.randomUUID(), read(doc)).snapshot();
        assertThatThrownBy(() -> editor.apply(old, read(doc), List.of(new NativeStructuralEdit.Delete(id(1))))).isInstanceOf(NativeStorageFailure.class);
        assertThatThrownBy(() -> editor.apply(old, read(doc), List.of(new NativeStructuralEdit.Move(id(1), id(4), 0)))).isInstanceOf(NativeStorageFailure.class);
        assertThatThrownBy(() -> editor.apply(old, read(doc), List.of(new NativeStructuralEdit.Move(id(2), id(3), 0)))).isInstanceOf(NativeStorageFailure.class);
        assertThatThrownBy(() -> editor.apply(old, read(doc), List.of(new NativeStructuralEdit.Insert(id(999), id(4), 4)))).isInstanceOf(NativeStorageFailure.class);
        assertThatThrownBy(() -> editor.apply(old, read(doc), List.of(new NativeStructuralEdit.Delete(id(999))))).isInstanceOf(NativeStorageFailure.class);
        ObjectNode next = doc.deepCopy();
        ((ObjectNode) next.path("root")).withArray("content").remove(1);
        ((ObjectNode) next.path("root")).put("version", 42);
        assertThatThrownBy(() -> editor.apply(old, read(next), List.of(new NativeStructuralEdit.Delete(id(4))))).isInstanceOf(NativeStorageFailure.class);
        var noop = editor.apply(old, read(doc), List.of(new NativeStructuralEdit.Move(id(4), id(1), 1)));
        assertThat(noop.snapshot()).isSameAs(old);
        assertThat(noop.additions()).isEmpty();
        assertThatThrownBy(() -> new NativeStructuralEdit.Insert(id(2), id(1), -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NativeStructuralEdit.Move(id(2), id(1), -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> editor.apply(old, read(doc), List.of())).isInstanceOf(NativeStorageFailure.class);
        assertThatThrownBy(() -> editor.apply(old, read(doc), Collections.nCopies(
                NativeStructuralEditor.MAX_EDITS + 1, new NativeStructuralEdit.Move(id(4), id(1), 1))))
                .isInstanceOf(NativeStorageFailure.class);
        assertThatThrownBy(() -> editor.apply(old, read(doc), List.of(
                new NativeStructuralEdit.Move(id(4), id(1), 1),
                new NativeStructuralEdit.Move(id(4), id(1), 1))))
                .isInstanceOf(NativeStorageFailure.class);
    }

    private static UUID id(int number) { return UUID.fromString(node(number, "future").path("id").asText()); }

    private static void exact(NativeEncodingPlan plan, ObjectNode expected) {
        assertThat(decode(plan).document().toJson()).isEqualTo(read(expected).toJson());
        Set<UUID> seen = new HashSet<>();
        Set<UUID> ids = new HashSet<>();
        plan.additions().forEach(value -> ids.add(value.objectId()));
        for (var value : plan.additions()) {
            assertThat(plan.snapshot().objects()).containsKey(value.objectId());
            assertThat(value.dagRank()).isLessThanOrEqualTo((short) 8);
            for (var edge : value.edges()) if (ids.contains(edge.child().objectId())) assertThat(seen).contains(edge.child().objectId());
            seen.add(value.objectId());
        }
    }
}
