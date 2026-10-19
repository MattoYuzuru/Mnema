package app.mnema.learning.onboarding;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL of {@code onboarding_journey}: one row per account that was offered or started the tour, written by the owner's own commands and by the first read of an
 * eligible account, plus (V75, OB-401) a {@code NOT_OFFERED} row, with no cohort and no {@code offered_at}, for an account that was never offered the tour but has a weekly
 * target or a counted week ({@link RhythmRepository}). The commands' writes are compare-and-set on {@code row_version}.
 *
 * <p>TODO(account-deletion task; owner: Learning's account-purge epic #409): delete the row by {@code account_id} (the tour state, experience and weekly target
 * are personal data tied to the account) and the account's command receipts of scope {@code onboarding} (partial index {@code command_receipt_onboarding_actor}).
 * Learning has no account purge path yet; see "Retention" in the Learning guide and the launch checklist before public activation.
 */
@Repository
class OnboardingRepository {
    /**
     * {@code cohort} is null for a row created by REPLAY outside the holdout cohort and for a {@code NOT_OFFERED} row; {@code offeredAt} is null exactly for a
     * {@code NOT_OFFERED} row.
     */
    record Row(int tourVersion, JourneyState state, Cohort cohort, JourneyBranch branch, String stepKey, Experience experience, Integer weeklyTarget,
               Instant offeredAt, Instant finishedAt, int replayCount, long rowVersion) { }

    /** TOUR and CONTROL are experiment data; UNASSIGNED is an account the experiment did not bucket (disabled, no secret): it gets the tour and the report leaves it out. */
    enum Cohort { TOUR, CONTROL, UNASSIGNED }

    enum Experience { NEW, FAMILIAR, CONFIDENT }

    /** One variant of the owner report. */
    record Group(long accounts, long activated, long returned) { }

    private final JdbcClient jdbc;

    OnboardingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Instant now() {
        return jdbc.sql("SELECT statement_timestamp()").query(Timestamp.class).single().toInstant();
    }

    Optional<Row> find(UUID account) {
        return jdbc.sql("SELECT tour_version,state,cohort,branch,step_key,experience,weekly_target,offered_at,finished_at,replay_count,row_version "
                        + "FROM app_learning.onboarding_journey WHERE account_id=:account")
                .param("account", account)
                .query((row, number) -> new Row(row.getInt("tour_version"), JourneyState.valueOf(row.getString("state")),
                        row.getString("cohort") == null ? null : Cohort.valueOf(row.getString("cohort")),
                        row.getString("branch") == null ? null : JourneyBranch.valueOf(row.getString("branch")), row.getString("step_key"),
                        row.getString("experience") == null ? null : Experience.valueOf(row.getString("experience")),
                        row.getObject("weekly_target", Integer.class), row.getTimestamp("offered_at") == null ? null : row.getTimestamp("offered_at").toInstant(),
                        row.getTimestamp("finished_at") == null ? null : row.getTimestamp("finished_at").toInstant(),
                        row.getInt("replay_count"), row.getLong("row_version")))
                .optional();
    }

