package app.mnema.learning.onboarding;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.study.attempt.AttemptService;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.StudyFixtures;
import app.mnema.learning.support.StudyFixtures.Issued;
import app.mnema.learning.support.StudyFixtures.Material;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static app.mnema.learning.onboarding.OnboardingFixture.JSON;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The weekly rhythm switched off, which is the default and production ({@code learning.onboarding.rhythm.enabled} unset) while the tour itself is on: no completion writes anything, a
 * lowered target meets no week, the view says {@code rhythm: null} and the path hides {@code RHYTHM_WEEK}. The preferences still work in every state: the target is the user's setting
 * whether or not the rhythm counts it. A second context has the rhythm on and the tour off: the weeks are counted, nothing is announced and the view is the disabled one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "learning.onboarding.enabled=true", "learning.onboarding.eligible-from=2026-01-01T00:00:00Z", "spring.datasource.hikari.maximum-pool-size=8"})
@Import(StatementCounting.class)
class WeeklyRhythmOffIntegrationTest extends PostgresIntegrationTest {
    private static final LocalDate MON = LocalDate.of(2026, 10, 12);
    private static final Map<String, String> NONE = Map.of();

    @LocalServerPort private int port;
    @Autowired private WeeklyRhythm rhythm;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private AttemptService attempts;
    @Autowired private MediaCatalog media;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
    }

    private UUID template() {
        return RhythmSessions.template(decks, items, exercises, sessions, media, jdbc);
    }

    private long rows(UUID account) {
        return jdbc.sql("SELECT count(*) FROM app_learning.onboarding_journey WHERE account_id=:a").param("a", account).query(Long.class).single();
    }

    private String column(UUID account, String column) {
        return jdbc.sql("SELECT " + column + "::text FROM app_learning.onboarding_journey WHERE account_id=:a").param("a", account)
                .query((row, number) -> row.getString(1)).list().getFirst();
    }

    private boolean reached(UUID account) {
        return jdbc.sql("SELECT count(*) FROM app_learning.account_milestone WHERE account_id=:a AND milestone_key='RHYTHM_WEEK'").param("a", account).query(Long.class).single() == 1;
    }

    private HttpResponse<String> get(UUID account) {
        return HttpIdentityFixture.send(port, "GET", "/onboarding", HttpIdentityFixture.reader(account), null, NONE);
    }

    @Test
    void aCompletionThatWouldMeetTheWeekWritesNothingAndCostsNoStatement() {
        UUID account = UUID.randomUUID();
        UUID template = template();
        for (int day = 0; day < 4; day++) RhythmSessions.complete(jdbc, template, account, "PRACTICE", MON.plusDays(day));

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            StatementCounting.start();
            rhythm.sessionCompleted(account, "PRACTICE", MON.plusDays(3));
            rhythm.sessionCompleted(account, "SCHEDULED", MON.plusDays(3));
            assertThat(StatementCounting.stop()).isEmpty();
        });

        assertThat(rows(account)).isZero();
        assertThat(reached(account)).isFalse();
    }

    @Test
    void theLastAnswerOfARealSessionWritesNothingToTheRhythm() {
        UUID account = UUID.randomUUID();
        StudyFixtures fixtures = new StudyFixtures(decks, items, exercises, sessions, media, jdbc);
        Material material = fixtures.material(account);
        fixtures.publish(material, fixtures.freeResponse(material, StudyFixtures.blocks(StudyFixtures.text("Вопрос?")), StudyFixtures.blocks(StudyFixtures.text("Ответ")), "ответ"), "Цель");
        jdbc.sql("INSERT INTO app_learning.onboarding_journey(account_id,tour_version,state,weekly_target,replay_count,weeks_met,row_version,updated_at) "
                + "VALUES (:a,1,'NOT_OFFERED',1,0,0,1,statement_timestamp())").param("a", account).update();

        List<Issued> issued = fixtures.issue(material, "PRACTICE", null);
        for (Issued presentation : issued) {
            attempts.submit(account, material.deck(), presentation.session(), StudyFixtures.attempt(presentation, StudyFixtures.textResponse("ответ")));
        }

        assertThat(jdbc.sql("SELECT status FROM app_learning.study_session WHERE session_id=:s").param("s", issued.getFirst().session()).query(String.class).single()).isEqualTo("COMPLETE");
        assertThat(column(account, "weeks_met")).isEqualTo("0");
        assertThat(column(account, "last_met_week")).isNull();
        assertThat(reached(account)).isFalse();
    }

    @Test
    void theViewHasNoRhythmAndThePathHidesTheMilestone() {
        UUID account = UUID.randomUUID();

        JsonNode view = JSON.readTree(get(account).body());

        assertThat(view.path("enabled").booleanValue()).isTrue();
        assertThat(view.has("rhythm")).isTrue();
        assertThat(view.path("rhythm").isNull()).isTrue();
        assertThat(view.path("path").path("hidden")).extracting(JsonNode::stringValue).contains("RHYTHM_WEEK");
        assertThat(view.path("milestones")).extracting(milestone -> milestone.path("key").stringValue()).doesNotContain("RHYTHM_WEEK");
    }

    @Test
    void aLoweredTargetStoresTheAnswerAndMeetsNoWeek() {
        UUID account = UUID.randomUUID();
        // a day this week by the database clock, so that the target below would be met if the rhythm counted
        RhythmSessions.complete(jdbc, template(), account, "PRACTICE", jdbc.sql("SELECT (statement_timestamp() AT TIME ZONE 'UTC')::date").query(LocalDate.class).single());
        JsonNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("rowVersion", "0").put("weeklyTarget", 1);

        HttpResponse<String> answer = HttpIdentityFixture.send(port, "PUT", "/onboarding/preferences", HttpIdentityFixture.reader(account), body.toString(), NONE);

        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(200);
        JsonNode view = JSON.readTree(answer.body());
        assertThat(view.path("journey").path("state").stringValue()).isEqualTo("NOT_OFFERED");
        assertThat(view.path("journey").path("weeklyTarget").intValue()).isOne();
        assertThat(view.path("journey").path("rowVersion").stringValue()).isEqualTo("1");
        assertThat(view.path("rhythm").isNull()).isTrue();
        assertThat(column(account, "weekly_target")).isEqualTo("1");
        assertThat(column(account, "weeks_met")).isEqualTo("0");
        assertThat(reached(account)).isFalse();
    }
}
