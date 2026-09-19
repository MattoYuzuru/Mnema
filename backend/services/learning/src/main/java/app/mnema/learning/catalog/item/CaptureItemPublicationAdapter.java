package app.mnema.learning.catalog.item;

import app.mnema.learning.catalog.authoring.CaptureItemPublisher;
import app.mnema.learning.catalog.content.NativeDocument;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
final class CaptureItemPublicationAdapter implements CaptureItemPublisher {
    private final ItemService items;

    CaptureItemPublicationAdapter(ItemService items) { this.items = items; }

    @Override
    public JsonNode create(UUID actor, UUID deckId, long expectedDeckVersion, UUID commandId,
                           UUID expectedDeckRevisionId, Integer ordinal, NativeDocument document) {
        return items.publish(actor, deckId, expectedDeckVersion,
                ItemPublicationCommand.create(commandId, expectedDeckRevisionId, ordinal, document)).acknowledgement();
    }
}
