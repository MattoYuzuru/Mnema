package app.mnema.learning.billing;

import app.mnema.learning.usage.UsageClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * One pass over the unfinished orders whose notification may never have arrived (a bank that gave up, a deploy at the wrong minute). It claims up to
 * {@value #BATCH} orders {@code FOR UPDATE SKIP LOCKED} in a short transaction that stamps {@code last_checked_at}, so several workers take disjoint orders
 * and an order is looked at once per {@code reconcile-interval}; then, outside that transaction, asks the bank about each and applies the answer like any
 * other trigger. A {@code FAILED} order with a bank payment is asked about too, once per {@link #FAILED_RECHECK}, for {@link #FAILED_WINDOW} after it was
 * created: a late or second attempt on the bank's form may still pay it. An order older than a minute and still {@code CREATED} without a payment is failed
 * {@code INIT_FAILED} once its link time is over; a pending order whose form was never completed a day after its link ended is failed {@code EXPIRED}; any
 * other order still unfinished {@link PaymentStateApplier#REVIEW_AFTER} after its link ended goes to {@code REVIEW} ({@code STALE}) and is not asked about
 * again. A bank failure is logged by the client and the pass continues with the next order.
 */
@Service
class PaymentReconciliation {
    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliation.class);
    static final int BATCH = 50;
    /** A fresh order is the notification's and the return page's business first. */
    private static final Duration SETTLE = Duration.ofMinutes(1);
    private static final Set<String> UNPAID_FORM = Set.of("NEW", "FORM_SHOWED");
    /** How long after its creation a failed order with a bank payment is still asked about. */
    static final Duration FAILED_WINDOW = Duration.ofHours(72);
    /** The gap between two looks at one failed order: a late payment is rare and its notification usually comes first. */
    static final Duration FAILED_RECHECK = Duration.ofHours(1);

    private final BillingRepository repository;
    private final BillingSettings settings;
    private final TBankClient bank;
    private final PaymentStateApplier applier;
    private final UsageClock clock;
    private final TransactionTemplate transaction;

    PaymentReconciliation(BillingRepository repository, BillingSettings settings, TBankClient bank, PaymentStateApplier applier, UsageClock clock,
                          PlatformTransactionManager transactions) {
        this.repository = repository;
        this.settings = settings;
        this.bank = bank;
        this.applier = applier;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** @return how many orders were looked at */
    int runOnce() {
        if (!settings.configured()) return 0;
        Instant now = clock.now();
        List<BillingOrder> claimed = transaction.execute(status ->
                repository.claimOpen(now, now.minus(SETTLE), now.minus(settings.reconcileInterval), now.minus(FAILED_WINDOW), now.minus(FAILED_RECHECK),
                        BATCH));
        if (claimed == null) return 0;
        for (BillingOrder order : claimed) {
            try {
                reconcile(order, now);
            } catch (RuntimeException failure) {
                log.warn("billing reconcile failed order_id={} error_type={}", order.orderId(), failure.getClass().getSimpleName());
            }
        }
        return claimed.size();
    }

    private void reconcile(BillingOrder order, Instant now) {
        if (order.paymentId() == null) {
            // Init never answered: nothing to ask the bank about, and the payer's link time is over.
            if (order.status() == OrderStatus.CREATED && order.expiresAt().isBefore(now)) applier.failUninitialized(order.orderId());
            return;
        }
        try {
            BankState state = bank.getState(order.paymentId());
            applier.apply(order.orderId(), state, PaymentStateApplier.Trigger.RECONCILE);
            if (UNPAID_FORM.contains(state.status()) && now.isAfter(order.expiresAt().plus(PaymentStateApplier.EXPIRY_GRACE))) {
                applier.failAbandoned(order.orderId());
            }
        } catch (PaymentProviderException failure) {
            // Logged by the client; an order the bank never answers about still stops being asked about once it is stale.
        }
        if (order.status().open() && now.isAfter(order.expiresAt().plus(PaymentStateApplier.REVIEW_AFTER))) applier.holdStale(order.orderId());
    }
}
