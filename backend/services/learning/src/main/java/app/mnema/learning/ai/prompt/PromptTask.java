package app.mnema.learning.ai.prompt;

/**
 * The prompt a task is built from. Generation tasks share the stable global prefix (core, style, all five skills) and
 * the per-deck brief, then end with their own volatile section; exercises and grading have their own prefixes.
 */
public enum PromptTask {
    MATERIAL("material", true),
    EDIT("edit", true),
    EXERCISES("exercises", false),
    /** Revising ONE existing exercise (REVISE_EXERCISE, #294): the exercise in the output form, the material and the instruction. */
    EXERCISE_EDIT("exercise-edit", false),
    /** The intent of «Попросить Мнему…» (#294): one sentence to one operation of a closed vocabulary. */
    INTENT("intent", false),
    /** The planner of «Сначала показать план» (AI-14, #295): the plan of a batch of exercises or materials, strict JSON, with the budget as input. */
    PLAN("plan", false),
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
