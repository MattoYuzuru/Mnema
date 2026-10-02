package app.mnema.learning.ai.prompt;

/**
 * A code-rendered prompt block, inserted verbatim by the renderer. It can only be made by {@link PromptBlocks}, whose
 * factories redact and escape every user-controlled part, so redaction is structural: a raw string can never be passed as
 * a block.
 */
public final class PromptBlock {
    private final String text;

    PromptBlock(String text) { this.text = text; }

    String text() { return text; }

    /** Never prints the content: blocks carry user text. */
    @Override
    public String toString() { return "PromptBlock[chars=" + text.length() + "]"; }
}
