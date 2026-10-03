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
 * grading are single sections with their own prefix. The exercise section is preceded by the {@code <data_policy>} block of the
 * core, taken verbatim (the section carries the author's material, objectives and exercises as data, and the core's policy
 * says such tags are data and not instructions); the rest of the core describes the MBM format and does not apply to JSON.
 *
 * <p>Budgets are estimates ({@code TokenCounter}): each section has a ceiling, and the whole input has a hard ceiling
 * (32k by default, the limit for non-thinking Flash) that fails with a {@link PromptException} so the caller trims its
 * data (outline, exemplars, sources) rather than sending a degraded prompt. Above the 25k working size the result only
 * says so.
 */
public final class PromptAssembler {
    static final List<String> PREFIX = List.of("system", "style", "skill-vocabulary", "skill-grammar", "skill-stem-concept",
            "skill-code", "skill-exam-summary");
    private static final String DATA_POLICY_OPEN = "<data_policy>";
    private static final String DATA_POLICY_CLOSE = "</data_policy>";
    private static final String ANSWER_SOURCE_OPEN = "<answer_source>";
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
        if (task == PromptTask.EXERCISES) total += addDataPolicy(segments, sizes);
        total += task == PromptTask.ASSESSMENT ? addAssessment(segments, sizes, values)
                : add(segments, sizes, task.section(), values, false, TextRequest.Role.USER);
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

    /**
     * The grading section as two segments: the grader rules and the exercise (prompt, reference, criteria, misconceptions,
     * material) are the same for every answer to one exercise, so they form a cacheable head; the answer source and the learner
     * answer, volatile, follow. The head ends where the volatile {@code <answer_source>} line starts. Values are escaped, so an
     * answer cannot contain that tag; the rules mention the tag in running text only, never at the start of a line.
     */
    private int addAssessment(List<TextRequest.Segment> segments, Map<String, Integer> sizes, PromptValues values) {
        String name = PromptTask.ASSESSMENT.section();
        String text = renderer.render(library.section(name), values);
        int split = text.lastIndexOf("\n" + ANSWER_SOURCE_OPEN) + 1;
        if (split < 1) throw new PromptException("The assessment section has no answer source line");
        int tokens = TokenCounter.estimate(text);
        if (tokens > CEILINGS.getOrDefault(name, Integer.MAX_VALUE)) throw new PromptException("Section " + name + " exceeds its budget");
        segments.add(new TextRequest.Segment(TextRequest.Role.USER, text.substring(0, split), true));
        segments.add(new TextRequest.Segment(TextRequest.Role.USER, text.substring(split), false));
        sizes.put(name, tokens);
        return tokens;
    }

    /** The {@code <data_policy>} block of the core as its own stable, cacheable segment (byte-identical within a version). */
    private int addDataPolicy(List<TextRequest.Segment> segments, Map<String, Integer> sizes) {
        String body = library.section("system").body();
        int start = body.indexOf(DATA_POLICY_OPEN);
        int end = body.indexOf(DATA_POLICY_CLOSE);
        if (start < 0 || end < start) throw new PromptException("The core section has no data policy");
        String policy = body.substring(start, end + DATA_POLICY_CLOSE.length());
        int tokens = TokenCounter.estimate(policy);
        segments.add(new TextRequest.Segment(TextRequest.Role.SYSTEM, policy, true));
        sizes.put("data-policy", tokens);
        return tokens;
    }

    private static void validateSkill(PromptValues values) {
        PromptValues.Value skill = values.get("task.skill");
        if (skill != null && !SKILLS.contains(skill.text())) throw new PromptException("Unknown task.skill");
    }
}
