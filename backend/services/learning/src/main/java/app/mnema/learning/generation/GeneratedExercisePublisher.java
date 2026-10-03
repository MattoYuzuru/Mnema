package app.mnema.learning.generation;

import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Caller-owned port isolating approval from the way {@code ExerciseService} stages a publication (the pattern of
 * {@link GeneratedItemPublisher}): the catalog implements it, the generation module calls it, in its own transaction. An
 * approval of generated exercises publishes them one after the other through this port, each with a child command id and the
 * deck revision and version the previous publication left, so the whole approval is one transaction and one chain of deck
 * revisions; any failure rolls all of it back.
 */
public interface GeneratedExercisePublisher {
    /**
     * Publishes one new exercise (and its objective) at the end of the deck's exercises.
     *
     * <p>The implementation also marks the exercise «Новое» and, for a {@code create} objective whose normalized title equals
     * the title of an objective already bound to the same subject material, publishes a {@code reuse} of that objective instead:
     * an approval of three exercises that name the same direction creates one objective.
     *
     * @param commandId the publication's own command identifier (the approval derives it from its own and the artifact)
     * @param objective {@code {operation: create, title}} or {@code {operation: reuse, objectiveId, objectiveRevisionId}}
     * @param exercise {@code {type, schemaVersion, enabled, subject, content, answerKey, evaluatorPolicy}} as the authoring UI sends it
     * @return the publication acknowledgement of the catalog: {@code deckRevisionId}, {@code deckVersion}, {@code exerciseId},
     *         {@code exerciseRevisionId}, {@code objectiveId}, {@code objectiveRevisionId}
     * @throws app.mnema.learning.platform.api.InvalidRequestException the command is not a valid exercise publication
     * @throws ObjectiveUnavailableException a reused objective is gone or belongs to another material
     */
    JsonNode create(UUID actor, UUID deckId, long expectedDeckVersion, UUID commandId, UUID expectedDeckRevisionId,
                    JsonNode objective, JsonNode exercise);

    /**
     * Publishes the next revision of an existing exercise ({@code REVISE_EXERCISE}, #294): an ordinary update of that exercise, in place.
     * The objective is the one the exercise has ({@code reuse}, published against its current revision); the exercise is not marked «Новое».
     *
     * @param expectedExerciseRevisionId the revision the revision was made of, which must still be the exercise's head
     * @throws app.mnema.learning.platform.api.InvalidRequestException the command is not a valid exercise publication
     * @throws ObjectiveUnavailableException the objective is gone or belongs to another material
     */
    JsonNode revise(UUID actor, UUID deckId, long expectedDeckVersion, UUID commandId, UUID expectedDeckRevisionId, UUID exerciseId,
                    UUID expectedExerciseRevisionId, JsonNode objective, JsonNode exercise);

    /**
     * A {@code reuse} objective that is gone or is no longer bound to the exercise's subject material: the proposal rests on something
     * that does not exist any more, so the approval answers {@code SOURCE_STALE} and never a version conflict. (A reused objective
     * whose head revision merely moved is not this: the implementation publishes against the current revision.)
     */
    final class ObjectiveUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ObjectiveUnavailableException() {
            super("The objective of the proposal is no longer available", null, false, false);
        }
    }
}
