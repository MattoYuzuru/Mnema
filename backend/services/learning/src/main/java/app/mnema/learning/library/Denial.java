package app.mnema.learning.library;

/**
 * Why a viewer got {@link AccessLevel#NONE}. {@link #NOT_FOUND} covers a missing, deleted, private, never published and rotated-away deck alike: the
 * answer must not tell them apart. {@link #INVITE_ONLY} is the one refusal that is allowed to exist: the deck is reachable only for invited accounts
 * (403, without any deck data).
 */
public enum Denial {
    NOT_FOUND, INVITE_ONLY
}
