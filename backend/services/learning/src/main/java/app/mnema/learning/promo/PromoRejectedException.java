package app.mnema.learning.promo;

import java.util.Objects;

/**
 * A redemption the rules refuse, with the stable reason the client explains calmly ({@code PROMO_INVALID}, {@code PROMO_EXHAUSTED},
 * {@code PROMO_ALREADY_USED}, {@code PROMO_NOT_ELIGIBLE}, {@code PROMO_VELOCITY}). It carries no code, account or address.
 */
public final class PromoRejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** {@code INVALID} covers an unknown, disabled, expired or not yet started code in one answer, so the response is no oracle for which. */
    public enum Reason { INVALID, EXHAUSTED, ALREADY_USED, NOT_ELIGIBLE, VELOCITY }

    private final Reason reason;

    public PromoRejectedException(Reason reason) {
        super("Promo code rejected", null, false, false);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
