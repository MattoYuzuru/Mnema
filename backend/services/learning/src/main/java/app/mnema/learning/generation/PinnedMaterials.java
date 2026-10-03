package app.mnema.learning.generation;

import app.mnema.learning.catalog.content.NativeDocument;
import app.mnema.learning.catalog.content.NativeDocumentReader;
import app.mnema.learning.catalog.content.NativeNodeIndex;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.generation.exercise.ExerciseContext;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A pinned material revision as the exercise generator sees it: its top-level blocks with the handles of MBM edits
 * ({@code b1}, {@code b2}, ... in document order, whatever their type) and their plain text, and the whole node index for
 * resolving a quoted node. Only a block with quotable text (not blank, at most {@link #MAX_QUOTE} characters, the limit of a
 * prompt or reference slot) is offered as a handle, so a quote that passes the lint is always publishable.
 */
@Component
class PinnedMaterials {
    /** The longest text a MATERIAL block of an exercise slot may resolve to (the PROMPT and REFERENCE slots). */
    static final int MAX_QUOTE = 4_000;

    /** One top-level block that can be quoted. */
    record Block(String handle, UUID nodeId, String text) { }

    /** The blocks of a revision and its node index. */
    record Pinned(UUID memberKey, UUID itemRevisionId, List<Block> blocks, NativeNodeIndex index) {
        /** The plain text of any node of the revision, or empty when the node is gone. */
        Optional<String> text(UUID nodeId) {
            return index.text(nodeId);
        }

        /** The generator's view: handle to node id and text. */
        ExerciseContext.Material material() {
            Map<String, ExerciseContext.Block> blocks = new LinkedHashMap<>();
            for (Block block : this.blocks) blocks.put(block.handle(), new ExerciseContext.Block(block.nodeId(), block.text()));
            return new ExerciseContext.Material(memberKey, itemRevisionId, blocks);
        }

        boolean quotable(UUID nodeId) {
            return index.text(nodeId).filter(text -> !text.isBlank() && text.length() <= MAX_QUOTE).isPresent();
        }
    }

    private final ItemService items;
    private final NativeDocumentReader reader = new NativeDocumentReader();

    PinnedMaterials(ItemService items) {
        this.items = items;
    }

    /** The revision's blocks, or empty when the material or the revision is gone. */
    Optional<Pinned> read(UUID owner, UUID deck, UUID member, UUID revision) {
        try {
            JsonNode detail = items.read(owner, deck, member, revision);
            NativeDocument document = reader.readRetained(detail.path("document").toString().getBytes(StandardCharsets.UTF_8));
            NativeNodeIndex index = NativeNodeIndex.of(document);
            List<Block> blocks = new ArrayList<>();
            int number = 1;
            for (JsonNode block : document.toJson().path("root").path("content")) {
                String handle = "b" + number++;
                UUID nodeId = UUID.fromString(block.path("id").stringValue(""));
                String text = index.text(nodeId).orElse("");
                if (!text.isBlank() && text.length() <= MAX_QUOTE) blocks.add(new Block(handle, nodeId, text));
            }
            return Optional.of(new Pinned(member, revision, List.copyOf(blocks), index));
        } catch (ResourceNotFoundException | IllegalArgumentException gone) {
            return Optional.empty();
        }
    }
}
