package app.mnema.learning.catalog.item;

import app.mnema.learning.catalog.content.NativeDocument;
import app.mnema.learning.catalog.content.NativeDocumentReader;
import app.mnema.learning.catalog.content.storage.NativeRevisionPlanner;
import app.mnema.learning.generation.GeneratedItemPublisher;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Approval of generated materials and of a revised one: one {@link ItemService#publish} (a create, or a save of the existing material) with
 * the caller's completion in its transaction.
 */
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

    @Override
    public JsonNode revise(UUID actor, UUID deckId, long expectedDeckVersion, UUID commandId, UUID expectedDeckRevisionId, UUID memberKey,
                           UUID expectedItemRevisionId, NativeDocument document, Completion completion) {
        // the save names the place the material has in the deck now and the edits that turn the head it stands on into the revision
        JsonNode head = items.read(actor, deckId, memberKey, null);
        int ordinal = head.path("ordinal").intValue();
        NativeDocument stored = new NativeDocumentReader().readRetained(head.path("document").toString().getBytes(StandardCharsets.UTF_8));
        NativeRevisionPlanner.Plan plan = NativeRevisionPlanner.plan(stored, document);
        return items.publish(actor, deckId, expectedDeckVersion,
                ItemPublicationCommand.revision(commandId, expectedDeckRevisionId, memberKey, expectedItemRevisionId, ordinal, plan.document(), plan.edits()),
                (publication, replayed) -> completion.commit(publication, replayed)).acknowledgement();
    }
}
