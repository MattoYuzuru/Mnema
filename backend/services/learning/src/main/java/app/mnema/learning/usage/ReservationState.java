package app.mnema.learning.usage;

/** {@code ACTIVE} holds credits; the other three are terminal and hold nothing. */
public enum ReservationState {
    ACTIVE,
    /** Ended after at least one debit; the unspent remainder was returned. */
    SETTLED,
    /** Ended without a debit; the whole hold was returned. */
    RELEASED,
    /** An orphaned hold passed its {@code expiresAt} and was returned. */
    EXPIRED
}
