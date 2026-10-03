package app.mnema.learning.generation.exercise;

import java.util.UUID;

/**
 * The identifier source of the exercise compiler. The model writes local IDs; the compiler asks for one real identifier per
 * local ID, by class, so a test can inject the golden allocator of {@code contracts/generation/exercises/README.md}. The
 * compiler never calls {@code UUID.randomUUID()} itself.
 */
public interface ExerciseIds {
    /** The id classes of the contract: option {@code o}, blank {@code bl}, left {@code l}, right {@code r}, item {@code i}, category {@code c}. */
    enum Kind { OPTION, BLANK, LEFT, RIGHT, ITEM, CATEGORY }

    UUID next(Kind kind);

    /** Production allocator: random UUIDv4. */
    static ExerciseIds random() {
        return kind -> UUID.randomUUID();
    }
}
