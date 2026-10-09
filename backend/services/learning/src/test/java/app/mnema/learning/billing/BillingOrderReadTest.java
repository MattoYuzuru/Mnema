package app.mnema.learning.billing;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code GET /api/billing/orders/{orderId}}: owner-only, and a read of a pending order asks the bank at most once per interval. */
class BillingOrderReadTest extends BillingIntegrationTest {
    @Test
    void anOwnerReadsTheOrderAndEverybodyElseSeesNothing() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = open(owner, "PLUS").path("orderId").stringValue(null);

        MockHttpServletResponse mine = read(owner, orderId);

        assertThat(mine.getStatus()).isEqualTo(200);
        assertThat(mine.getHeader("Cache-Control")).isEqualTo("private, no-store");
        assertThat(body(mine).path("orderId").stringValue(null)).isEqualTo(orderId);
        assertThat(body(mine).path("status").stringValue(null)).isEqualTo("PENDING");
        assertThat(body(mine).path("paymentUrl").stringValue(null)).startsWith("https://pay.tbank-online.com/");
        MockHttpServletResponse theirs = read(UUID.randomUUID(), orderId);
        MockHttpServletResponse unknown = read(owner, UUID.randomUUID().toString());
        assertThat(theirs.getStatus()).isEqualTo(404);
        assertThat(unknown.getStatus()).isEqualTo(404);
        assertThat(theirs.getContentAsString()).isEqualTo(unknown.getContentAsString().replace(body(unknown).path("instance").stringValue(null),
                body(theirs).path("instance").stringValue(null)));
        assertThat(body(theirs).path("code").stringValue(null)).isEqualTo("RESOURCE_NOT_FOUND");
        for (String bad : new String[] {"not-a-uuid", "1", "0199c7a2-3b4e-7c1d-9a2b-5e6f7a8b9c0d-x", "1-1-1-1-1"}) {
            assertThat(read(owner, bad).getStatus()).as(bad).isEqualTo(404);
        }
    }

    @Test
    void aPendingOrderAsksTheBankAtMostOncePerIntervalNoMatterWhoPolls() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = open(owner, "PLUS").path("orderId").stringValue(null);

        for (int index = 0; index < 5; index++) assertThat(read(owner, orderId).getStatus()).isEqualTo(200);
        assertThat(BANK.getStates).hasSize(1);

        clock.advance(Duration.ofSeconds(9));
        read(owner, orderId);
        assertThat(BANK.getStates).hasSize(1);
        clock.advance(Duration.ofSeconds(2));
        read(owner, orderId);
        assertThat(BANK.getStates).hasSize(2);
        assertThat(BANK.badSignatures).hasValue(0);
    }

    @Test
    void readsRacingForTheSameWindowAskTheBankOnce() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = open(owner, "PLUS").path("orderId").stringValue(null);
        List<Callable<Integer>> reads = new ArrayList<>();
        for (int index = 0; index < 8; index++) reads.add(() -> controller.order(jwt(owner), orderId).getStatusCode().value());

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<Integer> read : pool.invokeAll(reads)) assertThat(read.get()).isEqualTo(200);
        }

        assertThat(BANK.getStates).hasSize(1);
    }

    @Test
    void theReturnPageSeesThePaymentTheBankConfirmedAndNeverTrustsItsOwnQueryString() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = open(owner, "PLUS").path("orderId").stringValue(null);

        MockHttpServletResponse early = as(owner, controller).perform(MockMvcRequestBuilders.get("/billing/orders/" + orderId
                + "?Success=true&Status=CONFIRMED&PaymentId=1&Token=abc&OrderId=" + orderId)).andReturn().getResponse();
        assertThat(body(early).path("status").stringValue(null)).isEqualTo("PENDING");
        assertThat(snapshots(owner)).isZero();

        BANK.move(orderId, "CONFIRMED");
        clock.advance(Duration.ofSeconds(11));
        JsonNode paid = body(read(owner, orderId));

        assertThat(paid.path("status").stringValue(null)).isEqualTo("PAID");
        assertThat(paid.path("paymentUrl").isNull()).isTrue();
        assertThat(snapshots(owner)).isEqualTo(1);
        assertThat(events(orderId)).contains("GET_STATE:CONFIRMED:PAID");
        int calls = BANK.getStates.size();
        clock.advance(Duration.ofMinutes(5));
        read(owner, orderId);
        assertThat(BANK.getStates).as("a paid order is final").hasSize(calls);
    }

    @Test
    void aBankThatDoesNotAnswerLeavesTheCurrentStateInPlace() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = open(owner, "PLUS").path("orderId").stringValue(null);
        BANK.breakGetState = true;

        MockHttpServletResponse response = read(owner, orderId);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body(response).path("status").stringValue(null)).isEqualTo("PENDING");
        assertThat(BANK.getStates).hasSize(1);
        assertThat(status(orderId)).isEqualTo("PENDING");
    }

    @Test
    void theLinkIsHiddenOnceItsTimeIsOverAndAnOrderWithoutAPaymentIsNeverAskedAbout() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = open(owner, "PLUS").path("orderId").stringValue(null);
        clock.advance(Duration.ofMinutes(61));

        JsonNode expired = body(read(owner, orderId));

        assertThat(expired.path("status").stringValue(null)).isEqualTo("PENDING");
        assertThat(expired.path("paymentUrl").isNull()).isTrue();

        UUID other = UUID.randomUUID();
        BANK.refuseInit = true;
        assertThat(checkout(other, "PRO").getStatus()).isEqualTo(503);
        String uninitialized = jdbc.sql("SELECT order_id::text FROM app_learning.billing_order WHERE owner_id=:owner").param("owner", other)
                .query(String.class).single();
        int calls = BANK.getStates.size();
        JsonNode created = body(read(other, uninitialized));
        assertThat(created.path("status").stringValue(null)).as("CREATED is reported as PENDING").isEqualTo("PENDING");
        assertThat(created.path("paymentUrl").isNull()).isTrue();
        assertThat(BANK.getStates).hasSize(calls);
    }
}
