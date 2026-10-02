package app.mnema.learning.catalog.exercise;

import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.api.InvalidRequestException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static app.mnema.learning.support.ContractFixtures.bytes;
import static app.mnema.learning.support.ContractFixtures.mechanic;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Publication rules of ORDER and CATEGORIZE: limits, slot profiles, key agreement and strictness. */
class OrderCategorizeCommandTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ASSET = "aaaaaaaa-0000-4000-8000-000000000003";
    private static final String VIDEO_ID = "dQw4w9WgXcQ";

    @Test
    void theContractFixturesDeriveMediaFromEveryItemBlock() {
        ExerciseCommand order = ExerciseCommand.readCreate(bytes(mechanic("createOrder")));
        assertThat(order.exercise().type()).isEqualTo(ExerciseType.ORDER);
        assertThat(order.exercise().assets()).extracting(MediaCatalog.ExerciseAsset::kind)
                .containsExactly(MediaCatalog.Kind.IMAGE);
        assertThat(order.exercise().policy().id()).isEqualTo("deterministic-order");

        ExerciseCommand categorize = ExerciseCommand.readCreate(bytes(mechanic("createCategorize")));
        assertThat(categorize.exercise().type()).isEqualTo(ExerciseType.CATEGORIZE);
        assertThat(categorize.exercise().assets()).extracting(MediaCatalog.ExerciseAsset::kind)
                .containsExactly(MediaCatalog.Kind.AUDIO);
        // the fixture keeps the "Наречие" group empty: a distractor is valid
        AnswerKey.Categorize key = (AnswerKey.Categorize) AnswerKey.parse(ExerciseType.CATEGORIZE,
                categorize.exercise().answerKey());
        assertThat(key.assignments().stream().map(AnswerKey.Assignment::categoryId).distinct()).hasSize(2);
        assertThat(((ExerciseContent.Categorize) categorize.exercise().model()).categories()).hasSize(3);
    }

    @Test
    void everyItemOfEverySlotCountsTowardsMediaAndMaterialDerivation() {
        ExerciseCommand order = ExerciseCommand.readCreate(bytes(patched("createOrder", body -> {
            blocks(body, 1).add(Blocks.image("aaaaaaaa-0000-4000-8000-000000000021"));
            blocks(body, 0).removeAll().add(Blocks.material());
        })));
        assertThat(order.exercise().assets()).hasSize(2);
        assertThat(order.exercise().materials()).singleElement()
                .satisfies(material -> assertThat(material.maxText()).isEqualTo(Slot.SEQUENCE.maxText()));
        assertThat(Slot.SEQUENCE.maxText()).isEqualTo(1_000);
    }

    @Test
    void orderHasTwoToTwelveItemsAndAnExactPermutationKey() {
        assertInvalid("createOrder", body -> reduceOrder(body, 1));
        ExerciseCommand.readCreate(bytes(patched("createOrder", body -> reduceOrder(body, 2))));
        ExerciseCommand.readCreate(bytes(patched("createOrder", body -> growOrder(body, 12))));
        assertInvalid("createOrder", body -> growOrder(body, 13));
        assertInvalid("createOrder", body -> items(content(body)).removeAll());

        // the key must be exactly the item ids: missing, foreign, duplicated, extra, ids of a stale item
        assertInvalid("createOrder", body -> sequence(body).remove(5));
        assertInvalid("createOrder", body -> sequence(body).set(0, JSON.getNodeFactory().stringNode(UUID.randomUUID().toString())));
        assertInvalid("createOrder", body -> sequence(body).set(1, sequence(body).get(0)));
        assertInvalid("createOrder", body -> sequence(body).add(UUID.randomUUID().toString()));
        assertInvalid("createOrder", body -> sequence(body).add(sequence(body).get(0)));
        assertInvalid("createOrder", body -> answerKey(body).putArray("sequence"));
        assertInvalid("createOrder", body -> answerKey(body).remove("sequence"));
        // item identifiers are unique and canonical
        assertInvalid("createOrder", body -> ((ObjectNode) items(content(body)).get(1)).put("itemId",
                items(content(body)).get(0).path("itemId").stringValue(null)));
        assertInvalid("createOrder", body -> ((ObjectNode) items(content(body)).get(1)).put("itemId",
                items(content(body)).get(1).path("itemId").stringValue(null).toUpperCase()));
        // any permutation of the ids is a valid explicit key
        ExerciseCommand.readCreate(bytes(patched("createOrder", body -> {
            ArrayNode sequence = sequence(body);
            var first = sequence.get(0);
            sequence.set(0, sequence.get(5));
            sequence.set(5, first);
        })));
    }

    @Test
    void orderItemsUseTheSequenceProfile() {
        // 1000 UTF-16 units fit, 1001 do not; newlines and indentation are preserved verbatim for code
        String code = "for (;;) {\n    x++;\n}\n" + "y".repeat(970);
        ExerciseCommand command = ExerciseCommand.readCreate(bytes(patched("createOrder",
                body -> itemText(body, 0, code))));
        assertThat(command.exercise().content().path("items").get(0).path("blocks").get(0).path("text").stringValue(null))
                .isEqualTo(code);
        ExerciseCommand.readCreate(bytes(patched("createOrder", body -> itemText(body, 0, "x".repeat(1_000)))));
        assertInvalid("createOrder", body -> itemText(body, 0, "x".repeat(1_001)));
        assertInvalid("createOrder", body -> itemText(body, 0, "😀".repeat(501)));
        assertInvalid("createOrder", body -> itemText(body, 0, "  \n "));
        // one text-like and one media block at most, 1..2 blocks, and no YOUTUBE
        ExerciseCommand.readCreate(bytes(patched("createOrder",
                body -> blocks(body, 0).add(Blocks.image(ASSET)))));
        assertInvalid("createOrder", body -> blocks(body, 0).add(Blocks.text("second")));
        assertInvalid("createOrder", body -> blocks(body, 5).add(Blocks.image(ASSET)));
        assertInvalid("createOrder", body -> blocks(body, 0).removeAll());
        assertInvalid("createOrder", body -> blocks(body, 0).removeAll()
                .add(Blocks.youtube()));
        assertInvalid("createOrder", body -> blocks(body, 0)
                .add(Blocks.image(ASSET)).add(Blocks.image(ASSET)));
        // the prompt is optional (0..8 blocks)
        ExerciseCommand.readCreate(bytes(patched("createOrder", body -> content(body).withArray("prompt").removeAll())));
        assertInvalid("createOrder", body -> {
            ArrayNode prompt = content(body).withArray("prompt");
            while (prompt.size() < 9) prompt.add(Blocks.text("more"));
        });
        // audio and video items are ordinary compact media, no extra mechanic
        ExerciseCommand.readCreate(bytes(patched("createOrder", body -> blocks(body, 0)
                .add(Blocks.audio("aaaaaaaa-0000-4000-8000-000000000011")))));
    }

    @Test
    void orderRejectsUnknownFieldsAndMismatchedPartsAtEveryLevel() {
        assertInvalid("createOrder", body -> content(body).put("distractors", 1));
        assertInvalid("createOrder", body -> ((ObjectNode) items(content(body)).get(0)).put("position", 1));
        assertInvalid("createOrder", body -> answerKey(body).put("accepted", "x"));
        assertInvalid("createOrder", body -> answerKey(body).put("kind", "CATEGORIZE"));
        assertInvalid("createOrder", body -> evaluator(body).put("id", "deterministic-match"));
        assertInvalid("createOrder", body -> evaluator(body).put("id", "deterministic-categorize"));
        assertInvalid("createOrder", body -> exercise(body).set("content",
                mechanic("createCategorize").path("exercise").path("content")));
        assertInvalid("createOrder", body -> exercise(body).set("answerKey",
                mechanic("createCategorize").path("exercise").path("answerKey")));
        assertInvalid("createMatchMixed", body -> exercise(body).put("type", "ORDER"));
    }

    @Test
    void categorizeHasTwoToSixCategoriesAndTwoToTwelveItems() {
        assertInvalid("createCategorize", body -> reduceCategories(body, 1));
        ExerciseCommand.readCreate(bytes(patched("createCategorize", body -> reduceCategories(body, 2))));
        ExerciseCommand.readCreate(bytes(patched("createCategorize", body -> growCategories(body, 6))));
        assertInvalid("createCategorize", body -> growCategories(body, 7));
        assertInvalid("createCategorize", body -> categories(content(body)).removeAll());

        assertInvalid("createCategorize", body -> reduceCategorizeItems(body, 1));
        ExerciseCommand.readCreate(bytes(patched("createCategorize", body -> reduceCategorizeItems(body, 2))));
        ExerciseCommand.readCreate(bytes(patched("createCategorize", body -> growCategorizeItems(body, 12))));
        assertInvalid("createCategorize", body -> growCategorizeItems(body, 13));
    }

    @Test
    void categoryLabelsAreBoundedPlainAndUniqueAfterTrimAndCaseFold() {
        ExerciseCommand.readCreate(bytes(patched("createCategorize", body -> label(body, 0, "ж".repeat(80)))));
        assertInvalid("createCategorize", body -> label(body, 0, "ж".repeat(81)));
        assertInvalid("createCategorize", body -> label(body, 0, "😀".repeat(41)));
        assertInvalid("createCategorize", body -> label(body, 0, ""));
        assertInvalid("createCategorize", body -> label(body, 0, "   "));
        // identical, differing only by case, and differing only by surrounding whitespace
        assertInvalid("createCategorize", body -> label(body, 0, "Глагол"));
        assertInvalid("createCategorize", body -> label(body, 0, "ГЛАГОЛ"));
        assertInvalid("createCategorize", body -> label(body, 0, "  глагол\t"));
        // different labels that merely look related stay distinct
        ExerciseCommand.readCreate(bytes(patched("createCategorize", body -> label(body, 0, "Глагол 2"))));
        // identifiers are unique
        assertInvalid("createCategorize", body -> ((ObjectNode) categories(content(body)).get(1)).put("categoryId",
                categories(content(body)).get(0).path("categoryId").stringValue(null)));
        assertInvalid("createCategorize", body -> ((ObjectNode) categories(content(body)).get(0)).put("color", "red"));
        assertInvalid("createCategorize", body -> ((ObjectNode) categories(content(body)).get(0)).remove("label"));
        assertInvalid("createCategorize", body -> ((ObjectNode) categories(content(body)).get(0)).put("label", 5));
    }

    @Test
    void labelsCollideAfterNfcWhitespaceAndFullCaseFoldAndInvisibleLabelsAreBlank() {
        // «Глагол» is category 1 of the fixture; each variant of category 0 must collide with another label
        String[][] pairs = {{"e\u0301", "\u00e9"}, {"A", "A "}, {"A", "A\u00a0"}, {"Stra\u00dfe", "STRASSE"},
                {"\u03a3", "\u03c2"}, {"\u200bX", "x"}, {"\u3000Y\u2003", "y"}};
        for (String[] pair : pairs) {
            assertInvalid("createCategorize", body -> {
                label(body, 0, pair[0]);
                label(body, 1, pair[1]);
            });
        }
        // distinct labels stay distinct, internal spaces and different letters are not folded away
        ExerciseCommand.readCreate(bytes(patched("createCategorize", body -> {
            label(body, 0, "New York");
            label(body, 1, "NewYork");
        })));
        // the stored label is verbatim, not the comparison key
        ExerciseCommand stored = ExerciseCommand.readCreate(bytes(patched("createCategorize", body -> {
            label(body, 0, " Stra\u00dfe\u00a0");
            label(body, 1, "Other");
        })));
        assertThat(stored.exercise().content().path("categories").get(0).path("label").stringValue(null))
                .isEqualTo(" Stra\u00dfe\u00a0");
        // a label that shows nothing is blank: whitespace, NBSP, ideographic space, zero-width and format characters
        for (String invisible : new String[] {"\u00a0", "\u3000", "\u200b", "\u2060\ufeff", " \u200b\t\u00a0", "\u00ad"}) {
            assertInvalid("createCategorize", body -> label(body, 0, invisible));
        }
    }

    @Test
    void anOrderNeedsAtLeastTwoDistinctEquivalenceClasses() {
        // every tile identical: any arrangement is trivially correct
        assertInvalid("createOrder", body -> {
            for (int index = 0; index < items(content(body)).size(); index++) {
                items(content(body)).set(index, item(UUID.fromString(items(content(body)).get(index)
                        .path("itemId").stringValue(null)), Blocks.text("очень")));
            }
        });
        assertInvalid("createOrder", body -> {
            reduceOrder(body, 2);
            itemText(body, 0, "same");
            itemText(body, 1, "same");
        });
        // the contract fixture has «очень» twice but also other tiles; two classes are enough
        ExerciseCommand.readCreate(bytes(patched("createOrder", body -> {
            reduceOrder(body, 3);
            itemText(body, 0, "a");
            itemText(body, 1, "b");
            itemText(body, 2, "b");
        })));
        // tiles that differ only in an author-only audio title are the same class
        assertInvalid("createOrder", body -> {
            reduceOrder(body, 2);
            blocks(body, 0).removeAll().add(Blocks.audio("aaaaaaaa-0000-4000-8000-000000000011"));
            ((ObjectNode) blocks(body, 0).get(0)).put("title", "first");
            blocks(body, 1).removeAll().add(Blocks.audio("aaaaaaaa-0000-4000-8000-000000000011"));
            ((ObjectNode) blocks(body, 1).get(0)).put("title", "second");
        });
    }

    @Test
    void categorizeKeyAssignsEveryItemToExactlyOneExistingCategory() {
        // an item assigned to a category that no longer exists (a removed category) is rejected at publication
        assertInvalid("createCategorize", body -> ((ObjectNode) assignments(body).get(0)).put("categoryId",
                UUID.randomUUID().toString()));
        assertInvalid("createCategorize", body -> categories(content(body)).remove(1));
        // incomplete, duplicated, foreign and extra assignments
        assertInvalid("createCategorize", body -> assignments(body).remove(3));
        assertInvalid("createCategorize", body -> ((ObjectNode) assignments(body).get(1)).put("itemId",
                assignments(body).get(0).path("itemId").stringValue(null)));
        assertInvalid("createCategorize", body -> ((ObjectNode) assignments(body).get(1)).put("itemId",
                UUID.randomUUID().toString()));
        assertInvalid("createCategorize", body -> assignments(body).addObject()
                .put("itemId", UUID.randomUUID().toString())
                .put("categoryId", categories(content(body)).get(0).path("categoryId").stringValue(null)));
        assertInvalid("createCategorize", body -> assignments(body).add(assignments(body).get(0)));
        assertInvalid("createCategorize", body -> ((ObjectNode) assignments(body).get(0)).put("weight", 1));
        assertInvalid("createCategorize", body -> answerKey(body).put("kind", "ORDER"));
        assertInvalid("createCategorize", body -> evaluator(body).put("id", "deterministic-order"));
        // identifiers are unique
        assertInvalid("createCategorize", body -> ((ObjectNode) items(content(body)).get(1)).put("itemId",
                items(content(body)).get(0).path("itemId").stringValue(null)));
        assertInvalid("createCategorize", body -> ((ObjectNode) items(content(body)).get(0)).put("categoryId", "x"));
    }

    @Test
    void categoryOrderLabelsAndEmptyGroupsDoNotChangeWhatIsAKeyForTheItems() {
        // several items in one category and an empty distractor category are valid
        ExerciseCommand.readCreate(bytes(patched("createCategorize", body -> {
            String only = categories(content(body)).get(0).path("categoryId").stringValue(null);
            assignments(body).forEach(assignment -> ((ObjectNode) assignment).put("categoryId", only));
        })));
        // reversing the category list and renaming labels keeps the same key valid: ids are canonical
        ExerciseCommand reversed = ExerciseCommand.readCreate(bytes(patched("createCategorize", body -> {
            ArrayNode categories = categories(content(body));
            List<ObjectNode> copies = new ArrayList<>();
            categories.forEach(category -> copies.add(((ObjectNode) category).deepCopy()));
            categories.removeAll();
            for (int index = copies.size() - 1; index >= 0; index--) categories.add(copies.get(index));
            label(body, 0, "Совсем другое имя");
        })));
        assertThat(reversed.exercise().answerKey().path("assignments")).hasSize(4);
    }

    @Test
    void categorizeItemsUseTheCompactProfile() {
        ExerciseCommand.readCreate(bytes(patched("createCategorize", body -> categorizeText(body, 0, "x".repeat(300)))));
        assertInvalid("createCategorize", body -> categorizeText(body, 0, "x".repeat(301)));
        assertInvalid("createCategorize", body -> blocks(body, 0).add(Blocks.text("b")));
        assertInvalid("createCategorize", body -> blocks(body, 0).removeAll()
                .add(Blocks.youtube()));
        assertInvalid("createCategorize", body -> blocks(body, 0).removeAll());
        // the prompt is optional
        ExerciseCommand.readCreate(bytes(patched("createCategorize",
                body -> content(body).withArray("prompt").removeAll())));
    }

    // ---- builders ----

    private static void reduceOrder(ObjectNode body, int count) {
        while (items(content(body)).size() > count) {
            items(content(body)).remove(items(content(body)).size() - 1);
            sequence(body).remove(sequence(body).size() - 1);
        }
    }

    private static void growOrder(ObjectNode body, int count) {
        while (items(content(body)).size() < count) {
            UUID id = UUID.randomUUID();
            items(content(body)).add(item(id, Blocks.text("extra " + id)));
            sequence(body).add(id.toString());
        }
    }

    private static void reduceCategories(ObjectNode body, int count) {
        // keep the last items assigned to the first categories only
        while (categories(content(body)).size() > count) {
            String removed = categories(content(body)).remove(categories(content(body)).size() - 1)
                    .path("categoryId").stringValue(null);
            String kept = categories(content(body)).get(0).path("categoryId").stringValue(null);
            assignments(body).forEach(assignment -> {
                if (assignment.path("categoryId").stringValue(null).equals(removed)) ((ObjectNode) assignment).put("categoryId", kept);
            });
        }
    }

    private static void growCategories(ObjectNode body, int count) {
        while (categories(content(body)).size() < count) {
            categories(content(body)).addObject().put("categoryId", UUID.randomUUID().toString())
                    .put("label", "Группа " + categories(content(body)).size());
        }
    }

    private static void reduceCategorizeItems(ObjectNode body, int count) {
        while (items(content(body)).size() > count) {
            items(content(body)).remove(items(content(body)).size() - 1);
            assignments(body).remove(assignments(body).size() - 1);
        }
    }

    private static void growCategorizeItems(ObjectNode body, int count) {
        String category = categories(content(body)).get(0).path("categoryId").stringValue(null);
        while (items(content(body)).size() < count) {
            UUID id = UUID.randomUUID();
            items(content(body)).add(item(id, Blocks.text("extra " + id)));
            assignments(body).addObject().put("itemId", id.toString()).put("categoryId", category);
        }
    }

    private static void label(ObjectNode body, int index, String value) {
        ((ObjectNode) categories(content(body)).get(index)).put("label", value);
    }

    private static void itemText(ObjectNode body, int index, String text) {
        ((ObjectNode) blocks(body, index).get(0)).put("text", text);
    }

    private static ArrayNode blocks(ObjectNode body, int item) {
        return (ArrayNode) items(content(body)).get(item).get("blocks");
    }

    private static void categorizeText(ObjectNode body, int index, String text) { itemText(body, index, text); }

    private static ArrayNode items(ObjectNode content) { return content.withArray("items"); }

    private static ArrayNode categories(ObjectNode content) { return content.withArray("categories"); }

    private static ArrayNode sequence(ObjectNode body) { return answerKey(body).withArray("sequence"); }

    private static ArrayNode assignments(ObjectNode body) { return answerKey(body).withArray("assignments"); }

    private static ObjectNode item(UUID id, ObjectNode block) {
        ObjectNode item = JSON.createObjectNode().put("itemId", id.toString());
        item.putArray("blocks").add(block);
        return item;
    }

    private static ObjectNode patched(String fixture, Consumer<ObjectNode> change) {
        ObjectNode body = mechanic(fixture);
        change.accept(body);
        return body;
    }

    private static void assertInvalid(String fixture, Consumer<ObjectNode> change) {
        ObjectNode body = patched(fixture, change);
        assertThatThrownBy(() -> ExerciseCommand.readCreate(bytes(body)))
                .as(body.toString()).isInstanceOf(InvalidRequestException.class);
    }

    private static ObjectNode exercise(ObjectNode body) { return body.withObject("exercise"); }

    private static ObjectNode content(ObjectNode body) { return exercise(body).withObject("content"); }

    private static ObjectNode answerKey(ObjectNode body) { return exercise(body).withObject("answerKey"); }

    private static ObjectNode evaluator(ObjectNode body) { return exercise(body).withObject("evaluatorPolicy"); }

    private static final class Blocks {
        static ObjectNode text(String value) { return JSON.createObjectNode().put("kind", "TEXT").put("text", value); }

        static ObjectNode image(String asset) {
            return JSON.createObjectNode().put("kind", "IMAGE").put("assetId", asset).put("alt", "alt");
        }

        static ObjectNode audio(String asset) {
            return JSON.createObjectNode().put("kind", "AUDIO").put("assetId", asset).put("title", "line");
        }

        static ObjectNode youtube() {
            return JSON.createObjectNode().put("kind", "YOUTUBE").put("videoId", VIDEO_ID).put("title", "clip");
        }

        static ObjectNode material() {
            return JSON.createObjectNode().put("kind", "MATERIAL")
                    .put("memberKey", "1e000000-0000-4000-8000-000000000001")
                    .put("itemRevisionId", "1e000000-0000-4000-8000-000000000002")
                    .put("nodeId", "7e000000-0000-4000-8000-000000000001");
        }
    }
}
