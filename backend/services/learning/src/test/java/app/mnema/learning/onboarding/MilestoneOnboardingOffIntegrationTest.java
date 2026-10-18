package app.mnema.learning.onboarding;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.profile.LearningGoal;
import app.mnema.learning.profile.LearningProfileService;
import app.mnema.learning.support.PostgresIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With {@code learning.onboarding.enabled} off (the default and production) the facts of the path are still written, because they are harmless history the path will show once it is
 * switched on, but nothing is announced and the view stays the empty one. A milestone reached now is not announced later either.
 */
@SpringBootTest
class MilestoneOnboardingOffIntegrationTest extends PostgresIntegrationTest {
    @Autowired private JdbcClient jdbc;
    @Autowired private LearningProfileService profile;
    @Autowired private DeckService decks;
    @Autowired private MeterRegistry meters;
    @Autowired private OnboardingSettings settings;

    @Test
    void theRowsAreWrittenAndCountedButNothingIsAnnouncedWhileTheTourIsOff() {
        assertThat(settings.enabled()).isFalse();
        UUID account = UUID.randomUUID();
        double before = meters.counter("mnema_path_milestone_total", "key", "GOAL_TOLD", "source", "LIVE").count();

        profile.answer(account, LearningGoal.LANGUAGE);
        decks.create(account, new DeckCommand(UUID.randomUUID(), "Колода", "Описание"));

        assertThat(jdbc.sql("SELECT milestone_key FROM app_learning.account_milestone WHERE account_id=:a AND achieved_at IS NOT NULL ORDER BY milestone_key")
                .param("a", account).query(String.class).list()).containsExactly("FIRST_DECK", "GOAL_TOLD");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.notification WHERE owner_id=:a").param("a", account).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.notification_cursor WHERE owner_id=:a").param("a", account).query(Long.class).single())
                .as("the publisher was never asked").isZero();
        assertThat(meters.counter("mnema_path_milestone_total", "key", "GOAL_TOLD", "source", "LIVE").count()).isEqualTo(before + 1);
        // the rows are real facts: the same event later changes nothing
        profile.answer(account, LearningGoal.WORK);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.account_milestone WHERE account_id=:a").param("a", account).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.notification WHERE owner_id=:a").param("a", account).query(Long.class).single()).isZero();
    }
}
