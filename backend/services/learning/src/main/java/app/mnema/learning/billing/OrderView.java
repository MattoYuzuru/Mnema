package app.mnema.learning.billing;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The {@code Order} of {@code contracts/billing}: what the paywall and the return page show. Members are written even when null. {@code paymentUrl} is
 * set only while the order is {@code PENDING} and its link has not expired; {@code CREATED} (Init has not answered) is reported as {@code PENDING}.
 * {@code receiptUrl} is the printable «Мой налог» receipt, set once it is registered (and while the order is {@code PAID}).
 */
record OrderView(String orderId, String plan, String period, String status, long amountKopecks, long listPriceKopecks, Integer discountPercent,
                 String paymentUrl, String expiresAt, String createdAt, String paidAt, String periodStart, String periodEnd,
                 String receiptUrl) {
    static OrderView of(BillingOrder order, Instant now, String receiptUrl) {
        boolean linkOpen = order.status() == OrderStatus.PENDING && order.paymentUrl() != null && order.expiresAt().isAfter(now);
        return new OrderView(order.orderId().toString(), order.plan().name(), BillingOrder.PERIOD, order.status().wire(), order.amountKopecks(),
                order.listPriceKopecks(), order.discountPercent(), linkOpen ? order.paymentUrl() : null, wire(order.expiresAt()),
                wire(order.createdAt()), wire(order.paidAt()), wire(order.periodStart()), wire(order.periodEnd()),
                receiptUrl);
    }

    private static String wire(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
