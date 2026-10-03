package app.mnema.learning.generation;

import app.mnema.learning.platform.api.RateLimitedException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;

/**
 * The hourly rate limit of the intent call ({@code learning.generation.intent.per-hour}), kept in the database because every service
 * instance is stateless: one row per call (the account and the time, never a text), counted over the last hour under an advisory lock of the
 * account, so two calls at once cannot both take the last place. The call that is over the limit answers {@code 429 RATE_LIMITED} with the
 * seconds until the oldest call of the window leaves it. The rows of an account older than a day are deleted with its next call, and
 * the retention worker deletes the rest.
 */
@Component
class IntentUses {
    private static final Duration WINDOW = Duration.ofHours(1);

    private final JdbcClient jdbc;
    private final GenerationSettings settings;
    private final TransactionTemplate transaction;

    IntentUses(JdbcClient jdbc, GenerationSettings settings, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(5);
    }

    /**
     * Takes one place of the owner's hour.
     *
     * @throws RateLimitedException the hour is full
     */
    void take(UUID owner) {
        Long wait = transaction.execute(status -> {
            jdbc.sql("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended('generation.intent:' || :owner, 0))) lock")
                    .param("owner", owner.toString()).query(Integer.class).single();
            jdbc.sql("DELETE FROM app_learning.generation_intent_use WHERE owner_id=:owner AND used_at < CURRENT_TIMESTAMP - interval '1 day'")
                    .param("owner", owner).update();
            long used = jdbc.sql("SELECT count(*) FROM app_learning.generation_intent_use WHERE owner_id=:owner AND used_at > CURRENT_TIMESTAMP - "
                    + "(:seconds * interval '1 second')").param("owner", owner).param("seconds", WINDOW.toSeconds()).query(Long.class).single();
            if (used >= settings.intent().perHour()) {
                return jdbc.sql("SELECT GREATEST(1, CEIL(EXTRACT(EPOCH FROM (min(used_at) + (:seconds * interval '1 second') - CURRENT_TIMESTAMP))))::bigint "
                        + "FROM app_learning.generation_intent_use WHERE owner_id=:owner AND used_at > CURRENT_TIMESTAMP - (:seconds * interval '1 second')")
                        .param("owner", owner).param("seconds", WINDOW.toSeconds()).query(Long.class).single();
            }
            jdbc.sql("INSERT INTO app_learning.generation_intent_use(owner_id,used_at) VALUES (:owner,CURRENT_TIMESTAMP)").param("owner", owner).update();
            return null;
        });
        if (wait != null) throw new RateLimitedException(wait);
    }

    /** Deletes the rows older than two hours of every account (the retention worker's pass); returns how many. */
    int purge() {
        return jdbc.sql("DELETE FROM app_learning.generation_intent_use WHERE used_at < CURRENT_TIMESTAMP - interval '2 hours'").update();
    }
}
