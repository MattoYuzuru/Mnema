package app.mnema.learning.usage;

import java.time.Instant;
import java.util.UUID;

/**
 * A hold on one period's balance, scoped to one admission. The session and turn identifiers are opaque to usage: no
 * foreign key, the generation module owns what they name.
 */
public record Reservation(UUID reservationId, UUID ownerId, ReservationScope scope, UUID sessionId, UUID turnId,
                          String periodId, ReservationState state, int heldCredits, int debitedCredits,
                          String rateCardVersion, Instant createdAt, Instant expiresAt) {
    /** Credits still held on the balance: zero once the reservation has ended. */
    public int heldRemaining() {
        return state == ReservationState.ACTIVE ? heldCredits - debitedCredits : 0;
    }
}
