package app.mnema.learning.study.session;

import app.mnema.learning.catalog.exercise.AnswerKey;
import app.mnema.learning.catalog.exercise.ExerciseType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.random.RandomGenerator;

import static app.mnema.learning.support.ContractFixtures.fixture;
import static app.mnema.learning.support.ContractFixtures.mechanic;
import static org.assertj.core.api.Assertions.assertThat;

class LearnerContentTest {
    private static final String MATERIAL_TEXT = "Ядро, митохондрии и рибосомы.";

    @Test
    void everyMechanicResolvesToTheContractLearnerContentWithoutAuthorOnlyData() {
        for (var entry : Map.of("createSelfCheck", "selfCheck", "createFreeResponseAudio", "freeResponse",
                "createCloze", "cloze", "createChoiceVideoMultiple", "choice").entrySet()) {
            ObjectNode exercise = mechanic(entry.getKey()).withObject("exercise");
            ExerciseType type = ExerciseType.valueOf(exercise.path("type").textValue());
            LearnerContent.Resolved resolved = LearnerContent.issue(type, exercise.path("content"),
                    AnswerKey.parse(type, exercise.path("answerKey")), new Random(1), material -> MATERIAL_TEXT);
            JsonNode expected = fixture("mechanics.json").path("presentations").path(entry.getValue()).path("content");
            assertThat(LearnerContent.view(resolved.content(), false)).as(entry.getKey()).isEqualTo(expected);
            String learner = resolved.content().toString();
            // media titles and the key never enter learner content; the transcript is stored but filtered
            for (String forbidden : new String[] {"Моё объяснение", "Слово 12", "Опыт 3", "Шипение", "accepted",
                    "correctOptionIds", "answerKey", "toList"}) {
                assertThat(LearnerContent.view(resolved.content(), false).toString()).doesNotContain(forbidden);
            }
            assertThat(learner).doesNotContain("\"title\"");
        }
    }

    @Test
    void freeResponseKeepsItsReferenceForFeedbackOnlyAndTranscriptsForAnExplicitReveal() {
        ObjectNode exercise = mechanic("createFreeResponseAudio").withObject("exercise");
        exercise.withObject("content").withArray("reference").addObject().put("kind", "TEXT").put("text", "Das Gedächtnis");
        LearnerContent.Resolved resolved = LearnerContent.issue(ExerciseType.FREE_RESPONSE, exercise.path("content"),
                AnswerKey.parse(ExerciseType.FREE_RESPONSE, exercise.path("answerKey")), new Random(1), m -> "x");
        assertThat(resolved.content().has("reference")).isFalse();
        assertThat(resolved.reveal().path("reference").get(0).path("text").textValue()).isEqualTo("Das Gedächtnis");

        assertThat(LearnerContent.hasTranscript(resolved.content())).isTrue();
        assertThat(LearnerContent.view(resolved.content(), false).toString()).doesNotContain("Erinnerung");
        assertThat(LearnerContent.view(resolved.content(), false).path("prompt").get(0).path("transcriptAvailable")
                .booleanValue()).isTrue();
        assertThat(LearnerContent.view(resolved.content(), true).path("prompt").get(0).path("transcript").textValue())
                .isEqualTo("Erinnerung");
        // the stored form is never mutated by a view
        assertThat(resolved.content().path("prompt").get(0).path("transcript").textValue()).isEqualTo("Erinnerung");

        ObjectNode choice = mechanic("createChoiceVideoMultiple").withObject("exercise");
        LearnerContent.Resolved plain = LearnerContent.issue(ExerciseType.CHOICE, choice.path("content"),
                AnswerKey.parse(ExerciseType.CHOICE, choice.path("answerKey")), new Random(1), m -> "x");
        assertThat(LearnerContent.hasTranscript(plain.content())).isFalse();
    }

