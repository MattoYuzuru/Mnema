package app.mnema.learning.onboarding;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.community.DeckEngagement;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.StudyFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in evidence harness of the hot path of a study session (OB-301): the latency of the statements the milestone recorder adds to the transaction that completes a session (median and
 * 95th percentile of 300 completions after 100 warm-up ones) for an account with 2000 completed sessions in a table of 100,000, and EXPLAIN (ANALYZE, BUFFERS) of each statement. It
 * prints {@code HOTPATH} and {@code PLAN} lines for the evidence file and asserts only the statement counts (which {@link MilestoneHotPathIntegrationTest} asserts in the gate with small
 * fixtures). Timing on a shared machine proves nothing in a gate, so it is skipped unless {@code MNEMA_PATH_EVIDENCE=1}:
 * <pre>
 * MNEMA_PATH_EVIDENCE=1 ./gradlew :services:learning:cleanTest :services:learning:test --tests '*MilestoneHotPathEvidenceTest'
 * </pre>
 * (the output is in {@code backend/services/learning/build/test-results/test/TEST-*MilestoneHotPathEvidenceTest.xml}, {@code system-out}).
 */
@SpringBootTest(properties = {"learning.onboarding.enabled=true", "learning.onboarding.eligible-from=2026-01-01T00:00:00Z", "spring.datasource.hikari.maximum-pool-size=4"})
@Import(StatementCounting.class)
@EnabledIfEnvironmentVariable(named = "MNEMA_PATH_EVIDENCE", matches = "1")
class MilestoneHotPathEvidenceTest extends PostgresIntegrationTest {
    private static final int SESSIONS = 2_000;
    private static final int FILLER = 100_000;
    private static final int WARM_UP = 100;
    private static final int ITERATIONS = 300;

