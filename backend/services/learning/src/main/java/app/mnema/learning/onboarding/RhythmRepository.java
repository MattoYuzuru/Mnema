package app.mnema.learning.onboarding;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL of the weekly rhythm (OB-401, ADR-ONB-004): the counters in {@code onboarding_journey} ({@code weeks_met}, {@code last_met_week} of V72 and the
 * {@code NOT_OFFERED} row of V75) and the days of a week, read from {@code study_session} through the partial index {@code study_session_study_dates} (V74).
 * No statement here touches {@code row_version}: that is the client's compare-and-set for the tour commands, and a session completion must not make the next one fail.
 *
 * <p>TODO(account-deletion task; owner: Learning's account-purge epic #409): the counters go with the {@code onboarding_journey} row ({@link OnboardingRepository}).
 */
@Repository
class RhythmRepository {
    /** The rhythm columns of the journey row: the target ({@code null}: the default), the weeks met and the Monday of the last week counted ({@code null}: none). */
    record Journey(Integer weeklyTarget, int weeksMet, LocalDate lastMetWeek) { }

    /**
     * The distinct local study dates of the account's completed SCHEDULED and PRACTICE sessions in {@code [from, to)}. The predicate repeats the one of the partial index
     * {@code study_session_study_dates}, so the planner answers it from that index whatever the account's history ({@code WeeklyRhythmIntegrationTest} holds it to that with EXPLAIN).
     */
    static final String DAYS = """
            SELECT DISTINCT local_study_date FROM app_learning.study_session
             WHERE account_id=:account AND status='COMPLETE' AND mode IN ('SCHEDULED','PRACTICE')
               AND local_study_date >= :from AND local_study_date < :to
             ORDER BY local_study_date
            """;

    /**
     * Counts a week once: creates the {@code NOT_OFFERED} row of an account that has none (no cohort, no {@code offered_at}; on the wire it reads as no row), or adds a week to the
     * row that is there, only if its {@code last_met_week} is older than this week. A repeat, a late completion of an older week and a lost race change nothing and return no row.
     * {@code row_version} is neither written nor bumped.
     */
    private static final String MEET = """
            INSERT INTO app_learning.onboarding_journey AS j(account_id,tour_version,state,offered_at,replay_count,weeks_met,last_met_week,row_version,updated_at)
            VALUES (:account,:tour,'NOT_OFFERED',NULL,0,1,:week,0,statement_timestamp())
            ON CONFLICT (account_id) DO UPDATE
               SET weeks_met = j.weeks_met + 1, last_met_week = EXCLUDED.last_met_week, updated_at = EXCLUDED.updated_at
             WHERE j.last_met_week IS NULL OR j.last_met_week < EXCLUDED.last_met_week
            """;

    private final JdbcClient jdbc;

    RhythmRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Instant now() {
        return jdbc.sql("SELECT statement_timestamp()").query(Timestamp.class).single().toInstant();
    }

    /** The rhythm columns of the account's journey row, by primary key; empty when the account has no row. */
    Optional<Journey> journey(UUID account) {
        return jdbc.sql("SELECT weekly_target,weeks_met,last_met_week FROM app_learning.onboarding_journey WHERE account_id=:account")
                .param("account", account)
                .query((row, number) -> new Journey(row.getObject("weekly_target", Integer.class), row.getInt("weeks_met"), row.getObject("last_met_week", LocalDate.class)))
                .optional();
    }

    /**
     * Serializes the rhythm writers of one account until the end of the transaction. The days of a week are counted under it, so that of two concurrent completions the second
     * waits for the first's commit and then counts both sessions.
     */
    void lock(UUID account) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", "onboarding.rhythm:" + account).query().listOfRows();
    }

    /** The days of {@code [week, week + 7)} that have a completed session, ascending (at most seven). */
    List<LocalDate> days(UUID account, LocalDate week) {
        return jdbc.sql(DAYS).param("account", account).param("from", week).param("to", week.plusDays(7)).query(LocalDate.class).list();
    }

    /** @return whether this statement counted {@code week} (false: it was already counted, or a later one was) */
    boolean meet(UUID account, LocalDate week) {
        return jdbc.sql(MEET).param("account", account).param("tour", JourneySteps.TOUR_VERSION).param("week", week).update() == 1;
    }
}
