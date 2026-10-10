package app.mnema.learning.billing;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** The review fixes: one discounted order per discount, one Init per order, replayed notifications that cannot drive the bank, answers about the right payment. */
class BillingHardeningTest extends BillingIntegrationTest {
    @Autowired private PlatformTransactionManager transactions;

    private String orderOf(JsonNode order) {
        return order.path("orderId").stringValue(null);
    }

    @Test
    void aDiscountPricesOneUnfinishedOrderAtATime() throws Exception {
        UUID owner = UUID.randomUUID();
        discount(owner, 20, null);

        JsonNode plus = open(owner, "PLUS");
        JsonNode pro = open(owner, "PRO");
        JsonNode again = open(owner, "PLUS");

        assertThat(plus.path("amountKopecks").longValue()).isEqualTo(35_900);
        assertThat(plus.path("discountPercent").intValue()).isEqualTo(20);
        assertThat(pro.path("amountKopecks").longValue()).as("the discount is taken by the PLUS order").isEqualTo(99_000);
        assertThat(pro.path("discountPercent").isNull()).isTrue();
        assertThat(orderOf(again)).isEqualTo(orderOf(plus));
        assertThat(count("SELECT count(*) FROM app_learning.billing_order WHERE owner_id=? AND discount_code_id IS NOT NULL", owner)).isEqualTo(1);

        // Paying the discounted order spends the discount; afterwards nothing is discounted.
        BANK.move(orderOf(plus), "CONFIRMED");
        assertThat(notify(notification("notification-confirmed.json", orderOf(plus), "CONFIRMED")).getStatus()).isEqualTo(200);
        assertThat(discountRows(owner)).isZero();
    }

    @Test
    void aDiscountHeldByAnExpiredOrderPricesTheNextPurchase() throws Exception {
        UUID owner = UUID.randomUUID();
        discount(owner, 20, null);
        JsonNode first = open(owner, "PLUS");
        clock.advance(Duration.ofMinutes(61));

        JsonNode second = open(owner, "PRO");

        assertThat(second.path("amountKopecks").longValue()).isEqualTo(79_200);
        assertThat(status(orderOf(first))).isEqualTo("FAILED");
        assertThat(column(orderOf(first), "failure_reason")).isEqualTo("EXPIRED");
        assertThat(events(orderOf(first))).as("closing the holder is audited like any transition").containsExactly("INIT:NEW:PENDING", "RECONCILE:NEW:FAILED");
        // A payment that still reaches the closed order is granted: money taken must give access.
        BANK.move(orderOf(first), "CONFIRMED");
        assertThat(notify(notification("notification-confirmed.json", orderOf(first), "CONFIRMED")).getStatus()).isEqualTo(200);
        assertThat(status(orderOf(first))).isEqualTo("PAID");
        assertThat(snapshots(owner)).isEqualTo(1);
    }

    @Test
    void aDiscountThatLosesTheRaceForItsIndexIsDroppedNotFailed() throws Exception {
        UUID owner = UUID.randomUUID();
        discount(owner, 20, null);
        JsonNode holder = open(owner, "PLUS");
        BillingRepository blind = spy(new BillingRepository(jdbc));
        doReturn(Optional.empty()).when(blind).openDiscountHolder(any(), any(), any());
        BillingController racing = new BillingController(new BillingService(blind, settings, bank, applier, receipts, entitlements, prices, discounts, clock, npdReceipts, transactions));

        MockHttpServletResponse response = as(owner, racing).perform(post("/billing/checkout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"plan\":\"PRO\",\"period\":\"MONTH\"}").header("Idempotency-Key", UUID.randomUUID().toString())).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(body(response).path("amountKopecks").longValue()).isEqualTo(99_000);
        assertThat(body(response).path("discountPercent").isNull()).isTrue();
        assertThat(orderOf(holder)).isNotEqualTo(orderOf(body(response)));
        assertThat(orders(owner)).isEqualTo(2);
    }

