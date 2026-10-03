package app.mnema.learning.catalog.item;

import app.mnema.learning.generation.GeneratedItemPublisher;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

/** Approval of generated materials: one {@link ItemService#publish} with the caller's completion in its transaction. */
@Component
final class GeneratedItemPublicationAdapter implements GeneratedItemPublisher {
    private final ItemService items;

    GeneratedItemPublicationAdapter(ItemService items) { this.items = items; }

    @Override
    public JsonNode create(UUID actor, UUID deckId, long expectedDeckVersion, UUID commandId, UUID expectedDeckRevisionId,
                           List<Material> materials, Completion completion) {
        List<ItemPublicationCommand.Create> creates = materials.stream()
                .map(material -> new ItemPublicationCommand.Create(material.memberKey(), null, material.document())).toList();
        return items.publish(actor, deckId, expectedDeckVersion,
                ItemPublicationCommand.creates(commandId, expectedDeckRevisionId, creates),
                (publication, replayed) -> completion.commit(publication, replayed)).acknowledgement();
    }
}
