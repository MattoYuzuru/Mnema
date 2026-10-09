package app.mnema.learning.platform.api;

/** A checkout for a plan below the one the account already has ({@code 409 BILLING_PLAN_BELOW_CURRENT}): paying for it would change nothing. */
public final class BillingPlanBelowCurrentException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public BillingPlanBelowCurrentException() {
        super("Plan below current", null, false, false);
    }
}
