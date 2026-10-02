package app.mnema.learning.catalog.deck;

import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.id.UuidPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Deck hub statistics ({@code contracts/decks/hub.json}): structural facts and the next step, never vanity metrics.
 * One snapshot-isolated read-only transaction, so coverage, states, due days, mechanics and captures describe the
 * same Deck head.
 */
@Service
public class DeckInsightsService {
    /** Days in {@code dueByDay}. */
    static final int DUE_DAYS = 7;
    /**
     * Product calendar zone when the account zone is unknown (owner decision 2026-10-02). TODO(#281): read the
     * {@code learning.usage.calendar-zone} property once the usage layer introduces it. Study's own session date still
     * falls back to UTC; aligning that is a separate follow-up.
     */
    static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Moscow");
    /** The seven mechanics of {@code contracts/study}, in catalogue order; every one is always present in the response. */
    static final List<String> MECHANICS =
            List.of("SELF_CHECK", "FREE_RESPONSE", "CLOZE", "CHOICE", "MATCH", "ORDER", "CATEGORIZE");
    private static final List<String> STATES = List.of("NOT_STARTED", "LEARNING", "DUE", "ON_TRACK");

    private final DeckInsightsRepository repository;

    public DeckInsightsService(DeckInsightsRepository repository) { this.repository = repository; }

    @Transactional(timeout = 10, readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ObjectNode read(UUID actor, UUID deckId, String accountZone) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(deckId, "deckId");
        repository.preferSetJoins();
        DeckInsightsRepository.Head head = repository.head(actor, deckId).orElseThrow(ResourceNotFoundException::new);
        Instant asOf = repository.now();
        ZoneId zone = zone(accountZone);
        LocalDate today = asOf.atZone(zone).toLocalDate();

        int total = 0;
        int covered = 0;
        int[] states = new int[STATES.size()];
        int[] due = new int[DUE_DAYS];
        for (DeckInsightsRepository.MaterialGroup group : repository.materials(actor, deckId, asOf, zone.getId(), today)) {
            total += group.materials();
            if (group.covered()) covered += group.materials();
            states[STATES.indexOf(group.state())] += group.materials();
            if (group.bucket() != null && group.bucket() < DUE_DAYS) due[group.bucket()] += group.materials();
        }

        ObjectNode result = JsonNodeFactory.instance.objectNode().put("deckId", deckId.toString())
                .put("deckRevisionId", head.revisionId().toString()).put("deckVersion", Long.toString(head.version()))
                .put("asOf", asOf.toString()).put("timezone", zone.getId());
        result.putObject("coverage").put("total", total).put("withExercises", covered).put("withoutExercises", total - covered);
        ObjectNode stateCounts = result.putObject("states");
        for (int index = 0; index < STATES.size(); index++) stateCounts.put(STATES.get(index), states[index]);
        var days = result.putArray("dueByDay");
        for (int day = 0; day < DUE_DAYS; day++) days.addObject().put("date", today.plusDays(day).toString()).put("materials", due[day]);
        ObjectNode mechanics = result.putObject("exercisesByMechanic");
        MECHANICS.forEach(type -> mechanics.put(type, 0));
        repository.mechanics(deckId).forEach(mechanic -> mechanics.put(mechanic.type(), mechanic.exercises()));
        DeckInsightsRepository.Captures captures = repository.captures(actor, deckId);
        ObjectNode notes = result.putObject("captures").put("open", captures.open());
        if (captures.oldestOpenCreatedAt() == null) notes.putNull("oldestOpenCreatedAt");
        else notes.put("oldestOpenCreatedAt", captures.oldestOpenCreatedAt().toString());
        return result;
    }

    /** The authenticated {@code zoneinfo} claim, or the product calendar zone when it is absent or invalid. */
    static ZoneId zone(String claim) {
        if (claim == null || claim.isBlank()) return DEFAULT_ZONE;
        try {
            return ZoneId.of(claim);
        } catch (DateTimeException exception) {
            return DEFAULT_ZONE;
        }
    }
}
