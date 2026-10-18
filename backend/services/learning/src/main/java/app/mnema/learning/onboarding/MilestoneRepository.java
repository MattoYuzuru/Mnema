package app.mnema.learning.onboarding;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * SQL of {@code account_milestone} (V74): the facts of the «Путь Mnema» (ADR-ONB-002). Every write is ONE statement, and it returns a row only when it inserted or advanced the
 * record, so {@code achieved} tells whether it was this very statement that reached the target. A milestone of target 1 is never partial: its statement is {@code INSERT ... ON
 * CONFLICT DO NOTHING}, which takes no lock on the row that is already there (a repeat of an achieved fact must not serialize the account's transactions or order their locks). A counter
 * or a staged milestone is an upsert whose {@code WHERE} leaves an achieved row alone; PostgreSQL locks the conflicting row for it even when nothing changes, which is why only the
 * milestones that need it use it. {@code achieved_at} is the database clock and is written once. No statement here has an external dependency or touches another account's row, so a failure can only roll back the
 * caller's own transaction; reads use the primary key {@code (account_id, milestone_key)}.
 *
 * <p>TODO(account-deletion task; owner: Learning's account-purge epic #409): delete the rows by {@code account_id} together with {@code onboarding_journey}
 * ({@link OnboardingRepository}); the key, a counter and two times are personal data tied to the account. Learning has no account purge path yet; see "Retention" in the Learning
 * guide and the launch checklist before public activation.
 */
@Repository
class MilestoneRepository {
    /** The stored record of one milestone. {@code achievedAt} is null until the target is reached. */
    record Row(int progress, Instant achievedAt) { }

    /** The two milestones one completed study session may advance, with their progress. */
    record SessionProgress(Row firstSession, Row twoDays) { }

    private static final String HEAD = """
            INSERT INTO app_learning.account_milestone AS m(account_id, milestone_key, progress, achieved_at, first_at, source)
            """;

    /** A guard that makes the insert conditional on the deck being the account's own deck of purpose USER (the caller has only a deck id). */
    private static final String USER_DECK = " WHERE EXISTS (SELECT 1 FROM app_learning.deck d WHERE d.deck_id=:deck AND d.owner_id=:account AND d.purpose='USER')";

    /** A milestone of target 1: achieved by the insert, and a row that exists (it is achieved: a target-1 record is never partial) is left alone WITHOUT a row lock. */
    private static final String REACH_ONCE = HEAD + """
            SELECT CAST(:account AS uuid), CAST(:key AS text), CAST(1 AS smallint), statement_timestamp(), statement_timestamp(), 'LIVE'
            %s
            ON CONFLICT (account_id, milestone_key) DO NOTHING
            RETURNING true AS achieved
            """;

    /**
     * Raises the progress of a staged or counted milestone (target above 1) to {@code level} (capped at the target), never lowers it and never touches an achieved row. The WHERE of the update also skips a record whose progress does
     * not grow, so a repeated event writes nothing.
     */
    private static final String REACH = HEAD + """
            SELECT CAST(:account AS uuid), CAST(:key AS text), CAST(:level AS smallint), CASE WHEN CAST(:level AS smallint) >= CAST(:target AS smallint) THEN statement_timestamp() END,
                   statement_timestamp(), 'LIVE'
            %s
            ON CONFLICT (account_id, milestone_key) DO UPDATE
               SET progress = EXCLUDED.progress,
                   achieved_at = CASE WHEN EXCLUDED.progress >= CAST(:target AS smallint) THEN statement_timestamp() END
             WHERE m.achieved_at IS NULL AND m.progress < EXCLUDED.progress
            RETURNING (achieved_at IS NOT NULL) AS achieved
            """;

    /** Adds one to the progress of an unachieved record (capped at the target); a target of 1 is achieved by the insert itself. */
    private static final String INCREMENT = HEAD + """
            SELECT CAST(:account AS uuid), CAST(:key AS text), CAST(1 AS smallint), CASE WHEN 1 >= CAST(:target AS smallint) THEN statement_timestamp() END,
                   statement_timestamp(), 'LIVE'
            %s
            ON CONFLICT (account_id, milestone_key) DO UPDATE
               SET progress = LEAST(m.progress + 1, CAST(:target AS smallint)),
                   achieved_at = CASE WHEN m.progress + 1 >= CAST(:target AS smallint) THEN statement_timestamp() END
             WHERE m.achieved_at IS NULL
            RETURNING (achieved_at IS NOT NULL) AS achieved
            """;

    private final JdbcClient jdbc;

    MilestoneRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return whether this statement reached the target of {@code key} (false: it advanced, or changed nothing) */
    boolean reach(UUID account, MilestoneKey key, int level) {
        if (key.target() == 1) return jdbc.sql(REACH_ONCE.formatted("")).param("account", account).param("key", key.name()).query(Boolean.class).optional().orElse(false);
        return jdbc.sql(REACH.formatted("")).param("account", account).param("key", key.name()).param("level", Math.min(level, key.target()))
                .param("target", key.target()).query(Boolean.class).optional().orElse(false);
    }

    /** Like {@link #reach}, if {@code deck} is a deck of {@code account} with purpose USER (otherwise nothing is written). */
    boolean reachInUserDeck(UUID account, UUID deck, MilestoneKey key, int level) {
        if (key.target() == 1) {
            return jdbc.sql(REACH_ONCE.formatted(USER_DECK)).param("account", account).param("deck", deck).param("key", key.name()).query(Boolean.class).optional().orElse(false);
        }
        return jdbc.sql(REACH.formatted(USER_DECK)).param("account", account).param("deck", deck).param("key", key.name())
                .param("level", Math.min(level, key.target())).param("target", key.target()).query(Boolean.class).optional().orElse(false);
    }

    boolean increment(UUID account, MilestoneKey key) {
        return jdbc.sql(INCREMENT.formatted("")).param("account", account).param("key", key.name()).param("target", key.target())
                .query(Boolean.class).optional().orElse(false);
    }

    /** The one read of a completed study session: the records of the two milestones it can advance, by primary key. */
    SessionProgress sessionProgress(UUID account) {
        Row[] rows = new Row[2];
        jdbc.sql("""
                SELECT milestone_key, progress, achieved_at FROM app_learning.account_milestone
                 WHERE account_id=:account AND milestone_key IN ('FIRST_SESSION','TWO_DAYS')
                """).param("account", account).query((row, ignored) -> {
            Timestamp achieved = row.getTimestamp("achieved_at");
            rows["FIRST_SESSION".equals(row.getString("milestone_key")) ? 0 : 1] = new Row(row.getInt("progress"), achieved == null ? null : achieved.toInstant());
            return null;
        }).list();
        return new SessionProgress(rows[0], rows[1]);
    }

    /**
     * Whether the account has a completed SCHEDULED or PRACTICE session on another local study date than {@code date}: the second day of {@link MilestoneKey#TWO_DAYS}. Asked as
     * the min and the max date of the partial index {@code study_session_study_dates} (V74), which the planner answers with two index-only probes whatever the account's history
     * (an {@code EXISTS ... <> :date} could be planned as a scan of the table).
     */
    boolean studiedOnAnotherDay(UUID account, LocalDate date) {
        return jdbc.sql("""
                SELECT coalesce(min(local_study_date) <> max(local_study_date) OR min(local_study_date) <> :date, false) FROM app_learning.study_session
                 WHERE account_id=:account AND status='COMPLETE' AND mode IN ('SCHEDULED','PRACTICE')
                """).param("account", account).param("date", date).query(Boolean.class).single();
    }

    /** The records of the account, by key; at most one per milestone of the registry. */
    Map<MilestoneKey, Row> all(UUID account) {
        Map<MilestoneKey, Row> rows = new EnumMap<>(MilestoneKey.class);
        jdbc.sql("SELECT milestone_key, progress, achieved_at FROM app_learning.account_milestone WHERE account_id=:account").param("account", account)
                .query((row, ignored) -> {
                    Timestamp achieved = row.getTimestamp("achieved_at");
                    rows.put(MilestoneKey.valueOf(row.getString("milestone_key")), new Row(row.getInt("progress"), achieved == null ? null : achieved.toInstant()));
                    return null;
                }).list();
        return rows;
    }
}
