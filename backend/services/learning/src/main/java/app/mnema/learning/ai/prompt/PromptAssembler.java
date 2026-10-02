package app.mnema.learning.ai.prompt;

import app.mnema.learning.ai.AiProperties;
import app.mnema.learning.ai.TextRequest;
import app.mnema.learning.ai.TokenCounter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the segments of a call in cache-friendly order and enforces the size budgets.
 *
 * <p>Order (stable first, volatile last): core {@code system}, {@code style}, the five {@code skill-*} sections (all
 * system role, byte-identical for every user within a {@code prompt_version}, cacheable), then the per-deck
 * {@code deck-brief} (user role, cacheable within a session), then the task section (user role, volatile). Exercises and
 * grading are single sections with their own prefix.
 *
 * <p>Budgets are estimates ({@code TokenCounter}): each section has a ceiling, and the whole input has a hard ceiling
 * (32k by default, the limit for non-thinking Flash) that fails with a {@link PromptException} so the caller trims its
 * data (outline, exemplars, sources) rather than sending a degraded prompt. Above the 25k working size the result only
 * says so.
 */
public final class PromptAssembler {
    static final List<String> PREFIX = List.of("system", "style", "skill-vocabulary", "skill-grammar", "skill-stem-concept",
            "skill-code", "skill-exam-summary");
    private static final Set<String> SKILLS = Set.of("vocabulary", "grammar", "concept", "code", "exam_notes", "free");
    /** Ceilings in estimated tokens; the static ones guard against accidental growth of the cached prefix. */
    private static final Map<String, Integer> CEILINGS = Map.ofEntries(
            Map.entry("system", 3_500), Map.entry("style", 2_500), Map.entry("skill-vocabulary", 1_800),
            Map.entry("skill-grammar", 1_800), Map.entry("skill-stem-concept", 1_800), Map.entry("skill-code", 1_800),
            Map.entry("skill-exam-summary", 1_800), Map.entry("deck-brief", 14_000), Map.entry("material", 14_000),
            Map.entry("edit", 10_000), Map.entry("exercises", 14_000), Map.entry("assessment", 5_000));

    private final PromptLibrary library;
    private final PromptRenderer renderer = new PromptRenderer();
    private final AiProperties.Prompt limits;

    public PromptAssembler(PromptLibrary library, AiProperties.Prompt limits) {
        this.library = library;
        this.limits = limits;
    }

    public AssembledPrompt assemble(PromptTask task, PromptValues values) {
        if (task == PromptTask.MATERIAL) validateSkill(values);
        List<TextRequest.Segment> segments = new ArrayList<>();
        Map<String, Integer> sizes = new LinkedHashMap<>();
        int total = 0;
        if (task.generationPrefix()) {
            for (String name : PREFIX) total += add(segments, sizes, name, values, true, TextRequest.Role.SYSTEM);
            total += add(segments, sizes, "deck-brief", values, true, TextRequest.Role.USER);
        }
        total += add(segments, sizes, task.section(), values, false, TextRequest.Role.USER);
        if (total > limits.maxInputTokens()) throw new PromptException("Prompt exceeds the input ceiling");
        return new AssembledPrompt(library.version(), segments, total, sizes, total > limits.workingInputTokens());
    }

    private int add(List<TextRequest.Segment> segments, Map<String, Integer> sizes, String name, PromptValues values,
                    boolean cacheable, TextRequest.Role role) {
        PromptSection section = library.section(name);
        String text = renderer.render(section, values);
        int tokens = TokenCounter.estimate(text);
        if (tokens > CEILINGS.getOrDefault(name, Integer.MAX_VALUE)) {
            throw new PromptException("Section " + name + " exceeds its budget");
        }
        segments.add(new TextRequest.Segment(role, text, cacheable));
        sizes.put(name, tokens);
        return tokens;
    }

    private static void validateSkill(PromptValues values) {
        PromptValues.Value skill = values.get("task.skill");
        if (skill != null && !SKILLS.contains(skill.text())) throw new PromptException("Unknown task.skill");
    }
}
