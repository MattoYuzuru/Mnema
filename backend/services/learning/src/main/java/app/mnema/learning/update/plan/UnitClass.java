package app.mnema.learning.update.plan;

/**
 * How the plan classifies a unit. The declaration order is the order of the plan: what is new, what is safe, what needs a decision, what the author removed, what was
 * decided before and the author changed again.
 */
public enum UnitClass {
    /** The author added the unit and the copy has not got it; safe. */
    NEW,
    /** The author changed it and the learner did not (or both ended with the same content); safe. */
    SAFE,
    /** Both changed it differently, or the learner deleted what the author changed, or the unit cannot work with the learner's materials; the learner's version is the default. */
    CONFLICT,
    /** The author removed what the copy still has; never applied by «accept all safe». */
    DELETED,
    /** A decision was recorded and the author changed the unit after it. */
    ASK_AGAIN
}
