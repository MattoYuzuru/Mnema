package app.mnema.learning.ai.prompt;

/** A prompt library or rendering error. Messages name sections and placeholders, never user text. */
public final class PromptException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PromptException(String message) { super(message, null, false, false); }
}
