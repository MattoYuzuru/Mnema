package app.mnema.learning.update.plan;

/**
 * The plan the caller holds no longer describes the copy (409 {@code UPDATE_PLAN_STALE}): the copy's head, the source's published revision, the base or a decision changed
 * after the plan (or a cursor of it) was issued. The caller reads the plan again. Carries no data.
 */
public final class UpdatePlanStaleException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UpdatePlanStaleException() {
        super("The update plan is stale", null, false, false);
    }
}
