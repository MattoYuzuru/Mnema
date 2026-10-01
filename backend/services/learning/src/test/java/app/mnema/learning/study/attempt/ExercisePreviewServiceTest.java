package app.mnema.learning.study.attempt;

import app.mnema.learning.catalog.exercise.ExerciseType;
import app.mnema.learning.platform.api.InvalidRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static app.mnema.learning.study.attempt.PreviewRequests.hint;
import static app.mnema.learning.study.attempt.PreviewRequests.mechanicExercise;
import static app.mnema.learning.study.attempt.PreviewRequests.pairCheck;
import static app.mnema.learning.study.attempt.PreviewRequests.previewFixture;
import static app.mnema.learning.study.attempt.PreviewRequests.request;
import static app.mnema.learning.study.attempt.PreviewRequests.submit;
import static app.mnema.learning.support.ContractFixtures.JSON;
import static app.mnema.learning.support.ContractFixtures.bytes;
import static app.mnema.learning.support.ContractFixtures.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** One evaluator: preview feedback is Study feedback, checked on the shared golden cases and on Study's own code. */
class ExercisePreviewServiceTest {
    private static final String BLANK_1 = "b1a00000-0000-4000-8000-000000000001";
    private static final String BLANK_2 = "b1a00000-0000-4000-8000-000000000002";
    private static final String BLANK_3 = "b1a00000-0000-4000-8000-000000000003";
    private static final String LEFT_1 = "1e000000-0000-4000-8000-000000000001";
    private static final String RIGHT_1 = "7e000000-0000-4000-8000-000000000001";
    private static final String RIGHT_2 = "7e000000-0000-4000-8000-000000000002";
    private final ExercisePreviewService service = new ExercisePreviewService();

    @ParameterizedTest
    @CsvSource({"SELF_CHECK,createSelfCheck,selfCheck,false", "FREE_RESPONSE,createFreeResponseAudio,freeResponse,false",
            "CLOZE,createCloze,cloze,false", "CHOICE,createChoiceVideoMultiple,choice,false",
            "MATCH,createMatchMixed,match,true", "ORDER,createOrder,order,false",
            "CATEGORIZE,createCategorize,categorize,false"})
    void previewFeedbackEqualsStudyFeedbackForTheSameInputs(ExerciseType type, String create, String name,
                                                           boolean pairMistakes) {
        JsonNode mechanics = fixture("mechanics.json");
        ObjectNode exercise = mechanicExercise(create);
        JsonNode response = mechanics.path("submits").path(name).path("response");
        JsonNode golden = mechanics.path("feedback").path(name);
        List<UUID> hinted = hinted(golden);

        JsonNode preview = service.evaluate(ExercisePreviewCommand.read(bytes(
                request(exercise, submit(response, hinted, pairMistakes, false))))).path("feedback");

        // Study: the stored presentation content, the same subject and the same pair-retry rule.
        AttemptCommand.Response studyResponse = AttemptCommand.response(response);
        AttemptEvaluation.Subject subject = new AttemptEvaluation.Subject(type, exercise.path("evaluatorPolicy"),
                exercise.path("answerKey"), mechanics.path("presentations").path(name).path("content"),
                JSON.createObjectNode().set("reference", golden.path("referenceContent")), new HashSet<>(hinted), false);
        AttemptEvaluation study = AttemptEvaluation.evaluate(subject, studyResponse);
        if (pairMistakes && study.result() == AttemptEvaluation.Result.CORRECT) study = study.withPairRetry();

        assertThat(preview).isEqualTo(study.feedback());
        assertThat(preview).isEqualTo(golden);
    }

    @Test
    void theSharedPreviewFixturesProduceTheirDocumentedResults() {
        for (String name : new String[] {"Cloze", "Choice", "MatchAfterMistake", "FreeResponse", "Order", "Categorize"}) {
            JsonNode result = service.evaluate(ExercisePreviewCommand.read(bytes(previewFixture("submit" + name))));
            assertThat(result).isEqualTo(fixture("preview.json").path("submit" + name + "Result"));
        }
        assertThat(service.evaluate(ExercisePreviewCommand.read(bytes(previewFixture("pairCheck")))))
                .isEqualTo(fixture("preview.json").path("pairCheckResult"));
        assertThat(service.evaluate(ExercisePreviewCommand.read(bytes(previewFixture("hint")))))
                .isEqualTo(fixture("preview.json").path("hintResult"));
    }

