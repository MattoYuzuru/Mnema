package app.mnema.learning.billing;

import app.mnema.learning.billing.PaymentStateApplier.Outcome;
import app.mnema.learning.billing.PaymentStateApplier.Trigger;
import app.mnema.learning.usage.Entitlement;
import app.mnema.learning.usage.Plan;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** The signed notification and the state machine behind it: only the bank's own answer pays, everything repeats safely. */
class BillingNotificationTest extends BillingIntegrationTest {
    private static final Instant PAID_AT = Instant.parse("2026-10-09T09:00:00Z");

    private static final java.util.concurrent.atomic.AtomicLong OTHER_PAYMENTS = new java.util.concurrent.atomic.AtomicLong(7_000_000_000L);

    /** A bank payment id of its own: payment ids are unique across the shared test database. */
    private static String otherPayment() {
        return Long.toString(OTHER_PAYMENTS.incrementAndGet());
    }

    private String pendingOrder(UUID owner, String plan) throws Exception {
        return open(owner, plan).path("orderId").stringValue(null);
    }

    private void assertOk(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("text/plain");
        assertThat(response.getContentAsString()).isEqualTo("OK");
    }

    @Test
    void aConfirmedPaymentIsPaidOnceAndGrantsOneCalendarMonthOfThePlan() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = pendingOrder(owner, "PLUS");
        BANK.move(orderId, "CONFIRMED");

        MockHttpServletResponse response = notify(notification("notification-confirmed.json", orderId, "CONFIRMED"));

