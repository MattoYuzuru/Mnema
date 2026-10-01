package app.mnema.learning.study.session;

import app.mnema.learning.catalog.exercise.AnswerKey;
import app.mnema.learning.catalog.exercise.ExerciseType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Random;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;

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
    void matchSidesAreShuffledIndependentlyWithTheGivenSourceAndNeverAligned() {
        ObjectNode exercise = mechanic("createMatchMixed").withObject("exercise");
        Map<String, String> partner = partners(exercise);
        JsonNode content = issue(exercise, new Random(42));
        assertThat(issue(exercise, new Random(42))).as("same source, same order").isEqualTo(content);
        assertThat(ids(content.path("left"))).containsExactlyInAnyOrderElementsOf(ids(exercise.path("content").path("left")));
        assertThat(ids(content.path("right"))).containsExactlyInAnyOrderElementsOf(ids(exercise.path("content").path("right")));
        assertThat(content.path("left").get(0).path("blocks").get(0).has("title")).isFalse();

        boolean leftMoved = false;
        boolean rightMoved = false;
        Random random = new Random(7);
        for (int attempt = 0; attempt < 200; attempt++) {
            JsonNode shuffled = issue(exercise, random);
            List<String> left = ids(shuffled.path("left"));
            List<String> right = ids(shuffled.path("right"));
            assertThat(IntStream.range(0, left.size()).allMatch(index -> partner.get(left.get(index)).equals(right.get(index))))
                    .as("a column that lines up with its partners hands out the answer").isFalse();
            leftMoved |= !left.equals(ids(exercise.path("content").path("left")));
            rightMoved |= !right.equals(ids(exercise.path("content").path("right")));
        }
        assertThat(leftMoved).isTrue();
        assertThat(rightMoved).isTrue();
    }

    @Test
    void theShuffleIsNotAFunctionOfAnyIdentifierTheLearnerHolds() {
        // The permutation comes only from the supplied source: the content is persisted at issue, so nothing
        // in the exercise or presentation ids can reproduce it.
        ObjectNode exercise = mechanic("createMatchMixed").withObject("exercise");
        Set<List<String>> orders = new java.util.HashSet<>();
        for (int attempt = 0; attempt < 30; attempt++) orders.add(ids(issue(exercise, new java.security.SecureRandom()).path("left")));
        assertThat(orders.size()).isGreaterThan(5);
        // two issues from one source differ, and an id-seeded generator does not predict them
        RandomGenerator source = new java.security.SecureRandom();
        int predicted = 0;
        for (int attempt = 0; attempt < 50; attempt++) {
            UUID presentation = UUID.randomUUID();
            JsonNode issued = issue(exercise, source);
            List<String> guess = new ArrayList<>(ids(exercise.path("content").path("left")));
            java.util.Collections.shuffle(guess, new Random(presentation.getMostSignificantBits()));
            if (guess.equals(ids(issued.path("left")))) predicted++;
        }
        assertThat(predicted).isLessThan(10);
    }

    @Test
    void twoItemMatchRotatesWheneverTheShuffleWouldAlign() {
        ObjectNode exercise = mechanic("createMatchMixed").withObject("exercise");
        drop(exercise.withObject("content").withArray("left"), 3, 2);
        drop(exercise.withObject("content").withArray("right"), 3, 2);
        drop(exercise.withObject("answerKey").withArray("pairs"), 3, 2);
        Map<String, String> partner = partners(exercise);
        Set<List<String>> rightOrders = new java.util.HashSet<>();
        Random random = new Random(3);
        for (int attempt = 0; attempt < 100; attempt++) {
            JsonNode shuffled = issue(exercise, random);
            List<String> left = ids(shuffled.path("left"));
            List<String> right = ids(shuffled.path("right"));
            assertThat(partner.get(left.get(0)).equals(right.get(0)) && partner.get(left.get(1)).equals(right.get(1))).isFalse();
            rightOrders.add(right);
        }
        assertThat(rightOrders).hasSize(2);
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

    private static JsonNode issue(ObjectNode exercise, RandomGenerator random) {
        return LearnerContent.issue(ExerciseType.MATCH, exercise.path("content"),
                AnswerKey.parse(ExerciseType.MATCH, exercise.path("answerKey")), random, m -> MATERIAL_TEXT).content();
    }

    private static Map<String, String> partners(ObjectNode exercise) {
        AnswerKey.Match key = (AnswerKey.Match) AnswerKey.parse(ExerciseType.MATCH, exercise.path("answerKey"));
        Map<String, String> partner = new HashMap<>();
        key.pairs().forEach(pair -> partner.put(pair.leftId().toString(), pair.rightId().toString()));
        return partner;
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
