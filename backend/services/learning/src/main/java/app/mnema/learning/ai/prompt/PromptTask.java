package app.mnema.learning.ai.prompt;

/**
 * The prompt a task is built from. Generation tasks share the stable global prefix (core, style, all five skills) and
 * the per-deck brief, then end with their own volatile section; exercises and grading have their own prefixes.
 */
public enum PromptTask {
    MATERIAL("material", true),
    EDIT("edit", true),
    EXERCISES("exercises", false),
    ASSESSMENT("assessment", false);

    private final String section;
    private final boolean generationPrefix;

    PromptTask(String section, boolean generationPrefix) {
        this.section = section;
        this.generationPrefix = generationPrefix;
    }

    String section() { return section; }

    boolean generationPrefix() { return generationPrefix; }
}
