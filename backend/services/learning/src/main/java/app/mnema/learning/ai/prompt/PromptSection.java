package app.mnema.learning.ai.prompt;

import java.util.List;

/**
 * One prompt file: front matter plus the verbatim body. {@code placeholders} lists every {@code {{...}}} in order of
 * appearance; the static layers (system, style, skills) have none.
 */
public record PromptSection(String version, String name, String purpose, String body, List<Placeholder> placeholders) {
    public PromptSection {
        placeholders = List.copyOf(placeholders);
    }

    /** {@code defaultValue} is null for a required placeholder. */
    public record Placeholder(String name, String defaultValue) { }

    /** Static sections are byte-stable and sit in the global cache prefix. */
    public boolean isStatic() { return placeholders.isEmpty(); }
}
