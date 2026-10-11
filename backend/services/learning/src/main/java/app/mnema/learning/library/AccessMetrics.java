package app.mnema.learning.library;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The outcomes of the access code, counted as {@code mnema_deck_access_total{op,outcome}}. The labels are a fixed vocabulary (never a deck, an account, a
 * token or a login), so the series are bounded: {@code redeem} ok / noop / refused / limit, {@code request} created / silent / refused / limited,
 * {@code withdraw} ok / refused, {@code decision} approved / rejected / refused / limit, {@code grant} ok / refused / limited / unavailable, {@code revoke} ok.
 */
@Component
final class AccessMetrics {
    private final MeterRegistry meters;

    AccessMetrics(MeterRegistry meters) { this.meters = meters; }

    void count(String op, String outcome) {
        meters.counter("mnema_deck_access_total", "op", op, "outcome", outcome).increment();
    }
}
