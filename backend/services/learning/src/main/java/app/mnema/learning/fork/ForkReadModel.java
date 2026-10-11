package app.mnema.learning.fork;

import app.mnema.learning.library.DeckAccess;
import app.mnema.learning.library.Resolution;
import app.mnema.learning.library.Viewer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The {@code fork} member of the owner's deck JSON (contract {@code contracts/decks/forks.json}): the flags of the copy and what the copier may still know of the source.
 * {@code sourceAvailable} is computed at read, for the copier, by {@link DeckAccess} with the public code he copied by, so it goes dark exactly when he could no longer open
 * the source: the deck was deleted, made private, or its code was rotated away by lowering the level (a copier is never handed the new code). {@code updateAvailable} (the badge
 * «Есть обновление») is computed from the same resolution, so it costs no statement of its own. Nothing is written on read.
 */
@Component
public class ForkReadModel {
    private final DeckAccess access;

    ForkReadModel(DeckAccess access) { this.access = access; }

    /** The JSON of a copy's link, or JSON null for an ordinary deck ({@code link == null}). */
    public JsonNode describe(UUID actor, ForkLink link) {
        return describeAll(actor, java.util.Collections.singletonList(link)).getFirst();
    }

    /**
     * The JSON of every link of a page, in order ({@code null} entries stay JSON null): the sources of the whole page are resolved in two statements
     * ({@link DeckAccess#resolveAll}), not one per copy. Availability is decided by the code the copier stored, so a source whose code was rotated away reads as unavailable
     * even though the deck exists, until the copier opens the new link and presses «Добавить к себе» again (the re-link of {@link ForkService}).
     */
    public List<JsonNode> describeAll(UUID actor, List<ForkLink> links) {
        List<String> codes = links.stream().filter(java.util.Objects::nonNull).map(ForkLink::upstreamCode).distinct().toList();
        // a page without copies (and every ordinary deck's acknowledgement) asks nothing
        Map<String, Resolution> resolved = codes.isEmpty() ? Map.of() : access.resolveAll(Viewer.account(actor, null), codes);
        List<JsonNode> result = new ArrayList<>(links.size());
        for (ForkLink link : links) {
            if (link == null) {
                result.add(JsonNodeFactory.instance.nullNode());
                continue;
            }
            Resolution resolution = resolved.get(link.upstreamCode());
            boolean available = resolution.granted() && resolution.deck().deckId().equals(link.upstreamDeckId()) && resolution.deck().published() != null;
            ObjectNode json = JsonNodeFactory.instance.objectNode();
            if (available) json.put("upstreamCode", link.upstreamCode()).put("upstreamTitle", resolution.deck().published().title());
            else json.putNull("upstreamCode").putNull("upstreamTitle");
            // «Есть обновление» is computed here, at read, and writes nothing: the copy is ready and attached, its source opens by the stored code, and the author's published revision
            // is not the one the copy stands on or a unit the learner left for later is still on offer (the base rule of the architecture, section 7).
            boolean updateAvailable = available && !link.preparing() && !link.detached()
                    && (!link.baseRevisionId().equals(resolution.deck().published().revisionId()) || link.pendingUpdates());
            result.add(json.put("sourceAvailable", available).put("preparing", link.preparing()).put("failed", link.failed()).put("detached", link.detached())
                    .put("updateAvailable", updateAvailable).put("autoUpdate", link.autoUpdate()));
        }
        return result;
    }
}
