package app.mnema.identityaccount.security;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;

@Component
public class ExpiredStateCleanup {
    private final JdbcClient jdbcClient;
    private final Clock clock;

    public ExpiredStateCleanup(JdbcClient jdbcClient, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
    }

    /** Deletes at most this many rows per statement. */
    static final int BATCH = 1000;
    /** Statement repetitions per table in one run, so a backlog drains over runs without a long transaction chain. */
    static final int MAX_BATCHES_PER_RUN = 50;

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT5M")
    public void removeExpired() {
        drain(() -> jdbcClient.sql(
                        "DELETE FROM app_identity.rate_limit WHERE bucket IN (SELECT bucket FROM app_identity.rate_limit WHERE window_start < :cutoff LIMIT " + BATCH + ")")
                .param("cutoff", OffsetDateTime.now(clock).minusMinutes(30)).update());
        drain(() -> jdbcClient.sql(
                        "DELETE FROM app_identity.ownership_challenge WHERE secret_hash IN (SELECT secret_hash FROM app_identity.ownership_challenge WHERE expires_at <= :now LIMIT " + BATCH + ")")
                .param("now", OffsetDateTime.now(clock)).update());
        drain(() -> jdbcClient.sql("""
                DELETE FROM app_identity.oauth2_authorization WHERE id IN (
                    SELECT id FROM app_identity.oauth2_authorization
                    WHERE greatest(authorization_code_expires_at, access_token_expires_at,
                                   refresh_token_expires_at, oidc_id_token_expires_at) < :cutoff
                    LIMIT %d
                )
                """.formatted(BATCH)).param("cutoff", OffsetDateTime.now(clock).minusMinutes(10)).update());
    }

    /** Repeats a bounded delete until a batch comes back short or the per-run cap is reached. */
    private static void drain(java.util.function.IntSupplier deleteBatch) {
        for (int round = 0; round < MAX_BATCHES_PER_RUN; round++) if (deleteBatch.getAsInt() < BATCH) return;
    }
}
