package app.mnema.learning.billing;

import app.mnema.learning.billing.FakeMyTax.Mode;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The receipt outbox end to end: PostgreSQL, the real worker and client, a loopback «Мой налог» and the loopback bank. */
class NpdReceiptsTest extends BillingIntegrationTest {
    private static final String NAME = "Подписка Мнема Plus на 1 месяц";

    private static String nameOf(String plan, String orderId) {
        return "Подписка Мнема " + plan + " на 1 месяц, заказ №" + orderId.substring(0, 8);
    }

    @org.springframework.beans.factory.annotation.Autowired private NpdReceiptWorker worker;
    @org.springframework.beans.factory.annotation.Autowired private NpdSettings npdSettings;
    @org.springframework.beans.factory.annotation.Autowired private MyTaxClient taxClient;

    /** Receipts of other tests share the database; only the ones of this test may be open, or the batch and the counters would not be ours. */
    @BeforeEach
    void closeLeftovers() {
        jdbc.sql("UPDATE app_learning.billing_receipt SET state='CANCELLED', next_attempt_at=TIMESTAMPTZ '2100-01-01 00:00:00Z' WHERE state<>'CANCELLED'").update();
        jdbc.sql(CLOSE_OUT).param("before", Timestamp.from(Instant.parse("2100-01-01T00:00:00Z"))).update();
        org.springframework.test.util.ReflectionTestUtils.setField(worker, "nextCheck", Instant.MIN);
    }

    /** The statement of the guide ("Orders paid before V47") with the V47 deploy time as a parameter; keep the two in step. */
    private static final String CLOSE_OUT = """
            INSERT INTO app_learning.billing_receipt(order_id, state, service_name, amount_kopecks, operation_time, deadline_at, next_attempt_at, last_error_code, created_at, updated_at)
            SELECT o.order_id, 'CANCELLED', 'Заказ до V47, чек вне системы', o.amount_kopecks, o.paid_at, o.paid_at, now(), 'PRE_V47', now(), now()
            FROM app_learning.billing_order o
            WHERE o.status IN ('PAID', 'REFUNDED') AND NOT EXISTS (SELECT 1 FROM app_learning.billing_receipt r WHERE r.order_id = o.order_id)
              AND o.paid_at < :before""";

    private String paid(UUID owner, String plan) throws Exception {
        String orderId = open(owner, plan).path("orderId").stringValue(null);
        BANK.move(orderId, "CONFIRMED");
        assertThat(notify(notification("notification-confirmed.json", orderId, "CONFIRMED")).getStatus()).isEqualTo(200);
        assertThat(status(orderId)).isEqualTo("PAID");
        return orderId;
    }

    private void refund(String orderId, String bankStatus) throws Exception {
        BANK.move(orderId, bankStatus);
        assertThat(notify(notification("notification-refunded.json", orderId, bankStatus)).getStatus()).isEqualTo(200);
    }

    private String receipt(String orderId, String column) {
        return jdbc.sql("SELECT " + column + "::text FROM app_learning.billing_receipt WHERE order_id=CAST(:id AS uuid)").param("id", orderId)
                .query(String.class).optional().orElse(null);
    }

    private Instant receiptTime(String orderId, String column) {
        return jdbc.sql("SELECT " + column + " FROM app_learning.billing_receipt WHERE order_id=CAST(:id AS uuid)").param("id", orderId)
                .query(Timestamp.class).single().toInstant();
    }

    private long receipts(String orderId) {
        return count("SELECT count(*) FROM app_learning.billing_receipt WHERE order_id=?", UUID.fromString(orderId));
    }

    private void makeDue(String orderId) {
        jdbc.sql("UPDATE app_learning.billing_receipt SET next_attempt_at=:now WHERE order_id=CAST(:id AS uuid)").param("now", Timestamp.from(clock.now()))
                .param("id", orderId).update();
    }

    private double anomaly(String kind) {
        return anomalies(kind);
    }

    @Test
    void aPaidOrderQueuesOneReceiptInTheSameTransactionAndReplaysAddNone() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = paid(owner, "PLUS");

