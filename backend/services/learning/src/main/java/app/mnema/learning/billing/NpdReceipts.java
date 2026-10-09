package app.mnema.learning.billing;

import app.mnema.learning.billing.NpdReceipt.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The state machine of the «Мой налог» receipts ({@code billing_receipt}, V47) and the only writer of their state. Every method is one short transaction
 * under the receipt's row lock; none of them calls the tax service. {@link PaymentStateApplier} enqueues in the transaction that marks an order {@code PAID}
 * and cancels in the one that marks it {@code REFUNDED}; {@link NpdReceiptWorker} claims, calls the service outside any transaction and reports the result
 * here.
 *
 * <p>{@code PENDING → SENDING → REGISTERED → CANCEL_PENDING → CANCELLED}; {@code FAILED_PERMANENT} for a refusal retrying cannot cure. {@code SENDING} is
 * written by the claim, before the request, so a crash or a lost answer leaves a row that is looked up before it is sent again. An order in {@code REVIEW}
 * (a payment that does not match the order) gets no receipt: an operator decides what, if anything, to register.
 */
@Service
class NpdReceipts {
    private static final Logger log = LoggerFactory.getLogger(NpdReceipts.class);
    /** The first retry comes after this, then it doubles up to {@link #MAX_BACKOFF}. */
    static final Duration MIN_BACKOFF = Duration.ofMinutes(1);
    static final Duration MAX_BACKOFF = Duration.ofHours(6);
    /**
     * The lease of a claimed row, and the least wait after a request whose outcome is unknown: the incomes list may lag a little behind the registration, so
     * a receipt is looked up this long after the request, not at once.
     */
    static final Duration SETTLE = Duration.ofMinutes(5);
    /** After this many attempts without a registered receipt an operator is told once; the worker keeps trying until the deadline and beyond. */
    static final int ALERT_ATTEMPTS = 10;
    private static final String PRINT = "/print";

    private final NpdReceiptRepository repository;
    private final NpdSettings settings;

    NpdReceipts(NpdReceiptRepository repository, NpdSettings settings) {
        this.repository = repository;
        this.settings = settings;
    }

    /** The service name on the receipt, from the order's plan: «Подписка Мнема Plus на 1 месяц». */
    static String serviceName(BillingOrder order) {
        String plan = order.plan().name();
        return "Подписка Мнема " + plan.charAt(0) + plan.substring(1).toLowerCase(Locale.ROOT) + " на 1 месяц";
    }

    /**
     * The end of the legal term: 422-ФЗ art. 14 requires a receipt for a payment not made by cash or an electronic means of payment by the 9th of the month
     * after the one the money was received in. The returned instant is the start of the 10th, Europe/Moscow.
     */
    static Instant deadline(Instant operationTime) {
        return YearMonth.from(operationTime.atZone(MyTaxClient.MOSCOW)).plusMonths(1).atDay(10).atStartOfDay(MyTaxClient.MOSCOW).toInstant();
    }

    static Duration backoff(int attempts) {
        long minutes = MIN_BACKOFF.toMinutes() << Math.min(Math.max(attempts - 1, 0), 20);
        return minutes >= MAX_BACKOFF.toMinutes() ? MAX_BACKOFF : Duration.ofMinutes(minutes);
    }

    /** Queues the receipt of a paid order, once; runs in the caller's transaction ({@link PaymentStateApplier}, as the order becomes {@code PAID}). */
    @Transactional
    public void enqueue(BillingOrder paid, Instant now) {
        Instant operationTime = paid.paidAt();
        if (repository.insert(NpdReceipt.pending(paid.orderId(), serviceName(paid), paid.amountKopecks(), operationTime, deadline(operationTime), now))) {
            log.info("npd receipt queued order_id={}", paid.orderId());
        }
    }

    /** Annuls the receipt of a refunded order; runs in the caller's transaction. Nothing is called here: the worker sends the cancellation. */
    @Transactional
    public void cancelOnRefund(UUID orderId, Instant now) {
        Optional<NpdReceipt> locked = repository.lock(orderId);
        if (locked.isEmpty()) {
            log.warn("npd receipt missing for refunded order order_id={}", orderId);
            return;
        }
        NpdReceipt receipt = locked.get();
        switch (receipt.state()) {
            // Nothing was registered: there is nothing to annul, and the receipt is never sent.
            case PENDING -> save(receipt.in(State.CANCELLED, null, now, null), now);
            // A request may be on the wire: the worker annuls the receipt as soon as it knows whether there is one.
            case SENDING -> save(receipt.withCancelRequested(), now);
            case REGISTERED -> save(receipt.withAttempts(0).in(State.CANCEL_PENDING, receipt.receiptUuid(), now, null), now);
            // Already annulled or on its way; a receipt an operator has to look at stays theirs (the daily check reports the refunded order).
            case CANCEL_PENDING, CANCELLED, FAILED_PERMANENT -> { }
        }
        log.info("npd receipt refund order_id={} was={}", orderId, receipt.state());
    }

