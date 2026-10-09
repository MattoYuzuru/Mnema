package app.mnema.learning.billing;

import app.mnema.learning.promo.PromoDiscounts;
import app.mnema.learning.usage.EntitlementInbox;
import app.mnema.learning.usage.UsageCalendar;
import app.mnema.learning.usage.UsageClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Duration;
import java.time.Instant;
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
 * {@code REFUNDED} but does not withdraw the entitlement (that policy is #392). Every call appends one {@code billing_event}.
 */
@Service
class PaymentStateApplier {
    private static final Logger log = LoggerFactory.getLogger(PaymentStateApplier.class);
    private static final Set<String> FAILED = Set.of("REJECTED", "AUTH_FAIL", "CANCELED", "DEADLINE_EXPIRED");
    private static final Set<String> REVERSED = Set.of("REVERSED", "PARTIAL_REVERSED", "REFUNDED", "PARTIAL_REFUNDED");
    private static final Set<String> UNPAID_FORM = Set.of("NEW", "FORM_SHOWED");
    /** How long past its link a form that was never completed stays pending before the reconciler gives up on it. */
    static final Duration EXPIRY_GRACE = Duration.ofHours(24);

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

    PaymentStateApplier(BillingRepository repository, BillingSettings settings, EntitlementInbox inbox, PromoDiscounts discounts,
                        UsageCalendar calendar, UsageClock clock) {
        this.repository = repository;
        this.settings = settings;
        this.inbox = inbox;
        this.discounts = discounts;
        this.calendar = calendar;
        this.clock = clock;
    }

    /** Applies {@code state} to the order {@code orderId}. */
    @Transactional
    public Outcome apply(UUID orderId, BankState state, Trigger trigger) {
        Instant now = clock.now();
        String source = trigger == Trigger.RECONCILE ? "RECONCILE" : "GET_STATE";
        Optional<BillingOrder> locked = repository.lock(orderId);
        if (locked.isEmpty()) return Outcome.IGNORED;
        BillingOrder order = locked.get();
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
                log.error("billing duplicate payment order_id={} payment_id={}", orderId, state.paymentId());
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
                log.warn("billing amount mismatch order_id={} payment_id={}", orderId, state.paymentId());
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

    /** Records an {@code Init} the bank refused or did not answer; the order stays {@code CREATED} and a retry asks again. */
    void initFailed(UUID orderId, PaymentProviderException failure) {
        repository.insertEvent(new BillingRepository.Event(orderId, null, "INIT", null, false, failure.errorCode(), null, failure.reason().name()),
                clock.now());
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
            log.warn("billing discount already spent order_id={}", order.orderId());
        }
        return order.paid(bankStatus, now, start, end, snapshotId);
    }

    private void event(BillingOrder order, BankState state, String source, String outcome, Instant now) {
        repository.insertEvent(new BillingRepository.Event(order.orderId(), state.paymentId(), source, state.status(), state.success(),
                state.errorCode(), state.amount(), outcome), now);
    }
}
