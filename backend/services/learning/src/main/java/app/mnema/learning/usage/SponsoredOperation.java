package app.mnema.learning.usage;

/**
 * An operation Mnema pays for instead of the user's plan: a usage ledger entry of 0 credits tagged with the operation, counted per account and capped by the caller
 * ({@link UsageLedger#consumeSponsored}). Not a {@link Bucket}: it has no window, no plan allowance and no bar. The name is the ledger {@code operation} (and the row of the
 * owner console report's {@code usage.features}), so it is a closed list that is never built from a request.
 */
public enum SponsoredOperation {
    /** One of the first AI checks an account makes in its onboarding sample deck ({@code contracts/onboarding}, {@code constants.sampleDeck.usageOperation}). */
    ONBOARDING_SAMPLE_CHECK
}
