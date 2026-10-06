package app.mnema.learning.speech;

/** A recording is over the 2 MiB cap: {@code 413 PAYLOAD_TOO_LARGE}. Raised while the body is read, so no more than the cap and one byte is ever buffered. */
public final class PayloadTooLargeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    PayloadTooLargeException() { super("Payload too large", null, false, false); }
}