    @Test
    void anAnswerLengthBlankExposesOnlyTheWidthAndFixedBlanksKeepTheirOwn() {
        ObjectNode exercise = mechanic("createCloze").withObject("exercise");
        LearnerContent.Resolved resolved = LearnerContent.issue(ExerciseType.CLOZE, exercise.path("content"),
                AnswerKey.parse(ExerciseType.CLOZE, exercise.path("answerKey")), new Random(1), m -> "x");
        JsonNode passage = resolved.content().path("passage");
        assertThat(passage.get(1).path("size").path("mode").textValue()).isEqualTo("ANSWER_LENGTH");
        assertThat(passage.get(1).path("size").path("length").intValue()).isEqualTo(3);
        assertThat(passage.get(3).path("size").path("length").intValue()).isEqualTo(8);
        assertThat(passage.get(0).path("text").textValue()).isEqualTo("list.stream()\n    .");
    }

    @Test
    void matchSidesComeOnlyFromTheSuppliedSourceAndKeepAuthorOnlyDataOut() {
        ObjectNode exercise = mechanic("createMatchMixed").withObject("exercise");
        JsonNode content = issue(exercise, new Random(42));
        assertThat(issue(exercise, new Random(42))).as("same source, same order").isEqualTo(content);
        assertThat(ids(content.path("left"))).containsExactlyInAnyOrderElementsOf(ids(exercise.path("content").path("left")));
        assertThat(ids(content.path("right"))).containsExactlyInAnyOrderElementsOf(ids(exercise.path("content").path("right")));
        assertThat(content.path("left").get(0).path("blocks").get(0).has("title")).isFalse();
    }

    @Test
    void twoPairMatchCanBeIssuedAlignedOrCrossedBecauseTheKeyIsNeverConsulted() {
        ObjectNode exercise = twoPairMatch();
        List<String> left = ids(exercise.path("content").path("left"));
        List<String> right = ids(exercise.path("content").path("right"));
        // Collections.shuffle on two items draws nextInt(2) once per side: 1 keeps the order, 0 swaps.
        JsonNode aligned = issue(exercise, new ScriptedSource(1, 1));
        assertThat(ids(aligned.path("left"))).containsExactlyElementsOf(left);
        assertThat(ids(aligned.path("right"))).as("rows may line up with their partners").containsExactlyElementsOf(right);

        JsonNode crossed = issue(exercise, new ScriptedSource(1, 0));
        assertThat(ids(crossed.path("left"))).containsExactlyElementsOf(left);
        assertThat(ids(crossed.path("right"))).containsExactlyElementsOf(List.of(right.get(1), right.get(0)));

        JsonNode bothSwapped = issue(exercise, new ScriptedSource(0, 0));
        assertThat(ids(bothSwapped.path("left"))).containsExactlyElementsOf(List.of(left.get(1), left.get(0)));
        assertThat(ids(bothSwapped.path("right"))).containsExactlyElementsOf(List.of(right.get(1), right.get(0)));
    }

    @Test
    void thePermutationDoesNotDependOnTheAnswerKey() {
        ObjectNode exercise = twoPairMatch();
        ObjectNode swappedKey = exercise.deepCopy();
        com.fasterxml.jackson.databind.node.ArrayNode pairs = swappedKey.withObject("answerKey").withArray("pairs");
        String firstRight = pairs.get(0).path("rightId").textValue();
        ((ObjectNode) pairs.get(0)).put("rightId", pairs.get(1).path("rightId").textValue());
        ((ObjectNode) pairs.get(1)).put("rightId", firstRight);
        for (int[] draws : new int[][] {{1, 1}, {1, 0}, {0, 1}, {0, 0}}) {
            assertThat(issue(swappedKey, new ScriptedSource(draws)))
                    .as("draws %s", java.util.Arrays.toString(draws))
                    .isEqualTo(issue(exercise, new ScriptedSource(draws)));
        }
    }

    @Test
    void aFullyAlignedLargerMatchIsIssuedUnchangedWhenTheSourceKeepsEveryPosition() {
        ObjectNode exercise = mechanic("createMatchMixed").withObject("exercise");
        int size = exercise.path("content").path("left").size();
        int[] keep = new int[2 * (size - 1)];
        for (int index = 0; index < keep.length; index++) keep[index] = size - 1 - (index % (size - 1));
        JsonNode content = issue(exercise, new ScriptedSource(keep));
        assertThat(ids(content.path("left"))).containsExactlyElementsOf(ids(exercise.path("content").path("left")));
        assertThat(ids(content.path("right"))).containsExactlyElementsOf(ids(exercise.path("content").path("right")));
    }

