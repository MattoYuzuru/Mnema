package app.mnema.learning.ai;

import app.mnema.learning.ai.prompt.PromptAssembler;
import app.mnema.learning.ai.prompt.PromptLibrary;
import app.mnema.learning.capability.SemanticAssessmentProvider.AnswerSource;
import app.mnema.learning.capability.SemanticAssessmentProvider.CriterionGrade;
import app.mnema.learning.capability.SemanticAssessmentProvider.Flag;
import app.mnema.learning.capability.SemanticAssessmentProvider.GradeOutcome;
import app.mnema.learning.capability.SemanticAssessmentProvider.GradeRequest;
import app.mnema.learning.capability.SemanticAssessmentProvider.Run;
import app.mnema.learning.capability.SemanticAssessmentProvider.Verdict;
import app.mnema.learning.catalog.exercise.Rubric;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The grader on a scripted {@link TextGeneration}: the prompt it builds, the output it accepts, repairs and gives up on. */
class SemanticGraderTest {
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UserKeys KEYS = UserKeys.withSecret("0123456789abcdef0123456789abcdef", "k1");
    private static final PromptAssembler ASSEMBLER = new PromptAssembler(PromptLibrary.fromClasspath("v1"),
            new AiProperties.Prompt("v1", 32_000, 25_000));
    private static final Rubric RUBRIC = new Rubric("Оптимизатор перебирает планы и выбирает самый дешёвый.", List.of(
            new Rubric.Criterion(UUID.randomUUID(), "Перебирает альтернативные планы", Rubric.Tier.CORE, 3),
            new Rubric.Criterion(UUID.randomUUID(), "Выбирает план с наименьшей стоимостью", Rubric.Tier.CORE, 2),
            new Rubric.Criterion(UUID.randomUUID(), "Опирается на статистику", Rubric.Tier.DETAIL, 1)),
            List.of("Выполняет все планы"), List.of("planner", "cost"));
    private static final String ANSWER = "Оптимизатор   перебирает ПЛАНЫ, и выбирает самый дешёвый";

    /** The scripted provider: one function from request to result, every request recorded. */
    private static final class Fake implements TextGeneration {
        final List<TextRequest> requests = Collections.synchronizedList(new ArrayList<>());
        Function<TextRequest, AiResult<TextResponse>> script = request -> ok(valid(MET_ALL));

        @Override
        public AiResult<TextResponse> generate(TextRequest request) {
            requests.add(request);
            return script.apply(request);
        }
    }

    private static final String MET_ALL = "[{\"id\":\"c1\",\"quote\":\"перебирает планы\",\"note\":\"Есть.\",\"verdict\":\"MET\"},"
            + "{\"id\":\"c2\",\"quote\":\"выбирает самый дешёвый\",\"note\":\"Есть.\",\"verdict\":\"MET\"},"
            + "{\"id\":\"c3\",\"quote\":\"\",\"note\":\"Нет.\",\"verdict\":\"NOT_MET\"}]";

    private final Fake fake = new Fake();
    private final SemanticGrader grader = new SemanticGrader(fake, ASSEMBLER, KEYS, false);

    @AfterEach
    void close() { grader.close(); }

    private static AiResult<TextResponse> ok(String json) {
        return AiResult.ok(new TextResponse(json, TextResponse.FinishReason.STOP, Usage.ZERO, 0, null,
                new TextResponse.RouteUsed("stub", "stub")));
    }

    private static String valid(String criteria) { return "{\"criteria\":" + criteria + ",\"flags\":[]}"; }

    private static GradeRequest request(int runs, String answer) {
        return new GradeRequest(ACCOUNT, UUID.randomUUID(), "Объясните, как работает оптимизатор.", "", RUBRIC, answer,
                AnswerSource.TYPED, "ru", runs, Duration.ofSeconds(5));
    }

    private static Run graded(GradeOutcome outcome) {
        assertThat(outcome).isInstanceOf(GradeOutcome.Graded.class);
        return ((GradeOutcome.Graded) outcome).runs().getFirst();
    }

    private static String reason(GradeOutcome outcome) {
        assertThat(outcome).isInstanceOf(GradeOutcome.Unavailable.class);
        return ((GradeOutcome.Unavailable) outcome).reason();
    }

