package app.mnema.learning.onboarding;

import app.mnema.learning.usage.SponsoredOperation;
import app.mnema.learning.usage.UsageLedger;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The free AI checks of the onboarding sample deck (OB-203): the first {@code learning.onboarding.sample-deck.free-ai-checks} AI checks an ACCOUNT makes in its live
 * {@code SAMPLE} deck (an {@code ai-semantic} grade, or a speech transcription of an answer) are counted as the sponsored operation {@code ONBOARDING_SAMPLE_CHECK} in the usage
 * ledger instead of the plan's buckets. The count is the account's, for ever: deleting the sample deck and making another gives no more. A later check, a check in any other deck and
 * one the sponsorship did not cover are ordinary checks.
 *
 * <p>The callers keep the discipline of the paid check: {@link #covers} is the early read that admits (and is stale by design), {@link #consume} is called in the transaction that
 * stores the delivered grade or transcript, and when it answers {@code false} the caller consumes the plan's bucket exactly as before. A check that fails, is uncertain or ends in
 * self-check never reaches {@code consume} and costs nothing, like the paid one.
 */
@Component
public final class SampleChecks {
    private final SampleDeckSettings settings;
    private final SampleDeckRepository repository;
    private final UsageLedger ledger;

    SampleChecks(SampleDeckSettings settings, SampleDeckRepository repository, UsageLedger ledger) {
        this.settings = settings;
        this.repository = repository;
        this.ledger = ledger;
    }

    /** The free checks still left to the account (0 to the configured number). A plain read. */
    public int left(UUID account) {
        int limit = settings.freeAiChecks();
        if (limit == 0) return 0;
        return (int) Math.max(0, limit - ledger.sponsoredUsed(account, SponsoredOperation.ONBOARDING_SAMPLE_CHECK));
    }

    /** Whether a check the account is about to make in {@code deck} would be free now: the deck is its live sample deck and it has a free check left. A plain read. */
    public boolean covers(UUID account, UUID deck) {
        return settings.freeAiChecks() > 0 && repository.isLiveSample(account, deck) && left(account) > 0;
    }

    /**
     * Counts one delivered check of {@code deck} as free, in the caller's transaction, once per {@code key}.
     *
     * @return {@code true} when the check is free (nothing more to consume); {@code false} when the deck is not the account's live sample deck or the free checks are used up
     */
    public boolean consume(UUID account, UUID deck, String key, String reference) {
        if (settings.freeAiChecks() == 0 || !repository.isLiveSample(account, deck)) return false;
        return ledger.consumeSponsored(account, SponsoredOperation.ONBOARDING_SAMPLE_CHECK, settings.freeAiChecks(), key, reference);
    }
}
