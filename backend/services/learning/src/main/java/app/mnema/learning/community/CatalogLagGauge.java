package app.mnema.learning.community;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * {@code mnema_catalog_projection_lag}: how many rows of {@code public_deck} are behind their published revision (the scan of their mechanics has not caught up:
 * {@code projected_revision_id} differs from {@code published_revision_id}) for longer than {@value #AGE_MINUTES} minutes. At rest it is 0. A value above 0 that does not go
 * down means a projection job failed or the queue is stopped; the repair is the rebuild. One query, an index-only probe of the partial index {@code public_deck_lag} (which holds only
 * lagging rows), evaluated at most once a minute however often the gauge is scraped; a database that cannot be read reports NaN, never a false 0.
 */
@Component
class CatalogLagGauge {
    static final String NAME = "mnema_catalog_projection_lag";
    static final int AGE_MINUTES = 10;
    private static final Duration CACHE = Duration.ofMinutes(1);

    private final ProjectionRepository repository;
    private final LongSupplier nanos;
    private double cached;
    private long readAt;
    private boolean read;

    @Autowired
    CatalogLagGauge(ProjectionRepository repository, MeterRegistry meters) {
        this(repository, meters, System::nanoTime);
    }

    CatalogLagGauge(ProjectionRepository repository, MeterRegistry meters, LongSupplier nanos) {
        this.repository = repository;
        this.nanos = nanos;
        Gauge.builder(NAME, this::value).description("public_deck rows whose scan lags their published revision by more than 10 minutes").register(meters);
    }

    synchronized double value() {
        long now = nanos.getAsLong();
        if (!read || now - readAt > CACHE.toNanos()) {
            try {
                cached = repository.lagging(Duration.ofMinutes(AGE_MINUTES));
            } catch (RuntimeException unavailable) {
                cached = Double.NaN;
            }
            readAt = now;
            read = true;
        }
        return cached;
    }
}
