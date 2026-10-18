package app.mnema.learning.study.attempt;

import app.mnema.learning.onboarding.MilestoneKey;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code AI_EXPLANATION} of the «Путь Mnema» (OB-301): an answer concluded by the model ({@code ai-semantic}) in a scheduled or practice session, in the transaction that concludes it.
 * An uncertain or unavailable assessment and the learner's own rating are not that. The tour is on in this context, so the announcement is observable: the capability is on, so the
 * milestone is visible and nine of the thirteen are.
 */
@TestPropertySource(properties = {"learning.onboarding.enabled=true", "learning.onboarding.eligible-from=2026-01-01T00:00:00Z"})
class AssessmentMilestoneIntegrationTest extends AssessmentIntegrationTest {
    private boolean reached(UUID account) {
        return jdbc.sql("SELECT count(*) FROM app_learning.account_milestone WHERE account_id=:a AND milestone_key='AI_EXPLANATION' AND achieved_at IS NOT NULL")
                .param("a", account).query(Long.class).single() == 1;
    }

    private List<String> announced(UUID account) {
        return jdbc.sql("SELECT dedupe_key || ' ' || (params->>'done') || '/' || (params->>'total') FROM app_learning.notification WHERE owner_id=:a AND kind='PATH_MILESTONE' ORDER BY seq")
                .param("a", account).query(String.class).list();
    }

    @Test
    void aGradeOfTheModelIsTheMilestoneAnnouncedTogetherWithTheSessionItCompletes() {
        Case learner = issued();
        assertThat(reached(learner.actor())).isFalse();
        UUID attempt = UUID.randomUUID();
        submit(learner, attempt, "Он перебирает планы, потом берёт самый дешёвый [[stub:assess-complete]]");
        assertThat(settled(learner, attempt).path("status").stringValue(null)).isEqualTo("ASSESSED");

        assertThat(reached(learner.actor())).isTrue();
        // the deck, the material and the exercise came first; the last answer completes the session and is concluded by the model, in ONE transaction: both announced, with the same counts
        assertThat(announced(learner.actor())).containsExactly("path:FIRST_DECK 1/9", "path:OWN_MATERIAL 2/9", "path:FIRST_SESSION 4/9", "path:AI_EXPLANATION 4/9");
        // a second explanation is nothing new
        Case next = another(learner, "PRACTICE");
        UUID second = UUID.randomUUID();
        submit(next, second, "Он перебирает планы, потом берёт самый дешёвый [[stub:assess-complete]]");
        settled(next, second);
        assertThat(announced(learner.actor())).hasSize(4);
    }

    @Test
    void aPracticeSessionCountsToo() {
        Case first = issued();
        Case practice = another(first, "PRACTICE");
        UUID attempt = UUID.randomUUID();
        submit(practice, attempt, "Он перебирает планы, потом берёт самый дешёвый [[stub:assess-complete]]");
        settled(practice, attempt);
        assertThat(reached(first.actor())).isTrue();
    }

    @Test
    void anUncertainAssessmentAnUnavailableOneAndTheLearnersOwnRatingAreNotTheMilestone() {
        Case uncertain = issued();
        UUID one = UUID.randomUUID();
        submit(uncertain, one, "Не знаю [[stub:assess-unclear]]");
        JsonNode view = settled(uncertain, one);
        assertThat(view.path("status").stringValue(null)).isEqualTo("SELF_CHECK");
        assessments.selfRate(uncertain.actor(), uncertain.deck(), uncertain.session(), one, AttemptCommand.SelfRating.FULL);
        assertThat(state(one)).isEqualTo("DONE");
        assertThat(reached(uncertain.actor())).as("self-check after an uncertain grade").isFalse();
        assertThat(announced(uncertain.actor())).as("the session it completed is the milestone it did").contains("path:FIRST_SESSION 3/9").doesNotContain("path:AI_EXPLANATION 3/9");

        Case unavailable = issued();
        UUID two = UUID.randomUUID();
        submit(unavailable, two, "Ответ [[fake:fail]]");
        assertThat(settled(unavailable, two).path("status").stringValue(null)).isEqualTo("SELF_CHECK");
        assessments.selfRate(unavailable.actor(), unavailable.deck(), unavailable.session(), two, AttemptCommand.SelfRating.FULL);
        assertThat(reached(unavailable.actor())).as("self-check after a failure").isFalse();
    }
}
