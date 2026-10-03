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
}
