package app.mnema.learning.onboarding;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rhythm on and the tour off ({@code learning.onboarding.enabled} unset): the writes follow the rhythm's own switch, so weeks are counted from the day it is switched on, but the
 * tour's view is the disabled one (no journey, no rhythm) and nothing is announced, as for every milestone while the tour is off.
 */
@SpringBootTest(properties = {"learning.onboarding.rhythm.enabled=true", "spring.datasource.hikari.maximum-pool-size=4"})
class WeeklyRhythmOnboardingOffIntegrationTest extends PostgresIntegrationTest {
    private static final LocalDate MON = LocalDate.of(2026, 10, 12);

    @Autowired private WeeklyRhythm rhythm;
    @Autowired private OnboardingService onboarding;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;

    @Test
    void theWeekIsCountedButNothingIsAnnouncedAndTheViewIsTheDisabledOne() {
        UUID account = UUID.randomUUID();
        UUID template = RhythmSessions.template(decks, items, exercises, sessions, media, jdbc);
        for (int day = 0; day < 3; day++) {
            LocalDate date = MON.plusDays(day);
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                RhythmSessions.complete(jdbc, template, account, "SCHEDULED", date);
                rhythm.sessionCompleted(account, "SCHEDULED", date);
            });
        }

        assertThat(jdbc.sql("SELECT weeks_met::text FROM app_learning.onboarding_journey WHERE account_id=:a").param("a", account).query(String.class).single()).isEqualTo("1");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.account_milestone WHERE account_id=:a AND milestone_key='RHYTHM_WEEK' AND achieved_at IS NOT NULL")
                .param("a", account).query(Long.class).single()).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.notification WHERE owner_id=:a").param("a", account).query(Long.class).single()).isZero();

        JsonNode view = onboarding.read(account, true, null);
        assertThat(view.path("enabled").booleanValue()).isFalse();
        assertThat(view.path("journey").isNull()).isTrue();
        assertThat(view.path("rhythm").isNull()).isTrue();
        assertThat(view.path("milestones")).isEmpty();
    }
}