        assertOk(response);
        assertThat(status(orderId)).isEqualTo("PAID");
        assertThat(column(orderId, "snapshot_id")).isEqualTo("billing:" + orderId);
        assertThat(instant(orderId, "paid_at")).isEqualTo(PAID_AT);
        assertThat(instant(orderId, "period_start")).isEqualTo(PAID_AT);
        assertThat(instant(orderId, "period_end")).isEqualTo(Instant.parse("2026-11-09T09:00:00Z"));
        assertThat(snapshots(owner)).isEqualTo(1);
        Entitlement entitlement = entitlements.current(owner, clock.now());
        assertThat(entitlement.plan()).isEqualTo(Plan.PLUS);
        assertThat(entitlement.source()).isEqualTo(Entitlement.Source.BILLING);
        assertThat(entitlement.validUntil()).isEqualTo(Instant.parse("2026-11-09T09:00:00Z"));
        assertThat(BANK.getStates).hasSize(1);
        assertThat(BANK.badSignatures).hasValue(0);
        assertThat(events(orderId)).containsExactly("INIT:NEW:PENDING", "NOTIFICATION:CONFIRMED:RECEIVED", "GET_STATE:CONFIRMED:PAID");
        assertThat(body(read(owner, orderId)).path("status").stringValue(null)).isEqualTo("PAID");
        assertThat(body(read(owner, orderId)).path("paymentUrl").isNull()).isTrue();
        assertThat(body(read(owner, orderId)).path("periodEnd").stringValue(null)).isEqualTo("2026-11-09T09:00:00Z");
    }

    @Test
    void aRepeatedNotificationAnswersOkAndChangesNothing() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = pendingOrder(owner, "PLUS");
        BANK.move(orderId, "CONFIRMED");
        ObjectNode confirmed = notification("notification-confirmed.json", orderId, "CONFIRMED");
        assertOk(notify(confirmed));
        long version = Long.parseLong(column(orderId, "row_version"));

        assertOk(notify(confirmed));
        assertOk(notify(confirmed));

        assertThat(status(orderId)).isEqualTo("PAID");
        assertThat(snapshots(owner)).isEqualTo(1);
        assertThat(Long.parseLong(column(orderId, "row_version"))).isEqualTo(version);
        assertThat(BANK.getStates).as("a paid order is not asked about again").hasSize(1);
        assertThat(count("SELECT count(*) FROM app_learning.billing_event WHERE order_id=CAST(? AS uuid) AND source='NOTIFICATION'", orderId)).isEqualTo(1);
    }

    @Test
    void anAuthorizedNotificationAfterTheConfirmedOneKeepsTheOrderPaid() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = pendingOrder(owner, "PLUS");
        BANK.move(orderId, "CONFIRMED");
        assertOk(notify(notification("notification-confirmed.json", orderId, "CONFIRMED")));

        assertOk(notify(notification("notification-authorized.json", orderId, "AUTHORIZED")));

        assertThat(status(orderId)).isEqualTo("PAID");
        assertThat(snapshots(owner)).isEqualTo(1);
        assertThat(BANK.getStates).hasSize(1);
    }

    @Test
    void anAuthorizedPaymentStaysPendingUntilTheBankConfirmsIt() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = pendingOrder(owner, "PLUS");
        BANK.move(orderId, "AUTHORIZED");

        assertOk(notify(notification("notification-authorized.json", orderId, "AUTHORIZED")));

        assertThat(status(orderId)).isEqualTo("PENDING");
        assertThat(column(orderId, "provider_status")).isEqualTo("AUTHORIZED");
        assertThat(snapshots(owner)).isZero();
        BANK.move(orderId, "CONFIRMED");
        assertOk(notify(notification("notification-confirmed.json", orderId, "CONFIRMED")));
        assertThat(status(orderId)).isEqualTo("PAID");
    }

    @Test
    void theNotificationIsATriggerOnlyTheBanksGetStateDecides() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = pendingOrder(owner, "PLUS");
        // A correctly signed CONFIRMED notification while the bank itself still says NEW grants nothing.
        assertOk(notify(notification("notification-confirmed.json", orderId, "CONFIRMED")));

        assertThat(status(orderId)).isEqualTo("PENDING");
        assertThat(snapshots(owner)).isZero();
        assertThat(BANK.getStates).hasSize(1);
    }

    @Test
    void aRejectedPaymentFailsTheOrderWithoutAGrant() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = pendingOrder(owner, "PRO");
        BANK.move(orderId, "REJECTED");

        assertOk(notify(notification("notification-rejected.json", orderId, "REJECTED")));

        assertThat(status(orderId)).isEqualTo("FAILED");
        assertThat(column(orderId, "failure_reason")).isEqualTo("REJECTED");
        assertThat(snapshots(owner)).isZero();
        assertThat(body(read(owner, orderId)).path("status").stringValue(null)).isEqualTo("FAILED");
        assertThat(body(read(owner, orderId)).path("paymentUrl").isNull()).isTrue();
    }

    @Test
    void everyFailureStatusFailsAnOpenOrder() throws Exception {
        for (String failure : new String[] {"AUTH_FAIL", "CANCELED", "DEADLINE_EXPIRED", "ATTEMPTS_EXPIRED", "REVERSED", "PARTIAL_REVERSED", "REFUNDED"}) {
            String orderId = pendingOrder(UUID.randomUUID(), "PLUS");
            BANK.move(orderId, failure);

            assertOk(notify(notification("notification-rejected.json", orderId, failure)));

            assertThat(status(orderId)).as(failure).isEqualTo("FAILED");
            assertThat(column(orderId, "failure_reason")).isEqualTo(failure);
        }
    }

    @Test
    void aPartlyRefundedPaymentThatWasNeverGrantedGoesToReview() throws Exception {
        for (String before : new String[] {"PENDING", "FAILED"}) {
            UUID owner = UUID.randomUUID();
            String orderId = pendingOrder(owner, "PLUS");
            if (before.equals("FAILED")) {
                BANK.move(orderId, "REJECTED");
                assertOk(notify(notification("notification-rejected.json", orderId, "REJECTED")));
            }
            assertThat(status(orderId)).isEqualTo(before);
            BANK.move(orderId, "PARTIAL_REFUNDED");
            double counted = anomalies("partial_refund");

            assertOk(notify(notification("notification-refunded.json", orderId, "PARTIAL_REFUNDED")));

            assertThat(status(orderId)).as(before).isEqualTo("REVIEW");
            assertThat(column(orderId, "failure_reason")).isEqualTo("PARTIAL_REFUND");
            assertThat(snapshots(owner)).isZero();
            assertThat(anomalies("partial_refund")).isEqualTo(counted + 1);
        }
    }

    @Test
    void aRefundAfterPaymentMarksTheOrderRefundedAndKeepsTheEntitlement() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = pendingOrder(owner, "PLUS");
        BANK.move(orderId, "CONFIRMED");
        assertOk(notify(notification("notification-confirmed.json", orderId, "CONFIRMED")));

        BANK.move(orderId, "REFUNDED");
        assertOk(notify(notification("notification-refunded.json", orderId, "REFUNDED")));

        assertThat(status(orderId)).isEqualTo("REFUNDED");
        assertThat(snapshots(owner)).isEqualTo(1);
        assertThat(entitlements.current(owner, clock.now()).plan()).isEqualTo(Plan.PLUS);
        assertThat(body(read(owner, orderId)).path("status").stringValue(null)).isEqualTo("REFUNDED");
        // A later REJECTED or a repeated CONFIRMED neither re-grants nor changes a refunded order.
        BANK.move(orderId, "CONFIRMED");
        assertOk(notify(notification("notification-confirmed.json", orderId, "CONFIRMED")));
        assertThat(status(orderId)).isEqualTo("REFUNDED");
        assertThat(snapshots(owner)).isEqualTo(1);
    }

    @Test
    void aPaidOrderIsNotTakenBackByALaterRejection() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = pendingOrder(owner, "PLUS");
        BANK.move(orderId, "CONFIRMED");
        assertOk(notify(notification("notification-confirmed.json", orderId, "CONFIRMED")));
        BANK.move(orderId, "REJECTED");

        assertThat(applier.apply(UUID.fromString(orderId), bank.getState(BANK.paymentOf(orderId).paymentId), Trigger.GET_STATE)).isEqualTo(Outcome.UNCHANGED);

        assertThat(status(orderId)).isEqualTo("PAID");
        assertThat(snapshots(owner)).isEqualTo(1);
    }

    @Test
    void aConfirmedAmountThatIsNotTheOrdersGoesToReviewAndGrantsNothing() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID code = discount(owner, 20, "PRO");
        String orderId = pendingOrder(owner, "PRO");
        BANK.move(orderId, "CONFIRMED");
        BANK.paymentOf(orderId).answeredAmount = 100L;
        double counted = anomalies("amount_mismatch");

        assertOk(notify(notification("notification-amount-mismatch.json", orderId, "CONFIRMED")));

        assertThat(status(orderId)).isEqualTo("REVIEW");
        assertThat(anomalies("amount_mismatch")).isEqualTo(counted + 1);
        assertThat(column(orderId, "failure_reason")).isEqualTo("AMOUNT_MISMATCH");
        assertThat(snapshots(owner)).isZero();
        assertThat(discountRows(owner)).as("the discount is spent by a payment, not by a mismatch").isEqualTo(1);
        assertThat(entitlements.current(owner, clock.now()).plan()).isEqualTo(Plan.FREE);
        assertThat(code).isNotNull();
        // The order stays in review whatever the bank says next.
        BANK.paymentOf(orderId).answeredAmount = null;
        assertOk(notify(notification("notification-confirmed.json", orderId, "CONFIRMED")));
        assertThat(status(orderId)).isEqualTo("REVIEW");
        assertThat(snapshots(owner)).isZero();
    }

    @Test
    void thePromoDiscountOfAPaidOrderIsConsumedButAStrongerOneRedeemedMeanwhileSurvives() throws Exception {
        UUID owner = UUID.randomUUID();
        discount(owner, 20, "PRO");
        String orderId = pendingOrder(owner, "PRO");
        BANK.move(orderId, "CONFIRMED");
        assertOk(notify(notification("notification-confirmed.json", orderId, "CONFIRMED")));
        assertThat(discountRows(owner)).isZero();
        assertThat(snapshots(owner)).isEqualTo(1);

        UUID other = UUID.randomUUID();
        UUID first = discount(other, 20, "PRO");
        String second = pendingOrder(other, "PRO");
        jdbc.sql("DELETE FROM app_learning.promo_discount WHERE owner_id=?").param(1, other).update();
        discount(other, 40, "PRO");
        BANK.move(second, "CONFIRMED");
        assertOk(notify(notification("notification-confirmed.json", second, "CONFIRMED")));
        assertThat(status(second)).isEqualTo("PAID");
        assertThat(discountRows(other)).as("the 40 % code of another id is not the one this order used").isEqualTo(1);
        assertThat(discounts.consume(other, first)).isFalse();
    }

    @Test
    void aSecondPaidOrderOfThePlanStartsWhereThePreviousMonthEnds() throws Exception {
        UUID owner = UUID.randomUUID();
        String first = pendingOrder(owner, "PLUS");
        BANK.move(first, "CONFIRMED");
        assertOk(notify(notification("notification-confirmed.json", first, "CONFIRMED")));

        clock.advance(Duration.ofDays(1));
        String second = pendingOrder(owner, "PLUS");
        assertThat(second).isNotEqualTo(first);
        BANK.move(second, "CONFIRMED");
        assertOk(notify(notification("notification-confirmed.json", second, "CONFIRMED")));

        assertThat(instant(second, "period_start")).isEqualTo(instant(first, "period_end")).isEqualTo(Instant.parse("2026-11-09T09:00:00Z"));
        assertThat(instant(second, "paid_at")).isEqualTo(Instant.parse("2026-10-10T09:00:00Z"));
        assertThat(instant(second, "period_end")).isEqualTo(Instant.parse("2026-12-09T09:00:00Z"));
        assertThat(snapshots(owner)).isEqualTo(2);
        assertThat(entitlements.current(owner, clock.now()).validUntil()).as("the second month has not started yet").isEqualTo(Instant.parse("2026-11-09T09:00:00Z"));
        assertThat(entitlements.current(owner, Instant.parse("2026-11-10T00:00:00Z")).validUntil()).isEqualTo(Instant.parse("2026-12-09T09:00:00Z"));

        // After both months a payment starts now.
        clock.set("2027-02-01T09:00:00Z");
        String third = pendingOrder(owner, "PLUS");
        BANK.move(third, "CONFIRMED");
        assertOk(notify(notification("notification-confirmed.json", third, "CONFIRMED")));
        assertThat(instant(third, "period_start")).isEqualTo(Instant.parse("2027-02-01T09:00:00Z"));
    }

    @Test
    void aPaymentOfAnotherPlanStartsNow() throws Exception {
        UUID owner = UUID.randomUUID();
        String plus = pendingOrder(owner, "PLUS");
        BANK.move(plus, "CONFIRMED");
        assertOk(notify(notification("notification-confirmed.json", plus, "CONFIRMED")));
        String pro = pendingOrder(owner, "PRO");
        BANK.move(pro, "CONFIRMED");

        assertOk(notify(notification("notification-confirmed.json", pro, "CONFIRMED")));

        assertThat(instant(pro, "period_start")).isEqualTo(PAID_AT);
        assertThat(entitlements.current(owner, clock.now()).plan()).isEqualTo(Plan.PRO);
    }

    @Test
    void notificationsRacingForOneOrderGrantExactlyOnce() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = pendingOrder(owner, "PLUS");
        BANK.move(orderId, "CONFIRMED");
        byte[] body = notification("notification-confirmed.json", orderId, "CONFIRMED").toString().getBytes(StandardCharsets.UTF_8);
        List<Callable<TBankNotifications.Reply>> tasks = new ArrayList<>();
        for (int index = 0; index < 8; index++) tasks.add(() -> notifications.handle(body));

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<TBankNotifications.Reply> reply : pool.invokeAll(tasks)) assertThat(reply.get()).isEqualTo(TBankNotifications.Reply.OK);
        }

        assertThat(status(orderId)).isEqualTo("PAID");
        assertThat(snapshots(owner)).isEqualTo(1);
    }

    @Test
    void paymentsOfOneOwnerGrantedAtTheSameTimeQueueTheirMonths() throws Exception {
        UUID owner = UUID.randomUUID();
        String first = pendingOrder(owner, "PLUS");
        // The second order must be a different purchase to stay open next to the first.
        discount(owner, 10, "PLUS");
        String second = pendingOrder(owner, "PLUS");
        assertThat(second).isNotEqualTo(first);
        BANK.move(first, "CONFIRMED");
        BANK.move(second, "CONFIRMED");
        List<Callable<TBankNotifications.Reply>> tasks = new ArrayList<>();
        for (String orderId : new String[] {first, second}) {
            byte[] body = notification("notification-confirmed.json", orderId, "CONFIRMED").toString().getBytes(StandardCharsets.UTF_8);
            tasks.add(() -> notifications.handle(body));
        }

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<TBankNotifications.Reply> reply : pool.invokeAll(tasks)) assertThat(reply.get()).isEqualTo(TBankNotifications.Reply.OK);
        }

        List<Instant> starts = jdbc.sql("SELECT period_start FROM app_learning.billing_order WHERE owner_id=? ORDER BY period_start").param(1, owner)
                .query(java.sql.Timestamp.class).list().stream().map(java.sql.Timestamp::toInstant).toList();
        assertThat(starts).containsExactly(PAID_AT, Instant.parse("2026-11-09T09:00:00Z"));
    }

    @Test
    void aForgedTokenOrAForeignTerminalIsForbiddenAndChangesNothing() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = pendingOrder(owner, "PLUS");
        BANK.move(orderId, "CONFIRMED");
        long events = count("SELECT count(*) FROM app_learning.billing_event");
        long getStates = BANK.getStates.size();

        ObjectNode forged = notification("notification-confirmed.json", orderId, "CONFIRMED");
        forged.put("Token", BillingFixtures.tbank("notification-forged-token.json").path("Token").stringValue(null));
        ObjectNode tampered = notification("notification-confirmed.json", orderId, "CONFIRMED");
        tampered.put("Amount", 1);
        ObjectNode foreign = notification("notification-confirmed.json", orderId, "CONFIRMED");
        foreign.put("TerminalKey", "1700000000999DEMO");
        TBankToken.sign(foreign, BillingFixtures.PASSWORD);
        ObjectNode unsigned = notification("notification-confirmed.json", orderId, "CONFIRMED");
        unsigned.remove("Token");

        for (ObjectNode bad : new ObjectNode[] {forged, tampered, foreign, unsigned, BillingFixtures.tbank("notification-forged-token.json"),
                BillingFixtures.tbank("notification-foreign-terminal.json")}) {
            MockHttpServletResponse response = notify(bad);
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentType()).startsWith("text/plain");
            assertThat(response.getContentAsString()).isEqualTo("ERROR");
        }

        assertThat(status(orderId)).isEqualTo("PENDING");
        assertThat(count("SELECT count(*) FROM app_learning.billing_event")).isEqualTo(events);
        assertThat(BANK.getStates).hasSize((int) getStates);
        assertThat(snapshots(owner)).isZero();
    }

    @Test
    void anUnknownOrAMalformedOrderIsAcknowledgedBecauseARetryCouldNotHelp() throws Exception {
        long events = count("SELECT count(*) FROM app_learning.billing_event");

        assertOk(notify(BillingFixtures.notification("notification-confirmed.json", UUID.randomUUID(), otherPayment(), "CONFIRMED", 44_900)));
        ObjectNode notUuid = BillingFixtures.notification("notification-confirmed.json", UUID.randomUUID(), otherPayment(), "CONFIRMED", 44_900);
        notUuid.put("OrderId", "00000-not-a-uuid");
        assertOk(notify(TBankToken.sign(notUuid, BillingFixtures.PASSWORD)));
        ObjectNode noPayment = BillingFixtures.notification("notification-confirmed.json", UUID.randomUUID(), otherPayment(), "CONFIRMED", 44_900);
        noPayment.remove("PaymentId");
        assertOk(notify(TBankToken.sign(noPayment, BillingFixtures.PASSWORD)));

        assertThat(count("SELECT count(*) FROM app_learning.billing_event")).isEqualTo(events);
        assertThat(BANK.getStates).isEmpty();
    }

    @Test
    void aBankThatCannotBeAskedMakesTheBankRetryAndTheOrderStaysUntouched() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = pendingOrder(owner, "PLUS");
        BANK.move(orderId, "CONFIRMED");
        BANK.breakGetState = true;
        ObjectNode confirmed = notification("notification-confirmed.json", orderId, "CONFIRMED");

        MockHttpServletResponse down = notify(confirmed);

        assertThat(down.getStatus()).isEqualTo(503);
        assertThat(down.getContentAsString()).isEqualTo("ERROR");
        assertThat(status(orderId)).isEqualTo("PENDING");
        assertThat(snapshots(owner)).isZero();

        BANK.breakGetState = false;
        assertOk(notify(confirmed));
        assertThat(status(orderId)).isEqualTo("PAID");
        assertThat(snapshots(owner)).isEqualTo(1);
    }

    @Test
    void aRetryOfANotificationWhoseApplyFailedIsProcessedForEveryOrderTheBankMayStillChange() throws Exception {
        // A late payment of a failed order: the first delivery fails, the bank's retry an hour later pays it.
        UUID owner = UUID.randomUUID();
        String failed = pendingOrder(owner, "PLUS");
        BANK.move(failed, "REJECTED");
        assertOk(notify(notification("notification-rejected.json", failed, "REJECTED")));
        BANK.move(failed, "CONFIRMED");
        ObjectNode confirmed = notification("notification-confirmed.json", failed, "CONFIRMED");
        BANK.breakGetState = true;
        assertThat(notify(confirmed).getStatus()).isEqualTo(503);
        assertThat(events(failed)).as("nothing counts as handled").doesNotContain("NOTIFICATION:CONFIRMED:RECEIVED");
        BANK.breakGetState = false;
        clock.advance(Duration.ofHours(1));

        assertOk(notify(confirmed));

        assertThat(status(failed)).isEqualTo("PAID");
        assertThat(snapshots(owner)).isEqualTo(1);
        assertThat(events(failed)).containsSubsequence("NOTIFICATION:CONFIRMED:RECEIVED", "GET_STATE:CONFIRMED:PAID");

        // A refund of a paid order: the same.
        BANK.move(failed, "REFUNDED");
        ObjectNode refunded = notification("notification-refunded.json", failed, "REFUNDED");
        BANK.breakGetState = true;
        assertThat(notify(refunded).getStatus()).isEqualTo(503);
        BANK.breakGetState = false;
        clock.advance(Duration.ofHours(1));
        assertOk(notify(refunded));
        assertThat(status(failed)).isEqualTo("REFUNDED");
        assertThat(count("SELECT count(*) FROM app_learning.billing_event WHERE order_id=CAST(? AS uuid) AND source='NOTIFICATION'", failed)).isEqualTo(3);
    }

    @Test
    void aNotificationWaitsForTheBankAtMostTheNotificationTimeoutAndItsRetryIsProcessed() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = pendingOrder(owner, "PLUS");
        BANK.move(orderId, "CONFIRMED");
        ObjectNode confirmed = notification("notification-confirmed.json", orderId, "CONFIRMED");
        BANK.delayMillis = 2_000;
        long started = System.nanoTime();

        MockHttpServletResponse slow = notify(confirmed);

        assertThat(slow.getStatus()).isEqualTo(503);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("notification-timeout is PT1S here, request-timeout PT5S").isLessThan(Duration.ofMillis(1_800));
        assertThat(status(orderId)).isEqualTo("PENDING");
        BANK.delayMillis = 0;
        assertOk(notify(confirmed));
        assertThat(status(orderId)).isEqualTo("PAID");
        // The bank answers the abandoned call late; let it finish before the next test counts calls.
        for (int wait = 0; wait < 100 && BANK.getStates.size() < 2; wait++) Thread.sleep(50);
        assertThat(BANK.getStates).hasSize(2);
    }

    @Test
    void aBodyThatIsNotAJsonObjectOrIsTooLargeIsABadRequest() throws Exception {
        String valid = notification("notification-confirmed.json", pendingOrder(UUID.randomUUID(), "PLUS"), "CONFIRMED").toString();
        for (String bad : new String[] {"", "not json", "[]", "\"x\"", "{\"TerminalKey\":\"a\",\"TerminalKey\":\"b\"}", "{\"a\":" + "[".repeat(40) + "]".repeat(40) + "}",
                valid.substring(0, valid.length() - 3), "{\"Message\":\"" + "x".repeat(TBankNotifications.MAX_BYTES) + "\"}"}) {
            assertThat(notifications.handle(bad.getBytes(StandardCharsets.UTF_8))).as(bad.length() > 80 ? bad.substring(0, 80) : bad)
                    .isEqualTo(TBankNotifications.Reply.BAD_REQUEST);
        }
        MockHttpServletResponse response = as(UUID.randomUUID(), notificationController).perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/billing/tbank/notifications")
                        .content("x".repeat(TBankNotifications.MAX_BYTES + 10))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).isEqualTo("ERROR");
    }

    @Test
    void notificationsAreForbiddenWhileBillingIsNotConfigured() throws Exception {
        BillingSettings unset = new BillingSettings("ON", "", "", BANK.baseUrl(), "", "", Duration.ofHours(1), Duration.ofSeconds(10), 10,
                Duration.ofMinutes(2), Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5));
        TBankNotifications bare = new TBankNotifications(unset, repository, bank, applier, clock);

        assertThat(bare.handle(BillingFixtures.tbank("notification-confirmed.json").toString().getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(TBankNotifications.Reply.FORBIDDEN);
    }

    @Test
    void aBankStateOfAnotherTerminalOrOrderIsIgnored() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = pendingOrder(owner, "PLUS");
        String paymentId = BANK.paymentOf(orderId).paymentId;
        UUID id = UUID.fromString(orderId);
        BankState foreignTerminal = new BankState("1700000000999DEMO", orderId, paymentId, "CONFIRMED", 44_900L, true, "0");
        BankState foreignOrder = new BankState(BillingFixtures.TERMINAL, UUID.randomUUID().toString(), paymentId, "CONFIRMED", 44_900L, true, "0");
        BankState noOrder = new BankState(BillingFixtures.TERMINAL, null, paymentId, "CONFIRMED", 44_900L, true, "0");

        assertThat(applier.apply(id, foreignTerminal, Trigger.NOTIFICATION)).isEqualTo(Outcome.IGNORED);
        assertThat(applier.apply(id, foreignOrder, Trigger.GET_STATE)).isEqualTo(Outcome.IGNORED);
        assertThat(applier.apply(id, noOrder, Trigger.RECONCILE)).isEqualTo(Outcome.IGNORED);
        assertThat(applier.apply(UUID.randomUUID(), foreignTerminal, Trigger.GET_STATE)).isEqualTo(Outcome.IGNORED);

        assertThat(status(orderId)).isEqualTo("PENDING");
        assertThat(snapshots(owner)).isZero();
        assertThat(events(orderId)).contains("GET_STATE:CONFIRMED:IGNORED", "RECONCILE:CONFIRMED:IGNORED");
    }

    @Test
    void aSecondBankPaymentOfAPaidOrderIsRecordedAndNeverGrantsTwice() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = pendingOrder(owner, "PLUS");
        BANK.move(orderId, "CONFIRMED");
        assertOk(notify(notification("notification-confirmed.json", orderId, "CONFIRMED")));
        BankState duplicate = new BankState(BillingFixtures.TERMINAL, orderId, otherPayment(), "CONFIRMED", 44_900L, true, "0");
        double counted = anomalies("duplicate_payment");

        assertThat(applier.apply(UUID.fromString(orderId), duplicate, Trigger.GET_STATE)).isEqualTo(Outcome.UNCHANGED);

        assertThat(anomalies("duplicate_payment")).isEqualTo(counted + 1);
        assertThat(status(orderId)).isEqualTo("PAID");
        assertThat(snapshots(owner)).isEqualTo(1);
        assertThat(column(orderId, "payment_id")).isEqualTo(BANK.paymentOf(orderId).paymentId);
        assertThat(events(orderId)).contains("GET_STATE:CONFIRMED:DUPLICATE_PAYMENT");
    }

    @Test
    void aSecondPaymentThatIsNotConfirmedNeverDisturbsTheOrderButOneThatIsConfirmedPaysAFailedOrder() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = pendingOrder(owner, "PLUS");
        UUID id = UUID.fromString(orderId);
        String second = otherPayment();
        BankState rejectedSecond = new BankState(BillingFixtures.TERMINAL, orderId, second, "REJECTED", 44_900L, false, "1051");
        assertThat(applier.apply(id, rejectedSecond, Trigger.GET_STATE)).isEqualTo(Outcome.UNCHANGED);
        assertThat(status(orderId)).isEqualTo("PENDING");
        assertThat(events(orderId)).contains("GET_STATE:REJECTED:OTHER_PAYMENT");

        BANK.move(orderId, "REJECTED");
        assertOk(notify(notification("notification-rejected.json", orderId, "REJECTED")));
        assertThat(status(orderId)).isEqualTo("FAILED");

        BankState confirmedSecond = new BankState(BillingFixtures.TERMINAL, orderId, second, "CONFIRMED", 44_900L, true, "0");
        assertThat(applier.apply(id, confirmedSecond, Trigger.GET_STATE)).isEqualTo(Outcome.CHANGED);

        assertThat(status(orderId)).as("money taken must grant access").isEqualTo("PAID");
        assertThat(column(orderId, "payment_id")).isEqualTo(second);
        assertThat(snapshots(owner)).isEqualTo(1);
    }

    @Test
    void aPaymentIdIsAdoptedWhenInitNeverRecordedOne() throws Exception {
        UUID owner = UUID.randomUUID();
        BANK.breakInit = true;
        assertThat(checkout(owner, "PLUS").getStatus()).isEqualTo(503);
        String orderId = jdbc.sql("SELECT order_id::text FROM app_learning.billing_order WHERE owner_id=:owner").param("owner", owner).query(String.class).single();

        String adopted = otherPayment();

        applier.apply(UUID.fromString(orderId), new BankState(BillingFixtures.TERMINAL, orderId, adopted, "CONFIRMED", 44_900L, true, "0"), Trigger.NOTIFICATION);

        assertThat(status(orderId)).isEqualTo("PAID");
        assertThat(column(orderId, "payment_id")).isEqualTo(adopted);
        assertThat(snapshots(owner)).isEqualTo(1);
    }

    @Test
    void aConfirmedStateWithoutSuccessIsNotAPayment() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = pendingOrder(owner, "PLUS");
        String paymentId = BANK.paymentOf(orderId).paymentId;

        applier.apply(UUID.fromString(orderId), new BankState(BillingFixtures.TERMINAL, orderId, paymentId, "CONFIRMED", 44_900L, false, "1"), Trigger.GET_STATE);

        assertThat(status(orderId)).isEqualTo("PENDING");
        assertThat(snapshots(owner)).isZero();
    }
}