    // ------------------------------------------------------------------------------------------------- request

    @Test
    void theRequestIsOneCacheableExercisePrefixAndTheAnswerAsDataOnTheAssessRoute() {
        GradeRequest request = request(1, ANSWER);
        graded(grader.grade(request));

        assertThat(fake.requests).hasSize(1);
        TextRequest sent = fake.requests.getFirst();
        assertThat(sent.route()).isEqualTo(AiRoute.ASSESS);
        assertThat(sent.output()).isEqualTo(OutputContract.JSON);
        assertThat(sent.temperature()).isEqualTo(0.2);
        assertThat(sent.stepId()).as("the journal rows correlate with the attempt").isEqualTo(request.attemptId());
        assertThat(sent.attempt()).isEqualTo(1);
        assertThat(sent.listener()).isNull();
        assertThat(sent.userKey().value()).startsWith("k1.").doesNotContain(ACCOUNT.toString());
        assertThat(sent.segments()).hasSize(2);
        TextRequest.Segment head = sent.segments().get(0);
        TextRequest.Segment tail = sent.segments().get(1);
        assertThat(head.cacheable()).isTrue();
        assertThat(head.text()).contains("c1 · Перебирает альтернативные планы", "c2 · Выбирает план с наименьшей стоимостью", "c3 · Опирается на статистику")
                .contains("Оптимизатор перебирает планы и выбирает самый дешёвый.", "- Выполняет все планы", "planner, cost")
                .contains("Объясните, как работает оптимизатор.").contains("<feedback_language>ru</feedback_language>");
        assertThat(head.text()).as("tiers and weights are the server's business").doesNotContain("CORE", "DETAIL", "weight", "TERM");
        assertThat(tail.cacheable()).isFalse();
        assertThat(tail.text()).contains("<answer_source>TYPED</answer_source>").contains("\"" + ANSWER + "\"");
        // the cacheable prefix is the same for every answer to the exercise
        grader.grade(new GradeRequest(ACCOUNT, UUID.randomUUID(), request.exercisePrompt(), "", RUBRIC, "совсем другой ответ",
                AnswerSource.SPEECH, "ru", 1, Duration.ofSeconds(5)));
        assertThat(fake.requests.get(1).segments().getFirst().text()).isEqualTo(head.text());
        assertThat(fake.requests.get(1).segments().get(1).text()).contains("<answer_source>SPEECH</answer_source>");
    }

