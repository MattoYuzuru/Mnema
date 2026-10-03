package app.mnema.learning.generation;

import app.mnema.learning.catalog.content.NativeDocument;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

/**
 * Caller-owned port isolating approval from the way {@code ItemService} stages a publication (the pattern of
 * {@code CaptureItemPublisher}): the catalog implements it, the generation module calls it. The completion runs inside the
 * publication's own transaction, so the artifact's move to {@code PUBLISHED} commits or rolls back with the material.
 */
public interface GeneratedItemPublisher {
    /** One new material: its member key (chosen by the caller, so a retry names the same one) and its document. */
    record Material(UUID memberKey, NativeDocument document) { }

    /**
     * Publishes {@code materials} as new materials of the deck in ONE publication (one deck revision), in order, at the end.
     *
     * @param commandId the publication's own command identifier (the approval derives it from its own)
     * @return the publication acknowledgement of the catalog: {@code deckRevisionId}, {@code deckVersion} and one
     *         {@code changes[]} entry per material, in order
     */
    JsonNode create(UUID actor, UUID deckId, long expectedDeckVersion, UUID commandId, UUID expectedDeckRevisionId,
                    List<Material> materials, Completion completion);

    /** Runs in the publication transaction, also when the publication itself is a replay of a committed command. */
    @FunctionalInterface
    interface Completion {
        void commit(JsonNode publication, boolean replayed);
    }
}
