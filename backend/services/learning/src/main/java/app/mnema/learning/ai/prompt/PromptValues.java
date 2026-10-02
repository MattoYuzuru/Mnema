package app.mnema.learning.ai.prompt;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Values for the placeholders of a prompt. Two kinds, never interchangeable:
 * <ul>
 *   <li><b>text</b> values come from users, notes, materials, search results, history or learner answers. The renderer
 *       redacts personal-data patterns and escapes them, so they cannot close a tag or break an attribute;</li>
 *   <li><b>block</b> values are code-rendered structures ({@code *_blocks}, {@code *_lines}, {@code *.lines},
 *       {@code allowed_links}, {@code document}, {@code schema}) inserted verbatim. They are {@link PromptBlock}s, which only
 *       {@link PromptBlocks} can make; its factories escape and redact their parts.</li>
 * </ul>
 * The kind is fixed by the placeholder name, and the renderer rejects a value of the wrong kind.
 */
public final class PromptValues {
    private final Map<String, Value> values = new LinkedHashMap<>();

    record Value(boolean block, String text) { }

    /** True for the placeholder names that take a code-rendered block. */
    public static boolean isBlockName(String name) {
        return name.endsWith("_blocks") || name.endsWith("_lines") || name.endsWith(".lines")
                || name.equals("allowed_links") || name.equals("document") || name.equals("schema");
    }

    public static PromptValues create() { return new PromptValues(); }

    /** A user-controlled text value (the renderer rejects more than 64 KiB); null is the same as absent. */
    public PromptValues text(String name, String value) {
        if (isBlockName(name)) throw new PromptException("Placeholder " + name + " takes a block, not text");
        if (value != null) values.put(Objects.requireNonNull(name), new Value(false, value));
        return this;
    }

    public PromptValues number(String name, long value) { return text(name, Long.toString(value)); }

    /** A code-rendered block from {@link PromptBlocks}; an empty block is a legitimate value (no notes, no results). */
    public PromptValues block(String name, PromptBlock block) {
        if (!isBlockName(name)) throw new PromptException("Placeholder " + name + " takes text, not a block");
        values.put(name, new Value(true, Objects.requireNonNull(block).text()));
        return this;
    }

    Value get(String name) { return values.get(name); }

    boolean has(String name) { return values.containsKey(name); }
}