    @Test
    void twoRunsAreParallelIndependentCallsAtTheHigherTemperature() {
        GradeOutcome outcome = grader.grade(request(2, ANSWER));
        assertThat(((GradeOutcome.Graded) outcome).runs()).hasSize(2);
        assertThat(fake.requests).hasSize(2).allSatisfy(sent -> assertThat(sent.temperature()).isEqualTo(0.3));
        assertThat(fake.requests).extracting(TextRequest::attempt).containsExactlyInAnyOrder(1, 2);
        assertThat(fake.requests.get(0).segments()).isEqualTo(fake.requests.get(1).segments());
        assertThatThrownBy(() -> request(3, ANSWER)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GradeRequest(ACCOUNT, UUID.randomUUID(), "q", "", RUBRIC, "a", AnswerSource.TYPED, "ru", 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noPersonalDataReachesThePromptAndAQuoteOfTheRedactedFormStillChecksOut() {
        String answer = "Пишите на anna@example.com: оптимизатор перебирает планы";
        fake.script = sent -> ok(valid("[{\"id\":\"c1\",\"quote\":\"Пишите на [email]\",\"note\":\"\",\"verdict\":\"MET\"},"
                + "{\"id\":\"c2\",\"quote\":\"перебирает планы\",\"note\":\"\",\"verdict\":\"PARTLY\"},"
                + "{\"id\":\"c3\",\"quote\":\"\",\"note\":\"\",\"verdict\":\"NOT_MET\"}]"));
        Run run = graded(grader.grade(request(1, answer)));
        String prompt = fake.requests.getFirst().segments().get(1).text();
        assertThat(prompt).doesNotContain("anna@example.com").contains("[email]");
        assertThat(run.criteria().getFirst().verdict()).as("the model saw the redacted answer").isEqualTo(Verdict.MET);
        assertThat(run.criteria().getFirst().quote()).isEqualTo("Пишите на [email]");
    }

    // ------------------------------------------------------------------------------------------------ output

    @Test
    void aValidAnswerIsReturnedPerCriterionInRubricOrderWithQuotesAndNotes() {
        Run run = graded(grader.grade(request(1, ANSWER)));
        assertThat(run.criteria()).extracting(CriterionGrade::criterionId)
                .containsExactlyElementsOf(RUBRIC.criteria().stream().map(Rubric.Criterion::criterionId).toList());
        assertThat(run.criteria()).extracting(CriterionGrade::verdict).containsExactly(Verdict.MET, Verdict.MET, Verdict.NOT_MET);
        assertThat(run.criteria().get(0).quote()).isEqualTo("перебирает планы");
        assertThat(run.criteria().get(0).note()).isEqualTo("Есть.");
        assertThat(run.criteria().get(2).quote()).isNull();
        assertThat(run.flags()).isEmpty();
    }

    @Test
    void aMetOrPartlyVerdictWithoutAVerbatimQuoteIsDowngradedToUnclear() {
        fake.script = sent -> ok(valid("[{\"id\":\"c1\",\"quote\":\"это модель придумала\",\"note\":\"\",\"verdict\":\"MET\"},"
                + "{\"id\":\"c2\",\"quote\":\"\",\"note\":\"\",\"verdict\":\"PARTLY\"},"
                + "{\"id\":\"c3\",\"quote\":\"перебирает планы\",\"note\":\"\",\"verdict\":\"NOT_MET\"}]"));
        Run run = graded(grader.grade(request(1, ANSWER)));
        assertThat(run.criteria()).extracting(CriterionGrade::verdict).containsExactly(Verdict.UNCLEAR, Verdict.UNCLEAR, Verdict.NOT_MET);
        assertThat(run.criteria()).extracting(CriterionGrade::quote).containsOnlyNulls();
        // a quote made only of edge characters is no quote
        fake.script = sent -> ok(valid("[{\"id\":\"c1\",\"quote\":\" … \",\"note\":\"\",\"verdict\":\"MET\"},"
                + "{\"id\":\"c2\",\"note\":\"\",\"verdict\":\"MET\"},{\"id\":\"c3\",\"quote\":null,\"verdict\":\"MET\"}]"));
        assertThat(graded(grader.grade(request(1, ANSWER))).criteria()).extracting(CriterionGrade::verdict)
                .containsOnly(Verdict.UNCLEAR);
    }

    @Test
    void aQuoteMayDifferInWhitespaceCaseQuotesEllipsisAndEscapedEntities() {
        assertThat(SemanticGrader.verified("«Перебирает   планы»", SemanticGrader.normalize(ANSWER), "")).isEqualTo("Перебирает планы");
        assertThat(SemanticGrader.verified("ОПТИМИЗАТОР … самый дешёвый", SemanticGrader.normalize(ANSWER), "")).isNotNull();
        assertThat(SemanticGrader.verified("оптимизатор ... дешёвый", SemanticGrader.normalize(ANSWER), "")).isNotNull();
        assertThat(SemanticGrader.verified("дешёвый … оптимизатор", SemanticGrader.normalize(ANSWER), ""))
                .as("fragments must keep their order").isNull();
        // the prompt escapes & < >, so a model that copies the escaped text is read back as the character
        String answer = "если a < b и c & d";
        assertThat(SemanticGrader.verified("a &lt; b и c &amp; d", SemanticGrader.normalize(answer), "")).isEqualTo("a < b и c & d");
        assertThat(SemanticGrader.verified(null, "x", "x")).isNull();
        assertThat(SemanticGrader.verified("   ", "x", "x")).isNull();
        assertThat(SemanticGrader.verified("x".repeat(500), "x".repeat(600), "")).hasSize(400);
        assertThat(SemanticGrader.normalize(" Ёж\t\n  ЛЕС ")).isEqualTo("ёж лес");
    }

    @Test
    void flagsAreParsedAndUnknownOnesIgnored() {
        fake.script = sent -> ok("{\"criteria\":" + MET_ALL + ",\"flags\":[\"OFF_TOPIC\",\"INJECTION\",\"ASR_GARBLED\",\"WRONG_LANGUAGE\",\"TOO_SHORT\","
                + "\"SOMETHING_NEW\", 7]}");
        assertThat(graded(grader.grade(request(1, ANSWER))).flags()).containsExactlyInAnyOrder(Flag.OFF_TOPIC, Flag.INJECTION,
                Flag.ASR_GARBLED, Flag.WRONG_LANGUAGE, Flag.TOO_SHORT);
        fake.script = sent -> ok("{\"criteria\":" + MET_ALL + ",\"flags\":\"OFF_TOPIC\"}");
        assertThat(graded(grader.grade(request(1, ANSWER))).flags()).isEmpty();
        fake.script = sent -> ok("{\"criteria\":" + MET_ALL + "}");
        assertThat(graded(grader.grade(request(1, ANSWER))).flags()).isEmpty();
    }

    @Test
    void notesAreOneBoundedCleanLine() {
        fake.script = sent -> ok(valid("[{\"id\":\"c1\",\"quote\":\"перебирает планы\",\"note\":\"Строка\\none\\u0007\\n  две\",\"verdict\":\"MET\"},"
                + "{\"id\":\"c2\",\"quote\":\"\",\"note\":\"" + "я".repeat(500) + "\",\"verdict\":\"NOT_MET\"},"
                + "{\"id\":\"c3\",\"verdict\":\"NOT_MET\"}]"));
        Run run = graded(grader.grade(request(1, ANSWER)));
        assertThat(run.criteria().get(0).note()).isEqualTo("Строка one две");
        assertThat(run.criteria().get(1).note()).hasSize(240);
        assertThat(run.criteria().get(2).note()).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------- repair

    @Test
    void outputThatDoesNotFitIsSentBackOnceWithTheFindingAndThenAccepted() {
        for (String broken : List.of("не json", "[1]", "{\"criteria\":\"c1\"}", valid("[{\"id\":\"c1\",\"verdict\":\"NOT_MET\"}]"),
                valid("[{\"id\":\"c1\",\"verdict\":\"NOT_MET\"},{\"id\":\"c1\",\"verdict\":\"NOT_MET\"},{\"id\":\"c3\",\"verdict\":\"NOT_MET\"}]"),
                valid("[{\"id\":\"c1\",\"verdict\":\"NOT_MET\"},{\"id\":\"c2\",\"verdict\":\"NOT_MET\"},{\"id\":\"c9\",\"verdict\":\"NOT_MET\"}]"),
                valid("[{\"id\":\"c1\",\"verdict\":\"MAYBE\"},{\"id\":\"c2\",\"verdict\":\"NOT_MET\"},{\"id\":\"c3\",\"verdict\":\"NOT_MET\"}]"),
                valid("[{\"id\":\"c1\"},{\"id\":\"c2\",\"verdict\":\"NOT_MET\"},{\"id\":\"c3\",\"verdict\":\"NOT_MET\"}]"),
                valid("[{\"id\":\"c01\",\"verdict\":\"NOT_MET\"},{\"id\":\"c2\",\"verdict\":\"NOT_MET\"},{\"id\":\"c3\",\"verdict\":\"NOT_MET\"}]"),
                valid("[{\"id\":\"cx\",\"verdict\":\"NOT_MET\"},{\"id\":\"c2\",\"verdict\":\"NOT_MET\"},{\"id\":\"c3\",\"verdict\":\"NOT_MET\"}]"),
                valid("[{\"verdict\":\"NOT_MET\"},{\"id\":\"c2\",\"verdict\":\"NOT_MET\"},{\"id\":\"c3\",\"verdict\":\"NOT_MET\"}]"))) {
            fake.requests.clear();
            fake.script = sent -> sent.segments().stream().anyMatch(segment -> segment.text().startsWith(TextRequest.REPAIR_PREFIX))
                    ? ok(valid(MET_ALL)) : ok(broken);
            GradeOutcome outcome = grader.grade(request(1, ANSWER));
            assertThat(outcome).as(broken).isInstanceOf(GradeOutcome.Graded.class);
            assertThat(fake.requests).as(broken).hasSize(2);
            TextRequest.Segment repair = fake.requests.get(1).segments().getLast();
            assertThat(repair.text()).startsWith(TextRequest.REPAIR_PREFIX).as("the finding names what was wrong").contains("Предыдущий ответ не принят");
            assertThat(fake.requests.get(1).segments().subList(0, 2)).as("the cacheable prefix is untouched").isEqualTo(fake.requests.get(0).segments());
        }
    }

    @Test
    void outputThatNeverFitsIsUnavailableAfterOneRepair() {
        fake.script = sent -> ok("{\"criteria\":[]}");
        assertThat(reason(grader.grade(request(1, ANSWER)))).isEqualTo("INVALID_OUTPUT");
        assertThat(fake.requests).hasSize(2);
    }

    // ----------------------------------------------------------------------------------------------- failures

    @Test
    void everyProviderFailureIsAStableReasonNeverAThrownError() {
        record Case(AiFailure failure, String reason) { }
        for (Case failure : List.of(new Case(new AiFailure.Timeout(), "TIMEOUT"), new Case(new AiFailure.RateLimited(Duration.ZERO), "RATE_LIMITED"),
                new Case(new AiFailure.Transient("http_503"), "PROVIDER_ERROR"), new Case(new AiFailure.InvalidOutput("x"), "INVALID_OUTPUT"),
                new Case(new AiFailure.Refusal("x"), "REFUSAL"), new Case(new AiFailure.BudgetExhausted(), "BUDGET"),
                new Case(new AiFailure.NotConfigured("x"), "NOT_CONFIGURED"), new Case(new AiFailure.CircuitOpen(), "CIRCUIT_OPEN"))) {
            fake.script = sent -> AiResult.failed(failure.failure());
            assertThat(reason(grader.grade(request(1, ANSWER)))).isEqualTo(failure.reason());
            assertThat(reason(grader.grade(request(2, ANSWER)))).isEqualTo(failure.reason());
        }
    }

    @Test
    void oneFailedRunOfTwoMakesTheWholeGradeUnavailable() {
        fake.script = sent -> sent.attempt() == 1 ? ok(valid(MET_ALL)) : AiResult.failed(new AiFailure.Timeout());
        assertThat(reason(grader.grade(request(2, ANSWER)))).isEqualTo("TIMEOUT");
    }

    @Test
    void theDeadlineBoundsTheWholeGradingIncludingTheRepair() {
        GradeRequest request = new GradeRequest(ACCOUNT, UUID.randomUUID(), "q", "", RUBRIC, ANSWER, AnswerSource.TYPED, "ru", 1,
                Duration.ofMillis(100));
        fake.script = sent -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return ok("{\"criteria\":[]}");
        };
        assertThat(reason(grader.grade(request))).isEqualTo("DEADLINE");
        assertThat(fake.requests).as("no repair is started past the deadline").hasSize(1);
        assertThat(fake.requests.getFirst().deadline()).isLessThanOrEqualTo(Duration.ofMillis(100));
    }

    @Test
    void aPromptThatCannotBeBuiltIsUnavailableNotAnError() {
        assertThat(reason(grader.grade(request(1, "я".repeat(70_000))))).isEqualTo("PROMPT");
        assertThat(fake.requests).isEmpty();
        // a real provider without the user-key secret cannot be addressed: the grade is unavailable, not an exception
        SemanticGrader unkeyed = new SemanticGrader(fake, ASSEMBLER, UserKeys.withSecret("short", "k1"), false);
        assertThat(reason(unkeyed.grade(request(1, ANSWER)))).isEqualTo("PROMPT");
        unkeyed.close();
        // the Stub needs no secret
        SemanticGrader stub = new SemanticGrader(fake, ASSEMBLER, UserKeys.withSecret("short", "k1"), true);
        assertThat(stub.grade(request(1, ANSWER))).isInstanceOf(GradeOutcome.Graded.class);
        stub.close();
    }

    @Test
    void theRequestAndItsLogLineNeverShowTheAnswer() {
        String text = request(1, ANSWER).toString();
        assertThat(text).doesNotContain("перебирает", "Оптимизатор").contains("runs=1", "source=TYPED");
    }
}
