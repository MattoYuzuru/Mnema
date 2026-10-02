package app.mnema.learning.study.attempt;

import app.mnema.learning.catalog.exercise.ExerciseDefinition;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.json.ContentJsonReader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Strict envelope of one stateless author-preview evaluation: the exercise as the editor would publish it
 * (without deck subject, enabled flag or objective) plus exactly one action. The exercise is validated by the
 * same {@link ExerciseDefinition} as publication; nothing here identifies or reads stored data.
 *
 * <p>Every failure is the same {@link InvalidRequestException}, so error text never echoes author content.
 */
record ExercisePreviewCommand(ExerciseDefinition definition, ObjectNode content, ObjectNode answerKey,
                              ObjectNode evaluatorPolicy, Action action) {
    static final int MAX_BYTES = 65_536;
    private static final int SCHEMA_VERSION = 2;
    private static final int MAX_HINTS = 12;
    private static final ContentJsonReader JSON = new ContentJsonReader(MAX_BYTES, 32, 20_000);

    ExercisePreviewCommand {
        content = content.deepCopy();
        answerKey = answerKey.deepCopy();
        evaluatorPolicy = evaluatorPolicy.deepCopy();
    }

    @Override public ObjectNode content() { return content.deepCopy(); }
    @Override public ObjectNode answerKey() { return answerKey.deepCopy(); }
    @Override public ObjectNode evaluatorPolicy() { return evaluatorPolicy.deepCopy(); }

    static ExercisePreviewCommand read(InputStream input) {
        try {
            JsonNode body = JSON.read(input.readNBytes(MAX_BYTES + 1));
            AttemptCommand.fields(body, Set.of("exercise", "action"));
            JsonNode exercise = body.path("exercise");
            AttemptCommand.fields(exercise,
                    Set.of("type", "schemaVersion", "content", "answerKey", "evaluatorPolicy"));
            JsonNode version = exercise.path("schemaVersion");
            if (!version.isIntegralNumber() || !version.canConvertToInt()
                    || version.intValue() != SCHEMA_VERSION) throw invalid();
            ExerciseDefinition definition = ExerciseDefinition.read(exercise.path("type"), exercise.path("content"),
                    exercise.path("answerKey"), exercise.path("evaluatorPolicy"));
            return new ExercisePreviewCommand(definition, (ObjectNode) exercise.path("content"),
                    (ObjectNode) exercise.path("answerKey"), (ObjectNode) exercise.path("evaluatorPolicy"),
                    action(body.path("action")));
        } catch (IOException | IllegalArgumentException exception) { throw invalid(); }
    }

    private static Action action(JsonNode value) {
        if (!value.path("kind").isString()) throw invalid();
        return switch (value.path("kind").stringValue(null)) {
            case "SUBMIT" -> {
                AttemptCommand.fields(value, Set.of("kind", "response", "hintedBlankIds", "pairMistakes",
                        "transcriptRevealed"));
                AttemptCommand.Response response = AttemptCommand.response(value.path("response"));
                // Cancelling is a Study terminal command; a preview has nothing to terminate.
                if (response instanceof AttemptCommand.CancelResponse) throw invalid();
                yield new Submit(response, hintedBlankIds(value.path("hintedBlankIds")),
                        flag(value.path("pairMistakes")), flag(value.path("transcriptRevealed")));
            }
            case "PAIR_CHECK" -> {
                AttemptCommand.fields(value, Set.of("kind", "leftId", "rightId"));
                yield new PairCheck(AttemptCommand.id(value.path("leftId"), false),
                        AttemptCommand.id(value.path("rightId"), false));
            }
            case "HINT" -> {
                AttemptCommand.fields(value, Set.of("kind", "blankId"));
                yield new Hint(AttemptCommand.id(value.path("blankId"), false));
            }
            default -> throw invalid();
        };
    }

    private static Set<UUID> hintedBlankIds(JsonNode value) {
        if (!value.isArray() || value.size() > MAX_HINTS) throw invalid();
        Set<UUID> distinct = new HashSet<>();
        for (JsonNode id : value) if (!distinct.add(AttemptCommand.id(id, false))) throw invalid();
        return Set.copyOf(distinct);
    }

    private static boolean flag(JsonNode value) {
        if (!value.isBoolean()) throw invalid();
        return value.booleanValue();
    }

    private static InvalidRequestException invalid() { return new InvalidRequestException(); }

    sealed interface Action permits Submit, PairCheck, Hint { }

    /** {@code hintedBlankIds}, {@code pairMistakes} and {@code transcriptRevealed} replace Study's server records. */
    record Submit(AttemptCommand.Response response, Set<UUID> hintedBlankIds, boolean pairMistakes,
                  boolean transcriptRevealed) implements Action { }

    record PairCheck(UUID leftId, UUID rightId) implements Action { }

    record Hint(UUID blankId) implements Action { }
}
