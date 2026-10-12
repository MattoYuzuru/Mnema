package app.mnema.learning.community;

/**
 * The display rule of the audience numbers on a deck card, in ONE place for every route (contracts/community/catalog.json, constants {@code audienceMinShown} and
 * {@code audienceRounding}; product contract «Сообщество»): below {@value #MIN_SHOWN} the field is {@code null}, so that an exact small audience never leaves the server; from
 * {@value #MIN_SHOWN} up it is floored to two significant digits (10 to 99 are exact, 1234 shows 1200, 100999 shows 100000). The raw counts stay in {@code deck_rank}; nothing but
 * this helper turns them into something a client sees.
 */
public final class AudienceCounts {
    /** The audience below which nothing is shown. */
    public static final int MIN_SHOWN = 10;

    private AudienceCounts() { }

    /**
     * @param raw the exact count (not negative)
     * @return {@code null} below {@value #MIN_SHOWN}, otherwise {@code raw} floored to two significant digits
     * @throws IllegalArgumentException for a negative count
     */
    public static Integer shown(int raw) {
        if (raw < 0) throw new IllegalArgumentException("A count is not negative");
        if (raw < MIN_SHOWN) return null;
        int step = 1;
        // the largest power of ten that leaves at least two digits: 100..999 floor to tens, 1000..9999 to hundreds, and so on
        while (raw / step >= 100) step *= 10;
        return raw / step * step;
    }
}
