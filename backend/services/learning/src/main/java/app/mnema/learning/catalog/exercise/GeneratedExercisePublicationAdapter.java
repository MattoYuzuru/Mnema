package app.mnema.learning.catalog.exercise;

import app.mnema.learning.generation.GeneratedExercisePublisher;
import app.mnema.learning.platform.text.TitleNormalizer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Approval of generated exercises: one {@link ExerciseService#publish} in the caller's transaction. The generated command is
 * read by the very parser the publication endpoint uses, an objective that the material already has under the same title is
 * reused rather than created again, and the published exercise gets its «Новое» mark in the same transaction.
 */
@Component
final class GeneratedExercisePublicationAdapter implements GeneratedExercisePublisher {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ExerciseService exercises;
    private final ExerciseRepository repository;
    private final ExerciseNewMarks marks;

    GeneratedExercisePublicationAdapter(ExerciseService exercises, ExerciseRepository repository, ExerciseNewMarks marks) {
        this.exercises = exercises;
        this.repository = repository;
        this.marks = marks;
    }

    @Override
    public JsonNode create(UUID actor, UUID deckId, long expectedDeckVersion, UUID commandId, UUID expectedDeckRevisionId,
                           JsonNode objective, JsonNode exercise) {
        ObjectNode envelope = JSON.createObjectNode().put("commandId", commandId.toString())
                .put("expectedDeckRevisionId", expectedDeckRevisionId.toString());
        envelope.set("objective", known(actor, deckId, objective, exercise.path("subject").path("memberKey").stringValue("")));
        envelope.set("exercise", exercise.deepCopy());
        ExerciseCommand command = ExerciseCommand.readCreate(new ByteArrayInputStream(envelope.toString().getBytes(StandardCharsets.UTF_8)));
        JsonNode acknowledgement = exercises.publish(actor, deckId, null, expectedDeckVersion, command).acknowledgement();
        marks.mark(actor, deckId, UUID.fromString(acknowledgement.path("exerciseId").stringValue("")));
        return acknowledgement;
    }

    /** A {@code create} objective whose title the material already carries is the existing objective (its current revision). */
    private JsonNode known(UUID actor, UUID deckId, JsonNode objective, String member) {
        if (!objective.path("operation").stringValue("").equals("create") || member.isEmpty()) return objective.deepCopy();
        String wanted = TitleNormalizer.normalize(objective.path("title").stringValue(""));
        UUID memberKey;
        try {
            memberKey = UUID.fromString(member);
        } catch (IllegalArgumentException malformed) {
            return objective.deepCopy();
        }
        Optional<ExerciseRepository.ObjectiveRow> existing = repository.objectivesOf(actor, deckId, memberKey).stream()
                .filter(row -> TitleNormalizer.normalize(row.title()).equals(wanted)).findFirst();
        if (existing.isEmpty()) return objective.deepCopy();
        return JSON.createObjectNode().put("operation", "reuse").put("objectiveId", existing.get().objectiveId().toString())
                .put("objectiveRevisionId", existing.get().revisionId().toString());
    }
}
