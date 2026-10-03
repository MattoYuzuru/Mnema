package app.mnema.learning.catalog.content.storage;

import app.mnema.learning.catalog.content.NativeDocument;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static app.mnema.learning.catalog.content.storage.NativeStorageFixtures.decode;
import static app.mnema.learning.catalog.content.storage.NativeStorageFixtures.document;
import static app.mnema.learning.catalog.content.storage.NativeStorageFixtures.node;
import static app.mnema.learning.catalog.content.storage.NativeStorageFixtures.read;
import static app.mnema.learning.catalog.content.storage.NativeStorageFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plan of a revised material (REVISE_ITEM, #294): whatever shape the model's rewrite has, the plan's document and edits are accepted by
 * the storage editor and give exactly the document of the plan, and a rewrite that only changes content (or whose new inline nodes stand in for
 * the old ones) needs no structural edit at all.
 */
class NativeRevisionPlannerTest {
    private final NativeSnapshotCodec codec = new NativeSnapshotCodec();
    private final NativeStructuralEditor editor = new NativeStructuralEditor();

    private static UUID id(int number) {
        return UUID.fromString(node(number, "future").path("id").asString());
    }

    /** Plans {@code revised} against {@code stored}, saves it the way the catalog does and returns the plan; fails if the editor refuses it. */
    private NativeRevisionPlanner.Plan saved(ObjectNode stored, ObjectNode revised) {
        NativeDocument before = read(stored);
        NativeRevisionPlanner.Plan plan = NativeRevisionPlanner.plan(before, read(revised));
        var old = codec.encode(UUID.randomUUID(), before);
        NativeEncodingPlan result = plan.edits().isEmpty() ? codec.replace(old.snapshot(), plan.document())
                : editor.apply(old.snapshot(), plan.document(), plan.edits());
        assertThat(decode(result).document().toJson()).isEqualTo(plan.document().toJson());
        return plan;
    }

    private static List<String> types(List<NativeStructuralEdit> edits) {
        List<String> kinds = new ArrayList<>();
        for (NativeStructuralEdit edit : edits) kinds.add(edit.getClass().getSimpleName());
        return kinds;
    }

    private static List<String> blockIds(NativeDocument document) {
        List<String> ids = new ArrayList<>();
        document.toJson().path("root").path("content").forEach(block -> ids.add(block.path("id").asString()));
        return ids;
    }

    @Test
    void aRewriteThatOnlyChangesContentNeedsNoStructuralEditEvenWhenItsInlineNodesHaveNewIds() {
        ObjectNode stored = document(node(2, "paragraph", text(3, "первый")), node(4, "paragraph", text(5, "второй")));
        // the rewrite keeps the block ids (same type) and gives the text nodes new ids, as the compiler does
        ObjectNode revised = document(node(2, "paragraph", text(30, "первый, переписанный")), node(4, "paragraph", text(50, "второй")));

        NativeRevisionPlanner.Plan plan = saved(stored, revised);

        assertThat(plan.edits()).isEmpty();
        // the new inline nodes took the identity of the ones they stand in for
        assertThat(plan.document().toJson().path("root").path("content").get(0).path("content").get(0).path("id").asString()).isEqualTo(id(3).toString());
        assertThat(plan.document().toJson().path("root").path("content").get(0).path("content").get(0).path("attrs").path("text").asString())
                .isEqualTo("первый, переписанный");
    }

    @Test
    void childrenThatAppearOrDisappearAreInsertedAndDeletedAtTheirFinalPlace() {
        ObjectNode stored = document(node(2, "paragraph", text(3, "один")), node(4, "paragraph", text(5, "два")), node(6, "paragraph", text(7, "три")));
        // a paragraph grows a second text, another is replaced by one with other words, and one appears at the end
        ObjectNode revised = document(node(2, "paragraph", text(30, "один"), text(31, "ещё")), node(10, "paragraph", text(11, "новый")),
                node(6, "paragraph", text(70, "три")), node(12, "paragraph", text(13, "в конце")));

        NativeRevisionPlanner.Plan plan = saved(stored, revised);

        // the replaced paragraph is the old one rewritten (same place, same type); only what really appears is an insert
        assertThat(types(plan.edits())).containsExactly("Insert", "Insert");
        assertThat(blockIds(plan.document())).containsExactly(id(2).toString(), id(4).toString(), id(6).toString(), id(12).toString());

        // a paragraph that goes away without a replacement is a delete
        NativeRevisionPlanner.Plan shorter = saved(stored, document(node(2, "paragraph", text(30, "один")), node(6, "paragraph", text(70, "три"))));
        assertThat(types(shorter.edits())).containsExactly("Delete");
        assertThat(blockIds(shorter.document())).containsExactly(id(2).toString(), id(6).toString());
    }

    @Test
    void aRewrittenListKeepsItsIdAndReplacesItsItems() {
        ObjectNode stored = document(node(2, "bullet_list", node(3, "list_item", node(4, "paragraph", text(5, "а"))),
                node(6, "list_item", node(7, "paragraph", text(8, "б")))));
        ObjectNode revised = document(node(2, "bullet_list", node(30, "list_item", node(40, "paragraph", text(50, "а"))),
                node(60, "list_item", node(70, "paragraph", text(80, "б"))), node(90, "list_item", node(91, "paragraph", text(92, "в")))));

        NativeRevisionPlanner.Plan plan = saved(stored, revised);

        // the two items that were there stand in for the old ones all the way down: one insert is all that is structural
        assertThat(types(plan.edits())).containsExactly("Insert");
        assertThat(blockIds(plan.document())).containsExactly(id(2).toString());
    }

    @Test
    void aBlockOfAnotherTypeIsAReplacementAndNeverTakesTheIdOfTheOne() {
        ObjectNode stored = document(node(2, "paragraph", text(3, "заголовок")), node(4, "paragraph", text(5, "текст")));
        ObjectNode heading = node(20, "heading", text(21, "заголовок"));
        heading.withObject("attrs").put("level", 2);
        ObjectNode revised = document(heading, node(4, "paragraph", text(50, "текст")));

        NativeRevisionPlanner.Plan plan = saved(stored, revised);

        // the heading takes no id of the paragraph it replaces; the paragraph after it keeps its text node
        assertThat(types(plan.edits())).containsExactlyInAnyOrder("Delete", "Insert");
        assertThat(blockIds(plan.document())).containsExactly(id(20).toString(), id(4).toString());
        assertThat(plan.document().toJson().path("root").path("content").get(1).path("content").get(0).path("id").asString()).isEqualTo(id(5).toString());
    }

    @Test
    void aBlockThatCannotBeEditedInPlaceIsReplacedAsAWholeWithNewIdentifiers() {
        // a node of a type native-v1 does not let be edited keeps its children opaque: a change of them replaces the whole block by a new one
        ObjectNode stored = document(node(2, "paragraph", text(3, "до")), node(4, "future", node(5, "future", text(6, "а"))));
        ObjectNode revised = document(node(2, "paragraph", text(30, "до")), node(4, "future", node(50, "future", text(60, "а")),
                node(51, "future", text(61, "б"))));

        NativeRevisionPlanner.Plan plan = saved(stored, revised);

        assertThat(types(plan.edits())).containsExactlyInAnyOrder("Delete", "Insert");
        // the paragraph is untouched, the replaced block has an identity of its own now
        assertThat(blockIds(plan.document()).get(0)).isEqualTo(id(2).toString());
        assertThat(blockIds(plan.document()).get(1)).isNotEqualTo(id(4).toString());
    }

    @Test
    void blocksThatSwappedPlacesAreReplacedBecauseAnEditHereCannotMoveThem() {
        ObjectNode stored = document(node(2, "paragraph", text(3, "а")), node(4, "paragraph", text(5, "б")), node(6, "paragraph", text(7, "в")));
        ObjectNode revised = document(node(2, "paragraph", text(30, "а")), node(6, "paragraph", text(70, "в")), node(4, "paragraph", text(50, "б")));

        NativeRevisionPlanner.Plan plan = saved(stored, revised);

        assertThat(plan.edits()).isNotEmpty();
        assertThat(blockIds(plan.document()).get(0)).isEqualTo(id(2).toString());
        List<String> texts = new ArrayList<>();
        plan.document().toJson().path("root").path("content").forEach(block -> texts.add(block.path("content").get(0).path("attrs").path("text").asString()));
        assertThat(texts).containsExactly("а", "в", "б");
    }

    @Test
    void anUnchangedDocumentIsAPlainReplaceWithTheSameIdentifiers() {
        ObjectNode stored = document(node(2, "paragraph", text(3, "а")), node(4, "divider"));
        JsonNode before = read(stored).toJson();

        NativeRevisionPlanner.Plan plan = saved(stored, stored.deepCopy());

        assertThat(plan.edits()).isEmpty();
        assertThat(plan.document().toJson()).isEqualTo(before);
    }

    @Test
    void aNewcomerNeverTakesAnIdThatWouldPutAKeptSiblingOutOfOrder() {
        // p2 is dropped, p3 is kept and a new paragraph follows it: the newcomer must not stand in for p2 (it would sit after p3)
        ObjectNode stored = document(node(2, "paragraph", text(3, "один")), node(4, "paragraph", text(5, "два")), node(6, "paragraph", text(7, "три")));
        ObjectNode revised = document(node(2, "paragraph", text(30, "один")), node(6, "paragraph", text(70, "три")), node(10, "paragraph", text(11, "новый")));

        NativeRevisionPlanner.Plan plan = saved(stored, revised);

        // the kept block keeps its id and place; the dropped one is a delete and the new one an insert
        assertThat(blockIds(plan.document()).subList(0, 2)).containsExactly(id(2).toString(), id(6).toString());
        assertThat(blockIds(plan.document()).get(2)).isNotEqualTo(id(4).toString());
        assertThat(types(plan.edits())).containsExactlyInAnyOrder("Delete", "Insert");

        // the same newcomer in the gap it fits keeps taking the id of the paragraph it replaces
        NativeRevisionPlanner.Plan inGap = saved(stored, document(node(2, "paragraph", text(30, "один")), node(10, "paragraph", text(11, "новый")),
                node(6, "paragraph", text(70, "три"))));
        assertThat(blockIds(inGap.document())).containsExactly(id(2).toString(), id(4).toString(), id(6).toString());
        assertThat(inGap.edits()).isEmpty();
    }

    @Test
    void theRootAndNestedListsAndHeadingsWithAttributesFollowTheStoredIdentity() {
        ObjectNode heading = node(2, "heading", text(3, "Заголовок"));
        heading.withObject("attrs").put("level", 2);
        ObjectNode nested = node(4, "bullet_list", node(5, "list_item", node(6, "paragraph", text(7, "а")),
                node(8, "bullet_list", node(9, "list_item", node(10, "paragraph", text(11, "вложенный"))))));
        ObjectNode stored = document(heading, nested);
        ObjectNode newHeading = node(20, "heading", text(21, "Новый заголовок"));
        newHeading.withObject("attrs").put("level", 3);
        ObjectNode newNested = node(4, "bullet_list", node(50, "list_item", node(60, "paragraph", text(70, "а")),
                node(80, "bullet_list", node(90, "list_item", node(100, "paragraph", text(110, "вложенный"))),
                        node(91, "list_item", node(101, "paragraph", text(111, "ещё один"))))));
        newHeading.put("id", id(2).toString());
        ObjectNode revised = document(newHeading, newNested);
        // the revised document may even give its root another id
        ((ObjectNode) revised.path("root")).put("id", id(999).toString());

        NativeRevisionPlanner.Plan plan = saved(stored, revised);

        assertThat(plan.document().toJson().path("root").path("id").asString()).isEqualTo(id(1).toString());
        assertThat(blockIds(plan.document())).containsExactly(id(2).toString(), id(4).toString());
        assertThat(plan.document().toJson().path("root").path("content").get(0).path("attrs").path("level").intValue()).isEqualTo(3);
        // everything that was there stands in for itself down to the nested paragraph; only the one new item is structural
        assertThat(types(plan.edits())).containsExactly("Insert");
    }

    @Test
    void anyShapeOfRewriteIsAcceptedByTheStorageEditorAndKeptBlocksKeepTheirIds() {
        java.util.Random random = new java.util.Random(294);
        int[] next = {1};
        for (int round = 0; round < 300; round++) {
            next[0] = 1;
            int blocks = 1 + random.nextInt(7);
            List<ObjectNode> oldBlocks = new ArrayList<>();
            List<Integer> oldIds = new ArrayList<>();
            for (int index = 0; index < blocks; index++) {
                int type = random.nextInt(3);
                oldBlocks.add(randomBlock(random, ++next[0], type, 1 + random.nextInt(3), next));
                oldIds.add(next[0] - 0);
            }
            ObjectNode stored = document(oldBlocks.toArray(ObjectNode[]::new));
            // the revision: every block that stays keeps its id and gets all-new inline ids; some are dropped, some added, some grow or shrink
            List<ObjectNode> revisedBlocks = new ArrayList<>();
            List<String> keptOrder = new ArrayList<>();
            for (ObjectNode old : oldBlocks) {
                if (random.nextInt(5) == 0) continue;
                String type = old.path("type").asString();
                ObjectNode copy = type.equals("bullet_list") ? randomList(random, 1000 + next[0]++, 1 + random.nextInt(3), next)
                        : randomBlock(random, 1000 + next[0]++, type.equals("heading") ? 1 : 0, 1, next);
                copy.put("id", old.path("id").asString());
                revisedBlocks.add(copy);
                keptOrder.add(old.path("id").asString());
                if (random.nextInt(4) == 0) revisedBlocks.add(randomBlock(random, 1000 + next[0]++, random.nextInt(3), 1 + random.nextInt(2), next));
            }
            if (revisedBlocks.isEmpty()) revisedBlocks.add(randomBlock(random, 1000 + next[0]++, 0, 1, next));
            boolean swapped = revisedBlocks.size() > 1 && random.nextInt(6) == 0;
            if (swapped) java.util.Collections.swap(revisedBlocks, 0, revisedBlocks.size() - 1);
            ObjectNode revised = document(revisedBlocks.toArray(ObjectNode[]::new));

            NativeRevisionPlanner.Plan plan = saved(stored, revised);

            if (!swapped) {
                // without a reordering every block of the revision that was in the stored document stays exactly where it was
                List<String> after = blockIds(plan.document());
                for (String kept : keptOrder) assertThat(after).as("round " + round).contains(kept);
            }
        }
    }

    private static ObjectNode randomBlock(java.util.Random random, int id, int type, int items, int[] next) {
        if (type == 2) return randomList(random, id, items, next);
        ObjectNode block = type == 1 ? node(id, "heading", text(++next[0] + 5_000, "заголовок " + random.nextInt(100)))
                : node(id, "paragraph", text(++next[0] + 5_000, "текст " + random.nextInt(100)));
        if (type == 1) block.withObject("attrs").put("level", 1 + random.nextInt(3));
        return block;
    }

    private static ObjectNode randomList(java.util.Random random, int id, int items, int[] next) {
        ObjectNode[] children = new ObjectNode[items];
        for (int index = 0; index < items; index++) {
            int base = 10_000 + (++next[0]) * 10;
            children[index] = node(base, "list_item", node(base + 1, "paragraph", text(base + 2, "пункт " + random.nextInt(100))));
        }
        return node(id, "bullet_list", children);
    }
}
