package app.mnema.learning.study.attempt;

import app.mnema.learning.catalog.exercise.ExerciseType;
import app.mnema.learning.platform.api.InvalidRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static app.mnema.learning.support.ContractFixtures.bytes;
import static app.mnema.learning.support.ContractFixtures.fixture;
import static app.mnema.learning.support.ContractFixtures.mechanic;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Evaluation is pure: every case runs on the shared wire fixtures, the same bytes the clients see. */
class AttemptEvaluationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BLANK_1 = "b1a00000-0000-4000-8000-000000000001";
    private static final String BLANK_2 = "b1a00000-0000-4000-8000-000000000002";
    private static final String BLANK_3 = "b1a00000-0000-4000-8000-000000000003";
    private static final String CHOICE_1 = "dddddddd-dddd-4ddd-8ddd-ddddddddddd1";
    private static final String CHOICE_2 = "dddddddd-dddd-4ddd-8ddd-ddddddddddd2";
    private static final String CHOICE_3 = "dddddddd-dddd-4ddd-8ddd-ddddddddddd3";

    @Test
    void freeResponseMatchesAnyAcceptedAnswerAfterNormalizationAndReportsTheContractFeedback() {
        AttemptEvaluation correct = evaluate(freeResponse(false), text("erinnerung "));
        assertThat(correct.result()).isEqualTo(AttemptEvaluation.Result.CORRECT);
        assertThat(correct.evidenceClass()).isEqualTo(AttemptEvaluation.EvidenceClass.HIGH);
        assertThat(correct.reasonCodes()).containsExactly("UNHINTED", "DETERMINISTIC", "PRODUCTION");
        assertThat(correct.feedback()).isEqualTo(feedback("freeResponse"));
        // any one accepted alternative suffices; none is required in addition
        assertThat(evaluate(freeResponse(false), text("DIE ERINNERUNG")).result()).isEqualTo(AttemptEvaluation.Result.CORRECT);

        AttemptEvaluation wrong = evaluate(freeResponse(false), text("Gedächtnis"));
        assertThat(wrong.result()).isEqualTo(AttemptEvaluation.Result.INCORRECT);
        assertThat(wrong.evidenceClass()).isEqualTo(AttemptEvaluation.EvidenceClass.HIGH);
        assertThat(evaluate(freeResponse(false), text("")).result()).isEqualTo(AttemptEvaluation.Result.INCORRECT);

        AttemptEvaluation transcript = evaluate(freeResponse(true), text("erinnerung"));
        assertThat(transcript.result()).isEqualTo(AttemptEvaluation.Result.CORRECT);
        assertThat(transcript.evidenceClass()).isEqualTo(AttemptEvaluation.EvidenceClass.LOW);
        assertThat(transcript.reasonCodes()).containsExactlyElementsOf(
                strings(evidenceFixture("freeResponseTranscript").path("reasonCodes")));
    }

    @Test
    void strictPreservesDiacriticsAndPunctuationWhileSoftIgnoresThemWithoutTypoTolerance() {
        Consumer<ObjectNode> accepted = key -> {
            key.putArray("accepted").add("co-opération!");
            key.putArray("normalization").add("UNICODE_NFC").add("TRIM").add("CASE_FOLD");
        };
        AttemptEvaluation.Subject strict = freeResponse(false, accepted);
        AttemptEvaluation.Subject soft = freeResponse(false, accepted.andThen(key -> key.put("matchingMode", "SOFT")));
        assertThat(evaluate(strict, text("  CO-OPÉRATION!  ")).result()).isEqualTo(AttemptEvaluation.Result.CORRECT);
        assertThat(evaluate(strict, text("co operation")).result()).isEqualTo(AttemptEvaluation.Result.INCORRECT);
        assertThat(evaluate(soft, text("co operation")).result()).isEqualTo(AttemptEvaluation.Result.CORRECT);
        assertThat(evaluate(soft, text("cooperation")).result()).isEqualTo(AttemptEvaluation.Result.CORRECT);
        assertThat(evaluate(soft, text("cooperatoin")).result()).isEqualTo(AttemptEvaluation.Result.INCORRECT);
        assertThat(evaluate(soft, text("cooperation")).feedback().path("appliedRules").toString()).contains("SOFT_MATCH");
        // decomposed and precomposed forms are the same text after NFC
        AttemptEvaluation.Subject precomposed = freeResponse(false, key -> key.putArray("accepted").add("café"));
        assertThat(evaluate(precomposed, text("café")).result()).isEqualTo(AttemptEvaluation.Result.CORRECT);
    }

    @Test
    void clozeScoresEachBlankSeparatelyAndCapsEvidenceOnlyWhenAHintWasRecorded() {
        AttemptCommand.Response answers = response("cloze");
        AttemptEvaluation hinted = evaluate(cloze(Set.of(UUID.fromString(BLANK_3)), false), answers);
        assertThat(hinted.result()).isEqualTo(AttemptEvaluation.Result.PARTIAL);
        assertThat(hinted.evidenceClass()).isEqualTo(AttemptEvaluation.EvidenceClass.MEDIUM);
        assertThat(hinted.reasonCodes()).containsExactlyElementsOf(strings(evidenceFixture("clozeHinted").path("reasonCodes")));
        assertThat(hinted.feedback()).isEqualTo(feedback("cloze"));
        assertThat(evidenceFixture("clozeHinted").path("evidenceClass").textValue()).isEqualTo("MEDIUM");

        AttemptEvaluation unhinted = evaluate(cloze(Set.of(), false), answers);
        assertThat(unhinted.evidenceClass()).isEqualTo(AttemptEvaluation.EvidenceClass.HIGH);
        assertThat(unhinted.reasonCodes()).containsExactly("UNHINTED", "DETERMINISTIC", "PRODUCTION");
        assertThat(unhinted.feedback().path("blanks").get(2).path("hinted").booleanValue()).isFalse();

        AttemptEvaluation transcript = evaluate(cloze(Set.of(UUID.fromString(BLANK_3)), true), answers);
        assertThat(transcript.evidenceClass()).isEqualTo(AttemptEvaluation.EvidenceClass.LOW);
        assertThat(transcript.reasonCodes()).containsExactly("TRANSCRIPT_ACCOMMODATION", "DETERMINISTIC", "PRODUCTION");

        AttemptEvaluation all = evaluate(cloze(Set.of(UUID.fromString(BLANK_1)), false), blanks("map", "toList", "map"));
        assertThat(all.result()).isEqualTo(AttemptEvaluation.Result.CORRECT);
        assertThat(all.evidenceClass()).isEqualTo(AttemptEvaluation.EvidenceClass.MEDIUM);
        // a wrong answer is never softened by a hint
        AttemptEvaluation none = evaluate(cloze(Set.of(UUID.fromString(BLANK_1)), false), blanks("x", "y", "z"));
        assertThat(none.result()).isEqualTo(AttemptEvaluation.Result.INCORRECT);
        assertThat(none.evidenceClass()).isEqualTo(AttemptEvaluation.EvidenceClass.HIGH);
        assertThat(none.reasonCodes()).containsExactly("HINTED", "DETERMINISTIC", "PRODUCTION");
        // repeated words are scored per blank: the third blank is case-insensitive, the first is not
        assertThat(evaluate(cloze(Set.of(), false), blanks("map", "toList", "Map")).result())
                .isEqualTo(AttemptEvaluation.Result.CORRECT);
        assertThat(evaluate(cloze(Set.of(), false), blanks("Map", "toList", "map")).result())
                .isEqualTo(AttemptEvaluation.Result.PARTIAL);
    }

    @Test
    void clozeRequiresTheExactIssuedBlankSet() {
        AttemptEvaluation.Subject subject = cloze(Set.of(), false);
        assertInvalid(subject, new AttemptCommand.ClozeResponse(List.of(
                new AttemptCommand.BlankText(UUID.fromString(BLANK_1), "map"))));
        assertInvalid(subject, new AttemptCommand.ClozeResponse(List.of(
                new AttemptCommand.BlankText(UUID.fromString(BLANK_1), "map"),
                new AttemptCommand.BlankText(UUID.fromString(BLANK_1), "map"),
                new AttemptCommand.BlankText(UUID.randomUUID(), "x"))));
        assertInvalid(subject, new AttemptCommand.ClozeResponse(List.of(
                new AttemptCommand.BlankText(UUID.fromString(BLANK_1), "map"),
                new AttemptCommand.BlankText(UUID.fromString(BLANK_2), "toList"),
                new AttemptCommand.BlankText(UUID.randomUUID(), "map"))));
        assertInvalid(subject, text("map"));
    }

    @Test
    void choiceComparesTheExactOptionSetAndRejectsUnissuedOrExtraSelections() {
        AttemptEvaluation correct = evaluate(choice(false), response("choice"));
        assertThat(correct.result()).isEqualTo(AttemptEvaluation.Result.CORRECT);
        assertThat(correct.evidenceClass()).isEqualTo(AttemptEvaluation.EvidenceClass.LOW);
        assertThat(correct.reasonCodes()).containsExactly("RECOGNITION", "DETERMINISTIC", "PRODUCTION");
        assertThat(correct.feedback()).isEqualTo(feedback("choice"));

        assertThat(evaluate(choice(false), options(CHOICE_1)).result()).isEqualTo(AttemptEvaluation.Result.INCORRECT);
        assertThat(evaluate(choice(false), options(CHOICE_1, CHOICE_2, CHOICE_3)).result())
                .isEqualTo(AttemptEvaluation.Result.INCORRECT);
        assertThat(evaluate(choice(true), response("choice")).reasonCodes())
                .containsExactly("RECOGNITION", "DETERMINISTIC", "TRANSCRIPT_ACCOMMODATION");
        assertInvalid(choice(false), options(CHOICE_1, UUID.randomUUID().toString()));
        assertInvalid(choice(false), text("x"));

        AttemptEvaluation.Subject single = choice(false, content -> content.put("selectionMode", "SINGLE"));
        assertThat(evaluate(single, options(CHOICE_3)).result()).isEqualTo(AttemptEvaluation.Result.INCORRECT);
        assertInvalid(single, options(CHOICE_1, CHOICE_2));
    }

    @Test
    void matchScoresPairsAndRequiresAnExactBijectionOfTheIssuedIds() {
        AttemptEvaluation correct = evaluate(match(false), response("match"));
        assertThat(correct.result()).isEqualTo(AttemptEvaluation.Result.CORRECT);
        assertThat(correct.evidenceClass()).isEqualTo(AttemptEvaluation.EvidenceClass.LOW);
        assertThat(correct.reasonCodes()).containsExactly("MATCHING", "DETERMINISTIC", "RECOGNITION");
        AttemptEvaluation retry = correct.withPairRetry();
        assertThat(retry.result()).isEqualTo(AttemptEvaluation.Result.PARTIAL);
        assertThat(retry.reasonCodes()).containsExactly("MATCHING", "DETERMINISTIC", "RECOGNITION", "PAIR_RETRY");
        assertThat(retry.feedback()).isEqualTo(feedback("match"));
        assertThat(correct.feedback().path("result").textValue()).isEqualTo("CORRECT");

        List<AttemptCommand.MatchPair> pairs = ((AttemptCommand.MatchResponse) response("match")).pairs();
        // swapping two rights makes exactly two pairs wrong; a rotation of all rights makes every pair wrong
        assertThat(evaluate(match(false), new AttemptCommand.MatchResponse(List.of(
                new AttemptCommand.MatchPair(pairs.get(0).leftId(), pairs.get(1).rightId()),
                new AttemptCommand.MatchPair(pairs.get(1).leftId(), pairs.get(0).rightId()),
                pairs.get(2), pairs.get(3)))).result()).isEqualTo(AttemptEvaluation.Result.PARTIAL);
        assertThat(evaluate(match(false), new AttemptCommand.MatchResponse(List.of(
                new AttemptCommand.MatchPair(pairs.get(0).leftId(), pairs.get(1).rightId()),
                new AttemptCommand.MatchPair(pairs.get(1).leftId(), pairs.get(2).rightId()),
                new AttemptCommand.MatchPair(pairs.get(2).leftId(), pairs.get(3).rightId()),
                new AttemptCommand.MatchPair(pairs.get(3).leftId(), pairs.get(0).rightId())))).result())
                .isEqualTo(AttemptEvaluation.Result.INCORRECT);
        assertThat(evaluate(match(true), response("match")).reasonCodes())
                .containsExactly("MATCHING", "DETERMINISTIC", "TRANSCRIPT_ACCOMMODATION");

        assertInvalid(match(false), new AttemptCommand.MatchResponse(pairs.subList(0, 3)));
        assertInvalid(match(false), new AttemptCommand.MatchResponse(List.of(pairs.get(0), pairs.get(1), pairs.get(2),
                new AttemptCommand.MatchPair(pairs.get(3).leftId(), UUID.randomUUID()))));
        assertInvalid(match(false), new AttemptCommand.MatchResponse(List.of(pairs.get(0), pairs.get(1), pairs.get(2),
                new AttemptCommand.MatchPair(UUID.randomUUID(), pairs.get(3).rightId()))));
        assertInvalid(match(false), new AttemptCommand.MatchResponse(List.of(pairs.get(0), pairs.get(0), pairs.get(2), pairs.get(3))));
        assertInvalid(match(false), text("x"));
    }

    @Test
    void selfCheckMapsTheBehavioralRatingToLowEvidence() {
        AttemptEvaluation.Subject subject = new AttemptEvaluation.Subject(ExerciseType.SELF_CHECK,
                evaluator("self-check"), mechanic("createSelfCheck").path("exercise").path("answerKey"),
                presentation("selfCheck").path("content"), JSON.createObjectNode(), Set.of(), false);
        for (var entry : Map.of("FULL", AttemptEvaluation.Result.CORRECT, "PARTIAL", AttemptEvaluation.Result.PARTIAL,
                "HINTED", AttemptEvaluation.Result.PARTIAL, "NOT_RECALLED", AttemptEvaluation.Result.INCORRECT).entrySet()) {
            AttemptEvaluation evaluation = evaluate(subject, new AttemptCommand.SelfCheckResponse(
                    AttemptCommand.SelfRating.valueOf(entry.getKey())));
            assertThat(evaluation.result()).isEqualTo(entry.getValue());
            assertThat(evaluation.evidenceClass()).isEqualTo(AttemptEvaluation.EvidenceClass.LOW);
            assertThat(evaluation.reasonCodes()).containsExactly("SELF_REPORT", entry.getKey());
        }
        assertThat(evaluate(subject, new AttemptCommand.SelfCheckResponse(AttemptCommand.SelfRating.PARTIAL)).feedback())
                .isEqualTo(feedback("selfCheck"));
        assertInvalid(subject, text("x"));
    }

    @Test
    void cancelUnavailableEvaluatorsAndMediaFailuresNeverAssess() {
        AttemptEvaluation cancelled = evaluate(freeResponse(false), new AttemptCommand.CancelResponse());
        assertThat(cancelled.status()).isEqualTo(AttemptEvaluation.Status.NOT_ASSESSED);
        assertThat(cancelled.result()).isNull();
        // the semantic evaluator has no runtime: it neither falls back to exact matching nor blames the learner
        AttemptEvaluation.Subject semantic = new AttemptEvaluation.Subject(ExerciseType.FREE_RESPONSE,
                mechanic("rejectedAiAssessment").path("exercise").path("evaluatorPolicy"),
                mechanic("rejectedAiAssessment").path("exercise").path("answerKey"),
                presentation("freeResponse").path("content"), reveal(), Set.of(), false);
        AttemptEvaluation unavailable = evaluate(semantic, text("свойство тела сохранять скорость"));
        assertThat(unavailable.status()).isEqualTo(AttemptEvaluation.Status.UNAVAILABLE);
        assertThat(unavailable.result()).isNull();
        assertThat(unavailable.feedback()).isEqualTo(feedback("evaluatorUnavailable"));
        AttemptEvaluation.Subject base = freeResponse(false);
        AttemptEvaluation.Subject future = new AttemptEvaluation.Subject(ExerciseType.FREE_RESPONSE,
                evaluator("deterministic-text").put("version", "2"), base.answerKey(), base.content(), reveal(),
                Set.of(), false);
        assertThat(evaluate(future, text("erinnerung")).status()).isEqualTo(AttemptEvaluation.Status.UNAVAILABLE);
        AttemptEvaluation media = AttemptEvaluation.mediaNotReady();
        assertThat(media.status()).isEqualTo(AttemptEvaluation.Status.NOT_ASSESSED);
        assertThat(media.reasonCodes()).containsExactly("MEDIA_NOT_READY");
        assertThat(media.feedback()).isEqualTo(feedback("mediaNotReady"));
    }

    @Test
    void aMalformedResponseIsInvalidBeforeMediaOrEvaluatorShortCircuits() {
        AttemptEvaluation.Subject semantic = new AttemptEvaluation.Subject(ExerciseType.FREE_RESPONSE,
                mechanic("rejectedAiAssessment").path("exercise").path("evaluatorPolicy"),
                mechanic("rejectedAiAssessment").path("exercise").path("answerKey"),
                presentation("freeResponse").path("content"), reveal(), Set.of(), false);
        for (boolean mediaReady : new boolean[] {true, false}) {
            assertThatThrownBy(() -> AttemptEvaluation.evaluate(semantic, new AttemptCommand.SelfCheckResponse(
                    AttemptCommand.SelfRating.FULL), mediaReady)).isInstanceOf(InvalidRequestException.class);
            assertThatThrownBy(() -> AttemptEvaluation.evaluate(choice(false), text("x"), mediaReady))
                    .isInstanceOf(InvalidRequestException.class);
            assertThatThrownBy(() -> AttemptEvaluation.evaluate(cloze(Set.of(), false), options(CHOICE_1), mediaReady)).isInstanceOf(InvalidRequestException.class);
            assertThatThrownBy(() -> AttemptEvaluation.evaluate(match(false), new AttemptCommand.MatchResponse(
                    List.of()), mediaReady)).isInstanceOf(InvalidRequestException.class);
            assertThatThrownBy(() -> AttemptEvaluation.evaluate(choice(false), options(CHOICE_1, UUID.randomUUID().toString()),
                    mediaReady)).isInstanceOf(InvalidRequestException.class);
        }
        // a well-formed response with unavailable media is not assessed, a cancel is never a media problem
        assertThat(AttemptEvaluation.evaluate(freeResponse(false), text("erinnerung"), false).reasonCodes())
                .containsExactly("MEDIA_NOT_READY");
        assertThat(AttemptEvaluation.evaluate(freeResponse(false), new AttemptCommand.CancelResponse(), false).reasonCodes())
                .isEmpty();
        assertThat(AttemptEvaluation.evaluate(semantic, text("x"), true).status())
                .isEqualTo(AttemptEvaluation.Status.UNAVAILABLE);
    }

    // ---- subjects built from the wire fixtures ----

    private static AttemptEvaluation.Subject freeResponse(boolean transcript) {
        return freeResponse(transcript, key -> { });
    }

    private static AttemptEvaluation.Subject freeResponse(boolean transcript, Consumer<ObjectNode> change) {
        ObjectNode answerKey = mechanic("createFreeResponseAudio").withObject("exercise").withObject("answerKey");
        change.accept(answerKey);
        return new AttemptEvaluation.Subject(ExerciseType.FREE_RESPONSE, evaluator("deterministic-text"), answerKey,
                presentation("freeResponse").path("content"), reveal(), Set.of(), transcript);
    }

    private static AttemptEvaluation.Subject cloze(Set<UUID> hinted, boolean transcript) {
        return new AttemptEvaluation.Subject(ExerciseType.CLOZE, evaluator("deterministic-cloze"),
                mechanic("createCloze").path("exercise").path("answerKey"), presentation("cloze").path("content"),
                JSON.createObjectNode(), hinted, transcript);
    }

    private static AttemptEvaluation.Subject choice(boolean transcript) { return choice(transcript, content -> { }); }

    private static AttemptEvaluation.Subject choice(boolean transcript, Consumer<ObjectNode> change) {
        ObjectNode content = presentation("choice").path("content").deepCopy();
        change.accept(content);
        return new AttemptEvaluation.Subject(ExerciseType.CHOICE, evaluator("deterministic-choice"),
                mechanic("createChoiceVideoMultiple").path("exercise").path("answerKey"), content,
                JSON.createObjectNode(), Set.of(), transcript);
    }

    private static AttemptEvaluation.Subject match(boolean transcript) {
        return new AttemptEvaluation.Subject(ExerciseType.MATCH, evaluator("deterministic-match"),
                mechanic("createMatchMixed").path("exercise").path("answerKey"), presentation("match").path("content"),
                JSON.createObjectNode(), Set.of(), transcript);
    }

    private static ObjectNode evaluator(String id) { return JSON.createObjectNode().put("id", id).put("version", "1"); }

    private static ObjectNode reveal() {
        ObjectNode reveal = JSON.createObjectNode();
        reveal.putArray("reference");
        return reveal;
    }

    private static JsonNode presentation(String name) { return fixture("mechanics.json").path("presentations").path(name); }

    private static JsonNode feedback(String name) { return fixture("mechanics.json").path("feedback").path(name); }

    private static JsonNode evidenceFixture(String name) { return fixture("mechanics.json").path("evidence").path(name); }

    private static List<String> strings(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false).map(JsonNode::textValue).toList();
    }

    private static AttemptCommand.Response response(String name) {
        return AttemptCommand.read(bytes(fixture("mechanics.json").path("submits").path(name))).response();
    }

    private static AttemptCommand.Response text(String value) { return new AttemptCommand.TextResponse(value); }

    private static AttemptCommand.Response blanks(String first, String second, String third) {
        return new AttemptCommand.ClozeResponse(List.of(
                new AttemptCommand.BlankText(UUID.fromString(BLANK_1), first),
                new AttemptCommand.BlankText(UUID.fromString(BLANK_2), second),
                new AttemptCommand.BlankText(UUID.fromString(BLANK_3), third)));
    }

    private static AttemptCommand.Response options(String... ids) {
        return new AttemptCommand.ChoiceResponse(Arrays.stream(ids).map(UUID::fromString).toList());
    }

    private static AttemptEvaluation evaluate(AttemptEvaluation.Subject subject, AttemptCommand.Response response) {
        return AttemptEvaluation.evaluate(subject, response);
    }

    private static void assertInvalid(AttemptEvaluation.Subject subject, AttemptCommand.Response response) {
        assertThatThrownBy(() -> AttemptEvaluation.evaluate(subject, response)).isInstanceOf(InvalidRequestException.class);
    }
}
