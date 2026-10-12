package app.mnema.learning.community;

import app.mnema.learning.platform.security.IdentityClaims.AccountSignals;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The writers of {@code deck_engagement} (Community/3, #450, migration {@code V68}): the incremental metrics of DIRECT copies, written <b>synchronously in the transaction of the event
 * itself</b> (the copy command, the completion of a scheduled study session), so there is no cursor over time, no outbox gap and no lag. Both are one statement and both join the caller's
 * transaction ({@code MANDATORY}).
 *
 * <p>The source's owner is never counted (a statement that would write one writes nothing), a copy of a copy counts only for its direct source ({@code deck_fork.upstream_deck_id}), and
 * a retry changes nothing. TODO(account-deletion task #409; owner: Learning's account-purge epic): the purge of an account must delete its rows here
 * ({@code WHERE account_id = :account}, served by {@code deck_engagement_account}); the source's metrics then lose that person, which is the intended effect.
 */
@Repository
public class DeckEngagement {
    /** The weight of a copy by an account that was not trusted at the copy (RFC 11.2); a trusted one weighs 1. */
    public static final float UNTRUSTED_WEIGHT = 0.25f;

    private final JdbcClient jdbc;

    DeckEngagement(JdbcClient jdbc) { this.jdbc = jdbc; }

    /**
     * The first copy of {@code source} by {@code account}: {@code FORK} with the weight and the "new account" flag from the signals of the request. A second copy (a deleted and re-made one)
     * is the same person and changes nothing. Nothing is written for the source's owner (the copy command refuses it with {@code FORK_OWN_DECK} before it gets here; this is the net).
     *
     * @return whether a row was written
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean forked(UUID source, UUID account, AccountSignals signals) {
        return jdbc.sql("""
                INSERT INTO app_learning.deck_engagement(deck_id, account_id, kind, first_at, last_at, trust, new_account)
                SELECT d.deck_id, :account, 'FORK', statement_timestamp(), statement_timestamp(), :trust, :fresh
                  FROM app_learning.deck d WHERE d.deck_id = :source AND d.owner_id <> :account
                ON CONFLICT (deck_id, account_id, kind) DO NOTHING
                """).param("source", source).param("account", account).param("trust", signals.trusted() ? 1.0f : UNTRUSTED_WEIGHT)
                .param("fresh", signals.newAccount()).update() == 1;
    }

    /**
     * A SCHEDULED study session of {@code account} has just become {@code COMPLETE} (call it in the transaction that completed it): if its deck is a live copy
     * ({@code deck_fork}, not released; a DETACHED copy still counts), {@code STUDY} of the copy's source is created or moved on: {@code last_at} to the completion, and one more
     * study day only when the session's local study date is later than the last counted one, so a retry or a second session of the day adds nothing. A deck that is not a copy, a replay
     * or practice session, and the source's own sessions write nothing: for a deck that is not a copy the whole cost is one primary-key probe of {@code study_session} and one of
     * {@code deck_fork}. The two probes are pinned in that order with {@code OFFSET 0} and a lateral subquery (the join order of the media probes of Share/9), so that no plan can
     * start from {@code deck_fork}, a table that grows with the copies, whatever the statistics say.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void studied(UUID account, UUID session) {
        jdbc.sql("""
                INSERT INTO app_learning.deck_engagement AS e(deck_id, account_id, kind, first_at, last_at, study_days, last_study_date)
                SELECT c.upstream_deck_id, d.account_id, 'STUDY', d.completed_at, d.completed_at, 1, d.local_study_date
                  FROM (SELECT s.account_id, s.deck_id, s.completed_at, s.local_study_date
                          FROM app_learning.study_session s
                         WHERE s.account_id = :account AND s.session_id = :session
                           AND s.mode = 'SCHEDULED' AND s.status = 'COMPLETE' AND s.completed_at IS NOT NULL
                        OFFSET 0) d
                 CROSS JOIN LATERAL (SELECT f.upstream_deck_id FROM app_learning.deck_fork f WHERE f.deck_id = d.deck_id AND f.released_at IS NULL OFFSET 0) c
                 WHERE EXISTS (SELECT 1 FROM app_learning.deck u WHERE u.deck_id = c.upstream_deck_id AND u.owner_id <> d.account_id)
                ON CONFLICT (deck_id, account_id, kind) DO UPDATE SET
                    last_at = GREATEST(e.last_at, EXCLUDED.last_at),
                    study_days = e.study_days + CASE WHEN EXCLUDED.last_study_date > e.last_study_date THEN 1 ELSE 0 END,
                    last_study_date = GREATEST(e.last_study_date, EXCLUDED.last_study_date)
                """).param("account", account).param("session", session).update();
    }
}