    @Test
    void anOrderPaidAfterItsDiscountWasSpentStillGrantsTheMonth() throws Exception {
        UUID owner = UUID.randomUUID();
        discount(owner, 20, "PRO");
        String orderId = orderOf(open(owner, "PRO"));
        jdbc.sql("DELETE FROM app_learning.promo_discount WHERE owner_id=?").param(1, owner).update();
        BANK.move(orderId, "CONFIRMED");
        double spent = anomalies("discount_spent");

        assertThat(notify(notification("notification-confirmed.json", orderId, "CONFIRMED")).getStatus()).isEqualTo(200);

        assertThat(status(orderId)).isEqualTo("PAID");
        assertThat(snapshots(owner)).isEqualTo(1);
        assertThat(anomalies("discount_spent")).isEqualTo(spent + 1);
    }

    @Test
    void onlyOneCallerOpensTheBankPaymentOfAnOrder() throws Exception {
        UUID owner = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        String json = "{\"plan\":\"PLUS\",\"period\":\"MONTH\"}";
        BANK.delayMillis = 1_000;
        AtomicReference<MockHttpServletResponse> first = new AtomicReference<>();
        Thread caller = Thread.ofVirtual().start(() -> {
            try {
                first.set(checkout(owner, json, key));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        for (int wait = 0; wait < 100 && BANK.arrivals.get() == 0; wait++) Thread.sleep(50);
        assertThat(BANK.arrivals).hasValue(1);

        MockHttpServletResponse loser = checkout(owner, json, key);

        assertThat(loser.getStatus()).isEqualTo(503);
        assertThat(body(loser).path("code").stringValue(null)).isEqualTo("PAYMENT_PROVIDER_UNAVAILABLE");
        caller.join();
        assertThat(first.get().getStatus()).isEqualTo(201);
        BANK.delayMillis = 0;
        MockHttpServletResponse retried = checkout(owner, json, key);
        assertThat(retried.getStatus()).isEqualTo(201);
        assertThat(retried.getContentAsString()).isEqualTo(first.get().getContentAsString());
        assertThat(BANK.inits).as("one bank payment for the order").hasSize(1);
    }

    @Test
    void aClaimOfAnInitThatNeverFinishedExpiresAfterThirtySeconds() throws Exception {
        UUID owner = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        String json = "{\"plan\":\"PLUS\",\"period\":\"MONTH\"}";
        BANK.breakInit = true;
        assertThat(checkout(owner, json, key).getStatus()).isEqualTo(503);
        BANK.breakInit = false;
        UUID orderId = UUID.fromString(onlyOrder(owner));
        assertThat(repository.claimInit(orderId, clock.now(), clock.now().minusSeconds(30))).as("a lost answer keeps the claim").isFalse();

        assertThat(checkout(owner, json, key).getStatus()).isEqualTo(503);
        assertThat(BANK.inits).hasSize(1);
        clock.advance(Duration.ofSeconds(31));
        MockHttpServletResponse later = checkout(owner, json, key);

        assertThat(later.getStatus()).isEqualTo(201);
        assertThat(BANK.inits).hasSize(2);
        assertThat(status(orderId.toString())).isEqualTo("PENDING");
    }

    @Test
    void aReplayedNotificationAsksTheBankAtMostOncePerRefreshInterval() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = orderOf(open(owner, "PLUS"));
        ObjectNode replay = notification("notification-authorized.json", orderId, "AUTHORIZED");

        assertThat(notify(replay).getContentAsString()).isEqualTo("OK");
        assertThat(BANK.getStates).as("the first delivery").hasSize(1);
        assertThat(notify(replay).getContentAsString()).isEqualTo("OK");
        assertThat(BANK.getStates).hasSize(2);
        for (int index = 0; index < 5; index++) assertThat(notify(replay).getContentAsString()).isEqualTo("OK");
        assertThat(BANK.getStates).as("replays inside the interval cost nothing").hasSize(2);

        clock.advance(Duration.ofSeconds(11));
        assertThat(notify(replay).getContentAsString()).isEqualTo("OK");
        assertThat(BANK.getStates).hasSize(3);
        assertThat(status(orderId)).isEqualTo("PENDING");
    }
}
