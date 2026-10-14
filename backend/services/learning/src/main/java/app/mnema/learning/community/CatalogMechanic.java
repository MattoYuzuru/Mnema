package app.mnema.learning.community;

import app.mnema.learning.catalog.exercise.ExerciseType;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * The «упражнения» facet of the catalogue (product contract «Поиск»): which kinds of exercise a published deck contains. It is the ONE place that maps the seven
 * mechanics of {@link ExerciseType} to the five things a learner can filter by, the bit of {@code public_deck.mechanics_mask} and the wire word of the filter
 * ({@code listening}, {@code matching}, {@code written}, {@code choice}, {@code order}).
 *
 * <p>{@link #LISTENING} is not a mechanic but a property of the content (an exercise whose revision references an AUDIO asset in {@code exercise_media_ref}, whatever
 * its mechanic), so it is set by {@link #maskOf} from the flag the projector reads. {@code SELF_CHECK} sets no bit: a flashcard is the default and not a filter.
 */
enum CatalogMechanic {
    /** «на слух» */
    LISTENING("listening", 1),
    /** «сопоставление»: MATCH and CATEGORIZE */
    MATCHING("matching", 2),
    /** «письменный ответ»: FREE_RESPONSE and CLOZE */
    WRITTEN("written", 4),
    /** «выбор варианта» */
    CHOICE("choice", 8),
    /** «по порядку» */
    ORDER("order", 16);

    /** The mask with every bit set; the column's CHECK is {@code BETWEEN 0 AND 31}. */
    static final int ALL = 31;

    private final String wire;
    private final int bit;

    CatalogMechanic(String wire, int bit) {
        this.wire = wire;
        this.bit = bit;
    }

    String wire() { return wire; }

    int bit() { return bit; }

    static Optional<CatalogMechanic> fromWire(String value) {
        for (CatalogMechanic mechanic : values()) if (mechanic.wire.equals(value)) return Optional.of(mechanic);
        return Optional.empty();
    }

    /** The bits one exercise of {@code type} sets; {@code referencesAudio} says that its revision pins an audio asset. */
    static int maskOf(ExerciseType type, boolean referencesAudio) {
        int mask = referencesAudio ? LISTENING.bit : 0;
        return mask | switch (type) {
            case SELF_CHECK -> 0;
            case MATCH, CATEGORIZE -> MATCHING.bit;
            case FREE_RESPONSE, CLOZE -> WRITTEN.bit;
            case CHOICE -> CHOICE.bit;
            case ORDER -> ORDER.bit;
        };
    }

    static Set<CatalogMechanic> fromMask(int mask) {
        Set<CatalogMechanic> found = EnumSet.noneOf(CatalogMechanic.class);
        for (CatalogMechanic mechanic : values()) if ((mask & mechanic.bit) != 0) found.add(mechanic);
        return found;
    }
}
