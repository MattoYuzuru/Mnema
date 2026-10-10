package app.mnema.learning.library;

import java.util.Objects;

/** The outcome of {@link DeckAccess}: a granted level with the deck, or {@link AccessLevel#NONE} with the {@link Denial} to answer. */
public record Resolution(AccessLevel level, Denial denial, DeckRef deck) {
    public Resolution {
        Objects.requireNonNull(level, "level");
        if ((level == AccessLevel.NONE) != (denial != null) || (level == AccessLevel.NONE) != (deck == null)) {
            throw new IllegalArgumentException("A denial has no deck and a grant has no denial");
        }
    }

    static Resolution granted(AccessLevel level, DeckRef deck) { return new Resolution(level, null, deck); }

    static Resolution denied(Denial denial) { return new Resolution(AccessLevel.NONE, denial, null); }

    public boolean granted() { return level != AccessLevel.NONE; }
}
