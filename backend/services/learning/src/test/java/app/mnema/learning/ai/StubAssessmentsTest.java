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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The Stub's grading answers, read back through the real grader: the markers of the learner answer and the lexical heuristic. */
class StubAssessmentsTest {
    private static final PromptAssembler ASSEMBLER = new PromptAssembler(PromptLibrary.fromClasspath("v1"),
            new AiProperties.Prompt("v1", 32_000, 25_000));
    private static final Rubric RUBRIC = new Rubric("Оптимизатор перебирает планы и выбирает самый дешёвый.", List.of(
            new Rubric.Criterion(UUID.randomUUID(), "Перебирает альтернативные планы выполнения запроса", Rubric.Tier.CORE, 3),
            new Rubric.Criterion(UUID.randomUUID(), "Выбирает план с наименьшей оценкой стоимости", Rubric.Tier.CORE, 3),
            new Rubric.Criterion(UUID.randomUUID(), "Оценка опирается на статистику таблиц", Rubric.Tier.DETAIL, 1)),
            List.of("Выполняет все планы"), List.of("planner"));
    private final StubTextAdapter stub = new StubTextAdapter();
    private final SemanticGrader grader = new SemanticGrader(request -> stub.attempt("stub", request, Duration.ofSeconds(5)), ASSEMBLER,
            UserKeys.withSecret("0123456789abcdef0123456789abcdef", "k1"), true);

    @AfterEach
    void close() { grader.close(); }

    private List<Run> grade(String answer, int runs) {
        GradeOutcome outcome = grader.grade(new GradeRequest(UUID.randomUUID(), UUID.randomUUID(), "Как работает оптимизатор?", "", RUBRIC,
                answer, AnswerSource.TYPED, "ru", runs, Duration.ofSeconds(5)));
        assertThat(outcome).isInstanceOf(GradeOutcome.Graded.class);
        return ((GradeOutcome.Graded) outcome).runs();
    }

    private List<Verdict> verdicts(Run run) { return run.criteria().stream().map(CriterionGrade::verdict).toList(); }

    @Test
    void theMarkersChooseTheOutcome() {
        assertThat(verdicts(grade("Планы [[stub:assess-complete]]", 1).getFirst())).containsExactly(Verdict.MET, Verdict.MET, Verdict.MET);
        assertThat(grade("Планы [[stub:assess-complete]]", 1).getFirst().criteria().getFirst().quote()).isEqualTo("Планы");
        assertThat(verdicts(grade("[[stub:assess-shallow]] только планы", 1).getFirst())).containsExactly(Verdict.MET, Verdict.PARTLY, Verdict.MET);
        assertThat(verdicts(grade("планы [[stub:assess-partial]]", 1).getFirst())).containsExactly(Verdict.MET, Verdict.NOT_MET, Verdict.NOT_MET);
        assertThat(verdicts(grade("планы [[stub:assess-contradicted]]", 1).getFirst())).containsExactly(Verdict.CONTRADICTED, Verdict.MET, Verdict.MET);
        Run off = grade("планы [[stub:assess-offtopic]]", 1).getFirst();
        assertThat(verdicts(off)).containsOnly(Verdict.NOT_MET);
        assertThat(off.flags()).containsExactly(Flag.OFF_TOPIC);
        assertThat(verdicts(grade("планы [[stub:assess-unclear]]", 1).getFirst())).containsOnly(Verdict.UNCLEAR);
        Run asr = grade("планы [[stub:assess-asr]]", 1).getFirst();
        assertThat(asr.flags()).containsExactly(Flag.ASR_GARBLED);
        assertThat(verdicts(asr)).containsOnly(Verdict.MET);
        // only the answer counts: a marker in the exercise or the rubric would not
        assertThat(grade("планы выполнения запроса", 1).getFirst().flags()).doesNotContain(Flag.ASR_GARBLED);
    }

    @Test
    void aQuoteFromAnAnswerThatIsOnlyAMarkerIsTheMarkerItself() {
        Run run = grade("[[stub:assess-complete]]", 1).getFirst();
        assertThat(run.criteria().getFirst().verdict()).isEqualTo(Verdict.MET);
        assertThat(run.criteria().getFirst().quote()).isEqualTo("[[stub:assess-complete]]");
    }

    @Test
    void theTwoRunsOfAPairCanDisagree() {
        List<Run> pair = grade("что-то [[stub:assess-disagree]]", 2);
        assertThat(pair).hasSize(2);
        assertThat(pair).filteredOn(run -> run.flags().contains(Flag.OFF_TOPIC)).hasSize(1);
        assertThat(pair.stream().flatMap(run -> verdicts(run).stream()).toList()).contains(Verdict.MET, Verdict.NOT_MET);
        // a single run sees the first answer only
        assertThat(grade("что-то [[stub:assess-disagree]]", 1).getFirst().flags()).isEmpty();
    }

    @Test
    void anInjectionMarkerAddsTheFlagToTheHeuristicGrade() {
        Run run = grade("перебирает планы выполнения запроса [[stub:assess-injection]]", 1).getFirst();
        assertThat(run.flags()).containsExactly(Flag.INJECTION);
        assertThat(run.criteria().getFirst().verdict()).isEqualTo(Verdict.MET);
    }

