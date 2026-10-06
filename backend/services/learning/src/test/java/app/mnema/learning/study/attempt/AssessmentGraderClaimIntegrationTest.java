package app.mnema.learning.study.attempt;

import app.mnema.learning.platform.wake.PostgresWakeListener;
import app.mnema.learning.platform.wake.WakeTarget;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hand-over of grading in a split topology: an ASSESSING answer is taken by exactly one grader (the claim), an answer nobody took is graded by the
 * sweep of a worker, and the insert of an answer notifies {@code mnema_assessments} (trigger of {@code V40}).
 */
class AssessmentGraderClaimIntegrationTest extends AssessmentIntegrationTest {
    @Value("${spring.datasource.url}") private String url;

    private Long claimedAt(UUID attempt) {
        return jdbc.sql("SELECT EXTRACT(EPOCH FROM claimed_at)::bigint FROM app_learning.study_assessment WHERE attempt_id=:id")
                .param("id", attempt).query(Long.class).optional().orElse(null);
    }

    @Test
    void theAcceptingGraderClaimsTheAnswerOnceAndANewGraderTakesOnlyWhatNobodyClaimed() throws Exception {
        Case learner = issued();
        UUID attempt = UUID.randomUUID();
        submit(learner, attempt, "Планы [[fake:block]] [[stub:assess-complete]]");
        assertThat(provider.entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(claimedAt(attempt)).as("the grader that accepted it took it").isNotNull();
        assertThat(assessments.awaitingGrader(10)).doesNotContain(attempt);
        Thread.sleep(700);
        assertThat(provider.callsOf(attempt)).as("the sweep (0.2 s) does not grade a claimed answer a second time").hasSize(1);

        // the process that accepted it never graded it (an api process without a key): nobody has taken it
        jdbc.sql("UPDATE app_learning.study_assessment SET claimed_at=NULL WHERE attempt_id=:id").param("id", attempt).update();
        assertThat(assessments.awaitingGrader(10)).contains(attempt);
        eventually(() -> provider.callsOf(attempt).size() == 2 ? Boolean.TRUE : null, "the worker's sweep took the answer");
        assertThat(claimedAt(attempt)).isNotNull();
        assertThat(assessments.awaitingGrader(10)).doesNotContain(attempt);
        provider.release.countDown();
        assertThat(settled(learner, attempt).path("status").stringValue(null)).isEqualTo("ASSESSED");
        assertThat(assessments.prepare(attempt)).as("an ended answer is not taken again").isEmpty();
    }

    @Test
    void acceptingAnAnswerNotifiesTheAssessmentChannelOfOtherProcesses() throws Exception {
        AtomicInteger wakes = new AtomicInteger();
        PostgresWakeListener listener = new PostgresWakeListener(List.of(new WakeTarget() {
            @Override public String channel() { return "mnema_assessments"; }

            @Override public void wake() { wakes.incrementAndGet(); }
        }), url, username(), password());
        listener.start();
        try {
            eventually(() -> listener.isListening() ? Boolean.TRUE : null, "the listener to connect");
            Thread.sleep(500);
            int before = wakes.get();
            Case learner = issued();
            UUID attempt = UUID.randomUUID();
            submit(learner, attempt, "Он перебирает планы [[stub:assess-complete]]");
            eventually(() -> wakes.get() > before ? Boolean.TRUE : null, "the notification of the answer");
            JsonNode settled = settled(learner, attempt);
            assertThat(settled.path("status").stringValue(null)).isEqualTo("ASSESSED");
        } finally {
            listener.stop();
        }
    }
}
