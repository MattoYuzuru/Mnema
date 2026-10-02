package app.mnema.learning.usage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The scheduled sweep: orphaned holds go back to their balances; old counters are trimmed. */
class UsageExpiryWorkerTest extends UsageIntegrationTest {
    @Autowired private UsageExpiryWorker worker;

    @Test
    void aSweepEndsEveryDueHoldAcrossBatchesAndLeavesLiveOnesAlone() {
        UUID owner = owner(Plan.PLUS);
        for (int i = 0; i < 5; i++) reserve(owner, 10);
        assertThat(balance(owner)).containsExactly(360, 0, 50);

        clock.set("2026-10-02T10:00:00Z");
        worker.sweep();
        assertThat(balance(owner)).containsExactly(360, 0, 50);

        clock.set("2026-10-02T11:00:42Z");
        worker.sweep();

        assertThat(balance(owner)).containsExactly(360, 0, 0);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.usage_reservation WHERE owner_id=:o AND state='EXPIRED'")
                .param("o", owner).query(Long.class).single()).isEqualTo(5);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.usage_reservation WHERE state='ACTIVE' AND expires_at<=:t")
                .param("t", java.sql.Timestamp.from(now())).query(Long.class).single()).isZero();
    }

    @Test
    void aSweepWithNothingDueDoesNothing() {
        UUID owner = owner(Plan.PLUS);
        reserve(owner, 10);
        worker.sweep();
        assertThat(balance(owner)).containsExactly(360, 0, 10);
    }
}
