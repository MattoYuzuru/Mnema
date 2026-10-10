package app.mnema.learning.billing;

import app.mnema.learning.usage.Plan;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The pure rules of the receipt outbox: the service name, the legal term and the backoff. */
class NpdReceiptsRulesTest {
    private static BillingOrder order(Plan plan) {
        return BillingOrder.created(UUID.randomUUID(), UUID.randomUUID(), plan, 49_900, 49_900, null, null, Instant.parse("2026-10-09T09:00:00Z"),
                Instant.parse("2026-10-09T10:00:00Z"));
    }

    @Test
    void theServiceNameIsRussianAndNamesThePlanAndThePeriod() {
        BillingOrder plus = order(Plan.PLUS);
        BillingOrder pro = order(Plan.PRO);
        assertThat(NpdReceipts.serviceName(plus)).isEqualTo("Подписка Мнема Plus на 1 месяц, заказ №" + plus.orderId().toString().substring(0, 8));
        assertThat(NpdReceipts.serviceName(pro)).startsWith("Подписка Мнема Pro на 1 месяц, заказ №").hasSizeLessThanOrEqualTo(128);
        assertThat(NpdReceipts.serviceName(order(Plan.PLUS))).as("unique per order").isNotEqualTo(NpdReceipts.serviceName(plus));
    }

    @Test
    void theTermEndsWithTheNinthOfTheNextMonthInMoscow() {
        // 9 November 2026 is the last day for a payment of October; the instant is the start of the 10th, Moscow (UTC+3).
        assertThat(NpdReceipts.deadline(Instant.parse("2026-10-09T09:00:00Z"))).isEqualTo(Instant.parse("2026-11-09T21:00:00Z"));
        assertThat(NpdReceipts.deadline(Instant.parse("2026-10-31T21:30:00Z"))).as("already November in Moscow").isEqualTo(Instant.parse("2026-12-09T21:00:00Z"));
        assertThat(NpdReceipts.deadline(Instant.parse("2026-12-15T09:00:00Z"))).as("across the year").isEqualTo(Instant.parse("2027-01-09T21:00:00Z"));
    }

    @Test
    void theBackoffDoublesFromAMinuteAndStopsAtSixHours() {
        assertThat(NpdReceipts.backoff(0)).isEqualTo(Duration.ofMinutes(1));
        assertThat(NpdReceipts.backoff(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(NpdReceipts.backoff(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(NpdReceipts.backoff(5)).isEqualTo(Duration.ofMinutes(16));
        assertThat(NpdReceipts.backoff(9)).isEqualTo(Duration.ofMinutes(256));
        assertThat(NpdReceipts.backoff(10)).isEqualTo(Duration.ofHours(6));
        assertThat(NpdReceipts.backoff(500)).isEqualTo(Duration.ofHours(6));
    }
}
