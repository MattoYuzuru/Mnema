package app.mnema.learning.study.attempt;

import app.mnema.learning.capability.LearningCapabilities;
import app.mnema.learning.capability.SemanticAssessmentProvider.CriterionGrade;
import app.mnema.learning.capability.SemanticAssessmentProvider.GradeOutcome;
import app.mnema.learning.capability.SemanticAssessmentProvider.Run;
import app.mnema.learning.capability.SemanticAssessmentProvider.Verdict;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.usage.Bucket;
import app.mnema.learning.usage.UsageLedger;
import app.mnema.learning.usage.UsageLimitReachedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The branches of {@link AssessmentService} that need no database: acceptance, strictness input, preparation, discarded and refused results. */
class AssessmentServiceTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");
    private final UUID actor = UUID.randomUUID();
    private final UUID deck = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();
    private final UUID exercise = UUID.randomUUID();
    private final UUID objective = UUID.randomUUID();
    private final UUID attemptId = UUID.randomUUID();
    private final AssessmentRepository assessments = mock(AssessmentRepository.class);
    private final AttemptRepository attempts = mock(AttemptRepository.class);
    private final AttemptConclusion conclusion = mock(AttemptConclusion.class);
    private final LearningCapabilities capabilities = mock(LearningCapabilities.class);
    private final UsageLedger ledger = mock(UsageLedger.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    private final ObjectNode rubric = rubric();
    private AssessmentService service;

    @BeforeEach
    void setUp() {
        service = new AssessmentService(assessments, attempts, conclusion, capabilities, ledger,
                new AssessmentSettings(Duration.ofSeconds(20), Duration.ofSeconds(2), 16, "ru", 3), events,
                mock(PlatformTransactionManager.class), meters);
        when(attempts.now()).thenReturn(NOW);
        when(capabilities.aiAssessment()).thenReturn(new LearningCapabilities.Status(true, null));
        when(ledger.fairUseFits(any(), eq(Bucket.ASSESSMENT), anyLong())).thenReturn(true);
        when(assessments.evaluatorPolicy(any(), any(), any())).thenReturn(Optional.of(JSON.createObjectNode().put("id", "ai-semantic")
                .put("version", "1").<ObjectNode>set("rubric", rubric)));
    }

    private static ObjectNode rubric() {
        ObjectNode rubric = JSON.createObjectNode().put("referenceAnswer", "Эталон");
        ArrayNode criteria = rubric.putArray("criteria");
        String[] tiers = {"CORE", "CORE", "DETAIL"};
        for (int index = 0; index < tiers.length; index++) {
            criteria.addObject().put("criterionId", UUID.randomUUID().toString()).put("description", "Пункт " + (index + 1))
                    .put("tier", tiers[index]).put("weight", 1);
        }
        rubric.putArray("misconceptions");
        rubric.putArray("acceptableTerms");
        return rubric;
    }

    private AttemptRepository.Presentation presentation(String mode, long epoch, ObjectNode content) {
        return new AttemptRepository.Presentation(actor, session, UUID.randomUUID(), deck, mode, "ACTIVE", "1234567890123456", exercise,
                UUID.randomUUID(), "FREE_RESPONSE", objective, UUID.randomUUID(), epoch, content, JSON.createObjectNode().<ObjectNode>set("reference",
                JSON.createArrayNode()), JSON.createObjectNode().put("id", "ai-semantic").put("version", "1"),
                JSON.createObjectNode().put("kind", "TEXT"), false, List.of(), UUID.randomUUID(), "mnema-baseline", "1", "hash",
                NOW.plusSeconds(3_600));
    }

    private static ObjectNode textContent(String text) {
        ObjectNode content = JSON.createObjectNode().put("responseInput", "TEXT");
        content.putArray("prompt").addObject().put("kind", "TEXT").put("text", text);
        return content;
    }

    private AttemptCommand command(AttemptRepository.Presentation presentation) {
        ObjectNode payload = JSON.createObjectNode().put("attemptId", attemptId.toString())
                .put("presentationId", presentation.presentationId().toString()).put("nonce", presentation.nonce());
        payload.putObject("response").put("kind", "TEXT").put("text", "ответ");
        payload.putNull("confidence").put("durationMs", 1_000);
        return new AttemptCommand(attemptId, presentation.presentationId(), presentation.nonce(), new AttemptCommand.TextResponse("ответ"),
                null, 1_000, payload);
    }

    private AttemptRepository.State state(long epoch, int level, int streak, long sequence) {
        return new AttemptRepository.State(actor, deck, objective, epoch, level, streak, 0, sequence, 1);
    }

    private AssessmentRepository.Row insertedRow() {
        ArgumentCaptor<AssessmentRepository.Row> captor = ArgumentCaptor.forClass(AssessmentRepository.Row.class);
        verify(assessments).insert(captor.capture(), any());
        return captor.getValue();
    }

    // -------------------------------------------------------------------------------------------- begin

    @ParameterizedTest
    @CsvSource({
            // level, streak, sequence (assessed attempts), earlier attempt at the exercise, expected strictness
            "0, 0, 0, false, S1",
            "1, 1, 1, true,  S1",
            "2, 0, 2, true,  S2",
            "2, 2, 2, true,  S3",
            "4, 0, 3, true,  S3",
            "4, 0, 3, false, S2",
            "5, 5, 6, false, S2"})
    void theAnswerIsRecordedWithTheStrictnessOfTheObjectivesState(int level, int streak, long sequence, boolean attempted, String expected) {
        AttemptRepository.Presentation presentation = presentation("SCHEDULED", 0, textContent("Вопрос"));
        when(assessments.state(actor, deck, objective)).thenReturn(Optional.of(state(0, level, streak, sequence)));
        when(assessments.attemptedExercise(actor, deck, exercise, 0)).thenReturn(attempted);

        Optional<AttemptService.SubmitResult> result = service.begin(actor, deck, session, presentation, command(presentation),
                new byte[32], new AttemptCommand.TextResponse("ответ"), NOW);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().accepted()).isTrue();
        assertThat(result.orElseThrow().outcome().path("status").stringValue(null)).isEqualTo("ASSESSING");
        AssessmentRepository.Row row = insertedRow();
        assertThat(row.strictness()).isEqualTo(expected);
        assertThat(row.state()).isEqualTo("ASSESSING");
        assertThat(row.reason()).isNull();
        assertThat(row.deadlineAt()).isEqualTo(NOW.plusSeconds(20));
        assertThat(row.expiresAt()).isEqualTo(presentation.expiresAt());
        assertThat(row.answerSource()).isEqualTo("TYPED");
        assertThat(row.response().path("text").stringValue(null)).isEqualTo("ответ");
        verify(events).publishEvent(new AssessmentAccepted(attemptId));
        assertThat(meters.counter("mnema_assessment_total", "outcome", "ACCEPTED", "reason", "NONE").count()).isEqualTo(1);
    }

    @Test
    void aLearnerWithoutStateInPracticeIsAtTheLenientLevelAndOneOfAnOtherEpochToo() {
        AttemptRepository.Presentation practice = presentation("PRACTICE", 0, textContent("Вопрос"));
        when(assessments.state(actor, deck, objective)).thenReturn(Optional.empty());
        service.begin(actor, deck, session, practice, command(practice), new byte[32], new AttemptCommand.TextResponse("ответ"), NOW);
        assertThat(insertedRow().strictness()).isEqualTo("S1");
        // a practice presentation of an old epoch: the new epoch's level is not its level
        org.mockito.Mockito.clearInvocations(assessments);
        when(assessments.state(actor, deck, objective)).thenReturn(Optional.of(state(3, 6, 6, 9)));
        service.begin(actor, deck, session, practice, command(practice), new byte[32], new AttemptCommand.TextResponse("ответ"), NOW);
        assertThat(insertedRow().strictness()).isEqualTo("S1");
    }

    @Test
    void aStaleEpochOrMissingStateOfAScheduledPresentationIsNotRecordedNotGradedAndNotCharged() {
        AttemptRepository.Presentation presentation = presentation("SCHEDULED", 0, textContent("Вопрос"));
        when(assessments.state(actor, deck, objective)).thenReturn(Optional.of(state(1, 0, 0, 0)));
        assertThat(service.begin(actor, deck, session, presentation, command(presentation), new byte[32],
                new AttemptCommand.TextResponse("ответ"), NOW)).isEmpty();
        when(assessments.state(actor, deck, objective)).thenReturn(Optional.empty());
        assertThat(service.begin(actor, deck, session, presentation, command(presentation), new byte[32],
                new AttemptCommand.TextResponse("ответ"), NOW)).isEmpty();
        verify(assessments, never()).insert(any(), any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void aCapabilityThatIsGoneOrASpentAllowanceGoesStraightToSelfCheckWithoutAStartedGrading() {
        AttemptRepository.Presentation presentation = presentation("SCHEDULED", 0, textContent("Вопрос"));
        when(assessments.state(actor, deck, objective)).thenReturn(Optional.of(state(0, 0, 0, 0)));
        when(capabilities.aiAssessment()).thenReturn(new LearningCapabilities.Status(false, LearningCapabilities.Reason.DISABLED));
        AttemptService.SubmitResult off = service.begin(actor, deck, session, presentation, command(presentation), new byte[32],
                new AttemptCommand.TextResponse("ответ"), NOW).orElseThrow();
        assertThat(off.outcome().path("status").stringValue(null)).isEqualTo("SELF_CHECK");
        assertThat(off.outcome().path("reason").stringValue(null)).isEqualTo("CAPABILITY_UNAVAILABLE");
        assertThat(off.outcome().path("selfCheck").path("criteria")).hasSize(3);
        assertThat(insertedRow().reason()).isEqualTo("CAPABILITY_UNAVAILABLE");
        org.mockito.Mockito.clearInvocations(assessments);

        when(capabilities.aiAssessment()).thenReturn(new LearningCapabilities.Status(true, null));
        when(ledger.fairUseFits(actor, Bucket.ASSESSMENT, 1)).thenReturn(false);
        AttemptService.SubmitResult spent = service.begin(actor, deck, session, presentation, command(presentation), new byte[32],
                new AttemptCommand.TextResponse("ответ"), NOW).orElseThrow();
        assertThat(spent.outcome().path("reason").stringValue(null)).isEqualTo("USAGE_LIMIT");
        AssessmentRepository.Row row = insertedRow();
        assertThat(row.state()).isEqualTo("UNAVAILABLE");
        assertThat(row.reason()).isEqualTo("USAGE_LIMIT");
        verify(events, never()).publishEvent(any());
    }

    @Test
    void anExactRetryShowsTheCurrentStateAndAChangedOneConflicts() {
        AttemptRepository.Presentation presentation = presentation("SCHEDULED", 0, textContent("Вопрос"));
        byte[] hash = new byte[32];
        hash[0] = 7;
        when(attempts.presentation(actor, deck, session, presentation.presentationId())).thenReturn(Optional.of(presentation));
        AssessmentRepository.Row row = new AssessmentRepository.Row(attemptId, actor, session, presentation.presentationId(), deck,
                "ASSESSING", null, "S1", hash, "TYPED", JSON.createObjectNode(), null, 1_000, NOW, NOW.plusSeconds(20), presentation.expiresAt());
        when(assessments.find(attemptId)).thenReturn(Optional.of(row));
        AttemptCommand command = command(presentation);

        AttemptService.SubmitResult replay = service.replay(command, actor, deck, session, hash).orElseThrow();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.accepted()).isTrue();
        assertThat(replay.outcome().path("retryAfterMs").intValue()).isEqualTo(700);
        // a presentation answered for a while is polled slower
        when(attempts.now()).thenReturn(NOW.plusSeconds(4));
        assertThat(service.replay(command, actor, deck, session, hash).orElseThrow().outcome().path("retryAfterMs").intValue()).isEqualTo(1_500);

        byte[] other = new byte[32];
        assertThatThrownBy(() -> service.replay(command, actor, deck, session, other)).isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> service.replay(command, UUID.randomUUID(), deck, session, hash)).isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> service.replay(command, actor, UUID.randomUUID(), session, hash)).isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> service.replay(command, actor, deck, UUID.randomUUID(), hash)).isInstanceOf(IdempotencyConflictException.class);
        when(assessments.find(attemptId)).thenReturn(Optional.empty());
        assertThat(service.replay(command, actor, deck, session, hash)).isEmpty();
    }

    // ------------------------------------------------------------------------------------------ prepare

    private AssessmentRepository.Row row(String state, JsonNode response, Instant deadline, String source, String strictness) {
        return new AssessmentRepository.Row(attemptId, actor, session, UUID.randomUUID(), deck, state, null, strictness, new byte[32], source,
                response, null, 1_000, NOW.minusSeconds(1), deadline, NOW.plusSeconds(3_600));
    }

    @Test
    void aGradingRequestIsBuiltOnlyForAnAnswerThatIsStillWaitingAndHasTimeLeft() {
        ObjectNode response = JSON.createObjectNode().put("kind", "TEXT").put("text", "ответ");
        AttemptRepository.Presentation presentation = presentation("SCHEDULED", 0, textContent("Вопрос один"));
        when(attempts.presentation(any(), any(), any(), any())).thenReturn(Optional.of(presentation));

        when(assessments.find(attemptId)).thenReturn(Optional.empty());
        assertThat(service.prepare(attemptId)).isEmpty();
        for (String state : new String[] {"DONE", "SELF_CHECK", "UNAVAILABLE"}) {
            when(assessments.find(attemptId)).thenReturn(Optional.of(row(state, response, NOW.plusSeconds(10), "TYPED", "S1")));
            assertThat(service.prepare(attemptId)).as(state).isEmpty();
        }
        when(assessments.find(attemptId)).thenReturn(Optional.of(row("ASSESSING", null, NOW.plusSeconds(10), "TYPED", "S1")));
        assertThat(service.prepare(attemptId)).as("the answer is gone").isEmpty();
        when(assessments.find(attemptId)).thenReturn(Optional.of(row("ASSESSING", response, NOW.plusMillis(100), "TYPED", "S1")));
        assertThat(service.prepare(attemptId)).as("less than 250 ms left: the sweeper ends it").isEmpty();

        when(assessments.find(attemptId)).thenReturn(Optional.of(row("ASSESSING", response, NOW.plusSeconds(10), "SPEECH", "S3")));
        assertThat(service.prepare(attemptId)).as("another grader took it").isEmpty();
        when(assessments.claim(attemptId)).thenReturn(true);
        var request = service.prepare(attemptId).orElseThrow();
        assertThat(request.exercisePrompt()).isEqualTo("Вопрос один");
        assertThat(request.answer()).isEqualTo("ответ");
        assertThat(request.runs()).isEqualTo(2);
        assertThat(request.source().name()).isEqualTo("SPEECH");
        assertThat(request.feedbackLanguage()).isEqualTo("ru");
        assertThat(request.deadline()).isEqualTo(Duration.ofSeconds(10));
        assertThat(request.rubric().criteria()).hasSize(3);
        assertThat(request.materialFragment()).isEmpty();

        when(assessments.find(attemptId)).thenReturn(Optional.of(row("ASSESSING", response, NOW.plusSeconds(10), "TYPED", "S1")));
        assertThat(service.prepare(attemptId).orElseThrow().runs()).isEqualTo(1);
        // a prompt made of media only is described, not left blank
        when(attempts.presentation(any(), any(), any(), any())).thenReturn(Optional.of(presentation("SCHEDULED", 0,
                JSON.createObjectNode().<ObjectNode>set("prompt", JSON.createArrayNode().add(JSON.createObjectNode().put("kind", "IMAGE"))))));
        assertThat(service.prepare(attemptId).orElseThrow().exercisePrompt()).contains("изображением");
        // the presentation is gone
        when(attempts.presentation(any(), any(), any(), any())).thenReturn(Optional.empty());
        assertThat(service.prepare(attemptId)).isEmpty();
    }

    @Test
    void aPathologicallyLongQuestionIsCutSoTheGradingPromptAlwaysFits() {
        when(assessments.claim(attemptId)).thenReturn(true);
        ObjectNode response = JSON.createObjectNode().put("kind", "TEXT").put("text", "ответ");
        ObjectNode content = JSON.createObjectNode();
        content.putArray("prompt").addObject().put("kind", "TEXT").put("text", "ы".repeat(20_000));
        when(attempts.presentation(any(), any(), any(), any())).thenReturn(Optional.of(presentation("SCHEDULED", 0, content)));
        when(assessments.find(attemptId)).thenReturn(Optional.of(row("ASSESSING", response, NOW.plusSeconds(10), "TYPED", "S1")));
        assertThat(service.prepare(attemptId).orElseThrow().exercisePrompt()).hasSize(AssessmentService.MAX_PROMPT_CHARS);
    }

    // ----------------------------------------------------------------------------------------- complete

    @Test
    void aFailedGradeEndsTheAnswerUnavailableWithTheReasonAndAnAnswerThatLeftAssessingIsLeftAlone() {
        ObjectNode response = JSON.createObjectNode().put("kind", "TEXT").put("text", "ответ");
        AttemptRepository.Presentation presentation = presentation("SCHEDULED", 0, textContent("Вопрос"));
        when(assessments.find(attemptId)).thenReturn(Optional.of(row("ASSESSING", response, NOW.plusSeconds(10), "TYPED", "S1")));
        when(assessments.forUpdate(attemptId)).thenReturn(Optional.of(row("ASSESSING", response, NOW.plusSeconds(10), "TYPED", "S1")));
        when(attempts.presentationForUpdate(any(), any(), any(), any())).thenReturn(Optional.of(presentation));

        service.complete(attemptId, new GradeOutcome.Unavailable("TIMEOUT"));
        verify(assessments).transition(attemptId, "ASSESSING", "UNAVAILABLE", "TIMEOUT", NOW);
        verify(conclusion, never()).conclude(any(), any(), any(), any(), any(), any(), any(), any(), any());

        // it was ended meanwhile (the learner chose self-check, the sweeper passed): the late result changes nothing
        org.mockito.Mockito.clearInvocations(assessments);
        when(assessments.find(attemptId)).thenReturn(Optional.of(row("SELF_CHECK", response, NOW.plusSeconds(10), "TYPED", "S1")));
        service.complete(attemptId, new GradeOutcome.Unavailable("TIMEOUT"));
        when(assessments.find(attemptId)).thenReturn(Optional.empty());
        service.complete(attemptId, new GradeOutcome.Unavailable("TIMEOUT"));
        // it moved between the unlocked read and the locked one
        when(assessments.find(attemptId)).thenReturn(Optional.of(row("ASSESSING", response, NOW.plusSeconds(10), "TYPED", "S1")));
        when(assessments.forUpdate(attemptId)).thenReturn(Optional.of(row("DONE", null, NOW.plusSeconds(10), "TYPED", "S1")));
        service.complete(attemptId, new GradeOutcome.Unavailable("TIMEOUT"));
        verify(assessments, never()).transition(any(), anyString(), anyString(), anyString(), any());
        verify(assessments, never()).finish(any(), any());
    }

    @Test
    void anAnswerThatIsNoLongerStoredEndsUnavailableAndUncertaintyEndsInSelfCheck() {
        AttemptRepository.Presentation presentation = presentation("SCHEDULED", 0, textContent("Вопрос"));
        when(attempts.presentationForUpdate(any(), any(), any(), any())).thenReturn(Optional.of(presentation));
        AssessmentRepository.Row gone = row("ASSESSING", null, NOW.plusSeconds(10), "TYPED", "S1");
        when(assessments.find(attemptId)).thenReturn(Optional.of(gone));
        when(assessments.forUpdate(attemptId)).thenReturn(Optional.of(gone));
        service.complete(attemptId, new GradeOutcome.Graded(List.of(allMet(Verdict.MET))));
        verify(assessments).transition(attemptId, "ASSESSING", "UNAVAILABLE", "ANSWER_GONE", NOW);
        assertThat(meters.counter("mnema_assessment_total", "outcome", "UNAVAILABLE", "reason", "ANSWER_GONE").count()).isEqualTo(1);

        ObjectNode response = JSON.createObjectNode().put("kind", "TEXT").put("text", "ответ");
        AssessmentRepository.Row waiting = row("ASSESSING", response, NOW.plusSeconds(10), "TYPED", "S1");
        when(assessments.find(attemptId)).thenReturn(Optional.of(waiting));
        when(assessments.forUpdate(attemptId)).thenReturn(Optional.of(waiting));
        service.complete(attemptId, new GradeOutcome.Graded(List.of(allMet(Verdict.UNCLEAR))));
        verify(assessments).transition(attemptId, "ASSESSING", "SELF_CHECK", "PROVIDER_UNCERTAIN", NOW);
        verify(ledger, never()).consume(any(), any(), anyLong(), anyString(), any());
    }

    @Test
    void aGradeThatTheAllowanceRefusesRollsBackAndEndsUnavailableWithUsageLimit() {
        ObjectNode response = JSON.createObjectNode().put("kind", "TEXT").put("text", "ответ");
        AttemptRepository.Presentation presentation = presentation("SCHEDULED", 0, textContent("Вопрос"));
        AssessmentRepository.Row waiting = row("ASSESSING", response, NOW.plusSeconds(10), "TYPED", "S1");
        when(assessments.find(attemptId)).thenReturn(Optional.of(waiting));
        when(assessments.forUpdate(attemptId)).thenReturn(Optional.of(waiting));
        when(attempts.presentationForUpdate(any(), any(), any(), any())).thenReturn(Optional.of(presentation));
        when(ledger.consume(any(), any(), anyLong(), anyString(), any())).thenThrow(new UsageLimitReachedException(
                new UsageLimitReachedException.Block(Bucket.ASSESSMENT, app.mnema.learning.usage.Window.DAY, app.mnema.learning.usage.Unit.COUNT,
                        5L, 5, 1, true, NOW, false, app.mnema.learning.usage.Plan.FREE)));

        service.complete(attemptId, new GradeOutcome.Graded(List.of(allMet(Verdict.MET))));

        verify(ledger).consume(actor, Bucket.ASSESSMENT, 1, "assessment:" + attemptId, attemptId.toString());
        verify(conclusion, never()).conclude(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(assessments).transition(attemptId, "ASSESSING", "UNAVAILABLE", "USAGE_LIMIT", NOW);
    }

    private Run allMet(Verdict verdict) {
        List<JsonNode> criteria = new java.util.ArrayList<>();
        rubric.path("criteria").forEach(criteria::add);
        return new Run(criteria.stream().map(criterion -> new CriterionGrade(UUID.fromString(criterion.path("criterionId").stringValue(null)),
                verdict, verdict == Verdict.MET ? "ответ" : null, "")).toList(), Set.of());
    }

    @Test
    void aFinishedAssessmentIsAlwaysReadAsItsOutcomeNeverAsASelfCheckViewEvenWhenTheGradeLandsBetweenTheTwoReads() {
        ObjectNode outcome = JSON.createObjectNode().put("attemptId", attemptId.toString()).put("status", "ASSESSED");
        AttemptRepository.Presentation presentation = presentation("SCHEDULED", 0, textContent("Вопрос"));
        AttemptRepository.Receipt receipt = new AttemptRepository.Receipt(attemptId, actor, session, presentation.presentationId(), deck,
                new byte[32], "SCHEDULED", "ASSESSED", outcome, null);
        when(attempts.ownsDeck(actor, deck)).thenReturn(true);
        when(attempts.presentation(any(), any(), any(), any())).thenReturn(Optional.of(presentation));
        AssessmentRepository.Row done = new AssessmentRepository.Row(attemptId, actor, session, presentation.presentationId(), deck, "DONE",
                null, "S1", new byte[32], "TYPED", null, null, 1_000, NOW, NOW.plusSeconds(20), presentation.expiresAt());
        when(assessments.find(attemptId)).thenReturn(Optional.of(done));
        // the poll looked for the receipt before the grade was stored (none), found the row after it (DONE): the receipt is read again
        when(attempts.receipt(attemptId)).thenReturn(Optional.empty()).thenReturn(Optional.of(receipt));
        assertThat(service.read(actor, deck, session, attemptId)).isEqualTo(outcome);

        // the same race for an exact retry of the submit: not «accepted», the stored outcome
        when(attempts.receipt(attemptId)).thenReturn(Optional.of(receipt));
        byte[] hash = new byte[32];
        AttemptService.SubmitResult retry = service.replay(command(presentation), actor, deck, session, hash).orElseThrow();
        assertThat(retry.outcome()).isEqualTo(outcome);
        assertThat(retry.accepted()).isFalse();
        assertThat(retry.replayed()).isTrue();
    }

    @Test
    void anAccountWithTooManyAnswersInFlightIsBusyAndGoesStraightToSelfCheck() {
        AttemptRepository.Presentation presentation = presentation("SCHEDULED", 0, textContent("Вопрос"));
        when(assessments.state(actor, deck, objective)).thenReturn(Optional.of(state(0, 0, 0, 0)));
        when(assessments.inFlight(actor)).thenReturn(2);
        service.begin(actor, deck, session, presentation, command(presentation), new byte[32], new AttemptCommand.TextResponse("ответ"), NOW);
        verify(events).publishEvent(new AssessmentAccepted(attemptId));
        org.mockito.Mockito.clearInvocations(assessments, events);
        when(assessments.inFlight(actor)).thenReturn(3);
        AttemptService.SubmitResult busy = service.begin(actor, deck, session, presentation, command(presentation), new byte[32],
                new AttemptCommand.TextResponse("ответ"), NOW).orElseThrow();
        assertThat(busy.outcome().path("status").stringValue(null)).isEqualTo("SELF_CHECK");
        assertThat(busy.outcome().path("reason").stringValue(null)).isEqualTo("BUSY");
        assertThat(insertedRow().state()).isEqualTo("UNAVAILABLE");
        verify(events, never()).publishEvent(any());
    }

    @Test
    void aResultThatCannotBeStoredIsRetriedOnceAndThenEndsUnavailableAtOnceInsteadOfWaitingForTheSweeper() {
        when(assessments.find(attemptId)).thenThrow(new IllegalStateException("deadlock")).thenReturn(Optional.empty());
        service.complete(attemptId, new GradeOutcome.Unavailable("TIMEOUT"));
        verify(assessments, org.mockito.Mockito.times(2)).find(attemptId);
        verify(assessments, never()).transition(any(), anyString(), anyString(), anyString(), any());

        org.mockito.Mockito.clearInvocations(assessments);
        when(assessments.find(attemptId)).thenThrow(new IllegalStateException("deadlock"));
        when(assessments.transition(attemptId, "ASSESSING", "UNAVAILABLE", "PROVIDER_UNAVAILABLE", NOW)).thenReturn(true);
        service.complete(attemptId, new GradeOutcome.Unavailable("TIMEOUT"));
        verify(assessments, org.mockito.Mockito.times(2)).find(attemptId);
        verify(assessments).transition(attemptId, "ASSESSING", "UNAVAILABLE", "PROVIDER_UNAVAILABLE", NOW);
        assertThat(meters.counter("mnema_assessment_total", "outcome", "UNAVAILABLE", "reason", "PROVIDER_UNAVAILABLE").count()).isEqualTo(1);

        // even ending it can fail: the sweeper is the last resort, the caller never sees an error
        when(assessments.transition(any(), anyString(), anyString(), anyString(), any())).thenThrow(new IllegalStateException("down"));
        service.complete(attemptId, new GradeOutcome.Unavailable("TIMEOUT"));
    }

    // ------------------------------------------------------------------------------------------ sweepers

    @Test
    void theSweepersDelegateToTheRepository() {
        when(assessments.expireOverdue(anyInt())).thenReturn(3).thenReturn(0);
        assertThat(service.expireOverdue()).isEqualTo(3);
        assertThat(service.expireOverdue()).isZero();
        assertThat(meters.counter("mnema_assessment_total", "outcome", "UNAVAILABLE", "reason", "DEADLINE").count()).isEqualTo(3);
        when(assessments.clearAnswers(any(), anyInt())).thenReturn(4);
        assertThat(service.clearExpiredAnswers(500)).isEqualTo(4);
        verify(assessments).clearAnswers(NOW, 500);
        assertThat(AssessmentService.semantic(presentation("SCHEDULED", 0, textContent("q")))).isTrue();
    }
}
