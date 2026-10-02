package app.mnema.learning.platform.idempotency;

/** Raised when a command identifier is reused outside its original immutable command envelope. */
public final class IdempotencyConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;


    public IdempotencyConflictException() {
        super("The command identifier was already used for a different command");
    }
}
