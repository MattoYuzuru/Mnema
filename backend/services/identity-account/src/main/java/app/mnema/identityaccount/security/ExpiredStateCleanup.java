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

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT5M")
    public void removeExpired() {
        jdbcClient.sql(
                        "DELETE FROM app_identity.rate_limit WHERE bucket IN (SELECT bucket FROM app_identity.rate_limit WHERE window_start < :cutoff LIMIT 1000)")
                .param("cutoff", OffsetDateTime.now(clock).minusMinutes(30)).update();
        jdbcClient.sql(
                        "DELETE FROM app_identity.ownership_challenge WHERE secret_hash IN (SELECT secret_hash FROM app_identity.ownership_challenge WHERE expires_at <= :now LIMIT 1000)")
                .param("now", OffsetDateTime.now(clock)).update();
        jdbcClient.sql("""
                DELETE FROM app_identity.oauth2_authorization WHERE id IN (
                    SELECT id FROM app_identity.oauth2_authorization
                    WHERE greatest(authorization_code_expires_at, access_token_expires_at,
                                   refresh_token_expires_at, oidc_id_token_expires_at) < :cutoff
                    LIMIT 1000
                )
                """).param("cutoff", OffsetDateTime.now(clock).minusMinutes(10)).update();
    }
}
