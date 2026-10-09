package app.mnema.learning.platform.api;

/**
 * The bank did not answer {@code Init} in time or refused it ({@code 503 PAYMENT_PROVIDER_UNAVAILABLE}). The order stays without a payment link and
 * a retry with the same {@code Idempotency-Key} asks the bank again. It carries neither the bank's message nor its error code.
 */
public final class PaymentProviderUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PaymentProviderUnavailableException() {
        super("Payment provider unavailable", null, false, false);
    }
}