    @Test
    void orderItemsAreIssuedAsTheContractLearnerContentWithoutTheKeyOrAuthorLabels() {
        ObjectNode exercise = mechanic("createOrder").withObject("exercise");
        LearnerContent.Resolved resolved = LearnerContent.issue(ExerciseType.ORDER, exercise.path("content"),
                AnswerKey.parse(ExerciseType.ORDER, exercise.path("answerKey")), new Random(7), m -> MATERIAL_TEXT);
        JsonNode content = LearnerContent.view(resolved.content(), false);
        JsonNode expected = fixture("mechanics.json").path("presentations").path("order").path("content");
        // the fixture shows one possible shuffle: same prompt and the same tiles, in any permutation
        assertThat(content.path("prompt")).isEqualTo(expected.path("prompt"));
        assertThat(content.path("items")).containsExactlyInAnyOrderElementsOf(expected.path("items"));
        assertThat(content.fieldNames()).toIterable().containsExactlyInAnyOrder("prompt", "items");
        assertThat(resolved.reveal()).isEmpty();
        assertThat(content.toString()).doesNotContain("sequence", "answerKey", "\"title\"");
    }

    @Test
    void anOrderBoardThatWouldShowTheSolutionIsRedrawnWithinABoundAndThenRotated() {
        ObjectNode exercise = mechanic("createOrder").withObject("exercise");
        List<String> key = ids(exercise.path("content").path("items"));
        // identity draws leave the board in key order, i.e. solved: n-1 draws per shuffle, every one of them kept
        CountingSource solved = new CountingSource(true);
        List<String> shown = ids(issueOrder(exercise, solved).path("items"));
        assertThat(solved.draws).as("1 draw plus the bounded redraws, five bounded draws each")
                .isEqualTo((1 + LearnerContent.MAX_REDRAWS) * 5);
        // deterministic last resort: the first rotation whose equivalence-class sequence differs from the key
        assertThat(shown).containsExactly(key.get(5), key.get(0), key.get(1), key.get(2), key.get(3), key.get(4));

        // a first draw that is not the key stops immediately: one shuffle only
        CountingSource first = new CountingSource(false);
        List<String> kept = ids(issueOrder(exercise, first).path("items"));
        assertThat(first.draws).isEqualTo(5);
        assertThat(kept).isNotEqualTo(key);
        assertThat(kept).containsExactlyInAnyOrderElementsOf(key);
    }

    @Test
    void aRedrawFollowsTheEquivalenceClassSequenceNotTheIdentifiers() {
        ObjectNode exercise = threeWordOrder();
        List<String> key = ids(exercise.path("content").path("items"));
        // draws (3 items: nextInt(3), nextInt(2)) continue from the board of the previous draw: (2,1) keeps the key,
        // (1,1) only swaps the two identical «b» tiles (still the solved class sequence), (2,0) then swaps the first
        // two tiles and finally shows a different class sequence
        JsonNode shown = issueOrder(exercise, new ScriptedSource(2, 1, 1, 1, 2, 0));
        assertThat(ids(shown.path("items"))).containsExactly(key.get(2), key.get(0), key.get(1));
    }

    @Test
    void twoItemOrderIsUniformSoNeitherLayoutIsEverExcluded() {
        ObjectNode exercise = threeWordOrder();
        drop((com.fasterxml.jackson.databind.node.ArrayNode) exercise.path("content").path("items"), 2);
        exercise.withObject("answerKey").withArray("sequence").remove(2);
        List<String> key = ids(exercise.path("content").path("items"));
        // one draw only: 1 keeps the order (the solved board is allowed), 0 swaps it
        assertThat(ids(issueOrder(exercise, new ScriptedSource(1)).path("items"))).containsExactlyElementsOf(key);
        assertThat(ids(issueOrder(exercise, new ScriptedSource(0)).path("items")))
                .containsExactly(key.get(1), key.get(0));
    }

