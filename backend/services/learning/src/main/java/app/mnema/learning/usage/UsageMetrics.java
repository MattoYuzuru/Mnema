package app.mnema.learning.usage;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code mnema_usage_reserved_credits}: credits held by ACTIVE reservations right now (held minus already debited), the figure that shows work in
 * flight and a leak (a hold that is never settled or released grows it until the expiry worker ends it). Read from the table when scraped, so every
 * process reports the same number; a database that cannot be read reports 0, like the queue-age gauge.
 */
@Component
class UsageMetrics {
    UsageMetrics(JdbcClient jdbc, MeterRegistry meters) {
        Gauge.builder("mnema_usage_reserved_credits", jdbc, UsageMetrics::reserved).baseUnit("credits")
                .description("Credits held by active reservations").register(meters);
    }

    private static double reserved(JdbcClient jdbc) {
        try {
            return jdbc.sql("SELECT COALESCE(SUM(held_credits - debited_credits),0) FROM app_learning.usage_reservation WHERE state='ACTIVE'")
                    .query(Long.class).single();
        } catch (RuntimeException unavailable) {
            return 0;
        }
    }
}
