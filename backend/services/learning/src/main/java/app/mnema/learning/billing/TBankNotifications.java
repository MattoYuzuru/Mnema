package app.mnema.learning.billing;

import app.mnema.learning.platform.json.ContentJsonReader;
import app.mnema.learning.usage.UsageClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Handles one T-Bank payment notification ({@code contracts/billing}). The order of trust is deliberate: a bounded, strictly parsed JSON object; credentials
 * configured; the {@code TerminalKey} is ours (constant time); the {@code Token} signature verifies; only then is anything read. A verified notification is
 * a trigger and not evidence: the state is asked from the bank ({@code GetState}) and that answer is applied by {@link PaymentStateApplier}. {@code OK} is
 * answered only after the apply transaction committed, or for a notification there is nothing to do for (retries would not help); everything else makes
 * the bank retry, hourly for a day and then daily.
 *
 * <p>Nothing here logs a token, a card field or a body; refusals say why in one word.
 */
@Service
class TBankNotifications {
    private static final Logger log = LoggerFactory.getLogger(TBankNotifications.class);
    /** The bank's notifications are a few hundred bytes; the cap bounds what an unauthenticated caller can make this endpoint read. */
    static final int MAX_BYTES = 16 * 1024;
    private static final ContentJsonReader READER = new ContentJsonReader(MAX_BYTES, 6, 512);
    private static final Pattern STATUS = Pattern.compile("[A-Z0-9_]{1,40}");
    private static final Set<String> PAYMENT_DONE = Set.of("CONFIRMED", "AUTHORIZED");

    /** What the endpoint answers: {@code OK} (200), {@code BAD_REQUEST} (400), {@code FORBIDDEN} (403) or {@code UNAVAILABLE} (503). */
    enum Reply { OK, BAD_REQUEST, FORBIDDEN, UNAVAILABLE }

    private final BillingSettings settings;
    private final BillingRepository repository;
    private final TBankClient bank;
    private final PaymentStateApplier applier;
    private final UsageClock clock;

    TBankNotifications(BillingSettings settings, BillingRepository repository, TBankClient bank, PaymentStateApplier applier, UsageClock clock) {
        this.settings = settings;
        this.repository = repository;
        this.bank = bank;
        this.applier = applier;
        this.clock = clock;
    }

    Reply handle(byte[] body) {
        if (body.length > MAX_BYTES) return Reply.BAD_REQUEST;
        ObjectNode notification;
        try {
            notification = (ObjectNode) READER.read(body);
        } catch (IllegalArgumentException failure) {
            return Reply.BAD_REQUEST;
        }
        if (!settings.configured()) {
            log.warn("billing notification refused reason=not_configured");
            return Reply.FORBIDDEN;
        }
        if (!notification.path("TerminalKey").isString() || !settings.isOwnTerminal(notification.path("TerminalKey").stringValue(null))) {
            log.warn("billing notification refused reason=foreign_terminal");
            return Reply.FORBIDDEN;
        }
        if (!TBankToken.verify(notification, settings.password())) {
            log.warn("billing notification refused reason=bad_token");
            return Reply.FORBIDDEN;
        }
        UUID orderId = TBankClient.orderId(text(notification, "OrderId"));
        String paymentId = TBankClient.paymentId(notification.path("PaymentId"));
        String status = text(notification, "Status");
        if (orderId == null || paymentId == null || status == null || !STATUS.matcher(status).matches()) {
            log.info("billing notification acknowledged reason=nothing_to_do");
            return Reply.OK;
        }
        try {
            return process(notification, orderId, paymentId, status);
        } catch (PaymentProviderException failure) {
            return Reply.UNAVAILABLE;
        } catch (RuntimeException failure) {
            log.error("billing notification failed order_id={} error_type={}", orderId, failure.getClass().getSimpleName());
            return Reply.UNAVAILABLE;
        }
    }

    private Reply process(ObjectNode notification, UUID orderId, String paymentId, String status) {
        Optional<BillingOrder> found = repository.find(orderId);
        if (found.isEmpty()) {
            log.info("billing notification acknowledged reason=unknown_order order_id={}", orderId);
            return Reply.OK;
        }
        BillingOrder order = found.get();
        Long amount = notification.path("Amount").isIntegralNumber() && notification.path("Amount").canConvertToLong()
                ? notification.path("Amount").longValue() : null;
        Boolean success = notification.path("Success").isBoolean() ? notification.path("Success").booleanValue() : null;
        // Only the facts the bank sent and we need: never the card fields, Data or the token.
        Instant now = clock.now();
        boolean first = repository.insertEvent(new BillingRepository.Event(orderId, paymentId, "NOTIFICATION", status, success, TBankClient.errorCode(notification),
                amount, "RECEIVED"), now);
        if ((order.status() == OrderStatus.PAID || order.status() == OrderStatus.REFUNDED) && PAYMENT_DONE.contains(status)) return Reply.OK;
        // A signed notification can be replayed by anyone who saw it: a repeat of a (payment, status) already seen asks the bank at most once per refresh interval,
        // like the return page. The bank's own retries after a failed GetState still pass, because the failed attempt stamped nothing.
        if (!first && !repository.claimRefresh(orderId, now, now.minus(settings.refreshInterval))) return Reply.OK;
        applier.apply(orderId, bank.getState(paymentId), PaymentStateApplier.Trigger.NOTIFICATION);
        return Reply.OK;
    }

    private static String text(JsonNode node, String name) {
        return node.path(name).isString() ? node.path(name).stringValue(null) : null;
    }
}
