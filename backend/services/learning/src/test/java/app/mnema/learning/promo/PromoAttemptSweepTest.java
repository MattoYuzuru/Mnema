package app.mnema.learning.promo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The retention sweep of redemption attempts: only rows older than two hours go, in bounded batches per tick. */
class PromoAttemptSweepTest extends PromoIntegrationTest {
    @Autowired private PromoRepository repository;

    /** The test context runs the api role only, where the scheduled bean is absent: the sweep is built by hand over the real repository and clock. */
    private PromoAttemptSweep sweep;

    @BeforeEach
    void sweeper() {
        sweep = new PromoAttemptSweep(repository, clock);
    }

    private void insert(UUID owner, Duration age, int count) {
        jdbc.sql("INSERT INTO app_learning.promo_attempt(owner_id,attempted_at) SELECT :owner,:at FROM generate_series(1,:count)")
                .param("owner", owner).param("at", Timestamp.from(now().minus(age))).param("count", count).update();
    }

    private long rows(UUID owner) {
        return jdbc.sql("SELECT count(*) FROM app_learning.promo_attempt WHERE owner_id=:o").param("o", owner).query(Long.class).single();
    }

    @Test
    void onlyAttemptsOlderThanTwoHoursAreDeleted() {
        UUID owner = UUID.randomUUID();
        insert(owner, Duration.ofHours(3), 4);
        insert(owner, Duration.ofMinutes(119), 2);
        insert(owner, Duration.ofMinutes(5), 1);

        assertThat(sweep.sweep()).isGreaterThanOrEqualTo(4);

        assertThat(rows(owner)).isEqualTo(3);
    }

    @Test
    void oneTickIsBoundedAndTheNextTickFinishesTheJob() {
        UUID owner = UUID.randomUUID();
        int total = PromoAttemptSweep.BATCH_SIZE * PromoAttemptSweep.MAX_BATCHES_PER_TICK + 5;
        insert(owner, Duration.ofHours(5), total);

        assertThat(sweep.sweep()).isGreaterThanOrEqualTo(PromoAttemptSweep.BATCH_SIZE * PromoAttemptSweep.MAX_BATCHES_PER_TICK);
        assertThat(rows(owner)).isLessThanOrEqualTo(5);

        sweep.sweep();
        assertThat(rows(owner)).isZero();
    }
}
