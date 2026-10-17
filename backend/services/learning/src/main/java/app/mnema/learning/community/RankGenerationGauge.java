package app.mnema.learning.community;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * {@code mnema_catalog_rank_generation} (the number of the newest completed generation, 0 before the first) and {@code mnema_catalog_rank_generation_age_seconds} (how long ago it was
 * completed; {@code NaN} before the first and when the database cannot be read, never a false 0): architecture section 14, "поколение ранга и его возраст". At rest the age stays
 * below an hour plus the duration of the job; a value that keeps growing means the hourly job fails or its queue is stopped. One probe of the primary key, at most once a minute
 * however often the gauges are scraped.
 */
@Component
class RankGenerationGauge {
    static final String GENERATION = "mnema_catalog_rank_generation";
    static final String AGE = "mnema_catalog_rank_generation_age_seconds";
    private static final Duration CACHE = Duration.ofMinutes(1);

    private final RankRepository repository;
    private final Clock clock;
    private final LongSupplier nanos;
    private Optional<RankRepository.Newest> cached = Optional.empty();
    private boolean failed;
    private long readAt;
    private boolean read;

    @Autowired
    RankGenerationGauge(RankRepository repository, MeterRegistry meters) {
        this(repository, meters, Clock.systemUTC(), System::nanoTime);
    }

    RankGenerationGauge(RankRepository repository, MeterRegistry meters, Clock clock, LongSupplier nanos) {
        this.repository = repository;
        this.clock = clock;
        this.nanos = nanos;
        Gauge.builder(GENERATION, this::generation).description("the newest completed rank generation").register(meters);
        Gauge.builder(AGE, this::age).description("seconds since the newest rank generation completed").register(meters);
    }

    private synchronized void refresh() {
        long now = nanos.getAsLong();
        if (read && now - readAt <= CACHE.toNanos()) return;
        try {
            cached = repository.newest();
            failed = false;
        } catch (RuntimeException unavailable) {
            failed = true;
        }
        readAt = now;
        read = true;
    }

    synchronized double generation() {
        refresh();
        return failed ? Double.NaN : cached.map(newest -> (double) newest.generation()).orElse(0.0);
    }

    synchronized double age() {
        refresh();
        if (failed) return Double.NaN;
        Instant now = clock.instant();
        return cached.map(newest -> Math.max(0.0, Duration.between(newest.completedAt(), now).toMillis() / 1000.0)).orElse(Double.NaN);
    }
}
