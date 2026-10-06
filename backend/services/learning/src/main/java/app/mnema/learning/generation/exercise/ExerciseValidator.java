package app.mnema.learning.generation.exercise;

import app.mnema.learning.catalog.exercise.ExerciseCommand;
import app.mnema.learning.catalog.exercise.TextRule;
import app.mnema.learning.study.attempt.ExerciseProbeEvaluator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The validation pipeline of one model exercise ({@code contracts/generation/exercises/README.md}), run before anything is shown:
 * <ol>
 *   <li><b>SCHEMA</b> — {@link ExerciseOutputSchema};</li>
 *   <li><b>LINT</b> — {@link ExerciseLint} (references, allowed mechanics, the per-mechanic table);</li>
 *   <li><b>COMPILE</b> — {@link ExerciseCompiler} builds the publication command with real identifiers;</li>
 *   <li><b>COMMAND</b> — {@link ExerciseCommand#readCreate}, the parser of the publication endpoint;</li>
 *   <li><b>SELF_EVALUATION</b> — {@link ExerciseProbeEvaluator}: the key is {@code CORRECT}, a distractor is not.</li>
 * </ol>
 * Each exercise is judged alone and stops at the first phase that has findings, so a finding is about the earliest mistake.
 * Findings carry codes and member paths, never content. A {@code COMMAND_REJECTED} is a gap of the compiler or the lint, not a
 * fault of the model: the caller logs it as such.
 */
public final class ExerciseValidator {
    private static final int MAX_TITLE = 240;
    private static final int MAX_PATHS = 6;

    /** A validated exercise: the command with the context's placeholders and what the Workshop shows about it. */
    public record Accepted(int index, ObjectNode command, String mechanic, String objectiveTitle, String title, List<String> warnings) {
        public Accepted {
            warnings = List.copyOf(warnings);
        }

        /** The payload of the artifact revision: {@code {objective, exercise}} (the envelope fields come at approval). */
        public ObjectNode artifactCommand() {
            ObjectNode payload = command.objectNode();
            payload.set("objective", command.path("objective").deepCopy());
            payload.set("exercise", command.path("exercise").deepCopy());
            return payload;
        }
    }

    /** One exercise's verdict: accepted, or the findings of the earliest failing phase. */
    public sealed interface Verdict permits Valid, Invalid { }

    public record Valid(Accepted exercise) implements Verdict { }

    public record Invalid(int index, List<ExerciseFinding> findings) implements Verdict { }

    private final ExerciseOutputSchema schema;

    public ExerciseValidator(ExerciseOutputSchema schema) {
        this.schema = schema;
    }

    /** Validates the exercise at {@code index} of an answer. */
    public Verdict validate(int index, JsonNode answered, ExerciseContext context, ExerciseIds ids) {
        return validate(index, answered, context, ids, Map.of());
    }

    /**
     * Validates the revision of an existing exercise: {@code known} maps the local IDs the model was shown to the identifiers they had,
     * and the compiled command keeps them for what the model kept.
     */
    public Verdict validate(int index, JsonNode answered, ExerciseContext context, ExerciseIds ids, Map<String, UUID> known) {
        // the prompt escaped and redacted the material: the model's copies are brought back to what the material really says
        JsonNode exercise = ModelStrings.normalize(answered, context);
        List<String> violations = schema.exercise(exercise);
        if (!violations.isEmpty()) {
            // a path is made of schema member names and indexes only: nothing the model wrote reaches a finding
            String paths = String.join(", ", violations.subList(0, Math.min(MAX_PATHS, violations.size()))).replaceAll("[^A-Za-z0-9_$.\\[\\], ?]", "?");
            return new Invalid(index, List.of(new ExerciseFinding(index, ExerciseCode.SCHEMA_INVALID, paths)));
        }
        List<String> warnings = new ArrayList<>();
        exercise = dropRepeatedAlternatives(exercise, warnings);
        List<ExerciseFinding> lint = ExerciseLint.lint(exercise, context);
        if (!lint.isEmpty()) return new Invalid(index, lint.stream().map(finding -> finding.at(index)).toList());

        ExerciseCompiler.Compiled compiled;
        ExerciseCommand command;
        try {
            compiled = ExerciseCompiler.compile(exercise, context, ids, known);
            command = ExerciseCommand.readCreate(new ByteArrayInputStream(compiled.command().toString().getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException rejected) {
            return new Invalid(index, List.of(new ExerciseFinding(index, ExerciseCode.COMMAND_REJECTED, null)));
        }
        List<ExerciseFinding> evaluation = new ArrayList<>();
        for (ExerciseProbeEvaluator.Outcome outcome : ExerciseProbeEvaluator.run(command.exercise())) {
            if (outcome.passed()) continue;
            evaluation.add(new ExerciseFinding(index, outcome.probe().isKey() ? ExerciseCode.SELF_EVALUATION_KEY_NOT_CORRECT
                    : ExerciseCode.SELF_EVALUATION_PROBE_ACCEPTED, null));
        }
        if (!evaluation.isEmpty()) return new Invalid(index, evaluation.stream().distinct().toList());
        return new Valid(new Accepted(index, compiled.command(), exercise.path("mechanic").stringValue(""),
                compiled.objectiveTitle(), title(compiled.command().path("exercise"), compiled.objectiveTitle()), warnings));
    }

    /**
     * A FREE_RESPONSE whose {@code accepted} lists the same answer twice (equal under the exercise's own normalization, the one
     * {@link ExerciseLint} and the evaluator compare with) loses the later repeats: nothing is lost, the evaluator accepts the same
     * set of answers. It is a warning, not a repair round.
     */
    private static JsonNode dropRepeatedAlternatives(JsonNode exercise, List<String> warnings) {
        if (!exercise.path("mechanic").stringValue("").equals("FREE_RESPONSE") || !exercise.path("accepted").isArray()) return exercise;
        List<String> accepted = new ArrayList<>();
        exercise.path("accepted").forEach(answer -> accepted.add(answer.stringValue("")));
        List<String> normalization = new ArrayList<>();
        if (exercise.has("normalization")) exercise.path("normalization").forEach(rule -> normalization.add(rule.stringValue("")));
        else normalization.addAll(List.of("UNICODE_NFC", "TRIM", "CASE_FOLD"));
        TextRule rule = new TextRule(accepted, normalization, exercise.path("matchingMode").stringValue("").equals("SOFT"));
        java.util.Set<String> seen = new java.util.HashSet<>();
        ArrayNode kept = JsonNodeFactory.instance.arrayNode();
        for (String answer : accepted) {
            if (seen.add(rule.canonical(answer))) kept.add(answer);
        }
        if (kept.size() == accepted.size()) return exercise;
        warnings.add("FREE_RESPONSE_ALTERNATIVE_DROPPED");
        ObjectNode copy = (ObjectNode) exercise.deepCopy();
        copy.set("accepted", kept);
        return copy;
    }

    /**
     * Validates a compiled command again after the server moved its pins to a newer revision of the material: the lint rules that
     * read the material text ({@link ExerciseRelint}), the publication parser and the probes. Empty findings mean the re-pinned
     * exercise is as good as the original.
     *
     * @param command the full command with placeholders ({@code commandId}, {@code expectedDeckRevisionId}, {@code objective}, {@code exercise})
     * @param nodeText the text of a quoted node in the new revision (empty when the node is gone)
     * @param blockTexts the quotable top-level blocks of the new revision
     */
    public List<ExerciseFinding> revalidate(ObjectNode command, java.util.function.Function<java.util.UUID, java.util.Optional<String>> nodeText,
                                            List<String> blockTexts) {
        List<ExerciseFinding> findings = new ArrayList<>(ExerciseRelint.check(command.path("exercise"), nodeText, blockTexts));
        if (!findings.isEmpty()) return findings;
        ExerciseCommand parsed;
        try {
            parsed = ExerciseCommand.readCreate(new ByteArrayInputStream(command.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException rejected) {
            return List.of(ExerciseFinding.of(ExerciseCode.COMMAND_REJECTED, null));
        }
        for (ExerciseProbeEvaluator.Outcome outcome : ExerciseProbeEvaluator.run(parsed.exercise())) {
            if (!outcome.passed()) {
                findings.add(ExerciseFinding.of(outcome.probe().isKey() ? ExerciseCode.SELF_EVALUATION_KEY_NOT_CORRECT
                        : ExerciseCode.SELF_EVALUATION_PROBE_ACCEPTED, null));
            }
        }
        return findings.stream().distinct().toList();
    }

    /**
     * The comparison key of an exercise for the duplicate rule: the normalized TEXT blocks of its prompt, and for a cloze the
     * passage with every blank as an underscore. {@code content} is the {@code content} of a compiled or stored exercise.
     */
    public static String promptKey(JsonNode content) {
        StringBuilder key = new StringBuilder();
        for (JsonNode block : content.path("prompt")) {
            if (block.path("kind").stringValue("").equals("TEXT")) key.append(block.path("text").stringValue("")).append('\n');
        }
        for (JsonNode segment : content.path("passage")) {
            key.append(segment.path("kind").stringValue("").equals("TEXT") ? segment.path("text").stringValue("") : "_");
        }
        return ExerciseTexts.normalize(key.toString());
    }

    /**
     * The preview title of an exercise artifact: the first TEXT block of the prompt, else the objective title, at most 240 code
     * points ({@code artifactSummary.title}).
     */
    public static String title(JsonNode exercise, String objectiveTitle) {
        String title = objectiveTitle == null ? "" : objectiveTitle;
        for (JsonNode block : exercise.path("content").path("prompt")) {
            if (block.path("kind").stringValue("").equals("TEXT") && !block.path("text").stringValue("").isBlank()) {
                title = block.path("text").stringValue("").strip();
                break;
            }
        }
        return title.codePointCount(0, title.length()) <= MAX_TITLE ? title : title.substring(0, title.offsetByCodePoints(0, MAX_TITLE));
    }
}
