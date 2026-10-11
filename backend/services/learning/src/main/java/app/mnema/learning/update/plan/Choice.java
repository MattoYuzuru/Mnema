package app.mnema.learning.update.plan;

/** The choice a unit starts with in the review screen. */
public enum Choice {
    /** Take the author's version (NEW and SAFE units). */
    ACCEPT,
    /** Keep the learner's version, including "keep it deleted" and "keep it declined" (CONFLICT and ASK_AGAIN units). */
    KEEP_MINE,
    /** Keep the unit although the author removed it (DELETED units). */
    KEEP
}
