package app.mnema.learning.library;

/** What {@link DeckAccess} resolved a viewer to: the level at which the viewer reads a deck. {@link #NONE} carries a {@link Denial}. */
public enum AccessLevel {
    OWNER, GRANTEE, PUBLIC, LINK, NONE
}