    @Test
    void theBoundaryHasNoCollaboratorsSoItCannotReachADatabaseClockOrMediaCatalog() {
        assertThat(ExercisePreviewService.class.getDeclaredConstructors()).singleElement()
                .satisfies(constructor -> assertThat(constructor.getParameterCount()).isZero());
        assertThat(java.util.Arrays.stream(ExercisePreviewService.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))).isEmpty();
        assertThat(java.util.Arrays.stream(ExercisePreviewController.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getType)).containsExactly(ExercisePreviewService.class);
    }

    @Test
    void freeResponseReferenceContentIsAlwaysEmptyBecauseTheEditorShowsTheDraft() {
        ObjectNode exercise = mechanicExercise("createFreeResponseAudio");
        exercise.withObject("content").withArray("reference").addObject().put("kind", "TEXT").put("text", "Explanation");
        JsonNode feedback = service.evaluate(ExercisePreviewCommand.read(bytes(request(exercise,
                submit(JSON.createObjectNode().put("kind", "TEXT").put("text", "wrong")))))).path("feedback");
        assertThat(feedback.path("result").textValue()).isEqualTo("INCORRECT");
        assertThat(feedback.path("referenceContent")).isEmpty();
        assertThat(feedback.path("referenceContent").isArray()).isTrue();
        assertThat(feedback.path("reference").textValue()).isEqualTo("Erinnerung");
    }

    @Test
    void aSemanticEvaluatorIsUnavailableAfterStructuralValidationNeverSubstituted() {
        ObjectNode exercise = mechanicExercise("rejectedAiAssessment");
        JsonNode result = service.evaluate(ExercisePreviewCommand.read(bytes(request(exercise,
                submit(JSON.createObjectNode().put("kind", "TEXT").put("text", "свойство тела сохранять скорость"))))));
        assertThat(result).isEqualTo(fixture("preview.json").path("aiSemanticResult"));
        // the response must still fit the type: structural validation precedes UNAVAILABLE
        assertThatThrownBy(() -> service.evaluate(ExercisePreviewCommand.read(bytes(request(exercise,
                submit(JSON.createObjectNode().put("kind", "SELF_CHECK").put("rating", "FULL")))))))
                .isInstanceOf(InvalidRequestException.class);
        // and an invalid rubric never gets that far
        exercise.withObject("evaluatorPolicy").withObject("rubric").withArray("criteria").removeAll();
        assertThatThrownBy(() -> ExercisePreviewCommand.read(bytes(request(exercise, hint(BLANK_1)))))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void theResponseKindMustMatchTheTypeAndIdsSetsAndMapsAreValidatedLikeStudy() {
        JsonNode text = JSON.createObjectNode().put("kind", "TEXT").put("text", "x");
        JsonNode cloze = previewFixture("submitCloze").path("action").path("response");
        JsonNode choice = previewFixture("submitChoice").path("action").path("response");
        JsonNode match = previewFixture("submitMatchAfterMistake").path("action").path("response");
        for (String create : new String[] {"createSelfCheck", "createFreeResponseAudio", "createCloze",
                "createChoiceVideoMultiple", "createMatchMixed"}) {
            ExerciseType type = ExerciseType.valueOf(mechanicExercise(create).path("type").textValue());
            for (JsonNode response : new JsonNode[] {text, cloze, choice, match}) {
                boolean fits = switch (response.path("kind").textValue()) {
                    case "TEXT" -> type == ExerciseType.FREE_RESPONSE;
                    case "CLOZE" -> type == ExerciseType.CLOZE;
                    case "CHOICE" -> type == ExerciseType.CHOICE;
                    default -> type == ExerciseType.MATCH;
                };
                if (fits) continue;
                assertInvalid(request(mechanicExercise(create), submit(response)));
            }
        }
        // foreign, missing and duplicated ids, a partial pair map, an unknown option, SINGLE with two options
        assertInvalid(withResponse("submitCloze", r -> r.withArray("blanks").remove(0)));
        assertInvalid(withResponse("submitCloze", r -> ((ObjectNode) r.withArray("blanks").get(0))
                .put("blankId", UUID.randomUUID().toString())));
        assertInvalid(withResponse("submitChoice", r -> r.withArray("optionIds").add(UUID.randomUUID().toString())));
        assertInvalid(withResponse("submitMatchAfterMistake", r -> r.withArray("pairs").remove(0)));
        assertInvalid(withResponse("submitMatchAfterMistake", r -> ((ObjectNode) r.withArray("pairs").get(0))
                .put("rightId", UUID.randomUUID().toString())));
        assertInvalid(withResponse("submitMatchAfterMistake", r -> ((ObjectNode) r.withArray("pairs").get(0))
                .put("rightId", RIGHT_2)));
        ObjectNode single = previewFixture("submitChoice");
        single.withObject("exercise").withObject("content").put("selectionMode", "SINGLE");
        single.withObject("exercise").withObject("answerKey").withArray("correctOptionIds").remove(1);
        assertInvalid(single);
        assertThat(service.evaluate(ExercisePreviewCommand.read(bytes(withResponse("submitChoice",
                r -> r.withArray("optionIds").remove(1))))).path("feedback").path("result").textValue())
                .isEqualTo("INCORRECT");
    }

    @Test
    void orderAndCategorizePreviewsValidateTheDraftAndTheResponseLikeStudy() {
        ObjectNode order = previewFixture("submitOrder");
        // the equivalent swap of the identical tiles gives exactly the golden Study feedback
        JsonNode mechanics = fixture("mechanics.json");
        assertThat(service.evaluate(ExercisePreviewCommand.read(bytes(request(mechanicExercise("createOrder"),
                submit(mechanics.path("submits").path("orderEquivalent").path("response"))))))
                .path("feedback")).isEqualTo(mechanics.path("feedback").path("orderEquivalent"));
        // a correct explicit order and its equivalent swap are both CORRECT; a misplaced item is INCORRECT
        assertThat(feedbackResult(order)).isEqualTo("CORRECT");
        JsonNode misplaced = withResponse("submitOrder", r -> {
            var sequence = r.withArray("sequence");
            var first = sequence.get(0);
            sequence.set(0, sequence.get(4));
            sequence.set(4, first);
        }).path("action").path("response");
        JsonNode wrong = service.evaluate(ExercisePreviewCommand.read(bytes(request(
                previewFixture("submitOrder").withObject("exercise"), submit(misplaced))))).path("feedback");
        assertThat(wrong.path("result").textValue()).isEqualTo("INCORRECT");
        assertThat(wrong.path("positions").get(0).path("correct").booleanValue()).isFalse();
        // unknown, missing, duplicate and foreign ids are a 400, never a wrong answer
        assertInvalid(withResponse("submitOrder", r -> r.withArray("sequence").remove(0)));
        assertInvalid(withResponse("submitOrder", r -> r.withArray("sequence").set(0,
                JSON.getNodeFactory().textNode(UUID.randomUUID().toString()))));
        assertInvalid(withResponse("submitOrder", r -> r.withArray("sequence").set(1, r.withArray("sequence").get(0))));
        assertInvalid(withResponse("submitOrder", r -> r.withArray("sequence").add(UUID.randomUUID().toString())));
        assertInvalid(withResponse("submitCategorize", r -> r.withArray("assignments").remove(0)));
        assertInvalid(withResponse("submitCategorize", r -> ((ObjectNode) r.withArray("assignments").get(0))
                .put("categoryId", UUID.randomUUID().toString())));
        assertInvalid(withResponse("submitCategorize", r -> ((ObjectNode) r.withArray("assignments").get(0))
                .put("itemId", UUID.randomUUID().toString())));
        assertInvalid(withResponse("submitCategorize", r -> ((ObjectNode) r.withArray("assignments").get(1))
                .put("itemId", r.withArray("assignments").get(0).path("itemId").textValue())));
        // the responses of the other mechanics do not fit
        assertInvalid(request(previewFixture("submitOrder").withObject("exercise"),
                submit(previewFixture("submitCategorize").path("action").path("response"))));
        assertInvalid(request(previewFixture("submitCategorize").withObject("exercise"),
                submit(previewFixture("submitOrder").path("action").path("response"))));
        assertInvalid(request(previewFixture("submitChoice").withObject("exercise"),
                submit(previewFixture("submitOrder").path("action").path("response"))));
        // pair checks and hints belong to MATCH and CLOZE only; an invalid draft never reaches the evaluator
        assertInvalid(request(order.withObject("exercise").deepCopy(), pairCheck(LEFT_1, RIGHT_1)));
        assertInvalid(request(order.withObject("exercise").deepCopy(), hint(BLANK_1)));
        assertInvalid(request(previewFixture("submitCategorize").withObject("exercise"), pairCheck(LEFT_1, RIGHT_1)));
        ObjectNode duplicateLabels = previewFixture("submitCategorize");
        ((ObjectNode) duplicateLabels.withObject("exercise").withObject("content").withArray("categories").get(0))
                .put("label", " ГЛАГОЛ ");
        assertInvalid(duplicateLabels);
        ObjectNode removedCategory = previewFixture("submitCategorize");
        removedCategory.withObject("exercise").withObject("content").withArray("categories").remove(0);
        assertInvalid(removedCategory);
    }

    private String feedbackResult(ObjectNode body) {
        return service.evaluate(ExercisePreviewCommand.read(bytes(body))).path("feedback").path("result").textValue();
    }

    @Test
    void hintedBlankIdsMustBeFirstLetterBlanksAndFlagDoesNotChangeFeedbackShape() {
        // BLANK_2 has no first-letter hint; BLANK_1/3 do. Hinted ids only mark the feedback.
        assertInvalid(withAction("submitCloze", a -> a.withArray("hintedBlankIds").add(BLANK_2)));
        assertInvalid(withAction("submitCloze", a -> a.withArray("hintedBlankIds").add(UUID.randomUUID().toString())));
        assertInvalid(withAction("submitChoice", a -> a.withArray("hintedBlankIds").add(BLANK_1)));
        JsonNode withExtraHint = service.evaluate(ExercisePreviewCommand.read(bytes(
                withAction("submitCloze", a -> a.withArray("hintedBlankIds").add(BLANK_1))))).path("feedback");
        assertThat(withExtraHint.path("blanks").findValuesAsText("hinted")).containsExactly("true", "false", "true");
        JsonNode unhinted = service.evaluate(ExercisePreviewCommand.read(bytes(
                withAction("submitCloze", a -> a.withArray("hintedBlankIds").removeAll())))).path("feedback");
        assertThat(unhinted.path("blanks").findValuesAsText("hinted")).containsExactly("false", "false", "false");
        // transcriptRevealed and pairMistakes are accepted for every type and never alter other mechanics
        JsonNode choice = service.evaluate(ExercisePreviewCommand.read(bytes(withAction("submitChoice", a -> {
            a.put("transcriptRevealed", true);
            a.put("pairMistakes", true);
        })))).path("feedback");
        assertThat(choice).isEqualTo(fixture("preview.json").path("submitChoiceResult").path("feedback"));
    }

    @Test
    void aWrongPairCheckOnlyMarksAPerfectMapPartialAndNeverAnImperfectOne() {
        // mistakes + perfect map => PARTIAL / PAIR_RETRY (the fixture); no mistakes => CORRECT
        ObjectNode clean = withAction("submitMatchAfterMistake", a -> a.put("pairMistakes", false));
        JsonNode correct = service.evaluate(ExercisePreviewCommand.read(bytes(clean))).path("feedback");
        assertThat(correct.path("result").textValue()).isEqualTo("CORRECT");
        assertThat(correct.path("appliedRules").toString()).doesNotContain("PAIR_RETRY");
        // mistakes + a wrong final map stays INCORRECT/PARTIAL without the retry rule
        ObjectNode wrong = withResponse("submitMatchAfterMistake", r -> {
            ((ObjectNode) r.withArray("pairs").get(0)).put("rightId", "7e000000-0000-4000-8000-000000000002");
            ((ObjectNode) r.withArray("pairs").get(1)).put("rightId", RIGHT_1);
        });
        JsonNode partial = service.evaluate(ExercisePreviewCommand.read(bytes(wrong))).path("feedback");
        assertThat(partial.path("result").textValue()).isEqualTo("PARTIAL");
        assertThat(partial.path("appliedRules").toString()).doesNotContain("PAIR_RETRY");
    }

    @Test
    void pairCheckAnswersTrueAndFalseAndOnlyForSidesOfTheDraftMatch() {
        ObjectNode exercise = previewFixture("submitMatchAfterMistake").withObject("exercise");
        assertThat(check(exercise, LEFT_1, RIGHT_1).path("correct").booleanValue()).isTrue();
        assertThat(check(exercise, LEFT_1, RIGHT_2).path("correct").booleanValue()).isFalse();
        assertThat(check(exercise, LEFT_1, RIGHT_1).size()).isOne();
        assertInvalid(request(exercise.deepCopy(), pairCheck(LEFT_1, UUID.randomUUID().toString())));
        assertInvalid(request(exercise.deepCopy(), pairCheck(UUID.randomUUID().toString(), RIGHT_1)));
        // ids are sides, not the other way round
        assertInvalid(request(exercise.deepCopy(), pairCheck(RIGHT_1, LEFT_1)));
        // other mechanics have no pairs
        assertInvalid(request(previewFixture("submitChoice").withObject("exercise"), pairCheck(LEFT_1, RIGHT_1)));
    }

    @Test
    void hintRevealsTheFirstGraphemeWithoutLeadingWhitespaceOnlyWhereTheAuthorEnabledIt() {
        ObjectNode exercise = previewFixture("submitCloze").withObject("exercise");
        assertThat(hintOf(exercise, BLANK_1)).isEqualTo(fixture("preview.json").path("hintResult"));
        assertThat(hintOf(exercise, BLANK_3).path("firstLetter").textValue()).isEqualTo("m");
        assertInvalid(request(exercise.deepCopy(), hint(BLANK_2)));
        assertInvalid(request(exercise.deepCopy(), hint(UUID.randomUUID().toString())));
        assertInvalid(request(previewFixture("submitChoice").withObject("exercise"), hint(BLANK_1)));

        // leading whitespace of the reference never becomes the hint, and the letter is a whole grapheme
        for (String[] accepted : new String[][] {{"  \t Hund", "H"}, {" éclair", "é"},
                {"éclair", "é"}, {"👨‍👩‍👧 x",
                        "👨‍👩‍👧"}}) {
            ObjectNode variant = previewFixture("submitCloze").withObject("exercise");
            variant.withObject("answerKey").withArray("blanks").remove(2);
            variant.withObject("content").withArray("passage").remove(5);
            variant.withObject("content").withArray("passage").remove(4);
            ((ObjectNode) variant.withObject("answerKey").withArray("blanks").get(0)).putArray("accepted")
                    .add(accepted[0]);
            ((ObjectNode) variant.withObject("answerKey").withArray("blanks").get(1)).putArray("accepted")
                    .add("toList");
            // the first blank is ANSWER_LENGTH: give it a fixed width so any reference length is valid
            ((ObjectNode) variant.withObject("content").withArray("passage").get(1)).putObject("size")
                    .put("mode", "FIXED").put("length", 8);
            assertThat(hintOf(variant, BLANK_1).path("firstLetter").textValue()).isEqualTo(accepted[1]);
        }
    }

    private JsonNode check(ObjectNode exercise, String left, String right) {
        return service.evaluate(ExercisePreviewCommand.read(bytes(request(exercise.deepCopy(), pairCheck(left, right)))));
    }

    private JsonNode hintOf(ObjectNode exercise, String blank) {
        return service.evaluate(ExercisePreviewCommand.read(bytes(request(exercise.deepCopy(),
                hint(blank)))));
    }

    private void assertInvalid(ObjectNode body) {
        assertThatThrownBy(() -> service.evaluate(ExercisePreviewCommand.read(bytes(body))))
                .isInstanceOf(InvalidRequestException.class);
    }

    private static ObjectNode withResponse(String fixture, Consumer<ObjectNode> change) {
        ObjectNode body = previewFixture(fixture);
        change.accept(body.withObject("action").withObject("response"));
        return body;
    }

    private static ObjectNode withAction(String fixture, Consumer<ObjectNode> change) {
        ObjectNode body = previewFixture(fixture);
        change.accept(body.withObject("action"));
        return body;
    }

    private static List<UUID> hinted(JsonNode golden) {
        Set<UUID> ids = new HashSet<>();
        golden.path("blanks").forEach(blank -> {
            if (blank.path("hinted").booleanValue()) ids.add(UUID.fromString(blank.path("blankId").textValue()));
        });
        return List.copyOf(ids);
    }
}