    @Autowired private MilestoneRecorder recorder;
    @Autowired private DeckEngagement engagement;
    @Autowired private OnboardingService onboarding;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactions);
    }

    private LocalDate today() {
        return jdbc.sql("SELECT CURRENT_DATE").query(LocalDate.class).single();
    }

    /** {@code SESSIONS} completed sessions of a new account, on {@code dates} distinct local dates (1 = all today), cloned from a real session row. */
    private UUID account(UUID template, int dates) {
        return account(UUID.randomUUID(), template, dates, SESSIONS, false);
    }

    /** Other accounts' sessions, so that the table is big enough for the planner to behave as it does in production: 1000 accounts of 100 completed sessions. */
    private void others(UUID template) {
        account(null, template, 100, FILLER, true);
    }

    private UUID account(UUID account, UUID template, int dates, int count, boolean many) {
        List<String> columns = jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_schema = 'app_learning' AND table_name = 'study_session' ORDER BY ordinal_position")
                .query(String.class).list();
        String selected = columns.stream().map(column -> switch (column) {
            case "account_id" -> many ? "md5((g % 1000)::text)::uuid" : ":account";
            case "session_id", "command_id" -> "gen_random_uuid()";
            case "mode" -> "CASE WHEN g % 5 = 0 THEN 'PRACTICE' ELSE 'SCHEDULED' END";
            case "practice_order" -> "CASE WHEN g % 5 = 0 THEN 'SEEDED' END";
            case "status" -> "'COMPLETE'";
            case "local_study_date" -> "CURRENT_DATE - (g % :dates)";
            case "completed_at" -> "statement_timestamp()";
            default -> "s." + column;
        }).collect(Collectors.joining(","));
        var insert = jdbc.sql("INSERT INTO app_learning.study_session(" + String.join(",", columns) + ") SELECT " + selected
                        + " FROM app_learning.study_session s CROSS JOIN generate_series(1, " + count + ") g WHERE s.session_id = :template")
                .param("dates", dates).param("template", template);
        if (account != null) insert = insert.param("account", account);
        insert.update();
        jdbc.sql("ANALYZE app_learning.study_session").update();
        return account;
    }

    private UUID templateSession() {
        StudyFixtures fixtures = new StudyFixtures(decks, items, exercises, sessions, media, jdbc);
        StudyFixtures.Material material = fixtures.material();
        fixtures.publish(material, fixtures.freeResponse(material, StudyFixtures.blocks(StudyFixtures.text("Вопрос?")), StudyFixtures.blocks(StudyFixtures.text("Ответ")), "ответ"), "Цель");
        return fixtures.issueOne(material).session();
    }

    private void reset(UUID account) {
        jdbc.sql("DELETE FROM app_learning.account_milestone WHERE account_id = :a").param("a", account).update();
    }

    private void set(UUID account, String key, int progress, boolean achieved) {
        jdbc.sql("INSERT INTO app_learning.account_milestone(account_id, milestone_key, progress, achieved_at, first_at, source) VALUES (:a, :k, :p, CASE WHEN :done THEN statement_timestamp() END, statement_timestamp(), 'LIVE')")
                .param("a", account).param("k", key).param("p", progress).param("done", achieved).update();
    }

    private record Counted(int statements, List<String> sql) { }

    /** The statements of {@code work} inside a transaction that is rolled back (nothing of the account changes). */
    private Counted counted(Runnable work) {
        StatementCounting.start();
        tx().executeWithoutResult(status -> {
            work.run();
            status.setRollbackOnly();
        });
        List<String> log = StatementCounting.stop();
        // the transaction's own statements are not the work's: the pool resets a connection with none, so every entry is the work's
        return new Counted(log.size(), log);
    }

    private static String shape(String sql) {
        return sql.replaceAll("\\s+", " ").strip();
    }

    private record Distribution(double median, double p95, double p99, double max, double mean) { }

    private static Distribution distribution(long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        return new Distribution(sorted[sorted.length / 2] / 1000.0, sorted[(int) Math.ceil(sorted.length * 0.95) - 1] / 1000.0, sorted[(int) Math.ceil(sorted.length * 0.99) - 1] / 1000.0,
                sorted[sorted.length - 1] / 1000.0, Arrays.stream(sorted).average().orElse(0) / 1000.0);
    }

    private static void print(String name, int statements, Distribution d) {
        System.out.printf("HOTPATH %-66s statements=%d median=%.0fus p95=%.0fus p99=%.0fus max=%.0fus mean=%.0fus%n", name, statements, d.median(), d.p95(), d.p99(), d.max(), d.mean());
    }

    /** Times {@code call} with the planner as it is on this 100,000-row table (it prefers a sequential scan of the whole table, which it would not on a production-sized one). */
    private Distribution time(Runnable before, Runnable call) {
        return time(before, call, false);
    }

    /**
     * Times {@code call} inside an open transaction that is rolled back, {@code ITERATIONS} times after {@code WARM_UP}; {@code before} runs outside the timing. With
     * {@code productionPlans} sequential scans are switched off for the transaction, which gives the plan (the primary-key range of the account) the planner chooses on a table
     * with millions of sessions, where an account's few thousand rows are a small fraction.
     */
    private Distribution time(Runnable before, Runnable call, boolean productionPlans) {
        long[] nanos = new long[ITERATIONS];
        for (int index = -WARM_UP; index < ITERATIONS; index++) {
            before.run();
            int slot = index;
            tx().executeWithoutResult(status -> {
                if (productionPlans) jdbc.sql("SET LOCAL enable_seqscan = off").update();
                long started = System.nanoTime();
                call.run();
                long elapsed = System.nanoTime() - started;
                if (slot >= 0) nanos[slot] = elapsed;
                status.setRollbackOnly();
            });
        }
        return distribution(nanos);
    }

    /** Times a whole transaction, the commit and the notification of {@code beforeCommit} included. */
    private Distribution timeWhole(Runnable before, Runnable work) {
        long[] nanos = new long[ITERATIONS];
        for (int index = -WARM_UP; index < ITERATIONS; index++) {
            before.run();
            long started = System.nanoTime();
            tx().executeWithoutResult(status -> work.run());
            long elapsed = System.nanoTime() - started;
            if (index >= 0) nanos[index] = elapsed;
        }
        return distribution(nanos);
    }

    @Test
    void measure() throws Exception {
        UUID template = templateSession();
        others(template);
        UUID spread = account(template, 400);
        UUID sameDay = account(template, 1);
        LocalDate today = today();
        UUID oneSession = jdbc.sql("SELECT session_id FROM app_learning.study_session WHERE account_id = :a LIMIT 1").param("a", spread).query(UUID.class).single();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.study_session WHERE account_id = :a AND status = 'COMPLETE'").param("a", spread).query(Long.class).single()).isEqualTo(SESSIONS);

        // ---- statement counts ----
        Counted replay = counted(() -> recorder.sessionCompleted(spread, "REPLAY", today));
        assertThat(replay.statements()).as("a replay").isZero();

        set(spread, "FIRST_SESSION", 1, true);
        set(spread, "TWO_DAYS", 2, true);
        Counted allAchieved = counted(() -> recorder.sessionCompleted(spread, "SCHEDULED", today));
        assertThat(allAchieved.statements()).as("both achieved: one primary-key range read").isOne();
        reset(spread);

        set(sameDay, "FIRST_SESSION", 1, true);
        set(sameDay, "TWO_DAYS", 1, false);
        Counted oneDayOnly = counted(() -> recorder.sessionCompleted(sameDay, "SCHEDULED", today));
        assertThat(oneDayOnly.statements()).as("the second day still missing, nothing new: the read and the existence probe, no write").isEqualTo(2);
        reset(sameDay);

        Counted first = counted(() -> recorder.sessionCompleted(spread, "SCHEDULED", today));
        assertThat(first.statements()).as("the first completion: read, upsert, existence probe, upsert").isEqualTo(4);
        Counted firstSameDay = counted(() -> recorder.sessionCompleted(sameDay, "SCHEDULED", today));
        assertThat(firstSameDay.statements()).as("the first completion on a single day: read, upsert, existence probe, upsert").isEqualTo(4);
        System.out.println("HOTPATH statements: replay=0 allAchieved=1 oneDayOnly=2 firstCompletion=4");
        System.out.println("HOTPATH sql(first completion): " + first.sql().stream().map(MilestoneHotPathEvidenceTest::shape).collect(Collectors.joining(" || ")));

        // ---- the existing engagement call of the same transaction, for scale ----
        Distribution engagementCost = time(() -> { }, () -> engagement.studied(spread, oneSession));
        print("existing DeckEngagement.studied (non-copy deck)", counted(() -> engagement.studied(spread, oneSession)).statements(), engagementCost);

        // ---- latency of the added statements ----
        set(spread, "FIRST_SESSION", 1, true);
        set(spread, "TWO_DAYS", 2, true);
        print("sessionCompleted: both achieved (steady state)", 1, time(() -> { }, () -> recorder.sessionCompleted(spread, "SCHEDULED", today)));
        reset(spread);

        set(sameDay, "FIRST_SESSION", 1, true);
        set(sameDay, "TWO_DAYS", 1, false);
        print("sessionCompleted: second day missing, 2000 today [100k-row plan]", 2, time(() -> { }, () -> recorder.sessionCompleted(sameDay, "SCHEDULED", today)));
        print("sessionCompleted: second day missing, 2000 today [index plan]", 2, time(() -> { }, () -> recorder.sessionCompleted(sameDay, "SCHEDULED", today), true));
        reset(sameDay);

        print("sessionCompleted: first completion, 400 days [100k-row plan]", 4, time(() -> reset(spread), () -> recorder.sessionCompleted(spread, "SCHEDULED", today)));
        print("sessionCompleted: first completion, 400 days [index plan]", 4, time(() -> reset(spread), () -> recorder.sessionCompleted(spread, "SCHEDULED", today), true));
        print("sessionCompleted: replay", 0, time(() -> { }, () -> recorder.sessionCompleted(spread, "REPLAY", today)));

        // ---- the first completion as a whole transaction (commit and the two notifications), against an empty transaction ----
        Distribution empty = timeWhole(() -> { }, () -> { });
        print("empty transaction (begin, commit)", 0, empty);
        Distribution whole = timeWhole(() -> reset(spread), () -> recorder.sessionCompleted(spread, "SCHEDULED", today));
        StatementCounting.start();
        reset(spread);
        tx().executeWithoutResult(status -> recorder.sessionCompleted(spread, "SCHEDULED", today));
        List<String> wholeLog = StatementCounting.stop();
        print("first completion, whole transaction with its notifications", wholeLog.size(), whole);
        System.out.println("HOTPATH sql(first completion, whole transaction): " + wholeLog.stream().map(MilestoneHotPathEvidenceTest::shape).collect(Collectors.joining(" || ")));
        assertThat(wholeLog.size()).as("the four statements, then the notifications of the two milestones at the commit").isGreaterThan(4);
        reset(spread);

        // ---- the read of the onboarding view: one more statement for the path ----
        StatementCounting.start();
        onboarding.read(spread, false, null, null);
        List<String> viewLog = StatementCounting.stop();
        System.out.println("HOTPATH view statements=" + viewLog.size() + " : " + viewLog.stream().map(MilestoneHotPathEvidenceTest::shape).map(sql -> sql.substring(0, Math.min(90, sql.length()))).collect(Collectors.joining(" || ")));
        assertThat(viewLog.stream().filter(sql -> sql.contains("account_milestone")).count()).as("one primary-key range read of the path per view").isOne();

        // ---- plans ----
        explain(spread, sameDay, today);
    }

    private String literal(String sql, UUID account, String key, int level, int target) {
        return sql.replace(":account", "'" + account + "'").replace(":key", "'" + key + "'").replace(":level", Integer.toString(level)).replace(":target", Integer.toString(target));
    }

    private String constant(String name) throws Exception {
        java.lang.reflect.Field field = MilestoneRepository.class.getDeclaredField(name);
        field.setAccessible(true);
        return ((String) field.get(null)).formatted("");
    }

    private void plan(String name, String sql) {
        List<String> lines = jdbc.sql("EXPLAIN (ANALYZE, BUFFERS) " + sql).query(String.class).list();
        lines.forEach(line -> System.out.println("PLAN " + name + " | " + line));
    }

    private void explain(UUID spread, UUID sameDay, LocalDate today) throws Exception {
        String reach = constant("REACH");
        tx().executeWithoutResult(status -> {
            try {
                set(spread, "FIRST_SESSION", 1, true);
                set(spread, "TWO_DAYS", 1, false);
                plan("achieved-check read (FIRST_SESSION achieved, TWO_DAYS at 1 of 2)", "SELECT milestone_key, progress, achieved_at FROM app_learning.account_milestone WHERE account_id='" + spread
                        + "' AND milestone_key IN ('FIRST_SESSION','TWO_DAYS')");
                plan("upsert, row already achieved (no write)", literal(reach, spread, "FIRST_SESSION", 1, 1));
                plan("upsert, progress already 1 of 2 (no write)", literal(reach, spread, "TWO_DAYS", 1, 2));
                plan("upsert, progress 1 -> 2 of 2, achieves (one row updated)", literal(reach, spread, "TWO_DAYS", 2, 2));
                plan("upsert, new row (INSERT ... ON CONFLICT, one row inserted)", literal(reach, spread, "GOAL_TOLD", 1, 1));
                // the probe of MilestoneRepository.studiedOnAnotherDay, natural plan on the 100k-row table: min/max of the partial index study_session_study_dates
                for (Object[] probe : new Object[][] {{"400 study days", spread}, {"2000 sessions all today", sameDay}}) {
                    plan("TWO_DAYS second-day probe, " + probe[0] + " [100k-row table plan]", "SELECT coalesce(min(local_study_date) <> max(local_study_date) OR min(local_study_date) <> DATE '"
                            + today + "', false) FROM app_learning.study_session WHERE account_id='" + probe[1] + "' AND status='COMPLETE' AND mode IN ('SCHEDULED','PRACTICE')");
                }
                plan("path read for the view", "SELECT milestone_key, progress, achieved_at FROM app_learning.account_milestone WHERE account_id='" + spread + "'");
            } finally {
                status.setRollbackOnly();
            }
        });
    }
}
