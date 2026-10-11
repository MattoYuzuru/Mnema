package app.mnema.learning.library;

/** What {@link DeckAccess} resolved a viewer to: the level at which the viewer reads a deck. {@link #NONE} carries a {@link Denial}. */
public enum AccessLevel {
    OWNER, GRANTEE, PUBLIC, LINK, NONE;

    /**
     * Whether a viewer at this level may copy the deck («Добавить к себе», Share/10): any signed-in account on a {@code PUBLIC} or {@code LINK} deck and a grantee of an
     * {@code INVITE} deck. The owner is not offered a copy of his own deck, and {@code NONE} has no deck at all. The viewer must be signed in (a guest has no account to copy into).
     */
    public boolean canFork() {
        return this == PUBLIC || this == LINK || this == GRANTEE;
    }
}
