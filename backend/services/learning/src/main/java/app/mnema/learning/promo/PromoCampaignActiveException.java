package app.mnema.learning.promo;

/** Another campaign of the type is already enabled ({@code 409 PROMO_CAMPAIGN_ACTIVE}); carries nothing. */
public final class PromoCampaignActiveException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    PromoCampaignActiveException() {
        super("Another promo campaign of the type is enabled", null, false, false);
    }
}
