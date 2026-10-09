package app.mnema.learning.billing;

import app.mnema.learning.usage.Plan;

import java.time.Instant;
import java.util.UUID;

/**
 * An order as stored in {@code billing_order}. Amounts are kopecks. The period is always one month for now, so it has no field. The copies
 * ({@link #moved}, {@link #withPayment}, {@link #paid}) are what {@code PaymentStateApplier} writes back under the row lock.
 */
record BillingOrder(UUID orderId, UUID owner, Plan plan, OrderStatus status, long amountKopecks, long listPriceKopecks,
                    Integer discountPercent, UUID discountCodeId, String paymentId, String paymentUrl, String providerStatus,
                    String failureReason, String snapshotId, Instant periodStart, Instant periodEnd, Instant paidAt, Instant expiresAt,
                    Instant createdAt, Instant updatedAt, Instant lastCheckedAt, long rowVersion) {
    static final String PERIOD = "MONTH";

    /** A new order, not yet known to the bank. */
    static BillingOrder created(UUID orderId, UUID owner, Plan plan, long amountKopecks, long listPriceKopecks, Integer discountPercent,
                                UUID discountCodeId, Instant now, Instant expiresAt) {
        return new BillingOrder(orderId, owner, plan, OrderStatus.CREATED, amountKopecks, listPriceKopecks, discountPercent, discountCodeId,
                null, null, null, null, null, null, null, null, expiresAt, now, now, null, 0);
    }

    /** The same order in another status; {@code providerStatus} and {@code failureReason} replace the stored ones. */
    BillingOrder moved(OrderStatus to, String newProviderStatus, String newFailureReason) {
        return new BillingOrder(orderId, owner, plan, to, amountKopecks, listPriceKopecks, discountPercent, discountCodeId, paymentId,
                paymentUrl, newProviderStatus, newFailureReason, snapshotId, periodStart, periodEnd, paidAt, expiresAt, createdAt,
                updatedAt, lastCheckedAt, rowVersion);
    }

    /** The same order bound to a bank payment; the link is null when only the id is known. */
    BillingOrder withPayment(String newPaymentId, String newPaymentUrl) {
        return new BillingOrder(orderId, owner, plan, status, amountKopecks, listPriceKopecks, discountPercent, discountCodeId, newPaymentId,
                newPaymentUrl, providerStatus, failureReason, snapshotId, periodStart, periodEnd, paidAt, expiresAt, createdAt, updatedAt,
                lastCheckedAt, rowVersion);
    }

    /** The same order as {@code PAID} with the month it granted. */
    BillingOrder paid(String newProviderStatus, Instant at, Instant start, Instant end, String newSnapshotId) {
        return new BillingOrder(orderId, owner, plan, OrderStatus.PAID, amountKopecks, listPriceKopecks, discountPercent, discountCodeId,
                paymentId, null, newProviderStatus, null, newSnapshotId, start, end, at, expiresAt, createdAt, updatedAt, lastCheckedAt,
                rowVersion);
    }
}