    @Test
    void anOrderOfIdenticalItemsTerminatesBecauseNoLayoutCanDiffer() {
        ObjectNode exercise = threeWordOrder();
        ((ObjectNode) exercise.path("content").path("items").get(0).path("blocks").get(0)).put("text", "b");
        CountingSource always = new CountingSource(true);
        JsonNode shown = issueOrder(exercise, always);
        assertThat(shown.path("items")).hasSize(3);
        assertThat(always.draws).isEqualTo((1 + LearnerContent.MAX_REDRAWS) * 2);
    }

    @Test
    void categorizeShufflesItemsOnlyAndKeepsTheAuthoredCategoryOrderAndLabels() {
        ObjectNode exercise = mechanic("createCategorize").withObject("exercise");
        List<String> authored = ids(exercise.path("content").path("items"));
        // four items: nextInt(4), nextInt(3), nextInt(2); (0,0,0) is a full rotation of the list
        JsonNode content = issueCategorize(exercise, new ScriptedSource(0, 0, 0));
        assertThat(ids(content.path("items"))).containsExactlyInAnyOrderElementsOf(authored)
                .isNotEqualTo(authored);
        assertThat(content.path("categories")).isEqualTo(exercise.path("content").path("categories"));
        JsonNode expected = fixture("mechanics.json").path("presentations").path("categorize").path("content");
        assertThat(content.path("prompt")).isEqualTo(expected.path("prompt"));
        assertThat(content.path("categories")).isEqualTo(expected.path("categories"));
        assertThat(content.path("items")).containsExactlyInAnyOrderElementsOf(expected.path("items"));
        // the key is never read for the arrangement
        ObjectNode otherKey = exercise.deepCopy();
        ((ObjectNode) otherKey.path("answerKey").path("assignments").get(0)).put("categoryId",
                otherKey.path("content").path("categories").get(1).path("categoryId").textValue());
        assertThat(issueCategorize(otherKey, new ScriptedSource(0, 0, 0))).isEqualTo(content);
        assertThat(content.toString()).doesNotContain("assignments", "correct", "\"title\"");
    }

    @Test
    void feedbackReferenceContentKeepsAvailabilityButNeverTranscriptText() {
        ObjectNode exercise = mechanic("createFreeResponseAudio").withObject("exercise");
        exercise.withObject("content").withArray("reference").addObject().put("kind", "AUDIO")
                .put("assetId", "aaaaaaaa-0000-4000-8000-000000000009").put("title", "t").put("transcript", "LONG-TRANSCRIPT");
        LearnerContent.Resolved resolved = LearnerContent.issue(ExerciseType.FREE_RESPONSE, exercise.path("content"),
                AnswerKey.parse(ExerciseType.FREE_RESPONSE, exercise.path("answerKey")), new Random(1), m -> "x");
        JsonNode reference = resolved.reveal().path("reference").get(0);
        assertThat(reference.path("transcriptAvailable").booleanValue()).isTrue();
        assertThat(reference.has("transcript")).isFalse();
        assertThat(resolved.reveal().toString()).doesNotContain("LONG-TRANSCRIPT");
    }

    @Test
    void theFirstLetterIsTheFirstExtendedGraphemeClusterOfTheNormalizedAnswer() {
        String family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67";
        String flag = Character.toString(0x1F1E9) + Character.toString(0x1F1EA);
        assertThat(LearnerContent.firstLetter("map")).isEqualTo("m");
        // leading whitespace is skipped: a hint is never blank
        assertThat(LearnerContent.firstLetter("  Hund")).isEqualTo("H");
        assertThat(LearnerContent.firstLetter("\t\n\u00a0\u2003\u00e9clair")).isEqualTo("\u00e9");
        assertThat(LearnerContent.firstLetter(" " + Character.toString(0x1F600) + " x")).isEqualTo(Character.toString(0x1F600));
        // decomposed input is normalized first: e + combining acute becomes one precomposed character
        assertThat(LearnerContent.firstLetter("e\u0301clair")).isEqualTo("\u00e9");
        assertThat(LearnerContent.firstLetter("\u00e9clair")).isEqualTo("\u00e9");
        assertThat(LearnerContent.firstLetter(Character.toString(0x1F600) + " smile")).isEqualTo(Character.toString(0x1F600));
        assertThat(LearnerContent.firstLetter(family + " family")).isEqualTo(family);
        assertThat(LearnerContent.firstLetter(flag + " flag")).isEqualTo(flag);
        assertThat(LearnerContent.firstLetter("\ud55c\uad6d")).isEqualTo("\ud55c");
        assertThat(LearnerContent.firstLetter("\r\nx")).isEqualTo("x");
        // a base letter with a combining mark that has no precomposed form stays together
        assertThat(LearnerContent.firstLetter("q\u0307x")).isEqualTo("q\u0307");
    }

