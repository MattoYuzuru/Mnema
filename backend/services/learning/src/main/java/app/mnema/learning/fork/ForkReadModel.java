package app.mnema.learning.fork;

import app.mnema.learning.library.DeckAccess;
import app.mnema.learning.library.Resolution;
import app.mnema.learning.library.Viewer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

/**
 * The {@code fork} member of the owner's deck JSON (contract {@code contracts/decks/forks.json}): the flags of the copy and what the copier may still know of the source.
 * {@code sourceAvailable} is computed at read, for the copier, by {@link DeckAccess} with the public code he copied by, so it goes dark exactly when he could no longer open
 * the source: the deck was deleted, made private, or its code was rotated away by lowering the level (a copier is never handed the new code). Nothing is written on read.
 */
@Component
public class ForkReadModel {
    private final DeckAccess access;

    ForkReadModel(DeckAccess access) { this.access = access; }

    /** The JSON of a copy's link, or JSON null for an ordinary deck ({@code link == null}). */
    public tools.jackson.databind.JsonNode describe(UUID actor, ForkLink link) {
        if (link == null) return JsonNodeFactory.instance.nullNode();
        Resolution resolution = access.resolve(Viewer.account(actor, null), link.upstreamCode());
        boolean available = resolution.granted() && resolution.deck().deckId().equals(link.upstreamDeckId()) && resolution.deck().published() != null;
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        if (available) result.put("upstreamCode", link.upstreamCode()).put("upstreamTitle", resolution.deck().published().title());
        else result.putNull("upstreamCode").putNull("upstreamTitle");
        return result.put("sourceAvailable", available).put("preparing", link.preparing()).put("failed", link.failed()).put("detached", link.detached());
    }
}
