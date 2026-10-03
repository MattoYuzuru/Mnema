package app.mnema.learning.generation;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The blocks an edit addresses: a contiguous run of top-level blocks of one revision, from index {@code from} to {@code to}.
 * Media blocks inside the run are never shown to the model and never dropped by a rewrite ({@code media}); the rest are the blocks
 * the model rewrites ({@code text}). A selection that widens to whole blocks is contiguous by construction, so a request whose
 * node IDs are not is not an edit target.
 *
 * @param from index of the first block of the run among the top-level blocks
 * @param to index of the last block of the run
 * @param blocks every block of the run, in document order
 * @param text the blocks of the run that are not media, with their index among the top-level blocks
 * @param media the media blocks of the run, in document order
 */
record EditTarget(int from, int to, List<JsonNode> blocks, List<Indexed> text, List<JsonNode> media) {
    /** A block with its position among the top-level blocks (its handle is {@code b(index + 1)}). */
    record Indexed(int index, JsonNode block) { }

    /**
     * The run named by {@code nodeIds}, or empty when one is not a top-level block of {@code document}, an ID repeats, or the
     * blocks are not consecutive.
     */
    static Optional<EditTarget> resolve(JsonNode document, List<UUID> nodeIds) {
        List<JsonNode> top = EditDocument.blocks(document);
        Set<UUID> wanted = new HashSet<>(nodeIds);
        if (nodeIds.isEmpty() || wanted.size() != nodeIds.size()) return Optional.empty();
        int first = -1;
        int last = -1;
        int found = 0;
        for (int index = 0; index < top.size(); index++) {
            if (!wanted.contains(EditDocument.id(top.get(index)))) continue;
            if (first < 0) first = index;
            last = index;
            found++;
        }
        if (found != wanted.size() || last - first + 1 != found) return Optional.empty();
        List<JsonNode> blocks = new ArrayList<>(top.subList(first, last + 1));
        List<Indexed> text = new ArrayList<>();
        List<JsonNode> media = new ArrayList<>();
        for (int offset = 0; offset < blocks.size(); offset++) {
            JsonNode block = blocks.get(offset);
            if (EditDocument.isMedia(block)) media.add(block);
            else text.add(new Indexed(first + offset, block));
        }
        return Optional.of(new EditTarget(first, last, List.copyOf(blocks), List.copyOf(text), List.copyOf(media)));
    }

    /** True when every block of the run is a media block (the target of a media action). */
    boolean onlyMedia() {
        return text.isEmpty();
    }
}
