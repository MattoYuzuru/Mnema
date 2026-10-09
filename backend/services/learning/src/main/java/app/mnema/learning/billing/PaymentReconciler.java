package app.mnema.learning.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The schedule of {@link PaymentReconciliation}. It exists only for the {@code worker} and {@code all} roles ({@code learning.runtime.roles}), like the other
 * workers: an {@code api} process never polls the bank on a timer. {@code learning.billing.reconcile-interval} is both the delay between passes and the
 * shortest gap between two looks at one order; the pass is idempotent, so a lost or doubled run changes nothing.
 */
@Component
@ConditionalOnExpression("'${learning.runtime.roles:all}'.trim().toLowerCase() == 'worker' or "
        + "'${learning.runtime.roles:all}'.trim().toLowerCase() == 'all'")
class PaymentReconciler {
    private static final Logger log = LoggerFactory.getLogger(PaymentReconciler.class);

    private final PaymentReconciliation reconciliation;

    PaymentReconciler(PaymentReconciliation reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Scheduled(initialDelayString = "${learning.billing.reconcile-interval:PT2M}", fixedDelayString = "${learning.billing.reconcile-interval:PT2M}")
    void reconcile() {
        try {
            reconciliation.runOnce();
        } catch (RuntimeException failure) {
            log.warn("billing reconcile pass failed error_type={}", failure.getClass().getSimpleName());
        }
    }
}
