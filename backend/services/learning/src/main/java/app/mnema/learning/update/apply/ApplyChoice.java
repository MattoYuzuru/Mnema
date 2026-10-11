package app.mnema.learning.update.apply;

import java.util.Optional;

/**
 * What the owner says about one unit of an update plan in {@code POST /decks/{id}/update-plan:apply}. The wire names are the constants. Which ones a unit takes depends on its class
 * (contract {@code contracts/decks/updates.json}): NEW and SAFE take {@link #ACCEPT} or {@link #DECLINE}, CONFLICT and ASK_AGAIN {@link #ACCEPT} (the author's version) or
 * {@link #KEEP_MINE}, DELETED {@link #DELETE} (remove it from the copy) or {@link #KEEP}; METADATA, ORDER and OBJECTIVE follow their class.
 */
public enum ApplyChoice {
    /** Take the author's version (a new unit, a fix, or the author's side of a conflict or of a question asked again). */
    ACCEPT,
    /** Keep the copy's version (a conflict or a question asked again). */
    KEEP_MINE,
    /** Do not take what the author added or fixed; the unit is not offered again until the author changes it. */
    DECLINE,
    /** The author removed the unit: remove it from the copy. */
    DELETE,
    /** The author removed the unit: keep it in the copy. */
    KEEP;

    /** Whether the choice takes the author's state of the unit into the copy. */
    boolean takesAuthor() { return this == ACCEPT || this == DELETE; }

    /** The wire name, strict: lowercase or padded names are not choices. */
    static Optional<ApplyChoice> fromWire(String value) {
        if (value == null) return Optional.empty();
        for (ApplyChoice choice : values()) {
            if (choice.name().equals(value)) return Optional.of(choice);
        }
        return Optional.empty();
    }
}
