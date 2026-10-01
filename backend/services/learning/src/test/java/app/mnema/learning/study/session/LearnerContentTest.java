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
