package app.mnema.learning.billing;

import app.mnema.learning.promo.PromoDiscounts;
import app.mnema.learning.usage.EntitlementInbox;
import app.mnema.learning.usage.UsageCalendar;
import app.mnema.learning.usage.UsageClock;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The only writer of an order's status after {@code Init}: it applies what the bank said ({@code GetState}) to the order under its row lock, so a
 * notification, the return page and the reconciler of one order take turns and the outcome does not depend on who came first.
 *
 * <p>The state machine is {@code contracts/billing}'s. A bank {@code CONFIRMED} with the order's own terminal, order id and amount moves an order that is
 * not yet paid to {@code PAID}: in the same transaction the month goes to {@link EntitlementInbox} ({@code billing:{orderId}}, {@code BILLING}), starting at
 * the later of now and the end of the account's latest paid month of that plan, and the promo discount the order used is consumed. {@code PAID} is
 * absorbing for granting: a repeated {@code CONFIRMED} grants nothing, a later {@code REJECTED} takes nothing back, a refund marks the order
 * {@code REFUNDED} but does not withdraw the entitlement (that policy is #392). Every call appends one {@code billing_event}. The same transactions queue the
 * «Мой налог» receipt of a paid order and the annulment of a fully refunded one ({@link NpdReceipts}).
 *
 * <p>Money that needs an operator is an {@link Anomaly}: one log line {@code billing anomaly kind=…} (ERROR when money is at stake) and the counter
 * {@code mnema_billing_anomalies_total{kind}}.
 */
@Service
class PaymentStateApplier {
    private static final Logger log = LoggerFactory.getLogger(PaymentStateApplier.class);
    /** The bank's final statuses of a payment that took no money (developer.tbank.ru/eacq/intro/developer/operation-statuses, read 2026-10-09). */
    private static final Set<String> FAILED = Set.of("REJECTED", "AUTH_FAIL", "CANCELED", "DEADLINE_EXPIRED", "ATTEMPTS_EXPIRED");
    /** Money returned after an authorization or a confirmation; every other status (AUTHORIZING, 3DS_CHECKING, REFUNDING, …) is still in progress. */
    private static final Set<String> REVERSED = Set.of("REVERSED", "PARTIAL_REVERSED", "REFUNDED", "PARTIAL_REFUNDED");
    private static final Set<String> PARTIAL = Set.of("PARTIAL_REVERSED", "PARTIAL_REFUNDED");
    private static final Set<String> UNPAID_FORM = Set.of("NEW", "FORM_SHOWED");
    /** How long past its link a form that was never completed stays pending before the reconciler gives up on it. */
    static final Duration EXPIRY_GRACE = Duration.ofHours(24);
    /** How long past its link an order may stay unfinished in any other bank status before the reconciler stops asking and hands it to an operator. */
    static final Duration REVIEW_AFTER = Duration.ofHours(72);

    /** Money an operator must look at; {@code money} anomalies are logged as ERROR, the others as WARN. */
    enum Anomaly {
        DUPLICATE_PAYMENT(true), AMOUNT_MISMATCH(true), PARTIAL_REFUND(true), STALE_ORDER(true), DISCOUNT_SPENT(false),
        /** A receipt «Мой налог» that failed for good, was not registered after {@link NpdReceipts#ALERT_ATTEMPTS} attempts or is ambiguous. */
        RECEIPT_FAILED(true),
        /** A receipt still not registered after the 9th of the month after the payment (422-ФЗ art. 14). */
        RECEIPT_OVERDUE(true),
        /** «Мой налог» refused the login of the taxpayer (password changed, account blocked). */
        RECEIPT_AUTH(true),
        /** The daily check: a paid order without a registered receipt, or a refunded order whose receipt is not annulled. */
        RECEIPT_MISMATCH(true);

        private final boolean money;

        Anomaly(boolean money) {
            this.money = money;
        }
    }

    /** What asked: the log says it, the audit row says {@code GET_STATE} for the first two and {@code RECONCILE} for the third. */
    enum Trigger { NOTIFICATION, GET_STATE, RECONCILE }

    /** {@code IGNORED}: the state did not belong to the order; {@code CHANGED}: the order row was written; {@code UNCHANGED}: nothing to do. */
    enum Outcome { IGNORED, CHANGED, UNCHANGED }

    private final BillingRepository repository;
    private final BillingSettings settings;
    private final EntitlementInbox inbox;
    private final PromoDiscounts discounts;
    private final UsageCalendar calendar;
    private final UsageClock clock;
    private final MeterRegistry meters;
    private final NpdReceipts receipts;

    PaymentStateApplier(BillingRepository repository, BillingSettings settings, EntitlementInbox inbox, PromoDiscounts discounts,
                        UsageCalendar calendar, UsageClock clock, MeterRegistry meters, NpdReceipts receipts) {
        this.repository = repository;
        this.settings = settings;
        this.inbox = inbox;
        this.discounts = discounts;
        this.calendar = calendar;
        this.clock = clock;
        this.meters = meters;
        this.receipts = receipts;
    }

    /** Applies {@code state} to the order {@code orderId}. */
    @Transactional
    public Outcome apply(UUID orderId, BankState state, Trigger trigger) {
        return applyLocked(orderId, state, trigger, null);
    }

    /**
     * Applies the {@code state} a notification asked for and records {@code notification} in the same transaction: a notification counts as handled only once
     * its answer is applied, so a bank retry after a failure is processed in full.
     */
    @Transactional
    public Outcome applyNotified(UUID orderId, BankState state, BillingRepository.Event notification) {
        return applyLocked(orderId, state, Trigger.NOTIFICATION, notification);
    }

    private Outcome applyLocked(UUID orderId, BankState state, Trigger trigger, BillingRepository.Event notification) {
        Instant now = clock.now();
        String source = trigger == Trigger.RECONCILE ? "RECONCILE" : "GET_STATE";
        Optional<BillingOrder> locked = repository.lock(orderId);
        if (locked.isEmpty()) return Outcome.IGNORED;
        BillingOrder order = locked.get();
        if (notification != null) repository.insertEvent(notification, now);
        if (!settings.isOwnTerminal(state.terminalKey()) || !orderId.equals(TBankClient.orderId(state.orderId()))) {
            log.warn("billing bank state ignored order_id={} reason=foreign_terminal_or_order source={}", orderId, trigger);
            event(order, state, source, "IGNORED", now);
            return Outcome.IGNORED;
        }
        boolean confirmed = "CONFIRMED".equals(state.status()) && state.success();
        BillingOrder next = order;
        if (order.paymentId() == null) {
            next = order.withPayment(state.paymentId(), null);
        } else if (!order.paymentId().equals(state.paymentId())) {
            // A second bank payment of the same order (an Init that raced a retry). Only money taken for an order that is not yet paid grants access.
            if (confirmed && order.status() == OrderStatus.PAID) {
                anomaly(Anomaly.DUPLICATE_PAYMENT, orderId, state.paymentId());
                event(order, state, source, "DUPLICATE_PAYMENT", now);
                return Outcome.UNCHANGED;
            }
            if (!confirmed || !grantable(order.status())) {
                event(order, state, source, "OTHER_PAYMENT", now);
                return Outcome.UNCHANGED;
            }
            next = order.withPayment(state.paymentId(), null);
        }
        String outcome;
        if (confirmed) {
            if (!grantable(order.status())) {
                outcome = "NO_CHANGE";
            } else if (state.amount() == null || state.amount() != order.amountKopecks()) {
                anomaly(Anomaly.AMOUNT_MISMATCH, orderId, state.paymentId());
                next = next.moved(OrderStatus.REVIEW, state.status(), "AMOUNT_MISMATCH");
                outcome = "REVIEW";
            } else {
                next = grant(next, now, state.status());
                outcome = "PAID";
            }
        } else if (FAILED.contains(state.status())) {
            if (order.status().open()) {
                next = next.moved(OrderStatus.FAILED, state.status(), state.status());
                outcome = "FAILED";
            } else {
                outcome = "NO_CHANGE";
            }
        } else if (REVERSED.contains(state.status())) {
            if (order.status() == OrderStatus.PAID) {
                log.warn("billing order refunded, entitlement kept order_id={} bank_status={}", orderId, state.status());
                next = next.moved(OrderStatus.REFUNDED, state.status(), null);
                outcome = "REFUNDED";
                if (PARTIAL.contains(state.status())) {
                    // A part of the money is still ours: the receipt of the whole payment must neither stay nor simply disappear. An operator annuls it and
                    // registers the remainder (the daily check reports the refunded order whose receipt is not annulled).
                    anomaly(Anomaly.PARTIAL_REFUND, orderId, state.paymentId());
                } else {
                    receipts.cancelOnRefund(orderId, now);
                }
            } else if ("PARTIAL_REFUNDED".equals(state.status()) && grantable(order.status())) {
                // A confirmation this order never saw, then part of the money returned: some of it is still taken and nothing was granted.
                anomaly(Anomaly.PARTIAL_REFUND, orderId, state.paymentId());
                next = next.moved(OrderStatus.REVIEW, state.status(), "PARTIAL_REFUND");
                outcome = "REVIEW";
            } else if (order.status().open()) {
                next = next.moved(OrderStatus.FAILED, state.status(), state.status());
                outcome = "FAILED";
            } else {
                outcome = "NO_CHANGE";
            }
        } else if (order.status().open()) {
            next = next.moved(OrderStatus.PENDING, state.status(), null);
            outcome = "PENDING";
        } else {
            outcome = "NO_CHANGE";
        }
        event(order, state, source, outcome, now);
        if (next.equals(order)) return Outcome.UNCHANGED;
        repository.save(next, now);
        if (next.status() != order.status()) {
            log.info("billing order transition order_id={} from={} to={} bank_status={} source={}", orderId, order.status(), next.status(),
                    state.status(), trigger);
        }
        return Outcome.CHANGED;
    }

    /** Records that {@code Init} answered: the order is waiting for the payer. Keeps the stored payment when another answer got there first. */
    @Transactional
    public void initialized(UUID orderId, TBankClient.InitResult result) {
        Instant now = clock.now();
        Optional<BillingOrder> locked = repository.lock(orderId);
        if (locked.isEmpty()) return;
        BillingOrder order = locked.get();
        if (order.status() != OrderStatus.CREATED) {
            repository.insertEvent(new BillingRepository.Event(orderId, result.paymentId(), "INIT", result.status(), true, "0",
                    order.amountKopecks(), "SKIPPED"), now);
            return;
        }
        BillingOrder next = (order.paymentId() == null ? order.withPayment(result.paymentId(), result.paymentUrl()) : order)
                .moved(OrderStatus.PENDING, result.status(), null);
        repository.save(next, now);
        repository.insertEvent(new BillingRepository.Event(orderId, next.paymentId(), "INIT", result.status(), true, "0", order.amountKopecks(),
                "PENDING"), now);
        log.info("billing order transition order_id={} from={} to={} bank_status={} source=INIT", orderId, order.status(), next.status(), result.status());
    }

    /**
     * Records an {@code Init} the bank refused or did not answer. A refusal opened nothing and the bank would refuse the same {@code OrderId} again, so the
     * order fails {@code INIT_REFUSED} and the next checkout of the purchase opens a new order; after a timeout or a broken connection the bank may have
     * opened a payment, so the order stays {@code CREATED} for its retry, its notification or the reconciler.
     */
    @Transactional
    public void initFailed(UUID orderId, PaymentProviderException failure) {
        Instant now = clock.now();
        Optional<BillingOrder> locked = repository.lock(orderId);
        if (locked.isEmpty()) return;
        BillingOrder order = locked.get();
        boolean refused = failure.reason() == PaymentProviderException.Reason.REFUSED && order.status() == OrderStatus.CREATED && order.paymentId() == null;
        if (refused) {
            repository.save(order.moved(OrderStatus.FAILED, null, "INIT_REFUSED"), now);
            log.info("billing order transition order_id={} from={} to={} bank_status=- source=INIT", orderId, order.status(), OrderStatus.FAILED);
        }
        repository.insertEvent(new BillingRepository.Event(orderId, null, "INIT", null, false, failure.errorCode(), null,
                refused ? "FAILED" : failure.reason().name()), now);
    }

    /** Fails an order whose {@code Init} never succeeded and whose link time is over ({@code INIT_FAILED}). */
    @Transactional
    public boolean failUninitialized(UUID orderId) {
        Instant now = clock.now();
        return repository.lock(orderId).filter(order -> order.status() == OrderStatus.CREATED && order.paymentId() == null
                && order.expiresAt().isBefore(now)).map(order -> fail(order, "INIT_FAILED", now)).orElse(false);
    }

    /** Fails a pending order whose payment form was never completed and whose link ended more than a day ago ({@code EXPIRED}). */
    @Transactional
    public boolean failAbandoned(UUID orderId) {
        Instant now = clock.now();
        return repository.lock(orderId).filter(order -> order.status() == OrderStatus.PENDING
                && now.isAfter(order.expiresAt().plus(EXPIRY_GRACE))
                && (order.providerStatus() == null || UNPAID_FORM.contains(order.providerStatus()))).map(order -> fail(order, "EXPIRED", now)).orElse(false);
    }

    /**
     * Stops asking about an unfinished order whose link ended more than {@link #REVIEW_AFTER} ago and that the bank still reports in progress (or does not
     * answer about): it goes to {@code REVIEW} ({@code STALE}) for an operator.
     */
    @Transactional
    public boolean holdStale(UUID orderId) {
        Instant now = clock.now();
        return repository.lock(orderId).filter(order -> order.status().open() && now.isAfter(order.expiresAt().plus(REVIEW_AFTER))).map(order -> {
            repository.save(order.moved(OrderStatus.REVIEW, order.providerStatus(), "STALE"), now);
            repository.insertEvent(new BillingRepository.Event(order.orderId(), order.paymentId(), "RECONCILE", order.providerStatus(), null, null, null,
                    "REVIEW"), now);
            anomaly(Anomaly.STALE_ORDER, order.orderId(), order.paymentId());
            log.info("billing order transition order_id={} from={} to={} bank_status={} source=RECONCILE", order.orderId(), order.status(),
                    OrderStatus.REVIEW, order.providerStatus() == null ? "-" : order.providerStatus());
            return true;
        }).orElse(false);
    }

    /**
     * Closes the unfinished orders of {@code owner} that hold the discount of {@code codeId} past their link time ({@code EXPIRED}), so the discount can price
     * the next purchase. A payment that still reaches such an order is granted all the same, so closing it loses no money. Runs in the caller's transaction.
     */
    @Transactional
    public void expireDiscountHolders(UUID owner, UUID codeId) {
        Instant now = clock.now();
        for (BillingOrder order : repository.lockExpiredDiscountHolders(owner, codeId, now)) fail(order, "EXPIRED", now);
    }

    private boolean fail(BillingOrder order, String reason, Instant now) {
        repository.save(order.moved(OrderStatus.FAILED, order.providerStatus(), reason), now);
        repository.insertEvent(new BillingRepository.Event(order.orderId(), order.paymentId(), "RECONCILE", order.providerStatus(), null, null, null,
                "FAILED"), now);
        log.info("billing order transition order_id={} from={} to={} bank_status={} source=RECONCILE", order.orderId(), order.status(),
                OrderStatus.FAILED, order.providerStatus() == null ? "-" : order.providerStatus());
        return true;
    }

    /** The orders money may still turn into a paid month: not yet paid, and not refunded or held for review. */
    private static boolean grantable(OrderStatus status) {
        return status == OrderStatus.CREATED || status == OrderStatus.PENDING || status == OrderStatus.FAILED;
    }

    /** Marks the order paid and hands the month to usage; runs in the caller's transaction, under the order's row lock. */
    private BillingOrder grant(BillingOrder order, Instant now, String bankStatus) {
        // Two paid orders of one plan of one account must queue, not overlap: take the account's grant lock before reading the latest period end.
        repository.lockAccount("grant", order.owner());
        Instant start = repository.latestPaidPeriodEnd(order.owner(), order.plan(), now).filter(end -> end.isAfter(now)).orElse(now);
        Instant end = calendar.plusMonths(start, 1);
        String snapshotId = "billing:" + order.orderId();
        inbox.accept(new EntitlementInbox.Snapshot(snapshotId, order.owner(), order.plan(), "BILLING", start, end,
                JsonNodeFactory.instance.objectNode().put("allowances", "catalog").put("plan", order.plan().name()), end));
        if (order.discountCodeId() != null && !discounts.consume(order.owner(), order.discountCodeId())) {
            // The money is taken, so the month is granted; the discount was spent or replaced meanwhile and an operator may want to know.
            anomaly(Anomaly.DISCOUNT_SPENT, order.orderId(), order.paymentId());
        }
        BillingOrder paid = order.paid(bankStatus, now, start, end, snapshotId);
        // The income needs a «Мой налог» receipt (422-ФЗ): queued in this transaction, exactly once, so a paid order can never lack one.
        receipts.enqueue(paid, now);
        return paid;
    }

    void anomaly(Anomaly kind, UUID orderId, String paymentId) {
        String name = kind.name().toLowerCase(Locale.ROOT);
        if (kind.money) {
            log.error("billing anomaly kind={} order_id={} payment_id={}", name, orderId, paymentId == null ? "-" : paymentId);
        } else {
            log.warn("billing anomaly kind={} order_id={} payment_id={}", name, orderId, paymentId == null ? "-" : paymentId);
        }
        meters.counter("mnema_billing_anomalies_total", "kind", name).increment();
    }

    private void event(BillingOrder order, BankState state, String source, String outcome, Instant now) {
        repository.insertEvent(new BillingRepository.Event(order.orderId(), state.paymentId(), source, state.status(), state.success(),
                state.errorCode(), state.amount(), outcome), now);
    }
}
