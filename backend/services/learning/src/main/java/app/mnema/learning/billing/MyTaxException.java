package app.mnema.learning.billing;

/**
 * A call to «Мой налог» failed. It carries what the caller must decide on: whether the request may have been processed ({@link Outcome}), a short machine
 * code ({@code TIMEOUT}, {@code HTTP_422}, …) and the HTTP status; never a response body, a token, the INN or the password, so it is safe to log and store.
 */
final class MyTaxException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * {@code NOT_SENT}: the request certainly did not change anything (no connection, rate limit); {@code MAYBE_SENT}: it may have been processed
     * (timeout, broken connection, 5xx, an unreadable answer); {@code REJECTED}: the service refused this request; {@code AUTH}: logging in is not possible.
     */
    enum Outcome { NOT_SENT, MAYBE_SENT, REJECTED, AUTH }

    private final Outcome outcome;
    private final String code;
    private final int status;
    private final String operation;

    MyTaxException(Outcome outcome, String code, int status, String operation) {
        super("Moy Nalog failure", null, false, false);
        this.outcome = outcome;
        this.code = code;
        this.status = status;
        this.operation = operation;
    }

    Outcome outcome() {
        return outcome;
    }

    String code() {
        return code;
    }

    /** Which call failed: {@code income}, {@code cancel}, {@code incomes}, {@code receipt}, {@code login} or {@code refresh}. */
    String operation() {
        return operation;
    }

    /** The HTTP status, or 0 when there was no answer. */
    int status() {
        return status;
    }
}
