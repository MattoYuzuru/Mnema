package app.mnema.learning.study.attempt;

import app.mnema.learning.capability.SemanticAssessmentProvider;
import app.mnema.learning.capability.SemanticAssessmentProvider.GradeOutcome;
import app.mnema.learning.capability.SemanticAssessmentProvider.GradeRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
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
 */
@Component
class AssessmentRunner implements DisposableBean {
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
