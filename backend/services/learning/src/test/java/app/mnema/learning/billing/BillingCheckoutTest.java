package app.mnema.learning.billing;

import app.mnema.learning.usage.Plan;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code POST /api/billing/checkout}: the price, the idempotent order, the bank's Init and every refusal of the contract. */
class BillingCheckoutTest extends BillingIntegrationTest {
    @Test
    void aCheckoutCreatesTheOrderAsksTheBankOnceAndReturnsThePendingOrder() throws Exception {
        UUID owner = UUID.randomUUID();

        MockHttpServletResponse response = checkout(owner, "PLUS");

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
        JsonNode order = body(response);
        String orderId = order.path("orderId").stringValue(null);
        assertThat(order.path("plan").stringValue(null)).isEqualTo("PLUS");
        assertThat(order.path("period").stringValue(null)).isEqualTo("MONTH");
        assertThat(order.path("status").stringValue(null)).isEqualTo("PENDING");
        assertThat(order.path("amountKopecks").longValue()).isEqualTo(44_900);
        assertThat(order.path("listPriceKopecks").longValue()).isEqualTo(44_900);
        assertThat(order.path("discountPercent").isNull()).isTrue();
        assertThat(order.path("paymentUrl").stringValue(null)).isEqualTo("https://pay.tbank-online.com/So6mQeQB");
        assertThat(order.path("expiresAt").stringValue(null)).isEqualTo("2026-10-09T10:00:00Z");
        assertThat(order.path("createdAt").stringValue(null)).isEqualTo("2026-10-09T09:00:00Z");
        assertThat(order.path("paidAt").isNull() && order.path("periodStart").isNull() && order.path("periodEnd").isNull()).isTrue();
        assertThat(order.propertyNames()).containsExactlyInAnyOrderElementsOf(
                BillingFixtures.read("contracts/billing/billing.json").path("schemas").path("Order").propertyNames());

        assertThat(status(orderId)).isEqualTo("PENDING");
        assertThat(column(orderId, "provider_status")).isEqualTo("NEW");
        assertThat(column(orderId, "payment_id")).isEqualTo(BANK.paymentOf(orderId).paymentId);
        assertThat(events(orderId)).containsExactly("INIT:NEW:PENDING");
        assertThat(BANK.inits).hasSize(1);
        assertThat(BANK.badSignatures).hasValue(0);
        JsonNode init = BANK.inits.getFirst();
        assertThat(init.path("Amount").longValue()).isEqualTo(44_900);
        assertThat(init.path("OrderId").stringValue(null)).isEqualTo(orderId);
        assertThat(init.path("NotificationURL").stringValue(null)).isEqualTo("https://mnema.test/api/billing/tbank/notifications");
        assertThat(init.path("SuccessURL").stringValue(null)).isEqualTo("https://mnema.test/plans/payment/" + orderId);
        assertThat(init.path("RedirectDueDate").stringValue(null)).isEqualTo("2026-10-09T13:00:00+03:00");
        assertThat(init.has("Receipt")).isFalse();
    }

