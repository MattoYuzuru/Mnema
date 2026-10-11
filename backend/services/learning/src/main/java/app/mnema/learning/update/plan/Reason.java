package app.mnema.learning.update.plan;

/** Why a unit is a conflict (or is asked again). A closed list: the frontend owns the wording. */
public enum Reason {
    /** The learner changed the unit too and the content differs. */
    BOTH_CHANGED,
    /** The learner deleted the unit and the author changed it. */
    DELETED_BY_YOU,
    /** The author's exercise quotes nodes of a material whose copy head no longer has them. */
    MATERIAL_CHANGED,
    /** The author's exercise needs a material the learner deleted (or declined). */
    MATERIAL_DELETED,
    /** The author's exercise evidences an objective the learner changed (or declined to take). */
    OBJECTIVE_RENAMED,
    /** An earlier decision exists and the author changed the unit after it. */
    CHANGED_AFTER_DECISION
}
