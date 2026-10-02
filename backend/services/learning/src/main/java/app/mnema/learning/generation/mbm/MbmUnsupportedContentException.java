package app.mnema.learning.generation.mbm;

import java.util.List;

/**
 * The target range holds content MBM v1 cannot express without changing it (rendering is lossless except for the
 * normalizations documented in {@link MbmRenderer}): a node type outside MBM (for example
 * {@code youtube}, an opaque or future node), an attribute MBM has no syntax for (for example {@code lang} on a
 * paragraph), a mark combination that MBM's emphasis rules cannot spell, and so on. An edit of such a range must be
 * refused before any model call (AI never rewrites what it cannot see exactly). The exception carries node IDs and
 * types only, never content.
 */
public final class MbmUnsupportedContentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** One top-level block that cannot be rendered exactly. */
    public record Block(String nodeId, String type) implements java.io.Serializable {
    }

    private final transient List<Block> blocks;

    MbmUnsupportedContentException(List<Block> blocks) {
        super("The range contains " + blocks.size() + " block(s) that MBM v1 cannot express exactly");
        this.blocks = List.copyOf(blocks);
    }

    public List<Block> blocks() {
        return blocks;
    }
}