        assertThat(receipts(orderId)).isEqualTo(1);
        assertThat(receipt(orderId, "state")).isEqualTo("PENDING");
        assertThat(receipt(orderId, "service_name")).isEqualTo(nameOf("Plus", orderId));
        assertThat(receipt(orderId, "amount_kopecks")).isEqualTo(column(orderId, "amount_kopecks"));
        assertThat(receiptTime(orderId, "operation_time")).as("the payment's confirmation instant").isEqualTo(instant(orderId, "paid_at"));
        assertThat(receiptTime(orderId, "deadline_at")).as("the 9th of November, end of day, Moscow").isEqualTo(Instant.parse("2026-11-09T21:00:00Z"));
        assertThat(notify(notification("notification-confirmed.json", orderId, "CONFIRMED")).getStatus()).isEqualTo(200);
        clock.advance(Duration.ofMinutes(3));
        reconciliation.runOnce();
        assertThat(receipts(orderId)).isEqualTo(1);
        assertThat(read(owner, orderId).getStatus()).isEqualTo(200);
        assertThat(body(read(owner, orderId)).path("receiptUrl").isNull()).as("no link before the receipt is registered").isTrue();
        assertThat(count("SELECT count(*) FROM app_learning.billing_receipt r JOIN app_learning.billing_order o USING (order_id) WHERE o.owner_id=?", owner)).isEqualTo(1);
    }

    @Test
    void anOrderForReviewGetsNoAutomaticReceipt() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = open(owner, "PLUS").path("orderId").stringValue(null);
        BANK.move(orderId, "CONFIRMED");
        BANK.paymentOf(orderId).answeredAmount = 100L;

        assertThat(notify(notification("notification-confirmed.json", orderId, "CONFIRMED")).getStatus()).isEqualTo(200);

        assertThat(status(orderId)).isEqualTo("REVIEW");
        assertThat(receipts(orderId)).isZero();
        BANK.paymentOf(orderId).answeredAmount = null;
    }

    @Test
    void theWorkerRegistersTheReceiptAndTheBuyerGetsTheLink() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = paid(owner, "PRO");

        assertThat(worker.runOnce()).isEqualTo(1);

        assertThat(receipt(orderId, "state")).isEqualTo("REGISTERED");
        assertThat(NPD.incomes).hasSize(1);
        var request = NPD.incomes.getFirst();
        assertThat(request.path("services").path(0).path("name").stringValue(null)).isEqualTo(nameOf("Pro", orderId));
        assertThat(request.path("totalAmount").stringValue(null)).isEqualTo(FakeMyTaxMoney.rubles(column(orderId, "amount_kopecks")));
        assertThat(request.path("operationTime").stringValue(null)).isEqualTo("2026-10-09T12:00:00+03:00");
        String uuid = receipt(orderId, "receipt_uuid");
        assertThat(uuid).isEqualTo(NPD.receipts.getFirst().uuid);
        JsonNode view = body(read(owner, orderId));
        assertThat(view.path("receiptUrl").stringValue(null)).as("always the real service, never the configured base").isEqualTo("https://lknpd.nalog.ru/api/v1/receipt/" + FakeMyTax.INN + "/" + uuid + "/print");
        assertThat(worker.runOnce()).as("nothing is due any more").isZero();
        assertThat(NPD.incomes).hasSize(1);
    }

    @Test
    void aTimedOutIncomeIsNeverSentBlindAgainButLookedUpFirst() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = paid(owner, "PLUS");
        NPD.incomeMode = Mode.LOST_ANSWER;

        assertThat(worker.runOnce()).isEqualTo(1);

        assertThat(receipt(orderId, "state")).isEqualTo("SENDING");
        assertThat(receipt(orderId, "last_error_code")).isEqualTo("TIMEOUT");
        assertThat(NPD.receipts).as("the service registered it, the answer was lost").hasSize(1);
        assertThat(receiptTime(orderId, "next_attempt_at")).isEqualTo(clock.now().plus(NpdReceipts.SETTLE));
        NPD.incomeMode = Mode.OK;
        assertThat(worker.runOnce()).as("not due before the settle wait").isZero();
        clock.advance(NpdReceipts.SETTLE.plusSeconds(1));

        assertThat(worker.runOnce()).isEqualTo(1);

        assertThat(receipt(orderId, "state")).isEqualTo("REGISTERED");
        assertThat(receipt(orderId, "receipt_uuid")).isEqualTo(NPD.receipts.getFirst().uuid);
        assertThat(NPD.incomes).as("no second POST /income").hasSize(1);
        assertThat(NPD.receipts).hasSize(1);
    }

    @Test
    void aReceiptThatTheLookupDoesNotFindIsSentAgain() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = paid(owner, "PLUS");
        NPD.incomeMode = Mode.SERVER_ERROR_BEFORE;
        worker.runOnce();
        assertThat(receipt(orderId, "state")).isEqualTo("SENDING");
        NPD.incomeMode = Mode.OK;
        clock.advance(NpdReceipts.SETTLE.plusSeconds(1));

        worker.runOnce();

        assertThat(receipt(orderId, "state")).isEqualTo("REGISTERED");
        assertThat(NPD.incomes).hasSize(2);
        assertThat(NPD.receipts).hasSize(1);
        assertThat(NPD.incomeQueries).hasSize(1);
    }

    @Test
    void twoReceiptsWithOneFingerprintGoToAnOperator() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = paid(owner, "PLUS");
        NPD.incomeMode = Mode.LOST_ANSWER;
        worker.runOnce();
        FakeMyTax.Receipt first = NPD.receipts.getFirst();
        NPD.register(first.name, first.amount, first.operationTime);
        clock.advance(NpdReceipts.SETTLE.plusSeconds(1));
        double before = anomaly("receipt_failed");

        worker.runOnce();

        assertThat(receipt(orderId, "state")).isEqualTo("FAILED_PERMANENT");
        assertThat(receipt(orderId, "last_error_code")).isEqualTo("AMBIGUOUS_RECEIPT");
        assertThat(anomaly("receipt_failed")).isEqualTo(before + 1);
        assertThat(NPD.incomes).hasSize(1);
        clock.advance(Duration.ofDays(1));
        assertThat(worker.runOnce()).as("an operator's row is not retried").isZero();
    }

    @Test
    void anAnnulledReceiptWithTheFingerprintIsNotAdoptedEither() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = paid(owner, "PLUS");
        NPD.incomeMode = Mode.LOST_ANSWER;
        worker.runOnce();
        NPD.receipts.getFirst().cancelled = true;
        clock.advance(NpdReceipts.SETTLE.plusSeconds(1));

        worker.runOnce();

        assertThat(receipt(orderId, "state")).isEqualTo("FAILED_PERMANENT");
    }

    @Test
    void aLookupThatIsRefusedStaysUnknownInsteadOfSendingAgain() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = paid(owner, "PLUS");
        NPD.incomeMode = Mode.LOST_ANSWER;
        worker.runOnce();
        NPD.incomeMode = Mode.OK;
        NPD.listBroken = true;
        clock.advance(NpdReceipts.SETTLE.plusSeconds(1));

        worker.runOnce();

        assertThat(receipt(orderId, "state")).isEqualTo("SENDING");
        assertThat(receipt(orderId, "last_error_code")).isEqualTo("HTTP_500");
        assertThat(NPD.incomes).hasSize(1);
    }

    @Test
    void aRefundAnnulsTheRegisteredReceiptAsAReturn() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = paid(owner, "PLUS");
        worker.runOnce();
        String uuid = receipt(orderId, "receipt_uuid");

        refund(orderId, "REFUNDED");

        assertThat(status(orderId)).isEqualTo("REFUNDED");
        assertThat(receipt(orderId, "state")).isEqualTo("CANCEL_PENDING");
        assertThat(body(read(owner, orderId)).path("receiptUrl").isNull()).as("a refunded order has no link").isTrue();
        assertThat(worker.runOnce()).isEqualTo(1);
        assertThat(receipt(orderId, "state")).isEqualTo("CANCELLED");
        assertThat(NPD.cancels).hasSize(1);
        assertThat(NPD.cancels.getFirst().path("comment").stringValue(null)).isEqualTo("Возврат средств");
        assertThat(NPD.cancels.getFirst().path("receiptUuid").stringValue(null)).isEqualTo(uuid);
        assertThat(NPD.receipts.getFirst().cancelled).isTrue();
        assertThat(receipt(orderId, "receipt_uuid")).isEqualTo(uuid);
    }

    @Test
    void aRefundBeforeTheReceiptWasSentAnnulsItLocallyWithoutCallingTheService() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = paid(owner, "PLUS");

        refund(orderId, "REVERSED");

        assertThat(receipt(orderId, "state")).isEqualTo("CANCELLED");
        assertThat(receipt(orderId, "receipt_uuid")).isNull();
        assertThat(worker.runOnce()).isZero();
        assertThat(NPD.incomes).isEmpty();
        assertThat(NPD.cancels).isEmpty();
        assertThat(NPD.logins).isEmpty();
    }

    @Test
    void aRefundWhileTheOutcomeIsUnknownAnnulsTheReceiptOnceItIsFound() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = paid(owner, "PLUS");
        NPD.incomeMode = Mode.LOST_ANSWER;
        worker.runOnce();

        refund(orderId, "REFUNDED");

        assertThat(receipt(orderId, "state")).isEqualTo("SENDING");
        assertThat(receipt(orderId, "cancel_requested")).isEqualTo("true");
        NPD.incomeMode = Mode.OK;
        clock.advance(NpdReceipts.SETTLE.plusSeconds(1));
        worker.runOnce();
        assertThat(receipt(orderId, "state")).isEqualTo("CANCEL_PENDING");
        worker.runOnce();
        assertThat(receipt(orderId, "state")).isEqualTo("CANCELLED");
        assertThat(NPD.incomes).hasSize(1);
        assertThat(NPD.receipts.getFirst().cancelled).isTrue();
    }

    @Test
    void aRefundWhileUnknownAndNothingFoundAnnulsLocallyAndNeverSends() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = paid(owner, "PLUS");
        NPD.incomeMode = Mode.SERVER_ERROR_BEFORE;
        worker.runOnce();
        refund(orderId, "REFUNDED");
        NPD.incomeMode = Mode.OK;
        clock.advance(NpdReceipts.SETTLE.plusSeconds(1));

        worker.runOnce();

        assertThat(receipt(orderId, "state")).isEqualTo("CANCELLED");
        assertThat(NPD.incomes).hasSize(1);
        assertThat(NPD.receipts).isEmpty();
        assertThat(NPD.cancels).isEmpty();
    }

    @Test
    void anAnnulmentWhoseAnswerWasLostIsRecognisedNotSentTwice() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = paid(owner, "PLUS");
        worker.runOnce();
        refund(orderId, "REFUNDED");
        NPD.cancelMode = Mode.LOST_ANSWER;

        worker.runOnce();

        assertThat(receipt(orderId, "state")).isEqualTo("CANCEL_PENDING");
        assertThat(receipt(orderId, "last_error_code")).isEqualTo("TIMEOUT");
        NPD.cancelMode = Mode.OK;
        clock.advance(Duration.ofMinutes(2));
        worker.runOnce();
        assertThat(receipt(orderId, "state")).isEqualTo("CANCELLED");
        assertThat(NPD.cancels).hasSize(1);
        assertThat(NPD.receiptReads).hasValue(1);
    }

    @Test
    void aCancellationTheServiceRefusesForAnUnannulledReceiptGoesToAnOperator() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = paid(owner, "PLUS");
        worker.runOnce();
        refund(orderId, "REFUNDED");
        NPD.cancelMode = Mode.REJECTED;
        double before = anomaly("receipt_failed");

        worker.runOnce();

        assertThat(receipt(orderId, "state")).isEqualTo("FAILED_PERMANENT");
        assertThat(receipt(orderId, "last_error_code")).isEqualTo("HTTP_422");
        assertThat(anomaly("receipt_failed")).isEqualTo(before + 1);
    }

    @Test
    void aCancellationRefusedBecauseTheReceiptIsAlreadyAnnulledIsDone() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = paid(owner, "PLUS");
        worker.runOnce();
        refund(orderId, "REFUNDED");
        NPD.receipts.getFirst().cancelled = true;

        worker.runOnce();

        assertThat(receipt(orderId, "state")).isEqualTo("CANCELLED");
    }

    @Test
    void aPartialRefundKeepsTheReceiptForAnOperatorAndIsReportedDaily() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = paid(owner, "PLUS");
        worker.runOnce();
        double partial = anomaly("partial_refund");

        refund(orderId, "PARTIAL_REFUNDED");

        assertThat(status(orderId)).isEqualTo("REFUNDED");
        assertThat(anomaly("partial_refund")).isEqualTo(partial + 1);
        assertThat(receipt(orderId, "state")).isEqualTo("REGISTERED");
        double mismatch = anomaly("receipt_mismatch");
        worker.consistency(clock.now());
        assertThat(anomaly("receipt_mismatch")).isEqualTo(mismatch + 1);
    }

    @Test
    void theBackoffDoublesFromAMinuteAndRowsAreNotClaimedBeforeTheyAreDue() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = paid(owner, "PLUS");
        NPD.incomeMode = Mode.RATE_LIMITED;

        for (long minutes : new long[] {1, 2, 4, 8}) {
            assertThat(worker.runOnce()).isEqualTo(1);
            assertThat(receipt(orderId, "state")).as("rate limited: nothing was sent").isEqualTo("PENDING");
            assertThat(receiptTime(orderId, "next_attempt_at")).isEqualTo(clock.now().plus(Duration.ofMinutes(minutes)));
            assertThat(worker.runOnce()).isZero();
            clock.advance(Duration.ofMinutes(minutes));
        }
        assertThat(NPD.receipts).isEmpty();
        jdbc.sql("UPDATE app_learning.billing_receipt SET attempts=9 WHERE order_id=CAST(:id AS uuid)").param("id", orderId).update();
        worker.runOnce();
        assertThat(receiptTime(orderId, "next_attempt_at")).as("capped at six hours").isEqualTo(clock.now().plus(Duration.ofHours(6)));
    }

    @Test
    void afterTenAttemptsTheOperatorIsToldOnceAndTheWorkerKeepsTrying() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = paid(owner, "PLUS");
        NPD.incomeMode = Mode.RATE_LIMITED;
        jdbc.sql("UPDATE app_learning.billing_receipt SET attempts=8 WHERE order_id=CAST(:id AS uuid)").param("id", orderId).update();
        double before = anomaly("receipt_failed");

        worker.runOnce();
        assertThat(anomaly("receipt_failed")).isEqualTo(before);
        clock.advance(Duration.ofDays(1));
        worker.runOnce();
        assertThat(anomaly("receipt_failed")).isEqualTo(before + 1);
        clock.advance(Duration.ofDays(1));
        worker.runOnce();
        assertThat(anomaly("receipt_failed")).as("once").isEqualTo(before + 1);
        assertThat(receipt(orderId, "state")).isEqualTo("PENDING");
        NPD.incomeMode = Mode.OK;
        clock.advance(Duration.ofDays(1));
        worker.runOnce();
        assertThat(receipt(orderId, "state")).isEqualTo("REGISTERED");
    }

    @Test
    void aReceiptTheServiceRefusesFailsForGoodAndRaisesTheAlarmOnce() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = paid(owner, "PLUS");
        NPD.incomeMode = Mode.REJECTED;
        double before = anomaly("receipt_failed");

        worker.runOnce();

        assertThat(receipt(orderId, "state")).isEqualTo("FAILED_PERMANENT");
        assertThat(receipt(orderId, "last_error_code")).isEqualTo("HTTP_422");
        assertThat(anomaly("receipt_failed")).isEqualTo(before + 1);
        clock.advance(Duration.ofDays(2));
        assertThat(worker.runOnce()).isZero();
        assertThat(NPD.incomes).hasSize(1);
        // Fixed by an operator: back to PENDING and it is sent.
        NPD.incomeMode = Mode.OK;
        jdbc.sql("UPDATE app_learning.billing_receipt SET state='PENDING', attempts=0, next_attempt_at=:now WHERE order_id=CAST(:id AS uuid)")
                .param("now", Timestamp.from(clock.now())).param("id", orderId).update();
        worker.runOnce();
        assertThat(receipt(orderId, "state")).isEqualTo("REGISTERED");
    }

    @Test
    void theDeadlineAlarmFiresOnceEvenWhenSendingIsOff() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = paid(owner, "PLUS");
        NpdSettings off = new NpdSettings("OFF", "", "", NPD.baseUrl(), Duration.ofMinutes(1), Duration.ofSeconds(5), Duration.ofSeconds(1));
        NpdReceiptWorker offWorker = new NpdReceiptWorker(npdReceipts, off, taxClient, applier, clock);
        double before = anomaly("receipt_overdue");

        clock.set("2026-11-09T20:59:00Z");
        assertThat(offWorker.runOnce()).isZero();
        assertThat(anomaly("receipt_overdue")).as("the 9th is still allowed").isEqualTo(before);
        clock.set("2026-11-09T21:00:00Z");
        assertThat(offWorker.runOnce()).isZero();
        assertThat(anomaly("receipt_overdue")).isEqualTo(before + 1);
        assertThat(offWorker.runOnce()).isZero();
        assertThat(anomaly("receipt_overdue")).as("once per receipt").isEqualTo(before + 1);

        assertThat(receipt(orderId, "state")).as("OFF queued it and sent nothing").isEqualTo("PENDING");
        assertThat(NPD.incomes).isEmpty();
        assertThat(NPD.logins).isEmpty();
        // Switched on late: it is still registered.
        worker.runOnce();
        assertThat(receipt(orderId, "state")).isEqualTo("REGISTERED");
    }

    @Test
    void theDailyCheckReportsPaidOrdersWithoutAReceiptAndRefundedOnesNotAnnulled() throws Exception {
        UUID owner = UUID.randomUUID();
        String fine = paid(owner, "PLUS");
        worker.runOnce();
        String refunded = paid(UUID.randomUUID(), "PLUS");
        worker.runOnce();
        refund(refunded, "REFUNDED");
        String waiting = paid(UUID.randomUUID(), "PRO");
        String lost = paid(UUID.randomUUID(), "PRO");
        jdbc.sql("DELETE FROM app_learning.billing_receipt WHERE order_id=CAST(:id AS uuid)").param("id", lost).update();
        assertThat(receipt(fine, "state")).isEqualTo("REGISTERED");
        // Nothing is older than a day yet: only the refunded order whose receipt is still to be annulled is reported.
        double before = anomaly("receipt_mismatch");
        worker.consistency(clock.now());
        assertThat(anomaly("receipt_mismatch")).isEqualTo(before + 1);

        before = anomaly("receipt_mismatch");
        worker.consistency(clock.now().plus(Duration.ofDays(1)).plusSeconds(1));

        assertThat(anomaly("receipt_mismatch")).as("waiting, lost and the refunded one; not the registered one").isEqualTo(before + 3);
        assertThat(waiting).isNotEqualTo(lost);
    }

    @Test
    void theDailyCheckRunsOncePerDayInsideThePass() throws Exception {
        UUID owner = UUID.randomUUID();
        String waiting = paid(owner, "PLUS");
        NPD.incomeMode = Mode.RATE_LIMITED;
        clock.advance(Duration.ofDays(2));
        double before = anomaly("receipt_mismatch");

        worker.runOnce();
        double afterFirst = anomaly("receipt_mismatch");
        clock.advance(Duration.ofHours(1));
        worker.runOnce();

        assertThat(afterFirst).isGreaterThanOrEqualTo(before + 1);
        assertThat(anomaly("receipt_mismatch")).isEqualTo(afterFirst);
        assertThat(waiting).isNotBlank();
    }

    @Test
    void aRefusedLoginStopsThePassBlocksNewLoginsAndReleasesTheRest() throws Exception {
        UUID owner = UUID.randomUUID();
        String first = paid(owner, "PLUS");
        String second = paid(owner, "PRO");
        NPD.revokeTokens();
        NPD.rejectLogin = true;
        double before = anomaly("receipt_auth");

        assertThat(worker.runOnce()).isEqualTo(2);

        assertThat(anomaly("receipt_auth")).isEqualTo(before + 1);
        assertThat(receipt(first, "attempts")).as("a login outage is not an attempt of the receipt").isEqualTo("0");
        assertThat(receipt(second, "attempts")).isEqualTo("0");
        assertThat(NPD.logins).hasSize(1);
        assertThat(NPD.incomes).isEmpty();
        for (String orderId : List.of(first, second)) {
            assertThat(receipt(orderId, "state")).as("never sent: back to PENDING").isEqualTo("PENDING");
            assertThat(receipt(orderId, "last_error_code")).isIn("AUTH_REJECTED", "AUTH_BLOCKED");
        }
        clock.advance(Duration.ofMinutes(2));
        assertThat(worker.runOnce()).as("login is blocked: nothing is claimed or sent").isZero();
        assertThat(NPD.logins).hasSize(1);

        NPD.rejectLogin = false;
        clock.advance(MyTaxClient.LOGIN_BACKOFF);
        assertThat(worker.runOnce()).isEqualTo(2);
        assertThat(receipt(first, "state")).isEqualTo("REGISTERED");
        assertThat(receipt(second, "state")).isEqualTo("REGISTERED");
    }

    @Test
    void anUnexpectedFailureLeavesTheRowSendingWithItsLeaseSoItIsLookedUpNotResent() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = paid(owner, "PLUS");
        MyTaxClient broken = mock(MyTaxClient.class);
        when(broken.loginBlocked()).thenReturn(false);
        when(broken.registerIncome(any(), org.mockito.ArgumentMatchers.anyLong(), any())).thenThrow(new IllegalStateException("boom"));
        NpdReceiptWorker fragile = new NpdReceiptWorker(npdReceipts, npdSettings, broken, applier, clock);

        assertThat(fragile.runOnce()).isEqualTo(1);

        assertThat(receipt(orderId, "state")).isEqualTo("SENDING");
        assertThat(receiptTime(orderId, "next_attempt_at")).isEqualTo(clock.now().plus(NpdReceipts.SETTLE));
        clock.advance(NpdReceipts.SETTLE.plusSeconds(1));
        worker.runOnce();
        assertThat(receipt(orderId, "state")).isEqualTo("REGISTERED");
        assertThat(NPD.incomes).as("sent once by the real client, after a lookup").hasSize(1);
        assertThat(NPD.incomeQueries).hasSize(1);
    }

    @Test
    void severalWorkersNeverTakeTheSameReceiptTwice() throws Exception {
        UUID owner = UUID.randomUUID();
        String orderId = paid(owner, "PLUS");
        Instant now = clock.now();

        List<NpdReceiptRepository.Claim> first = npdReceipts.claim(now, 10);
        List<NpdReceiptRepository.Claim> second = npdReceipts.claim(now, 10);

        assertThat(first).hasSize(1);
        assertThat(first.getFirst().previous()).isEqualTo(NpdReceipt.State.PENDING);
        assertThat(first.getFirst().receipt().state()).isEqualTo(NpdReceipt.State.SENDING);
        assertThat(second).isEmpty();
        makeDue(orderId);
        assertThat(npdReceipts.claim(now, 10).getFirst().previous()).as("a lease that ended is looked up first").isEqualTo(NpdReceipt.State.SENDING);
    }

    @Test
    void noLogLineCarriesTheInnThePasswordATokenOrAReceiptBody() throws Exception {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level previous = root.getLevel();
        root.setLevel(Level.DEBUG);
        appender.start();
        root.addAppender(appender);
        try {
            UUID owner = UUID.randomUUID();
            String orderId = paid(owner, "PLUS");
            NPD.incomeMode = Mode.LOST_ANSWER;
            worker.runOnce();
            NPD.incomeMode = Mode.OK;
            clock.advance(NpdReceipts.SETTLE.plusSeconds(1));
            worker.runOnce();
            refund(orderId, "REFUNDED");
            NPD.cancelMode = Mode.REJECTED;
            worker.runOnce();
            NPD.revokeTokens();
            NPD.rejectLogin = true;
            String other = paid(UUID.randomUUID(), "PRO");
            clock.advance(Duration.ofMinutes(20));
            worker.runOnce();
            NPD.rejectLogin = false;
            clock.advance(MyTaxClient.LOGIN_BACKOFF);
            worker.runOnce();
            assertThat(receipt(other, "state")).isEqualTo("REGISTERED");
        } finally {
            root.detachAppender(appender);
            root.setLevel(previous);
        }
        assertThat(appender.list).anyMatch(event -> event.getFormattedMessage().startsWith("npd receipt "));
        for (ILoggingEvent event : appender.list) {
            // The loopback server logs its own request lines (with the path); the lines of the code under test are what is asserted.
            if (!event.getLoggerName().startsWith("app.mnema.")) continue;
            String line = event.getFormattedMessage() + " " + event.getThrowableProxy();
            assertThat(line).doesNotContain(FakeMyTax.INN, FakeMyTax.PASSWORD, FakeMyTax.PASSWORD_BASE64, "access-", "refresh-", "Bearer", NAME, "Мнема");
        }
        assertThat(appender.list).anyMatch(event -> event.getFormattedMessage().startsWith("npd receipt registered order_id="));
    }

    @Test
    void twoOrdersPaidInTheSameSecondNeverAdoptEachOthersReceipt() throws Exception {
        String a = paid(UUID.randomUUID(), "PLUS");
        worker.runOnce();
        String b = paid(UUID.randomUUID(), "PLUS");
        assertThat(instant(a, "paid_at")).isEqualTo(instant(b, "paid_at"));
        assertThat(receipt(a, "amount_kopecks")).isEqualTo(receipt(b, "amount_kopecks"));
        assertThat(receipt(a, "service_name")).isNotEqualTo(receipt(b, "service_name"));
        NPD.incomeMode = Mode.LOST_ANSWER;
        worker.runOnce();
        NPD.incomeMode = Mode.OK;
        clock.advance(NpdReceipts.SETTLE.plusSeconds(1));

        worker.runOnce();

        assertThat(receipt(b, "state")).isEqualTo("REGISTERED");
        assertThat(receipt(a, "receipt_uuid")).isEqualTo(NPD.receipts.get(0).uuid);
        assertThat(receipt(b, "receipt_uuid")).isEqualTo(NPD.receipts.get(1).uuid);
        assertThat(NPD.incomes).as("B was found by its own name, not sent again").hasSize(2);
    }

    @Test
    void aReceiptStoredOnAnotherOrderIsNeverAdoptedEvenWithTheSameFingerprint() throws Exception {
        String a = paid(UUID.randomUUID(), "PLUS");
        worker.runOnce();
        String b = paid(UUID.randomUUID(), "PLUS");
        jdbc.sql("UPDATE app_learning.billing_receipt SET service_name=:name WHERE order_id=CAST(:id AS uuid)").param("name", receipt(a, "service_name")).param("id", b).update();
        NPD.incomeMode = Mode.SERVER_ERROR_BEFORE;
        worker.runOnce();
        assertThat(receipt(b, "state")).isEqualTo("SENDING");
        NPD.incomeMode = Mode.OK;
        clock.advance(NpdReceipts.SETTLE.plusSeconds(1));

        worker.runOnce();

        assertThat(receipt(b, "state")).isEqualTo("REGISTERED");
        assertThat(receipt(b, "receipt_uuid")).as("A's receipt matched the fingerprint and was set aside").isNotEqualTo(receipt(a, "receipt_uuid"));
        assertThat(NPD.receipts).hasSize(2);
        assertThat(NPD.incomes).hasSize(3);
    }

    @Test
    void aRefundDuringTheFirstSendThatDidNotGoOutNeverRegistersAnIncome() throws Exception {
        String orderId = paid(UUID.randomUUID(), "PLUS");
        MyTaxClient racing = mock(MyTaxClient.class);
        when(racing.loginBlocked()).thenReturn(false);
        when(racing.registerIncome(any(), org.mockito.ArgumentMatchers.anyLong(), any())).thenAnswer(invocation -> {
            refund(orderId, "REFUNDED");
            throw new MyTaxException(MyTaxException.Outcome.NOT_SENT, "HTTP_429", 429, "income");
        });

        assertThat(new NpdReceiptWorker(npdReceipts, npdSettings, racing, applier, clock).runOnce()).isEqualTo(1);

        assertThat(receipt(orderId, "state")).as("not sent, refunded meanwhile: nothing to send or annul").isEqualTo("CANCELLED");
        assertThat(receipt(orderId, "receipt_uuid")).isNull();
        clock.advance(Duration.ofDays(1));
        assertThat(worker.runOnce()).isZero();
        assertThat(NPD.incomes).isEmpty();
        assertThat(NPD.cancels).isEmpty();
    }

    @Test
    void aRefundBetweenTheClaimAndTheRequestStopsTheSend() throws Exception {
        String orderId = paid(UUID.randomUUID(), "PLUS");
        List<NpdReceiptRepository.Claim> claimed = npdReceipts.claim(clock.now(), 10);
        refund(orderId, "REFUNDED");
        assertThat(receipt(orderId, "state")).isEqualTo("SENDING");

        worker.attempt(claimed.getFirst());

        assertThat(receipt(orderId, "state")).isEqualTo("CANCELLED");
        assertThat(NPD.incomes).isEmpty();
        assertThat(NPD.logins).isEmpty();
    }

    @Test
    void theLeaseIsRenewedRightBeforeARequestSoTheWaitCountsFromTheSend() throws Exception {
        String orderId = paid(UUID.randomUUID(), "PLUS");
        Instant claimedAt = clock.now();
        npdReceipts.claim(claimedAt, 10);
        assertThat(receiptTime(orderId, "next_attempt_at")).isEqualTo(claimedAt.plus(NpdReceipts.SETTLE));
        clock.advance(Duration.ofMinutes(4));

        assertThat(npdReceipts.begin(UUID.fromString(orderId), clock.now())).isPresent();

        assertThat(receiptTime(orderId, "next_attempt_at")).isEqualTo(clock.now().plus(NpdReceipts.SETTLE));
        refund(orderId, "REFUNDED");
        jdbc.sql("UPDATE app_learning.billing_receipt SET state='CANCELLED' WHERE order_id=CAST(:id AS uuid)").param("id", orderId).update();
        assertThat(npdReceipts.begin(UUID.fromString(orderId), clock.now())).as("a row nobody works on is left alone").isEmpty();
    }

    @Test
    void theDailyCheckLooksAtTheLastNinetyDaysAndAnOperatorClosesOutTheOlderOrders() throws Exception {
        String recent = paid(UUID.randomUUID(), "PLUS");
        String old = paid(UUID.randomUUID(), "PRO");
        jdbc.sql("DELETE FROM app_learning.billing_receipt WHERE order_id IN (CAST(:a AS uuid), CAST(:b AS uuid))").param("a", recent).param("b", old).update();
        jdbc.sql("UPDATE app_learning.billing_order SET paid_at=:at WHERE order_id=CAST(:id AS uuid)").param("at", Timestamp.from(clock.now().minus(Duration.ofDays(89)))).param("id", recent).update();
        jdbc.sql("UPDATE app_learning.billing_order SET paid_at=:at WHERE order_id=CAST(:id AS uuid)").param("at", Timestamp.from(clock.now().minus(Duration.ofDays(91)))).param("id", old).update();
        double before = anomaly("receipt_mismatch");

        worker.consistency(clock.now());
        assertThat(anomaly("receipt_mismatch")).as("only the order inside the window").isEqualTo(before + 1);

        assertThat(jdbc.sql(CLOSE_OUT).param("before", Timestamp.from(clock.now())).update()).isEqualTo(2);
        worker.consistency(clock.now());
        assertThat(anomaly("receipt_mismatch")).as("closed out: nothing more to report").isEqualTo(before + 1);
        assertThat(receipt(recent, "state")).isEqualTo("CANCELLED");
        assertThat(receipt(recent, "last_error_code")).isEqualTo("PRE_V47");
    }

    @Test
    void withSendingOffTheDailyCheckLogsOneWarningAndRaisesNoAnomaly() throws Exception {
        String first = paid(UUID.randomUUID(), "PLUS");
        String second = paid(UUID.randomUUID(), "PRO");
        jdbc.sql("DELETE FROM app_learning.billing_receipt WHERE order_id IN (CAST(:a AS uuid), CAST(:b AS uuid))").param("a", first).param("b", second).update();
        NpdSettings off = new NpdSettings("OFF", "", "", NPD.baseUrl(), Duration.ofMinutes(1), Duration.ofSeconds(5), Duration.ofSeconds(1));
        NpdReceiptWorker offWorker = new NpdReceiptWorker(npdReceipts, off, taxClient, applier, clock);
        double before = anomaly("receipt_mismatch");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        appender.start();
        root.addAppender(appender);
        try {
            offWorker.consistency(clock.now().plus(Duration.ofDays(2)));
        } finally {
            root.detachAppender(appender);
        }

        assertThat(anomaly("receipt_mismatch")).isEqualTo(before);
        List<ILoggingEvent> lines = appender.list.stream().filter(event -> event.getFormattedMessage().startsWith("npd receipts are off")).toList();
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().getLevel()).isEqualTo(Level.WARN);
        assertThat(lines.getFirst().getFormattedMessage()).contains("paid_without_receipt=2");
        assertThat(appender.list).noneMatch(event -> event.getLevel() == Level.ERROR && event.getFormattedMessage().contains("receipt_mismatch"));
    }

    @Test
    void eachFailureIsLoggedOnceByTheWorkerAndNeverByTheClient() throws Exception {
        String orderId = paid(UUID.randomUUID(), "PLUS");
        NPD.incomeMode = Mode.RATE_LIMITED;
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        appender.start();
        root.addAppender(appender);
        try {
            worker.runOnce();
        } finally {
            root.detachAppender(appender);
        }

        List<String> lines = appender.list.stream().filter(event -> event.getLoggerName().startsWith("app.mnema.")).map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("failed")).toList();
        assertThat(lines).containsExactly("npd receipt attempt failed order_id=" + orderId + " operation=income outcome=NOT_SENT code=HTTP_429");
    }

    @AfterEach
    void unblock() {
        NPD.reset();
    }
}