    /** The printable receipt of a registered order, or null: the buyer's link; it needs no login. */
    @Transactional(readOnly = true)
    public String receiptUrl(UUID orderId) {
        if (settings.inn() == null) return null;
        return repository.find(orderId).filter(receipt -> receipt.state() == State.REGISTERED && receipt.receiptUuid() != null)
                .map(receipt -> printUrl(settings, receipt.receiptUuid())).orElse(null);
    }

    static String printUrl(NpdSettings settings, String receiptUuid) {
        return settings.baseUrl + "/receipt/" + settings.inn() + "/" + receiptUuid + PRINT;
    }

    /** Takes the receipts that are due: see {@link NpdReceiptRepository#claimDue}. */
    @Transactional
    public List<NpdReceiptRepository.Claim> claim(Instant now, int limit) {
        return repository.claimDue(now, now.plus(SETTLE), limit);
    }

    /** The service registered the receipt {@code receiptUuid}; when a refund came meanwhile it is annulled next. */
    @Transactional
    public void registered(UUID orderId, String receiptUuid, Instant now) {
        repository.lock(orderId).filter(receipt -> receipt.state() == State.SENDING).ifPresent(receipt -> {
            if (receipt.cancelRequested()) {
                save(receipt.withAttempts(0).in(State.CANCEL_PENDING, receiptUuid, now, null), now);
            } else {
                save(receipt.in(State.REGISTERED, receiptUuid, now, null), now);
            }
            log.info("npd receipt registered order_id={} receipt={}", orderId, receiptUuid);
        });
    }

    /** A lookup found no receipt of a request that may have been lost and the order was refunded meanwhile: nothing exists to annul. */
    @Transactional
    public void neverRegistered(UUID orderId, Instant now) {
        repository.lock(orderId).filter(receipt -> receipt.state() == State.SENDING && receipt.cancelRequested()).ifPresent(receipt -> {
            save(receipt.in(State.CANCELLED, null, now, null), now);
            log.info("npd receipt never registered, annulled locally order_id={}", orderId);
        });
    }

    /** The service annulled the receipt of a refunded order. */
    @Transactional
    public void cancelled(UUID orderId, Instant now) {
        repository.lock(orderId).filter(receipt -> receipt.state() == State.CANCEL_PENDING).ifPresent(receipt -> {
            save(receipt.in(State.CANCELLED, receipt.receiptUuid(), now, null), now);
            log.info("npd receipt annulled order_id={} receipt={}", orderId, receipt.receiptUuid());
        });
    }

    /**
     * The attempt did not end: the row goes to {@code to} and is due again after the backoff (and after {@link #SETTLE} when a request may be on the wire).
     *
     * @return whether the attempts reached {@link #ALERT_ATTEMPTS} for the first time, so the operator must be told now
     */
    @Transactional
    public boolean retryLater(UUID orderId, State to, String errorCode, Instant now) {
        Optional<NpdReceipt> locked = repository.lock(orderId).filter(receipt -> receipt.state().open());
        if (locked.isEmpty()) return false;
        NpdReceipt receipt = locked.get();
        Duration wait = backoff(receipt.attempts());
        if (to == State.SENDING && wait.compareTo(SETTLE) < 0) wait = SETTLE;
        NpdReceipt next = receipt.in(to, receipt.receiptUuid(), now.plus(wait), errorCode);
        boolean alert = receipt.attempts() >= ALERT_ATTEMPTS && receipt.failedAlertedAt() == null;
        save(alert ? next.failedAlerted(now) : next, now);
        return alert;
    }

    /** A refusal that retrying cannot cure: an operator decides. */
    @Transactional
    public boolean failPermanently(UUID orderId, String errorCode, Instant now) {
        return repository.lock(orderId).filter(receipt -> receipt.state().open()).map(receipt -> {
            save(receipt.in(State.FAILED_PERMANENT, receipt.receiptUuid(), now, errorCode), now);
            return true;
        }).orElse(false);
    }

    /** Marks the receipts whose legal deadline passed without a registration; each is returned once, for the operator alarm. */
    @Transactional
    public List<UUID> markOverdue(Instant now, int limit) {
        List<NpdReceipt> overdue = repository.lockOverdue(now, limit);
        for (NpdReceipt receipt : overdue) save(receipt.overdueAlerted(now), now);
        return overdue.stream().map(NpdReceipt::orderId).toList();
    }

    /** The consistency check: PAID orders older than {@code paidBefore} without a registered receipt. */
    @Transactional(readOnly = true)
    public List<UUID> paidWithoutReceipt(Instant paidBefore, int limit) {
        return repository.paidWithoutReceipt(paidBefore, limit);
    }

    /** The consistency check: REFUNDED orders whose receipt is not annulled. */
    @Transactional(readOnly = true)
    public List<UUID> refundedWithoutCancellation(int limit) {
        return repository.refundedWithoutCancellation(limit);
    }

    private void save(NpdReceipt receipt, Instant now) {
        repository.save(receipt, now);
    }
}
