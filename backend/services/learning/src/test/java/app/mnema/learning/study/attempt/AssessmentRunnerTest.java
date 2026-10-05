package app.mnema.learning.study.attempt;

import app.mnema.learning.capability.SemanticAssessmentProvider;
import app.mnema.learning.capability.SemanticAssessmentProvider.AnswerSource;
import app.mnema.learning.capability.SemanticAssessmentProvider.GradeOutcome;
import app.mnema.learning.capability.SemanticAssessmentProvider.GradeRequest;
import app.mnema.learning.catalog.exercise.Rubric;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The runner: one grading per accepted answer, bounded, never throwing at the learner, always handing the outcome to the service. */
class AssessmentRunnerTest {
    private final AssessmentService service = mock(AssessmentService.class);
    private final SemanticAssessmentProvider provider = mock(SemanticAssessmentProvider.class);
    private final UUID attempt = UUID.randomUUID();

    private AssessmentRunner runner(int concurrency, Duration deadline) {
        return new AssessmentRunner(service, provider, new AssessmentSettings(deadline, Duration.ofMillis(500), concurrency, "ru", 3));
    }

    private GradeRequest request() {
        Rubric rubric = new Rubric("Эталон", List.of(new Rubric.Criterion(UUID.randomUUID(), "a", Rubric.Tier.CORE, 1),
                new Rubric.Criterion(UUID.randomUUID(), "b", Rubric.Tier.CORE, 1), new Rubric.Criterion(UUID.randomUUID(), "c", Rubric.Tier.DETAIL, 1)),
                List.of(), List.of());
        return new GradeRequest(UUID.randomUUID(), attempt, "q", "", rubric, "a", AnswerSource.TYPED, "ru", 1, Duration.ofSeconds(5));
    }

    @Test
    void aWaitingAnswerIsPreparedGradedAndTheOutcomeIsHandedToTheService() {
        GradeRequest request = request();
        GradeOutcome outcome = new GradeOutcome.Unavailable("TIMEOUT");
        when(service.prepare(attempt)).thenReturn(Optional.of(request));
        when(provider.grade(request)).thenReturn(outcome);
        runner(2, Duration.ofSeconds(2)).run(attempt);
        verify(service).complete(attempt, outcome);
    }

    @Test
    void anAnswerThatIsNotWaitingAnyMoreIsNotGraded() {
        when(service.prepare(attempt)).thenReturn(Optional.empty());
        runner(2, Duration.ofSeconds(2)).run(attempt);
        verify(provider, never()).grade(any());
        verify(service, never()).complete(any(), any());
    }

    @Test
    void aFailureInTheGraderOrTheServiceIsLoggedAndLeftToTheSweeper() {
        when(service.prepare(attempt)).thenReturn(Optional.of(request()));
        when(provider.grade(any())).thenThrow(new IllegalStateException("boom"));
        assertThatCode(() -> runner(2, Duration.ofSeconds(2)).run(attempt)).doesNotThrowAnyException();
        verify(service, never()).complete(any(), any());
        org.mockito.Mockito.doReturn(new GradeOutcome.Unavailable("TIMEOUT")).when(provider).grade(any());
        org.mockito.Mockito.doThrow(new IllegalStateException("db")).when(service).complete(any(), any());
        assertThatCode(() -> runner(2, Duration.ofSeconds(2)).run(attempt)).doesNotThrowAnyException();
    }

    @Test
    void anInstanceThatIsFullGradesNoFurtherAnswerAndTheSlotIsReleasedAfterwards() throws Exception {
        AssessmentRunner runner = runner(1, Duration.ofSeconds(1));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(service.prepare(any())).thenReturn(Optional.of(request()));
        when(provider.grade(any())).thenAnswer(invocation -> {
            inside.countDown();
            release.await(10, TimeUnit.SECONDS);
            return new GradeOutcome.Unavailable("TIMEOUT");
        });
        Thread first = Thread.ofVirtual().start(() -> runner.run(attempt));
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
        UUID second = UUID.randomUUID();
        runner.run(second);
        verify(service, never()).prepare(second);
        release.countDown();
        first.join(5_000);
        verify(service).complete(any(), any());
        // the slot is free again
        UUID third = UUID.randomUUID();
        runner.run(third);
        verify(service).prepare(third);
    }

    @Test
    void anInterruptedWaitStopsQuietlyAndTheCommitEventStartsAVirtualThread() throws Exception {
        AssessmentRunner full = runner(1, Duration.ofSeconds(30));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(service.prepare(any())).thenReturn(Optional.of(request()));
        when(provider.grade(any())).thenAnswer(invocation -> {
            inside.countDown();
            release.await(10, TimeUnit.SECONDS);
            return new GradeOutcome.Unavailable("TIMEOUT");
        });
        Thread holder = Thread.ofVirtual().start(() -> full.run(attempt));
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
        Thread waiting = Thread.ofPlatform().start(() -> full.run(UUID.randomUUID()));
        Thread.sleep(150);
        waiting.interrupt();
        waiting.join(5_000);
        assertThat(waiting.isAlive()).isFalse();
        release.countDown();
        holder.join(5_000);

        UUID announced = UUID.randomUUID();
        AssessmentRunner open = runner(2, Duration.ofSeconds(2));
        open.accepted(new AssessmentAccepted(announced));
        verify(service, timeout(5_000)).prepare(announced);
        open.destroy();
        // after the shutdown a late event is dropped, not thrown
        assertThatCode(() -> open.accepted(new AssessmentAccepted(UUID.randomUUID()))).doesNotThrowAnyException();
        assertThatThrownBy(() -> new AssessmentSettings(Duration.ofMillis(500), Duration.ofSeconds(2), 1, "ru", 3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSweepGradesWhatNobodyTookAsManyAsThereAreFreeSlotsAndAWakeAsksForIt() {
        UUID second = UUID.randomUUID();
        when(service.awaitingGrader(2)).thenReturn(List.of(attempt, second));
        when(service.prepare(any())).thenReturn(Optional.empty());
        AssessmentRunner worker = runner(2, Duration.ofSeconds(2));
        worker.sweep();
        verify(service, timeout(5_000)).prepare(attempt);
        verify(service, timeout(5_000)).prepare(second);

        assertThat(worker.channel()).isEqualTo("mnema_assessments");
        UUID woken = UUID.randomUUID();
        when(service.awaitingGrader(2)).thenReturn(List.of(woken));
        worker.wake();
        verify(service, timeout(5_000)).prepare(woken);

        // a failing look is logged, never thrown; after the shutdown a wake is dropped
        when(service.awaitingGrader(2)).thenThrow(new IllegalStateException("db"));
        assertThatCode(worker::sweep).doesNotThrowAnyException();
        worker.destroy();
        assertThatCode(worker::wake).doesNotThrowAnyException();
    }

    @Test
    void aFullInstanceDoesNotLookForMoreWork() throws Exception {
        AssessmentRunner full = runner(1, Duration.ofSeconds(30));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(service.prepare(any())).thenReturn(Optional.of(request()));
        when(provider.grade(any())).thenAnswer(invocation -> {
            inside.countDown();
            release.await(10, TimeUnit.SECONDS);
            return new GradeOutcome.Unavailable("TIMEOUT");
        });
        Thread holder = Thread.ofVirtual().start(() -> full.run(attempt));
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
        full.sweep();
        verify(service, never()).awaitingGrader(org.mockito.ArgumentMatchers.anyInt());
        release.countDown();
        holder.join(5_000);
    }
}
