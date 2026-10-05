package app.mnema.learning.study.attempt;

import app.mnema.learning.capability.SemanticAssessmentProvider;
import app.mnema.learning.capability.SemanticAssessmentProvider.GradeOutcome;
import app.mnema.learning.capability.SemanticAssessmentProvider.GradeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import app.mnema.learning.platform.wake.WakeTarget;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Grades accepted answers: after the submit transaction commits, each answer runs on its own virtual thread, bounded per
 * instance by {@code learning.ai.assess.concurrency}. The grader is called with <em>no transaction and no connection held</em>
 * (the provider layer refuses to run inside one); the result is stored by {@link AssessmentService#complete} in a short
 * transaction of its own. A lost wake-up, a crash or a full instance costs nothing but time: the row stays ASSESSING until the
 * deadline sweeper makes it UNAVAILABLE and the learner rates themselves.
 *
 * <p>Roles ({@code learning.runtime.roles}): the runner is the grader, so it exists only for {@code worker} and {@code all}; an {@code api} process
 * accepts the answer and holds no provider key. Grading is interactive (20 s), so the hand-over to a worker is as quick as the wake-up of the other
 * kinds of work: the insert of an ASSESSING row notifies {@code mnema_assessments} (trigger of {@code V40}) and the worker's {@link #wake} sweeps
 * the answers nobody has taken; {@link #sweep} does the same every {@code learning.ai.assess.sweep-interval}. The grading call is preceded by a claim
 * ({@link AssessmentService#prepare}), so the accepting process and a worker never grade one answer twice.
 */
@Component
@ConditionalOnExpression("'${learning.runtime.roles:all}'.trim().toLowerCase() == 'worker' or "
        + "'${learning.runtime.roles:all}'.trim().toLowerCase() == 'all'")
class AssessmentRunner implements DisposableBean, WakeTarget {
    private static final Logger LOG = LoggerFactory.getLogger(AssessmentRunner.class);

    private final AssessmentService service;
    private final SemanticAssessmentProvider provider;
    private final AssessmentSettings settings;
    private final Semaphore slots;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

    AssessmentRunner(AssessmentService service, SemanticAssessmentProvider provider, AssessmentSettings settings) {
        this.service = service;
        this.provider = provider;
        this.settings = settings;
        this.slots = new Semaphore(settings.concurrency());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void accepted(AssessmentAccepted event) {
        try {
            threads.execute(() -> run(event.attemptId()));
        } catch (RejectedExecutionException closing) {
            // the context is shutting down; the sweeper ends the answer
        }
    }

    @Override
    public String channel() { return "mnema_assessments"; }

    @Override
    public void wake() {
        try {
            threads.execute(this::sweep);
        } catch (RejectedExecutionException closing) {
            // the context is shutting down; the deadline sweeper ends the answers
        }
    }

    /** Grades the answers nobody has taken, as many as there are free slots: a worker process takes what an api process accepted. */
    @Scheduled(initialDelayString = "${learning.ai.assess.sweep-interval:PT2S}", fixedDelayString = "${learning.ai.assess.sweep-interval:PT2S}")
    void sweep() {
        try {
            int free = slots.availablePermits();
            if (free < 1) return;
            for (UUID attemptId : service.awaitingGrader(free)) {
                threads.execute(() -> run(attemptId));
            }
        } catch (RejectedExecutionException closing) {
            // shutting down
        } catch (RuntimeException failure) {
            LOG.warn("assessment_grader_sweep_failed error_type={}", failure.getClass().getSimpleName());
        }
    }

    /** Runs one grading to the end; package-private so a test can run it on its own thread. */
    void run(UUID attemptId) {
        boolean acquired = false;
        try {
            acquired = slots.tryAcquire(settings.deadline().toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) return;
            Optional<GradeRequest> request = service.prepare(attemptId);
            if (request.isEmpty()) return;
            GradeOutcome outcome = provider.grade(request.orElseThrow());
            service.complete(attemptId, outcome);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            // the answer stays ASSESSING and the sweeper ends it at the deadline; never the learner's error
            LOG.warn("assessment_run_failed attempt_id={} error_type={}", attemptId, failure.getClass().getSimpleName());
        } finally {
            if (acquired) slots.release();
        }
    }

    @Override
    public void destroy() {
        threads.shutdownNow();
    }
}
