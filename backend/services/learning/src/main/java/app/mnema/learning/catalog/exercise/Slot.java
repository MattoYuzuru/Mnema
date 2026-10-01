package app.mnema.learning.catalog.exercise;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.stream.Collectors;

import static app.mnema.learning.catalog.exercise.StrictJson.array;
import static app.mnema.learning.catalog.exercise.StrictJson.invalid;

/**
 * Content slot profiles: the contract matrix frontend and backend share. A profile fixes the text limit
 * per block (applied to TEXT and to resolved MATERIAL text) and the allowed block kinds.
 */
public enum Slot {
    /** Question stem of every mechanic. */
    PROMPT(4_000, true),
    /** Reveal-after-the-fact material: SELF_CHECK reference, FREE_RESPONSE explanation. */
    REFERENCE(4_000, true),
    /** One CHOICE option or one MATCH item: a short label plus at most one media block. */
    COMPACT(300, false),
    /** One ORDER item: like COMPACT, but long enough for a paragraph, a step or a code block (newlines are kept). */
    SEQUENCE(1_000, false);

    private final int maxText;
    private final boolean youtubeAllowed;

    Slot(int maxText, boolean youtubeAllowed) {
        this.maxText = maxText;
        this.youtubeAllowed = youtubeAllowed;
    }

    public int maxText() { return maxText; }

    boolean accepts(Block block) { return youtubeAllowed || !(block instanceof Block.Youtube); }

    /** Reads a block array with {@code min..max} blocks, enforcing the one-text-plus-one-media tile rule. */
    List<Block> read(JsonNode array, int min, int max) {
        List<Block> blocks = array(array, min, max).stream().map(node -> Block.parse(node, this))
                .collect(Collectors.toUnmodifiableList());
        if (this == COMPACT || this == SEQUENCE) {
            long text = blocks.stream().filter(block -> block instanceof Block.Text
                    || block instanceof Block.Material).count();
            if (text > 1 || blocks.size() - text > 1) throw invalid();
        }
        return blocks;
    }
}