    /** Three text tiles whose last two are identical: authored order «a», «b», «b». */
    private static ObjectNode threeWordOrder() {
        ObjectNode exercise = mechanic("createOrder").withObject("exercise");
        com.fasterxml.jackson.databind.node.ArrayNode items = (com.fasterxml.jackson.databind.node.ArrayNode)
                exercise.path("content").path("items");
        drop(items, 5, 4, 3);
        ((ObjectNode) items.get(0).path("blocks").get(0)).put("text", "a");
        ((ObjectNode) items.get(1).path("blocks").get(0)).put("text", "b");
        ((ObjectNode) items.get(2).path("blocks").get(0)).put("text", "b");
        exercise.withObject("answerKey").withArray("sequence").removeAll();
        items.forEach(item -> exercise.withObject("answerKey").withArray("sequence").add(item.path("itemId").textValue()));
        return exercise;
    }

    private static JsonNode issueOrder(ObjectNode exercise, RandomGenerator random) {
        return LearnerContent.issue(ExerciseType.ORDER, exercise.path("content"),
                AnswerKey.parse(ExerciseType.ORDER, exercise.path("answerKey")), random, m -> MATERIAL_TEXT).content();
    }

    private static JsonNode issueCategorize(ObjectNode exercise, RandomGenerator random) {
        return LearnerContent.issue(ExerciseType.CATEGORIZE, exercise.path("content"),
                AnswerKey.parse(ExerciseType.CATEGORIZE, exercise.path("answerKey")), random, m -> MATERIAL_TEXT).content();
    }

    /** Counts bounded draws; {@code identity} keeps every position, so a shuffle leaves the list unchanged. */
    private static final class CountingSource implements RandomGenerator {
        private final boolean identity;
        private int draws;

        CountingSource(boolean identity) { this.identity = identity; }

        @Override public int nextInt(int bound) {
            // a non-identity source rotates the first shuffle; every later draw keeps its position
            int value = identity || draws >= 5 ? bound - 1 : 0;
            draws++;
            return value;
        }

        @Override public long nextLong() { throw new AssertionError("only bounded draws are expected"); }
    }

    private static ObjectNode twoPairMatch() {
        ObjectNode exercise = mechanic("createMatchMixed").withObject("exercise");
        drop(exercise.withObject("content").withArray("left"), 3, 2);
        drop(exercise.withObject("content").withArray("right"), 3, 2);
        drop(exercise.withObject("answerKey").withArray("pairs"), 3, 2);
        return exercise;
    }

    /** Replays fixed bounded draws so a shuffle is fully determined by the test. */
    private static final class ScriptedSource implements RandomGenerator {
        private final int[] draws;
        private int next;

        ScriptedSource(int... draws) { this.draws = draws.clone(); }

        @Override public int nextInt(int bound) {
            if (next >= draws.length) throw new AssertionError("unexpected extra draw");
            int value = draws[next++];
            if (value < 0 || value >= bound) throw new AssertionError("draw " + value + " outside bound " + bound);
            return value;
        }

        @Override public long nextLong() { throw new AssertionError("only bounded draws are expected"); }
    }

    private static JsonNode issue(ObjectNode exercise, RandomGenerator random) {
        return LearnerContent.issue(ExerciseType.MATCH, exercise.path("content"),
                AnswerKey.parse(ExerciseType.MATCH, exercise.path("answerKey")), random, m -> MATERIAL_TEXT).content();
    }


    private static List<String> ids(JsonNode items) {
        List<String> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.path("itemId").textValue()));
        return ids;
    }

    private static void drop(com.fasterxml.jackson.databind.node.ArrayNode array, int... indexes) {
        for (int index : indexes) array.remove(index);
    }
}
