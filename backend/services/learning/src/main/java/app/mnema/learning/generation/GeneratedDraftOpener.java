package app.mnema.learning.generation;

import app.mnema.learning.catalog.content.NativeDocument;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Caller-owned port of the hand-off: creates an ordinary {@code EditingDraft} of a new material ({@code member_key} null)
 * from a document. Implemented by the authoring module; it joins the caller's transaction.
 */
public interface GeneratedDraftOpener {
    /**
     * @param commandId the draft command's own identifier (the hand-off derives it from its own)
     * @return the draft acknowledgement of the authoring module: {@code {commandId, draft: {draftId, deckId, rowVersion, ...}}}
     * @throws app.mnema.learning.platform.api.ResourceLimitExceededException the account's draft quota is reached
     */
    JsonNode open(UUID actor, UUID deckId, UUID commandId, NativeDocument document);
}
