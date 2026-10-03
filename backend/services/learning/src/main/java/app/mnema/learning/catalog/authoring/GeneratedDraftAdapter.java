package app.mnema.learning.catalog.authoring;

import app.mnema.learning.catalog.content.NativeDocument;
import app.mnema.learning.generation.GeneratedDraftOpener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/** Opens a generated proposal as an {@code EditingDraft} through the ordinary draft command, in the caller's transaction. */
@Component
final class GeneratedDraftAdapter implements GeneratedDraftOpener {
    private final DraftService drafts;

    GeneratedDraftAdapter(DraftService drafts) { this.drafts = drafts; }

    @Override
    public JsonNode open(UUID actor, UUID deckId, UUID commandId, NativeDocument document) {
        return drafts.create(actor, new AuthoringCommands.DraftCreate(commandId, deckId, null, null, document)).acknowledgement();
    }
}