    @Test
    void aRetryWithTheSameKeyReturnsTheSameOrderWithoutAskingTheBankAgain() throws Exception {
        UUID owner = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        String json = "{\"plan\":\"PLUS\",\"period\":\"MONTH\"}";

        MockHttpServletResponse first = checkout(owner, json, key);
        MockHttpServletResponse second = checkout(owner, json, key);

        assertThat(second.getStatus()).isEqualTo(201);
        assertThat(second.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(BANK.inits).hasSize(1);
        assertThat(orders(owner)).isEqualTo(1);
    }

    @Test
    void theSameKeyForAnotherBodyOrAnotherAccountIsAConflict() throws Exception {
        UUID owner = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        assertThat(checkout(owner, "{\"plan\":\"PLUS\",\"period\":\"MONTH\"}", key).getStatus()).isEqualTo(201);

        MockHttpServletResponse other = checkout(owner, "{\"plan\":\"PRO\",\"period\":\"MONTH\"}", key);

        assertThat(other.getStatus()).isEqualTo(409);
        assertThat(body(other).path("code").stringValue(null)).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(checkout(UUID.randomUUID(), "{\"plan\":\"PLUS\",\"period\":\"MONTH\"}", key).getStatus()).isEqualTo(409);
        assertThat(orders(owner)).isEqualTo(1);
    }

    @Test
    void anOpenOrderOfTheSamePurchaseIsReusedAndAnotherPlanGetsItsOwn() throws Exception {
        UUID owner = UUID.randomUUID();
        JsonNode first = open(owner, "PLUS");

        JsonNode again = open(owner, "PLUS");
        JsonNode pro = open(owner, "PRO");

        assertThat(again.path("orderId")).isEqualTo(first.path("orderId"));
        assertThat(again.path("paymentUrl")).isEqualTo(first.path("paymentUrl"));
        assertThat(pro.path("orderId")).isNotEqualTo(first.path("orderId"));
        assertThat(pro.path("amountKopecks").longValue()).isEqualTo(99_000);
        assertThat(orders(owner)).isEqualTo(2);
        assertThat(BANK.inits).hasSize(2);

        // Another account never shares an order.
        assertThat(open(UUID.randomUUID(), "PLUS").path("orderId")).isNotEqualTo(first.path("orderId"));
    }

    @Test
    void anOrderWhoseLinkIsAboutToExpireIsNotReused() throws Exception {
        UUID owner = UUID.randomUUID();
        JsonNode first = open(owner, "PLUS");
        clock.advance(Duration.ofMinutes(56));

        JsonNode second = open(owner, "PLUS");

        assertThat(second.path("orderId")).isNotEqualTo(first.path("orderId"));
        assertThat(second.path("expiresAt").stringValue(null)).isEqualTo("2026-10-09T10:56:00Z");
    }

    @Test
    void thePricesAreTheCatalogsAndAMatchingPromoDiscountIsAppliedInWholeRublesHalfUp() throws Exception {
        UUID pro = UUID.randomUUID();
        UUID proCode = discount(pro, 20, "PRO");
        JsonNode discounted = open(pro, "PRO");
        assertThat(discounted.path("amountKopecks").longValue()).isEqualTo(79_200);
        assertThat(discounted.path("listPriceKopecks").longValue()).isEqualTo(99_000);
        assertThat(discounted.path("discountPercent").intValue()).isEqualTo(20);
        assertThat(BANK.lastInit().path("Amount").longValue()).isEqualTo(79_200);
        assertThat(column(discounted.path("orderId").stringValue(null), "discount_code_id")).isEqualTo(proCode.toString());
        assertThat(discountRows(pro)).as("not consumed before the payment").isEqualTo(1);

        UUID rounding = UUID.randomUUID();
        discount(rounding, 15, null);
        assertThat(open(rounding, "PLUS").path("amountKopecks").longValue()).as("449 rub x 85 % = 381.65 rub").isEqualTo(38_200);

        assertThat(BillingService.discounted(449, 15)).isEqualTo(382);
        assertThat(BillingService.discounted(990, 20)).isEqualTo(792);
        assertThat(BillingService.discounted(449, 90)).isEqualTo(45);
    }

    @Test
    void aDiscountForAnotherPlanAndAnExpiredOneChangeNothing() throws Exception {
        UUID owner = UUID.randomUUID();
        discount(owner, 30, "PLUS");

        JsonNode pro = open(owner, "PRO");

        assertThat(pro.path("amountKopecks").longValue()).isEqualTo(99_000);
        assertThat(pro.path("discountPercent").isNull()).isTrue();

        UUID expired = UUID.randomUUID();
        discount(expired, 30, null);
        clock.set("2027-07-01T00:00:00Z");
        assertThat(open(expired, "PLUS").path("amountKopecks").longValue()).isEqualTo(44_900);
    }

    @Test
    void aPlanBelowTheCurrentOneIsRefusedAndTheSameOrAHigherOneIsNot() throws Exception {
        UUID owner = UUID.randomUUID();
        accept(owner, Plan.PRO, "promo:" + UUID.randomUUID(), "2026-10-01T00:00:00Z", "2026-11-01T00:00:00Z");

        MockHttpServletResponse below = checkout(owner, "PLUS");

        assertThat(below.getStatus()).isEqualTo(409);
        assertThat(body(below).path("code").stringValue(null)).isEqualTo("BILLING_PLAN_BELOW_CURRENT");
        assertThat(orders(owner)).isZero();
        assertThat(BANK.inits).isEmpty();
        assertThat(checkout(owner, "PRO").getStatus()).isEqualTo(201);

        UUID plus = UUID.randomUUID();
        accept(plus, Plan.PLUS, "promo:" + UUID.randomUUID(), "2026-10-01T00:00:00Z", "2026-11-01T00:00:00Z");
        assertThat(checkout(plus, "PLUS").getStatus()).isEqualTo(201);
        assertThat(checkout(plus, "PRO").getStatus()).isEqualTo(201);
    }

    @Test
    void invalidRequestsAreRefusedBeforeAnyOrderExists() throws Exception {
        UUID owner = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        List<String> bodies = new ArrayList<>(List.of("{\"plan\":\"FREE\",\"period\":\"MONTH\"}", "{\"plan\":\"MAX\",\"period\":\"MONTH\"}",
                "{\"plan\":\"plus\",\"period\":\"MONTH\"}", "{\"plan\":\"PLUS\",\"period\":\"YEAR\"}", "{\"plan\":\"PLUS\"}", "{\"period\":\"MONTH\"}",
                "{\"plan\":\"PLUS\",\"period\":\"MONTH\",\"amount\":1}", "{\"plan\":1,\"period\":\"MONTH\"}", "{\"plan\":\"PLUS\",\"period\":null}", "[]",
                "not json", "{}", "{\"plan\":\"PLUS\",\"plan\":\"PRO\",\"period\":\"MONTH\"}"));
        for (String json : bodies) {
            assertThat(checkout(owner, json, key).getStatus()).as(json).isEqualTo(400);
        }
        for (String badKey : new String[] {null, "not-a-uuid", "00000000-0000-0000-0000-000000000000", "0199c7a2-3b4e-1c1d-9a2b-5e6f7a8b9c0d"}) {
            MockHttpServletResponse response = checkout(owner, "{\"plan\":\"PLUS\",\"period\":\"MONTH\"}", badKey);
            assertThat(response.getStatus()).as(String.valueOf(badKey)).isEqualTo(400);
            assertThat(body(response).path("reason").stringValue(null)).isEqualTo("idempotency_key_required");
        }
        assertThat(orders(owner)).isZero();
        assertThat(BANK.inits).isEmpty();
    }

    @Test
    void theElevenerthOrderOfAnHourIsRateLimitedWithRetryAfter() throws Exception {
        UUID owner = UUID.randomUUID();
        for (int index = 0; index < 10; index++) {
            jdbc.sql("INSERT INTO app_learning.billing_order(order_id,owner_id,plan,period,status,amount_kopecks,list_price_kopecks,expires_at,created_at,updated_at) "
                            + "VALUES (?,?,'PLUS','MONTH','FAILED',44900,44900,?,?,?)").param(1, UUID.randomUUID()).param(2, owner)
                    .param(3, Timestamp.from(clock.now().plus(Duration.ofMinutes(30)))).param(4, Timestamp.from(clock.now().minus(Duration.ofMinutes(10 + index))))
                    .param(5, Timestamp.from(clock.now())).update();
        }

        MockHttpServletResponse limited = checkout(owner, "PLUS");

        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(body(limited).path("code").stringValue(null)).isEqualTo("RATE_LIMITED");
        // The oldest order is 19 minutes old: the next place frees in about 41 minutes.
        assertThat(Long.parseLong(limited.getHeader("Retry-After"))).isBetween(2_400L, 2_500L);
        assertThat(orders(owner)).isEqualTo(10);
        assertThat(BANK.inits).isEmpty();

        clock.advance(Duration.ofMinutes(45));
        assertThat(checkout(owner, "PLUS").getStatus()).isEqualTo(201);
    }

    @Test
    void aBankThatRefusesInitFailsTheOrderAndTheNextCheckoutOpensANewOne() throws Exception {
        UUID owner = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        String json = "{\"plan\":\"PLUS\",\"period\":\"MONTH\"}";
        BANK.refuseInit = true;

        MockHttpServletResponse failed = checkout(owner, json, key);

        assertThat(failed.getStatus()).isEqualTo(503);
        assertThat(body(failed).path("code").stringValue(null)).isEqualTo("PAYMENT_PROVIDER_UNAVAILABLE");
        assertThat(failed.getContentAsString()).doesNotContain("204").doesNotContain("токен");
        String orderId = onlyOrder(owner);
        assertThat(status(orderId)).as("the bank would refuse the same OrderId again").isEqualTo("FAILED");
        assertThat(column(orderId, "failure_reason")).isEqualTo("INIT_REFUSED");
        assertThat(column(orderId, "payment_id")).isNull();
        assertThat(events(orderId)).containsExactly("INIT:-:FAILED");

        BANK.refuseInit = false;
        MockHttpServletResponse replayed = checkout(owner, json, key);
        assertThat(replayed.getStatus()).as("the same key replays the failure without asking the bank").isEqualTo(503);
        assertThat(BANK.inits).hasSize(1);

        MockHttpServletResponse next = checkout(owner, json, UUID.randomUUID().toString());

        assertThat(next.getStatus()).isEqualTo(201);
        assertThat(body(next).path("orderId").stringValue(null)).isNotEqualTo(orderId);
        assertThat(body(next).path("status").stringValue(null)).isEqualTo("PENDING");
        assertThat(BANK.lastInit().path("OrderId").stringValue(null)).isEqualTo(body(next).path("orderId").stringValue(null));
        assertThat(BANK.inits).hasSize(2);
        assertThat(orders(owner)).isEqualTo(2);
    }

    @Test
    void anInitWhoseAnswerIsLostKeepsTheOrderForTheSameKeyAndItsNotification() throws Exception {
        UUID owner = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        String json = "{\"plan\":\"PLUS\",\"period\":\"MONTH\"}";
        BANK.breakInit = true;

        assertThat(checkout(owner, json, key).getStatus()).isEqualTo(503);

        String orderId = onlyOrder(owner);
        assertThat(status(orderId)).as("the bank may have opened a payment").isEqualTo("CREATED");
        assertThat(events(orderId)).containsExactly("INIT:-:HTTP_STATUS");
        // The payment the lost answer opened is paid and notified: the notification adopts it.
        BANK.move(orderId, "CONFIRMED");
        assertThat(notify(notification("notification-confirmed.json", orderId, "CONFIRMED")).getContentAsString()).isEqualTo("OK");
        assertThat(status(orderId)).isEqualTo("PAID");
        assertThat(column(orderId, "payment_id")).isEqualTo(BANK.paymentOf(orderId).paymentId);
        assertThat(snapshots(owner)).isEqualTo(1);
    }

    @Test
    void aPaymentLinkOnAnUnknownHostIsNeverHandedToTheBrowser() throws Exception {
        UUID owner = UUID.randomUUID();
        BANK.paymentUrl = "https://evil.example/pay";

        MockHttpServletResponse response = checkout(owner, "PLUS");

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).doesNotContain("evil.example");
        assertThat(jdbc.sql("SELECT status FROM app_learning.billing_order WHERE owner_id=:owner").param("owner", owner).query(String.class).single())
                .isEqualTo("CREATED");
    }

    @Test
    void anInitThatCompletesAfterAnotherPathAlreadyMovedTheOrderChangesNothing() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = open(owner, "PLUS").path("orderId").stringValue(null);
        String paymentId = column(orderId, "payment_id");
        Instant before = instant(orderId, "updated_at");

        applier.initialized(UUID.fromString(orderId), new TBankClient.InitResult("9999999999", "https://pay.tbank-online.com/other", "NEW"));

        assertThat(column(orderId, "payment_id")).isEqualTo(paymentId);
        assertThat(instant(orderId, "updated_at")).isEqualTo(before);
        assertThat(events(orderId)).containsExactly("INIT:NEW:PENDING", "INIT:NEW:SKIPPED");
        applier.initialized(UUID.randomUUID(), new TBankClient.InitResult("9999999998", "https://pay.tbank-online.com/x", "NEW"));
    }
}