    @Test
    void anInvalidMarkerNeverFitsSoGradingIsUnavailable() {
        GradeOutcome outcome = grader.grade(new GradeRequest(UUID.randomUUID(), UUID.randomUUID(), "q", "", RUBRIC,
                "ответ [[stub:assess-invalid]]", AnswerSource.TYPED, "ru", 1, Duration.ofSeconds(5)));
        assertThat(outcome).isEqualTo(new GradeOutcome.Unavailable("INVALID_OUTPUT"));
    }

    @Test
    void theLexicalHeuristicReadsContentWordsOfAPointAndAcceptableTerms() {
        // two content words of the description: met; one: partly; none: not met
        Run run = grade("Оптимизатор перебирает планы выполнения запроса", 1).getFirst();
        assertThat(verdicts(run)).containsExactly(Verdict.MET, Verdict.PARTLY, Verdict.NOT_MET);
        assertThat(run.criteria().getFirst().quote()).as("a verbatim stretch of the answer").isEqualTo("перебирает планы выполнения запроса");
        assertThat(run.flags()).isEmpty();
        // inflections match on the first five letters
        assertThat(verdicts(grade("Статистики влияют", 1).getFirst())).containsExactly(Verdict.NOT_MET, Verdict.NOT_MET, Verdict.PARTLY);
        assertThat(verdicts(grade("Статистики таблицы влияют", 1).getFirst())).containsExactly(Verdict.NOT_MET, Verdict.NOT_MET, Verdict.MET);
        // an acceptable term alone meets a point
        assertThat(verdicts(grade("planner", 1).getFirst())).containsOnly(Verdict.MET);
        // the pancake recipe touches nothing: off topic
        Run pancakes = grade("Смешайте муку, молоко и яйца, жарьте блины", 1).getFirst();
        assertThat(verdicts(pancakes)).containsOnly(Verdict.NOT_MET);
        assertThat(pancakes.flags()).containsExactly(Flag.OFF_TOPIC);
    }

    @Test
    void theSlowMarkerWaitsForTheBudgetAndThenTimesOutOrAnswers() {
        TextRequest request = new TextRequest(AiRoute.ASSESS, List.of(TextRequest.Segment.user("<grader>\n<criteria>\nc1 · планы\n</criteria>\n"
                + "<learner_answer>\"планы [[stub:assess-slow]]\"</learner_answer>", false)), OutputContract.JSON, 100, 0.2,
                Duration.ofSeconds(5), AiTestSupport.KEY, null, null, 1);
        long started = System.nanoTime();
        AiResult<TextResponse> result = stub.attempt("stub", request, Duration.ofMillis(200));
        assertThat(result).isInstanceOf(AiResult.Failed.class);
        assertThat(((AiResult.Failed<TextResponse>) result).failure()).isInstanceOf(AiFailure.Timeout.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(190)).isLessThan(Duration.ofSeconds(4));
        assertThat(StubAssessments.SLOW).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void theDeadlineMarkerIsAnswerScopedAndStillBoundedByTheCallBudget() {
        String prompt = "<grader>\n<criteria>\nc1 · планы\n</criteria>\n"
                + "<learner_answer>\"планы [[stub:assess-deadline]]\"</learner_answer>";
        assertThat(StubAssessments.delay(prompt)).isEqualTo(Duration.ofSeconds(25));
        assertThat(StubAssessments.delay(prompt.replace("планы [[stub:assess-deadline]]", "обычный")
                + " [[stub:assess-deadline]]")).isZero();
        TextRequest request = new TextRequest(AiRoute.ASSESS, List.of(TextRequest.Segment.user(prompt, false)),
                OutputContract.JSON, 100, 0.2, Duration.ofSeconds(20), AiTestSupport.KEY, null, null, 1);
        AiResult<TextResponse> result = stub.attempt("stub", request, Duration.ofMillis(1));
        assertThat(result).isInstanceOf(AiResult.Failed.class);
        assertThat(((AiResult.Failed<TextResponse>) result).failure()).isInstanceOf(AiFailure.Timeout.class);
        assertThat(StubAssessments.answer(prompt, 1)).contains("\"verdict\":\"MET\"");
    }

    @Test
    void anInterruptedWaitIsATransientFailure() throws Exception {
        TextRequest request = new TextRequest(AiRoute.ASSESS, List.of(TextRequest.Segment.user("<grader>\n<criteria>\nc1 · планы\n</criteria>\n"
                + "<learner_answer>\"планы [[stub:assess-slow]]\"</learner_answer>", false)), OutputContract.JSON, 100, 0.2,
                Duration.ofSeconds(5), AiTestSupport.KEY, null, null, 1);
        AiResult<?>[] result = new AiResult<?>[1];
        Thread thread = new Thread(() -> result[0] = stub.attempt("stub", request, Duration.ofSeconds(20)));
        thread.start();
        Thread.sleep(150);
        thread.interrupt();
        thread.join(5_000);
        assertThat(result[0]).isInstanceOf(AiResult.Failed.class);
        assertThat(((AiResult.Failed<?>) result[0]).failure()).isInstanceOf(AiFailure.Transient.class);
    }

    @Test
    void aRequestThatIsNotAGradingRequestIsNotAnswered() {
        assertThat(StubAssessments.isAssessmentRequest("<task kind=\"exercises\">")).isFalse();
        assertThat(StubAssessments.isAssessmentRequest("<grader> без ответа")).isFalse();
        assertThat(StubAssessments.delay("<grader><learner_answer>\"обычный\"</learner_answer>")).isZero();
        assertThat(StubAssessments.answer("<grader>", 1)).contains("\"criteria\":[]");
    }
}
