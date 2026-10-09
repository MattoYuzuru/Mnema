package app.mnema.learning.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The schedule of {@link NpdReceiptWorker}. It exists only for the {@code worker} and {@code all} roles ({@code learning.runtime.roles}), like
 * {@link PaymentReconciler}: an {@code api} process never calls «Мой налог» on a timer. The pass is idempotent and claims rows with
 * {@code FOR UPDATE SKIP LOCKED}, so a doubled or lost run changes nothing.
 */
@Component
@ConditionalOnExpression("'${learning.runtime.roles:all}'.trim().toLowerCase() == 'worker' or "
        + "'${learning.runtime.roles:all}'.trim().toLowerCase() == 'all'")
class NpdReceiptSchedule {
    private static final Logger log = LoggerFactory.getLogger(NpdReceiptSchedule.class);

    private final NpdReceiptWorker worker;

    NpdReceiptSchedule(NpdReceiptWorker worker) {
        this.worker = worker;
    }

    @Scheduled(initialDelayString = "${learning.billing.npd.interval:PT1M}", fixedDelayString = "${learning.billing.npd.interval:PT1M}")
    void run() {
        try {
            worker.runOnce();
        } catch (RuntimeException failure) {
            log.warn("npd receipt pass failed error_type={}", failure.getClass().getSimpleName());
        }
    }
}