    /** Whether the account owns a deck of its own that is not deleted. Its onboarding sample deck (OB-201) is made by the tour, not by the account: it does not count. */
    boolean hasLiveDeck(UUID account) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_learning.deck WHERE owner_id=:account AND deleted_at IS NULL AND purpose <> 'SAMPLE')")
                .param("account", account).query(Boolean.class).single();
    }

    /**
     * Creates the row at {@code row.rowVersion()} unless one exists, with the answers it carries ({@code experience}, {@code weeklyTarget}) and the {@code offeredAt} it carries
     * (null for a {@code NOT_OFFERED} row).
     *
     * @return whether this call created it
     */
    boolean insert(UUID account, Row row, Instant now) {
        return jdbc.sql("INSERT INTO app_learning.onboarding_journey(account_id,tour_version,state,cohort,branch,step_key,experience,weekly_target,offered_at,replay_count,row_version,updated_at) "
                        + "VALUES (:account,:tour,:state,:cohort,:branch,:step,:experience,:weekly,:offered,:replays,:version,:now) ON CONFLICT (account_id) DO NOTHING")
                .param("account", account).param("tour", row.tourVersion()).param("state", row.state().name())
                .param("cohort", row.cohort() == null ? null : row.cohort().name(), Types.VARCHAR)
                .param("branch", row.branch() == null ? null : row.branch().name(), Types.VARCHAR)
                .param("step", row.stepKey(), Types.VARCHAR)
                .param("experience", row.experience() == null ? null : row.experience().name(), Types.VARCHAR).param("weekly", row.weeklyTarget(), Types.SMALLINT)
                .param("offered", row.offeredAt() == null ? null : Timestamp.from(row.offeredAt()), Types.TIMESTAMP)
                .param("replays", row.replayCount()).param("version", row.rowVersion()).param("now", Timestamp.from(now))
                .update() == 1;
    }

    /**
     * Offers the tour to an eligible account in ONE statement: creates its {@code OFFERED} row in {@code cohort} (version 0), or turns the account's {@code NOT_OFFERED} row (V75)
     * into it (cohort and {@code offered_at} set, version bumped; its answers and counters stay). A row in any other state is left alone, so of concurrent first reads exactly one
     * statement changes a row.
     *
     * @return whether this call created or upgraded the row, which is the one that counts the exposure
     */
    boolean offer(UUID account, Cohort cohort, Instant now) {
        return jdbc.sql("INSERT INTO app_learning.onboarding_journey AS j(account_id,tour_version,state,cohort,offered_at,replay_count,row_version,updated_at) "
                        + "VALUES (:account,:tour,'OFFERED',:cohort,:now,0,0,:now) ON CONFLICT (account_id) DO UPDATE "
                        + "SET tour_version=EXCLUDED.tour_version,state='OFFERED',cohort=EXCLUDED.cohort,offered_at=EXCLUDED.offered_at,row_version=j.row_version+1,updated_at=EXCLUDED.updated_at "
                        + "WHERE j.state='NOT_OFFERED'")
                .param("account", account).param("tour", JourneySteps.TOUR_VERSION).param("cohort", cohort.name()).param("now", Timestamp.from(now))
                .update() == 1;
    }

    /**
     * Writes the mutable columns of {@code next} and bumps the version, if the row still has {@code expected}. {@code offered_at} is set when the state leaves
     * {@code NOT_OFFERED} (a replay from the row of V75) and stays null while it is {@code NOT_OFFERED}. @return whether it did
     */
    boolean update(UUID account, Row next, long expected, Instant now) {
        return jdbc.sql("UPDATE app_learning.onboarding_journey SET tour_version=:tour,state=:state,branch=:branch,step_key=:step,experience=:experience,"
                        + "weekly_target=:weekly,finished_at=:finished,replay_count=:replays,"
                        + "offered_at=CASE WHEN :state='NOT_OFFERED' THEN NULL ELSE coalesce(offered_at,:now) END,row_version=row_version+1,updated_at=:now "
                        + "WHERE account_id=:account AND row_version=:expected")
                .param("account", account).param("expected", expected).param("tour", next.tourVersion()).param("state", next.state().name())
                .param("branch", next.branch() == null ? null : next.branch().name(), Types.VARCHAR).param("step", next.stepKey(), Types.VARCHAR)
                .param("experience", next.experience() == null ? null : next.experience().name(), Types.VARCHAR)
                .param("weekly", next.weeklyTarget(), Types.SMALLINT)
                .param("finished", next.finishedAt() == null ? null : Timestamp.from(next.finishedAt()), Types.TIMESTAMP)
                .param("replays", next.replayCount()).param("now", Timestamp.from(now)).update() == 1;
    }

    /**
     * Per cohort of the experiment, for the accounts that entered it in {@code [from, to)} by {@code offered_at}: the group, the accounts with a completed
     * SCHEDULED session within {@code activationDays} of the offer and those with completed SCHEDULED or PRACTICE sessions on at least two local study dates
     * within {@code returnDays}. One grouped statement over the window's rows; it reaches {@code study_session} by its primary key (account_id, session_id).
     * Rows created by REPLAY ({@code cohort IS NULL}) and accounts the experiment did not bucket ({@code UNASSIGNED}) are not part of it. No account id leaves the statement.
     */
    Map<Cohort, Group> report(Instant from, Instant to, int activationDays, int returnDays) {
        Map<Cohort, Group> groups = new LinkedHashMap<>();
        jdbc.sql("""
                WITH members AS (
                    SELECT account_id, cohort, offered_at FROM app_learning.onboarding_journey
                     WHERE cohort IN ('TOUR','CONTROL') AND offered_at >= :from AND offered_at < :to
                ), activity AS (
                    SELECT m.account_id,
                           coalesce(bool_or(s.mode = 'SCHEDULED' AND s.completed_at < m.offered_at + make_interval(days => :activation)), false) AS activated,
                           count(DISTINCT s.local_study_date) FILTER (WHERE s.mode IN ('SCHEDULED','PRACTICE')) AS study_days
                      FROM members m
                      LEFT JOIN app_learning.study_session s
                        ON s.account_id = m.account_id AND s.status = 'COMPLETE' AND s.completed_at >= m.offered_at
                       AND s.completed_at < m.offered_at + make_interval(days => :returnDays)
                     GROUP BY m.account_id
                )
                SELECT m.cohort, count(*) AS accounts, count(*) FILTER (WHERE a.activated) AS activated, count(*) FILTER (WHERE a.study_days >= 2) AS returned
                  FROM members m JOIN activity a ON a.account_id = m.account_id
                 GROUP BY m.cohort
                """)
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).param("activation", activationDays).param("returnDays", returnDays)
                .query((row, number) -> Map.entry(Cohort.valueOf(row.getString("cohort")),
                        new Group(row.getLong("accounts"), row.getLong("activated"), row.getLong("returned"))))
                .list().forEach(entry -> groups.put(entry.getKey(), entry.getValue()));
        return groups;
    }
}
