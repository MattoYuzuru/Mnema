package app.mnema.identityaccount.security;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;

@Service
public class RateLimits {
    private final JdbcClient jdbcClient;
    private final Clock clock;

    public RateLimits(JdbcClient jdbcClient, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
    }

    /**
     * Autocommit before the protected transaction so denied attempts survive rollback.
     */
    public boolean allow(String action, String key, int maximum) {
        int count = jdbcClient.sql("""
                        INSERT INTO app_identity.rate_limit(bucket,window_start,attempts) VALUES(:bucket,:now,1)
                        ON CONFLICT(bucket) DO UPDATE SET
                         attempts=CASE WHEN rate_limit.window_start<=:cutoff THEN 1 ELSE LEAST(rate_limit.attempts+1,1000000) END,
                         window_start=CASE WHEN rate_limit.window_start<=:cutoff THEN :now ELSE rate_limit.window_start END
                        RETURNING attempts
                        """).param("bucket", Secrets.hash(action + ":" + key)).param("now", OffsetDateTime.now(clock))
                .param("cutoff", OffsetDateTime.now(clock).minusMinutes(15)).query(Integer.class).single();
        return count <= maximum;
    }
}
