package app.mnema.learning.billing;

/**
 * A call to the bank failed. It carries the reason and, when the bank answered, its numeric {@code ErrorCode}; never the bank's {@code Message} or
 * {@code Details}, a request body or a credential, so it is safe to log and to count.
 */
final class PaymentProviderException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** {@code REFUSED}: the bank answered {@code Success:false}. {@code NOT_CONFIGURED}: there was nothing to call with. */
    enum Reason { TIMEOUT, UNREACHABLE, HTTP_STATUS, INVALID_RESPONSE, REFUSED, NOT_CONFIGURED }

    private final Reason reason;
    private final String errorCode;

    PaymentProviderException(Reason reason, String errorCode) {
        super("Payment provider failure", null, false, false);
        this.reason = reason;
        this.errorCode = errorCode;
    }

    Reason reason() {
        return reason;
    }

    /** The bank's numeric error code, or null when it did not answer with one. */
    String errorCode() {
        return errorCode;
    }
}
