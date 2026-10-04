package app.mnema.learning.generation;

import app.mnema.learning.generation.Rows.Session;
import app.mnema.learning.usage.Reservation;
import app.mnema.learning.usage.UsageLedger;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The credit holds of one session: the reservation of its initial batch ({@code generation_session.reservation_id}) and
 * one {@code STEP} reservation for each artifact the user retried (its id is {@code reservationId} in the retried step's
 * input, so the debit of that step draws from it and from nothing else). A session shows, renews and releases all of them
 * together, so what the user sees as the session's credits is one number whichever hold paid.
 */
@Component
class SessionReservations {
    /** What the session's holds amount to now: credits still held and credits debited so far. */
    record Totals(int reserved, int spent) { }

    private final StepRepository steps;
    private final UsageLedger ledger;

    SessionReservations(StepRepository steps, UsageLedger ledger) {
        this.steps = steps;
        this.ledger = ledger;
    }

    /** The hold the step at hand draws from: its own retry reservation, else the session's initial batch. */
    static UUID forStep(Session session, JsonNode stepInput) {
        String own = stepInput.path("reservationId").stringValue(null);
        return own == null ? session.reservationId() : UUID.fromString(own);
    }

    /** The session's initial reservation first, then the retry reservations; never a duplicate. */
    List<UUID> ids(Session session) {
        Set<UUID> ids = new LinkedHashSet<>();
        if (session.reservationId() != null) ids.add(session.reservationId());
        ids.addAll(steps.reservationIds(List.of(session.sessionId())).getOrDefault(session.sessionId(), List.of()));
        return new ArrayList<>(ids);
    }

    /** Whether the session's batch hold is still live (a PLAN_READY plan's hold lapses by its time to live, the approval then reserves again). */
    boolean batchActive(Session session) {
        return session.reservationId() != null && ledger.reservation(session.ownerId(), session.reservationId())
                .filter(found -> found.state() == app.mnema.learning.usage.ReservationState.ACTIVE).isPresent();
    }

    Totals totals(Session session) {
        return totals(List.of(session)).get(session.sessionId());
    }

    /** {@code sessionId -> totals} for a page of sessions, the step reservations read in one statement. */
    Map<UUID, Totals> totals(Collection<Session> sessions) {
        Map<UUID, List<UUID>> own = steps.reservationIds(sessions.stream().map(Session::sessionId).toList());
        Map<UUID, Totals> result = new HashMap<>();
        for (Session session : sessions) {
            Set<UUID> ids = new LinkedHashSet<>();
            if (session.reservationId() != null) ids.add(session.reservationId());
            ids.addAll(own.getOrDefault(session.sessionId(), List.of()));
            int reserved = 0;
            int spent = 0;
            for (UUID id : ids) {
                var reservation = ledger.reservation(session.ownerId(), id);
                if (reservation.isPresent()) {
                    Reservation found = reservation.get();
                    reserved += found.heldRemaining();
                    spent += found.debitedCredits();
                }
            }
            result.put(session.sessionId(), new Totals(reserved, spent));
        }
        return result;
    }
}
