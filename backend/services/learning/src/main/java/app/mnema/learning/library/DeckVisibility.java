package app.mnema.learning.library;

/**
 * Who may read a deck besides its owner ({@code deck_publication.visibility}; a deck without a row is {@link #PRIVATE}).
 * {@link #LINK}: anyone with the link, guests included. {@link #INVITE}: accounts that hold a grant. {@link #PUBLIC}: everyone, and the
 * catalog once Share/8 and the community epic list it.
 */
public enum DeckVisibility {
    PRIVATE, INVITE, LINK, PUBLIC;

    /**
     * Whether moving from this level to {@code target} is a move to a more restrictive level. The levels are ordered by openness,
     * {@link #PUBLIC} &gt; {@link #LINK} &gt; {@link #INVITE} &gt; {@link #PRIVATE} (the declaration order), and ANY step down, {@code PUBLIC} to
     * {@code LINK} included, rotates the public code: «при понижении видимости ссылка меняется, и старые ссылки перестают работать» (product contract
     * «Доступ»). Raising the level or keeping it keeps the code, so a link shared after a lowering survives raising again.
     */
    public boolean lowersTo(DeckVisibility target) {
        return target.ordinal() < ordinal();
    }
}
