package app.mnema.learning.study.attempt;

import app.mnema.learning.capability.SemanticAssessmentProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

/** Real database deadline boundary without a sweep or wall-clock delay: only the result transaction decides whether to deliver. */
@SpringBootTest(properties = {"learning.runtime.roles=all", "learning.ai.provider=stub", "learning.features.ai-assessment.enabled=true",
        "learning.usage.entitlements.default-plan=PLUS", "learning.ai.assess.deadline=PT20S", "spring.datasource.hikari.maximum-pool-size=3"})
@Import(AssessmentDeadlineBoundaryIntegrationTest.DatabaseConfiguration.class)
class AssessmentDeadlineBoundaryIntegrationTest extends AssessmentIntegrationTest {
    private static final String DATABASE = createDatabase("assessment_deadline_" + UUID.randomUUID().toString().replace("-", ""));

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {
        @Bean
        DynamicPropertyRegistrar isolatedDatabase() {
            // Registrars run after inherited dynamic methods and before the DataSource/Flyway are instantiated.
            return registry -> {
                registry.add("spring.datasource.url", () -> DATABASE);
                registry.add("spring.flyway.url", () -> DATABASE);
            };
        }
    }

    // Bare mocks are not post-processed for scheduled/event callbacks; the test calls the real grader/completion explicitly.
    @MockitoBean(enforceOverride = true) private AssessmentRunner dormantRunner;
    @MockitoBean(enforceOverride = true) private AssessmentSweeper dormantSweeper;
    @MockitoSpyBean private AttemptRepository repository;
    @Autowired private SemanticAssessmentProvider grader;

    @ParameterizedTest
    @CsvSource({"19,true", "20,false", "21,false"})
    void theLockedAbsoluteDeadlineFencesDeliveryWithoutRelyingOnASweep(long completionSeconds, boolean deliver) {
        assertThat(jdbc.sql("SELECT current_database()").query(String.class).single()).startsWith("assessment_deadline_");
        Case learner = issued();
        UUID attempt = UUID.randomUUID();
        Instant acceptedAt = repository.now();
        doReturn(acceptedAt).when(repository).now();
        submit(learner, attempt, "Планы выполнения [[stub:assess-complete]]");
        var request = assessments.prepare(attempt).orElseThrow();
        var outcome = grader.grade(request);
        assertThat(outcome).isInstanceOf(SemanticAssessmentProvider.GradeOutcome.Graded.class);
        long calls = journalCalls(attempt);
        assertThat(calls).isPositive();
        assertThat(state(attempt)).isEqualTo("ASSESSING");
        long sequence = sequence(learner.actor());

        doReturn(acceptedAt.plusSeconds(completionSeconds)).when(repository).now();
        assessments.complete(attempt, outcome);

        assertThat(journalCalls(attempt)).as("provider spend is retained even when its grade is too late").isEqualTo(calls);
        if (deliver) {
            assertThat(state(attempt)).isEqualTo("DONE");
            assertThat(checks(learner.actor())).isOne();
            assertThat(count("study_evidence", learner.actor())).isOne();
            assertThat(sequence(learner.actor())).isGreaterThan(sequence);
        } else {
            assertThat(state(attempt)).isEqualTo("UNAVAILABLE");
            assertThat(reason(attempt)).isEqualTo("DEADLINE");
            assertThat(checks(learner.actor())).isZero();
            assertThat(count("study_attempt_tombstone", learner.actor())).isZero();
            assertThat(count("study_evidence", learner.actor())).isZero();
            assertThat(count("study_transition", learner.actor())).isZero();
            assertThat(sequence(learner.actor())).isEqualTo(sequence);
            var view = assessments.read(learner.actor(), learner.deck(), learner.session(), attempt);
            assertThat(view.path("status").stringValue()).isEqualTo("SELF_CHECK");
            assertThat(view.path("reason").stringValue()).isEqualTo("DEADLINE");
            assessments.complete(attempt, outcome);
            assertThat(checks(learner.actor())).isZero();
            assertThat(journalCalls(attempt)).isEqualTo(calls);
        }
    }

    private long journalCalls(UUID attempt) {
        return jdbc.sql("SELECT count(*) FROM app_learning.ai_provider_call WHERE step_id=:attempt AND outcome='OK'")
                .param("attempt", attempt).query(Long.class).single();
    }

    private long sequence(UUID actor) {
        return jdbc.sql("SELECT COALESCE(max(transition_sequence),0) FROM app_learning.study_state WHERE account_id=:actor")
                .param("actor", actor).query(Long.class).single();
    }
}
