package app.mnema.learning.onboarding;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.StudyFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hot path of a study session (OB-301), as the gate keeps it: how many statements the milestone recorder adds to the transaction that completes a session, counted at the JDBC
 * connection (every {@code prepareStatement} of the thread), with small fixtures that are removed afterwards. Replay adds none; once {@code FIRST_SESSION} and {@code TWO_DAYS} are
 * achieved the added cost is ONE primary-key range read; before that, the read, an upsert for each milestone that grows, and, only while {@code TWO_DAYS} is below its target, one
 * existence probe. The latency of those statements and their plans on a big table are {@link MilestoneHotPathEvidenceTest} (opt-in).
 */
@SpringBootTest(properties = {"learning.onboarding.enabled=true", "learning.onboarding.eligible-from=2026-01-01T00:00:00Z", "spring.datasource.hikari.maximum-pool-size=4"})
@Import(StatementCounting.class)
class MilestoneHotPathIntegrationTest extends PostgresIntegrationTest {
    private static final int SESSIONS = 40;

    @Autowired private MilestoneRecorder recorder;
    @Autowired private OnboardingService onboarding;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;

    /** {@code SESSIONS} completed sessions of a new account on {@code dates} local dates, cloned from a real session row. */
    private UUID account(UUID template, int dates) {
        UUID account = UUID.randomUUID();
        List<String> columns = jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_schema = 'app_learning' AND table_name = 'study_session' ORDER BY ordinal_position")
                .query(String.class).list();
        String selected = columns.stream().map(column -> switch (column) {
            case "account_id" -> ":account";
            case "session_id", "command_id" -> "gen_random_uuid()";
            case "mode" -> "CASE WHEN g % 5 = 0 THEN 'PRACTICE' ELSE 'SCHEDULED' END";
            case "practice_order" -> "CASE WHEN g % 5 = 0 THEN 'SEEDED' END";
            case "status" -> "'COMPLETE'";
            case "local_study_date" -> "CURRENT_DATE - (g % :dates)";
            case "completed_at" -> "statement_timestamp()";
            default -> "s." + column;
        }).collect(Collectors.joining(","));
        jdbc.sql("INSERT INTO app_learning.study_session(" + String.join(",", columns) + ") SELECT " + selected
                        + " FROM app_learning.study_session s CROSS JOIN generate_series(1, " + SESSIONS + ") g WHERE s.session_id = :template")
                .param("account", account).param("dates", dates).param("template", template).update();
        return account;
    }

    private void set(UUID account, String key, int progress, boolean achieved) {
        jdbc.sql("INSERT INTO app_learning.account_milestone(account_id, milestone_key, progress, achieved_at, first_at, source) VALUES (:a, :k, :p, CASE WHEN :done THEN statement_timestamp() END, statement_timestamp(), 'LIVE')")
                .param("a", account).param("k", key).param("p", progress).param("done", achieved).update();
    }

    private void reset(UUID account) {
        jdbc.sql("DELETE FROM app_learning.account_milestone WHERE account_id = :a").param("a", account).update();
    }

    /** The statements of {@code work} inside a transaction that is rolled back (nothing of the account changes). */
    private List<String> counted(Runnable work) {
        StatementCounting.start();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            work.run();
            status.setRollbackOnly();
        });
        return StatementCounting.stop();
    }

    @Test
    void theCompletionOfASessionAddsAKnownNumberOfStatements() {
        StudyFixtures fixtures = new StudyFixtures(decks, items, exercises, sessions, media, jdbc);
        StudyFixtures.Material material = fixtures.material();
        fixtures.publish(material, fixtures.freeResponse(material, StudyFixtures.blocks(StudyFixtures.text("Вопрос?")), StudyFixtures.blocks(StudyFixtures.text("Ответ")), "ответ"), "Цель");
        UUID template = fixtures.issueOne(material).session();
        UUID spread = account(template, 10);
        UUID sameDay = account(template, 1);
        LocalDate today = jdbc.sql("SELECT CURRENT_DATE").query(LocalDate.class).single();
        try {
            assertThat(counted(() -> recorder.sessionCompleted(spread, "REPLAY", today))).as("a replay").isEmpty();

            set(spread, "FIRST_SESSION", 1, true);
            set(spread, "TWO_DAYS", 2, true);
            assertThat(counted(() -> recorder.sessionCompleted(spread, "SCHEDULED", today))).as("both achieved: one primary-key range read").hasSize(1)
                    .allSatisfy(sql -> assertThat(sql).contains("account_milestone").startsWith("SELECT"));
            reset(spread);

            set(sameDay, "FIRST_SESSION", 1, true);
            set(sameDay, "TWO_DAYS", 1, false);
            assertThat(counted(() -> recorder.sessionCompleted(sameDay, "SCHEDULED", today))).as("the second day still missing, nothing new: the read and the existence probe, no write")
                    .hasSize(2).satisfies(log -> assertThat(log.get(1)).contains("study_session"));
            reset(sameDay);

            List<String> first = counted(() -> recorder.sessionCompleted(spread, "SCHEDULED", today));
            assertThat(first).as("the first completion: read, upsert, existence probe, upsert").hasSize(4);
            assertThat(first.get(1)).contains("INSERT INTO app_learning.account_milestone").contains("DO NOTHING");
            assertThat(first.get(3)).contains("INSERT INTO app_learning.account_milestone").contains("DO UPDATE");
            assertThat(counted(() -> recorder.sessionCompleted(sameDay, "PRACTICE", today))).as("the first completion on a single day").hasSize(4);

            // the read of the path by the view is one statement of the ledger
            StatementCounting.start();
            onboarding.read(spread, false);
            assertThat(StatementCounting.stop().stream().filter(sql -> sql.contains("account_milestone"))).as("one primary-key range read of the path per view").hasSize(1);
        } finally {
            for (UUID account : new UUID[] {spread, sameDay}) {
                reset(account);
                jdbc.sql("DELETE FROM app_learning.study_session WHERE account_id = :a").param("a", account).update();
            }
        }
    }
}
