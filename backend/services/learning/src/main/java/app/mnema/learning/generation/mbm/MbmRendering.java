package app.mnema.learning.generation.mbm;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Native-v1 blocks rendered as MBM for an edit context or an outline.
 *
 * @param text the MBM text; blocks are separated by blank lines
 * @param handles handle to {@code {nodeId, type}} of the top-level blocks, in document order; pass it to the compiler
 *                as {@link MbmOptions#handles()} (empty when rendered without handles)
 * @param links link targets used by the blocks; the caller decides which of them are trusted
 *              ({@link MbmOptions#allowedLinks()}) so an unchanged link survives the round trip
 * @param slotKeys slot keys the media directives of the text use
 */
public record MbmRendering(String text, Map<String, MbmOptions.Handle> handles, Set<String> links,
                           List<String> slotKeys) {

    public MbmRendering {
        handles = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(handles));
        links = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(links));
        slotKeys = List.copyOf(slotKeys);
    }
}
