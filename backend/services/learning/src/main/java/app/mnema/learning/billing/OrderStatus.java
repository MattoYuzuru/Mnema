package app.mnema.learning.billing;

/**
 * The states of an order ({@code contracts/billing}). {@code CREATED} is internal (Init has not answered yet) and is reported as {@code PENDING}. {@code PAID}
 * is absorbing for granting: only {@code REFUNDED} follows it.
 */
enum OrderStatus {
    CREATED, PENDING, PAID, FAILED, REFUNDED, REVIEW;

    /** Waiting for the bank: the states the reconciler and the return page still ask about. */
    boolean open() {
        return this == CREATED || this == PENDING;
    }

    /**
     * The bank may still move money of the order: a payment that comes after all ({@code CREATED} whose Init timed out, {@code FAILED} after a late or second
     * attempt) or a refund of a {@code PAID} one. A verified notification asks the bank about these; {@code REFUNDED} and {@code REVIEW} are final here.
     */
    boolean bankMayChange() {
        return this != REFUNDED && this != REVIEW;
    }

    /** The wire status: {@code CREATED} is reported as {@code PENDING}. */
    String wire() {
        return this == CREATED ? PENDING.name() : name();
    }
}
