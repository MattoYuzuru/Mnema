package app.mnema.learning.ai.prompt;

import app.mnema.learning.ai.TextRequest;

import java.util.List;
import java.util.Map;

/**
 * A rendered prompt as ordered segments (stable layers first, so the provider's prefix cache hits).
 *
 * @param promptVersion stored on every artifact revision made from this prompt
 * @param estimatedTokens approximate input size (see {@code TokenCounter})
 * @param sectionTokens approximate size per section, in order
 * @param overWorkingTarget true when the input exceeds the 12-25k working size; still below the hard ceiling
 */
public record AssembledPrompt(String promptVersion, List<TextRequest.Segment> segments, int estimatedTokens,
                              Map<String, Integer> sectionTokens, boolean overWorkingTarget) {
    public AssembledPrompt {
        segments = List.copyOf(segments);
        sectionTokens = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(sectionTokens));
    }
}
