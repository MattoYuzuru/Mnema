package app.mnema.learning.usage;

/** What one admission holds credits for ({@code contracts/usage} reservation lifecycle). */
public enum ReservationScope {
    /** The initial batch of a session; later chargeable actions take their own reservation. */
    SESSION,
    /** One edit turn or media redo. */
    TURN,
    /** One retried artifact or one deferred step that re-reserves after a period rollover. */
    STEP
}
