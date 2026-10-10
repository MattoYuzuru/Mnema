package app.mnema.learning.billing;

import app.mnema.learning.billing.MyTaxException.Outcome;
import app.mnema.learning.billing.NpdReceipt.State;
import app.mnema.learning.billing.NpdReceiptRepository.Claim;
import app.mnema.learning.billing.PaymentStateApplier.Anomaly;
import app.mnema.learning.usage.UsageClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One pass of the receipt outbox. It first raises the alarms that need no network and run even when sending is {@code OFF} (a receipt past its legal term; once a
 * day, the consistency of paid and refunded orders with their receipts), then, when sending is {@code ON}, claims up to {@value #BATCH} due receipts in a
 * short transaction, calls «Мой налог» for each <em>outside</em> any transaction and reports the result in another short one ({@link NpdReceipts}).
 *
 * <p><b>No duplicate receipts.</b> The service has no idempotency key. The claim persists {@code SENDING} before the request, so a lost answer, a timeout or a crash
 * always leaves a row that is looked up in the taxpayer's incomes (operation second, total and service name, as {@code varrcan/lknpd} documents) before it is sent
 * again: none found after the {@link NpdReceipts#SETTLE} wait means it is safe to send; exactly one is adopted; more than one, or an annulled one, is handed to an
 * operator ({@code receipt_failed}); a receipt that is the stored receipt of another order is never adopted, and the service name is unique per order. The lease of
 * a row is renewed right before each request, so the wait counts from the send. A request the service refused (4xx) is not retried blindly: it fails permanently for an operator.
 *
 * <p>A refund of a registered receipt annuls it ({@code «Возврат средств»}); before a retried annulment the receipt is read, so an annulment whose answer was lost
 * is recognised. A rejected login stops the pass and blocks logging in for {@link MyTaxClient#LOGIN_BACKOFF}, doubling on every refusal ({@code receipt_auth}); rows let go that way are not counted as attempts.
 */
@Service
class NpdReceiptWorker {
    private static final Logger log = LoggerFactory.getLogger(NpdReceiptWorker.class);
    static final int BATCH = 20;
    private static final int CHECK_LIMIT = 100;
    /** A paid order younger than this is the worker's business first; the daily check reports older ones without a registered receipt. */
    static final Duration CHECK_AFTER_PAYMENT = Duration.ofDays(1);
    static final Duration CHECK_EVERY = Duration.ofDays(1);
    /** The check looks at the last 90 days only; older orders (paid before V47) are closed out by an operator, see the guide. */
    static final Duration CHECK_WINDOW = Duration.ofDays(90);

    private final NpdReceipts receipts;
    private final NpdSettings settings;
    private final MyTaxClient client;
    private final PaymentStateApplier applier;
    private final UsageClock clock;
    private volatile Instant nextCheck = Instant.MIN;

    NpdReceiptWorker(NpdReceipts receipts, NpdSettings settings, MyTaxClient client, PaymentStateApplier applier, UsageClock clock) {
        this.receipts = receipts;
        this.settings = settings;
        this.client = client;
        this.applier = applier;
        this.clock = clock;
    }

    /** @return how many receipts were attempted */
    int runOnce() {
        Instant now = clock.now();
        for (UUID orderId : receipts.markOverdue(now, CHECK_LIMIT)) applier.anomaly(Anomaly.RECEIPT_OVERDUE, orderId, null);
        if (!now.isBefore(nextCheck)) {
            nextCheck = now.plus(CHECK_EVERY);
            consistency(now);
        }
        if (!settings.sending() || client.loginBlocked()) return 0;
        List<Claim> claimed = receipts.claim(now, BATCH);
        boolean stopped = false;
        for (Claim claim : claimed) {
            UUID orderId = claim.receipt().orderId();
            try {
                if (stopped) {
                    release(claim);
                } else {
                    attempt(claim);
                }
            } catch (MyTaxException failure) {
                stopped |= failed(claim, failure);
            } catch (RuntimeException failure) {
                // The claim keeps the row SENDING with a lease, so it is looked up and retried when the lease ends.
                log.warn("npd receipt attempt failed order_id={} error_type={}", orderId, failure.getClass().getSimpleName());
            }
        }
        return claimed.size();
    }

    /**
     * PAID orders older than a day without a registered receipt, and REFUNDED orders whose receipt is not annulled, within the last {@link #CHECK_WINDOW}
     * (newest first, at most {@value #CHECK_LIMIT} each). Sending {@code ON}: one {@code receipt_mismatch} per order. Sending {@code OFF}: the receipts
     * were never going to be there, so one WARN with the counts and no anomaly (payments made before receipts are switched on do not spam errors).
     */
    void consistency(Instant now) {
        Instant since = now.minus(CHECK_WINDOW);
        List<UUID> unreceipted = receipts.paidWithoutReceipt(since, now.minus(CHECK_AFTER_PAYMENT), CHECK_LIMIT);
        List<UUID> unannulled = receipts.refundedWithoutCancellation(since, CHECK_LIMIT);
        if (!settings.sending()) {
            if (!unreceipted.isEmpty() || !unannulled.isEmpty()) {
                log.warn("npd receipts are off: paid_without_receipt={} refunded_not_annulled={}", unreceipted.size(), unannulled.size());
            }
            return;
        }
        for (UUID orderId : unreceipted) applier.anomaly(Anomaly.RECEIPT_MISMATCH, orderId, null);
        for (UUID orderId : unannulled) applier.anomaly(Anomaly.RECEIPT_MISMATCH, orderId, null);
    }

    void attempt(Claim claim) {
        if (claim.receipt().state() == State.CANCEL_PENDING) {
            cancel(claim);
        } else {
            register(claim);
        }
    }

    private void register(Claim claim) {
        UUID orderId = claim.receipt().orderId();
        NpdReceipt receipt = receipts.begin(orderId, clock.now()).orElse(null);
        if (receipt == null) return;
        if (claim.previous() == State.SENDING) {
            List<MyTaxClient.Found> found = receipts.notOfOtherOrders(orderId, lookup(receipt));
            if (found.size() == 1 && !found.getFirst().cancelled()) {
                receipts.registered(orderId, found.getFirst().receiptUuid(), clock.now());
                log.info("npd receipt found after an unknown outcome order_id={}", orderId);
                return;
            }
            if (!found.isEmpty()) {
                // Two receipts with one fingerprint, or one that was annulled by someone: not ours to guess.
                permanent(orderId, "AMBIGUOUS_RECEIPT", clock.now());
                return;
            }
            // The wait before a resend counts from the send, not from the claim or the lookup.
            receipt = receipts.begin(orderId, clock.now()).orElse(null);
            if (receipt == null) return;
        }
        if (receipt.cancelRequested()) {
            // Refunded since the claim, and nothing is registered: there is nothing to send and nothing to annul.
            receipts.neverRegistered(orderId, clock.now());
            return;
        }
        String uuid = client.registerIncome(receipt.serviceName(), receipt.amountKopecks(), receipt.operationTime());
        receipts.registered(orderId, uuid, clock.now());
    }

    /** The lookup is not the request: a refusal of it says nothing about the receipt, which stays "unknown" and is looked up again later. */
    private List<MyTaxClient.Found> lookup(NpdReceipt receipt) {
        try {
            return client.findIncomes(receipt.operationTime(), receipt.amountKopecks(), receipt.serviceName());
        } catch (MyTaxException failure) {
            if (failure.outcome() == Outcome.REJECTED) throw new MyTaxException(Outcome.MAYBE_SENT, failure.code(), failure.status(), failure.operation());
            throw failure;
        }
    }

    private void cancel(Claim claim) {
        UUID orderId = claim.receipt().orderId();
        NpdReceipt receipt = receipts.begin(orderId, clock.now()).orElse(null);
        if (receipt == null) return;
        try {
            // An earlier attempt may have annulled the receipt without the answer reaching us.
            if (claim.receipt().attempts() > 1 && client.isCancelled(receipt.receiptUuid())) {
                receipts.cancelled(orderId, clock.now());
                return;
            }
            client.cancelIncome(receipt.receiptUuid());
        } catch (MyTaxException failure) {
            if (failure.outcome() != Outcome.REJECTED || !client.isCancelled(receipt.receiptUuid())) throw failure;
        }
        receipts.cancelled(orderId, clock.now());
    }

    /** @return whether the pass must stop (the login is refused) */
    private boolean failed(Claim claim, MyTaxException failure) {
        NpdReceipt receipt = claim.receipt();
        UUID orderId = receipt.orderId();
        Instant now = clock.now();
        boolean cancelling = receipt.state() == State.CANCEL_PENDING;
        State resume = cancelling ? State.CANCEL_PENDING : State.SENDING;
        State unsent = cancelling ? State.CANCEL_PENDING : claim.previous();
        // The one line of this failure: the client logs nothing.
        log.warn("npd receipt attempt failed order_id={} operation={} outcome={} code={}", orderId, failure.operation(), failure.outcome(), failure.code());
        switch (failure.outcome()) {
            case NOT_SENT -> later(orderId, unsent, failure.code(), now);
            case MAYBE_SENT -> later(orderId, resume, failure.code(), now);
            case AUTH -> {
                if (!"AUTH_BLOCKED".equals(failure.code())) applier.anomaly(Anomaly.RECEIPT_AUTH, orderId, null);
                receipts.release(orderId, unsent, failure.code(), now);
                return true;
            }
            case REJECTED -> permanent(orderId, failure.code(), now);
        }
        return false;
    }

    private void later(UUID orderId, State to, String code, Instant now) {
        if (receipts.retryLater(orderId, to, code, now)) applier.anomaly(Anomaly.RECEIPT_FAILED, orderId, null);
    }

    private void permanent(UUID orderId, String code, Instant now) {
        if (receipts.failPermanently(orderId, code, now)) applier.anomaly(Anomaly.RECEIPT_FAILED, orderId, null);
    }

    /** A claimed receipt the pass did not get to (the login was refused): it goes back to where it was, due after the shortest backoff, uncounted. */
    private void release(Claim claim) {
        State back = claim.receipt().state() == State.CANCEL_PENDING ? State.CANCEL_PENDING : claim.previous();
        receipts.release(claim.receipt().orderId(), back, "AUTH_BLOCKED", clock.now());
    }
}
