package app.mnema.learning.usage;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code mnema_usage_reserved_credits} follows the active holds in the table: it rises with a reservation and falls when it is released. */
class UsageMetricsTest extends UsageIntegrationTest {
    @Autowired private MeterRegistry meters;

    private double reserved() { return meters.get("mnema_usage_reserved_credits").gauge().value(); }

    @Test
    void theGaugeCountsTheCreditsOfActiveReservationsAndDropsWhenOneEnds() {
        assertThat(reserved()).isZero();
        UUID owner = owner(Plan.PLUS);
        Reservation held = reserve(owner, 10);
        reserve(owner, 4);
        assertThat(reserved()).isEqualTo(14);

        inTx(() -> {
            ledger.release(owner, held.reservationId());
            return null;
        });
        assertThat(reserved()).isEqualTo(4);
    }
}
