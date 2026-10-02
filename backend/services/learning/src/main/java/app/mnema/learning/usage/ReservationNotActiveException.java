package app.mnema.learning.usage;

/** A debit was attempted against a reservation that has ended (released, settled, expired or rolled over). */
public final class ReservationNotActiveException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient ReservationState state;

    public ReservationNotActiveException(ReservationState state) {
        super("Reservation is not active", null, false, false);
        this.state = state;
    }

    /** The terminal state the reservation is in. */
    public ReservationState state() {
        return state;
    }
}
