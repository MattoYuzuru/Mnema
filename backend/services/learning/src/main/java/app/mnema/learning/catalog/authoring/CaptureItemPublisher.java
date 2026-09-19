package app.mnema.learning.catalog.authoring;

import app.mnema.learning.catalog.content.NativeDocument;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/** Caller-owned port isolating Capture lifecycle from LearningItem staging details. */
public interface CaptureItemPublisher {
    JsonNode create(UUID actor, UUID deckId, long expectedDeckVersion, UUID commandId,
                    UUID expectedDeckRevisionId, Integer ordinal, NativeDocument document);
}
