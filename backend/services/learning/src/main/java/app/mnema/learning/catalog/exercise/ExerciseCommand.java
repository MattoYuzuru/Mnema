package app.mnema.learning.catalog.exercise;

import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.concurrency.VersionPreconditionRequiredException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.json.ContentJsonReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static app.mnema.learning.catalog.exercise.StrictJson.bool;
import static app.mnema.learning.catalog.exercise.StrictJson.fields;
import static app.mnema.learning.catalog.exercise.StrictJson.id;
import static app.mnema.learning.catalog.exercise.StrictJson.integer;
import static app.mnema.learning.catalog.exercise.StrictJson.invalid;
import static app.mnema.learning.catalog.exercise.StrictJson.nonBlank;

/**
 * Strict versioned exercise publication command. Every object has an exact field set: unknown fields,
 * legacy mechanic names and mismatched type/content/answer-key/evaluator combinations are rejected as
 * {@code INVALID_REQUEST}. Bindings are never accepted from the client; the server derives them.
 */
public record ExerciseCommand(UUID commandId, UUID expectedDeckRevisionId, UUID expectedExerciseRevisionId,
                              Objective objective, Exercise exercise, ObjectNode payload) {
    public static final int SCHEMA_VERSION = 2;
    public static final int MAX_MEDIA_BLOCKS = ExerciseDefinition.MAX_MEDIA_BLOCKS;
    public static final int MAX_TITLE = 160;
    private static final int MAX_BYTES = 262_144;
    private static final ContentJsonReader JSON = new ContentJsonReader(MAX_BYTES, 32, 20_000);

    public ExerciseCommand {
        commandId = UuidPolicy.requireCommandId(commandId);
        expectedDeckRevisionId = UuidPolicy.requireEntityId(expectedDeckRevisionId, "expectedDeckRevisionId");
        if (expectedExerciseRevisionId != null) {
            expectedExerciseRevisionId = UuidPolicy.requireEntityId(expectedExerciseRevisionId,
                    "expectedExerciseRevisionId");
        }
        payload = payload.deepCopy();
    }

    @Override public ObjectNode payload() { return payload.deepCopy(); }

    public ObjectNode envelope(UUID deckId, UUID exerciseId, long expectedDeckVersion) {
        ObjectNode envelope = payload();
        envelope.put("deckId", deckId.toString()).put("expectedDeckVersion", Long.toString(expectedDeckVersion));
        if (exerciseId != null) envelope.put("exerciseId", exerciseId.toString());
        return envelope;
    }

    public static ExerciseCommand readCreate(InputStream input) { return read(input, false); }

    public static ExerciseCommand readUpdate(InputStream input) { return read(input, true); }

    private static ExerciseCommand read(InputStream input, boolean update) {
        try {
            JsonNode body = JSON.read(input.readNBytes(MAX_BYTES + 1));
            if (!body.has("expectedDeckRevisionId")) throw new VersionPreconditionRequiredException();
            if (update && !body.has("expectedExerciseRevisionId")) throw new VersionPreconditionRequiredException();
            fields(body, update
                    ? Set.of("commandId", "expectedDeckRevisionId", "expectedExerciseRevisionId", "objective", "exercise")
                    : Set.of("commandId", "expectedDeckRevisionId", "objective", "exercise"), Set.of());
            Objective objective = objective(body.path("objective"));
            Exercise exercise = exercise(body.path("exercise"));
            return new ExerciseCommand(id(body, "commandId"), id(body, "expectedDeckRevisionId"),
                    update ? id(body, "expectedExerciseRevisionId") : null,
                    objective, exercise, (ObjectNode) body);
        } catch (IOException | IllegalArgumentException exception) {
            throw new InvalidRequestException();
        }
    }

    private static Objective objective(JsonNode value) {
        if (!value.isObject() || !value.path("operation").isTextual()) throw invalid();
        return switch (value.path("operation").textValue()) {
            case "create" -> {
                fields(value, "operation", "title");
                yield new CreateObjective(title(value));
            }
            case "reuse" -> {
                fields(value, "operation", "objectiveId", "objectiveRevisionId");
                yield new ReuseObjective(id(value, "objectiveId"), id(value, "objectiveRevisionId"));
            }
            case "revise" -> {
                if (!value.has("expectedObjectiveRevisionId")) throw invalid();
                fields(value, "operation", "objectiveId", "expectedObjectiveRevisionId", "title");
                yield new ReviseObjective(id(value, "objectiveId"), id(value, "expectedObjectiveRevisionId"),
                        title(value));
            }
            default -> throw invalid();
        };
    }

    private static String title(JsonNode objective) { return nonBlank(objective.path("title"), MAX_TITLE); }

    private static Exercise exercise(JsonNode value) {
        fields(value, "type", "schemaVersion", "enabled", "subject", "content", "answerKey", "evaluatorPolicy");
        integer(value.path("schemaVersion"), SCHEMA_VERSION, SCHEMA_VERSION);
        boolean enabled = bool(value.path("enabled"));
        JsonNode subject = value.path("subject");
        fields(subject, "memberKey", "itemRevisionId");
        ExerciseDefinition definition = ExerciseDefinition.read(value.path("type"), value.path("content"),
                value.path("answerKey"), value.path("evaluatorPolicy"));
        Exercise exercise = new Exercise(definition.type(), enabled, new Subject(id(subject, "memberKey"),
                id(subject, "itemRevisionId")), (ObjectNode) value.path("content"),
                (ObjectNode) value.path("answerKey"), (ObjectNode) value.path("evaluatorPolicy"), definition.model(),
                definition.policy());
        return exercise;
    }

    public sealed interface Objective permits CreateObjective, ReuseObjective, ReviseObjective { }
    public record CreateObjective(String title) implements Objective { }
    public record ReuseObjective(UUID objectiveId, UUID objectiveRevisionId) implements Objective { }
    public record ReviseObjective(UUID objectiveId, UUID expectedObjectiveRevisionId, String title)
            implements Objective { }

    /** The ASSESSED subject: the one deck-local material whose objective this exercise evidences. */
    public record Subject(UUID memberKey, UUID itemRevisionId) { }

    /**
     * Validated exercise. The JSON objects are the persisted form; {@code model} is the typed view of
     * {@code content} used to derive media references, materials and capability requirements.
     */
    public record Exercise(ExerciseType type, boolean enabled, Subject subject, ObjectNode content,
                           ObjectNode answerKey, ObjectNode evaluatorPolicy, ExerciseContent model,
                           EvaluatorPolicy policy) {
        public Exercise {
            content = content.deepCopy();
            answerKey = answerKey.deepCopy();
            evaluatorPolicy = evaluatorPolicy.deepCopy();
        }

        @Override public ObjectNode content() { return content.deepCopy(); }
        @Override public ObjectNode answerKey() { return answerKey.deepCopy(); }
        @Override public ObjectNode evaluatorPolicy() { return evaluatorPolicy.deepCopy(); }

        /** Distinct assets pinned by IMAGE, AUDIO and VIDEO blocks of every slot. */
        public List<MediaCatalog.ExerciseAsset> assets() { return ExerciseDefinition.assets(model); }

        /** MATERIAL blocks of every slot, in document order. */
        public List<Block.Material> materials() {
            return model.blocks().stream().filter(Block.Material.class::isInstance)
                    .map(Block.Material.class::cast).toList();
        }

        public boolean requiresSemanticAssessment() { return policy.semantic(); }

        public boolean requiresSpeechToText() {
            return model instanceof ExerciseContent.FreeResponse response && response.acceptsSpeech();
        }
    }
}
