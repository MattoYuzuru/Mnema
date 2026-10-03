package app.mnema.learning.generation.exercise;

import java.util.Objects;

/**
 * One finding about one exercise of a model answer: a stable code and, where it helps, the JSON path of the offending
 * member (for example {@code options}). Findings never echo content; a path is made of schema member names and indexes only.
 *
 * @param index zero-based position of the exercise in the answer; {@code -1} for a finding about the whole answer
 */
public record ExerciseFinding(int index, ExerciseCode code, String path) {
    public ExerciseFinding {
        Objects.requireNonNull(code, "code");
    }

    public static ExerciseFinding of(ExerciseCode code, String path) {
        return new ExerciseFinding(0, code, path);
    }

    public ExerciseFinding at(int position) {
        return new ExerciseFinding(position, code, path);
    }
}
