package app.mnema.learning.catalog.exercise;

import java.util.Optional;

/** The five canonical mechanics. Media kind is content, never a mechanic. */
public enum ExerciseType {
    SELF_CHECK("self-check"),
    FREE_RESPONSE("deterministic-text"),
    CLOZE("deterministic-cloze"),
    CHOICE("deterministic-choice"),
    MATCH("deterministic-match");

    private final String evaluatorId;

    ExerciseType(String evaluatorId) { this.evaluatorId = evaluatorId; }

    /** The deterministic evaluator of this mechanic; FREE_RESPONSE may instead use {@code ai-semantic}. */
    public String evaluatorId() { return evaluatorId; }

    /** Exact wire name only: no aliases and no case folding. */
    public static Optional<ExerciseType> fromWire(String value) {
        for (ExerciseType type : values()) if (type.name().equals(value)) return Optional.of(type);
        return Optional.empty();
    }
}
