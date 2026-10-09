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
 * operator ({@code receipt_failed}). A request the service refused (4xx) is not retried blindly: it fails permanently for an operator.
 *
 * <p>A refund of a registered receipt annuls it ({@code «Возврат средств»}); before a retried annulment the receipt is read, so an annulment whose answer was lost
 * is recognised. A rejected login stops the pass and blocks logging in for {@link MyTaxClient#LOGIN_BACKOFF} ({@code receipt_auth}).
 */
@Service
class NpdReceiptWorker {
    private static final Logger log = LoggerFactory.getLogger(NpdReceiptWorker.class);
    static final int BATCH = 20;
    private static final int CHECK_LIMIT = 100;
    /** A paid order younger than this is the worker's business first; the daily check reports older ones without a registered receipt. */
    static final Duration CHECK_AFTER_PAYMENT = Duration.ofDays(1);
    static final Duration CHECK_EVERY = Duration.ofDays(1);

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

    /** PAID orders older than a day without a registered receipt, and REFUNDED orders whose receipt is not annulled. */
    void consistency(Instant now) {
        for (UUID orderId : receipts.paidWithoutReceipt(now.minus(CHECK_AFTER_PAYMENT), CHECK_LIMIT)) applier.anomaly(Anomaly.RECEIPT_MISMATCH, orderId, null);
        for (UUID orderId : receipts.refundedWithoutCancellation(CHECK_LIMIT)) applier.anomaly(Anomaly.RECEIPT_MISMATCH, orderId, null);
    }

    private void attempt(Claim claim) {
        NpdReceipt receipt = claim.receipt();
        Instant now = clock.now();
        if (receipt.state() == State.CANCEL_PENDING) {
            cancel(receipt, now);
        } else {
            register(claim, now);
        }
    }

    private void register(Claim claim, Instant now) {
        NpdReceipt receipt = claim.receipt();
        if (claim.previous() == State.SENDING) {
            List<MyTaxClient.Found> found = lookup(receipt);
            if (found.size() == 1 && !found.getFirst().cancelled()) {
                receipts.registered(receipt.orderId(), found.getFirst().receiptUuid(), now);
                log.info("npd receipt found after an unknown outcome order_id={}", receipt.orderId());
                return;
            }
            if (!found.isEmpty()) {
                // Two receipts with one fingerprint, or one that was annulled by someone: not ours to guess.
                permanent(receipt.orderId(), "AMBIGUOUS_RECEIPT", now);
                return;
            }
            if (receipt.cancelRequested()) {
                receipts.neverRegistered(receipt.orderId(), now);
                return;
            }
        }
        String uuid = client.registerIncome(receipt.serviceName(), receipt.amountKopecks(), receipt.operationTime());
        receipts.registered(receipt.orderId(), uuid, now);
    }

    /** The lookup is not the request: a refusal of it says nothing about the receipt, which stays "unknown" and is looked up again later. */
    private List<MyTaxClient.Found> lookup(NpdReceipt receipt) {
        try {
            return client.findIncomes(receipt.operationTime(), receipt.amountKopecks(), receipt.serviceName());
        } catch (MyTaxException failure) {
            if (failure.outcome() == Outcome.REJECTED) throw new MyTaxException(Outcome.MAYBE_SENT, failure.code(), failure.status());
            throw failure;
        }
    }

    private void cancel(NpdReceipt receipt, Instant now) {
        try {
            // An earlier attempt may have annulled the receipt without the answer reaching us.
            if (receipt.attempts() > 1 && client.isCancelled(receipt.receiptUuid())) {
                receipts.cancelled(receipt.orderId(), now);
                return;
            }
            client.cancelIncome(receipt.receiptUuid());
        } catch (MyTaxException failure) {
            if (failure.outcome() != Outcome.REJECTED || !client.isCancelled(receipt.receiptUuid())) throw failure;
        }
        receipts.cancelled(receipt.orderId(), now);
    }

    /** @return whether the pass must stop (the login is refused) */
    private boolean failed(Claim claim, MyTaxException failure) {
        NpdReceipt receipt = claim.receipt();
        UUID orderId = receipt.orderId();
        Instant now = clock.now();
        boolean cancelling = receipt.state() == State.CANCEL_PENDING;
        State resume = cancelling ? State.CANCEL_PENDING : State.SENDING;
        log.warn("npd receipt attempt failed order_id={} outcome={} code={}", orderId, failure.outcome(), failure.code());
        switch (failure.outcome()) {
            case NOT_SENT -> later(orderId, cancelling ? State.CANCEL_PENDING : claim.previous(), failure.code(), now);
            case MAYBE_SENT -> later(orderId, resume, failure.code(), now);
            case AUTH -> {
                if (!"AUTH_BLOCKED".equals(failure.code())) applier.anomaly(Anomaly.RECEIPT_AUTH, orderId, null);
                later(orderId, cancelling ? State.CANCEL_PENDING : claim.previous(), failure.code(), now);
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

    /** A claimed receipt the pass did not get to (the login was refused): it goes back to where it was, due after the backoff. */
    private void release(Claim claim) {
        State back = claim.receipt().state() == State.CANCEL_PENDING ? State.CANCEL_PENDING : claim.previous();
        later(claim.receipt().orderId(), back, "AUTH_BLOCKED", clock.now());
    }
}
