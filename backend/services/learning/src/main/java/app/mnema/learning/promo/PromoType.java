package app.mnema.learning.promo;

/** What a promo code gives: a tier for N days or N months without renewal, or a percentage off the next purchase. */
public enum PromoType {
    TIER_DAYS, TIER_MONTHS, DISCOUNT_PERCENT;

    boolean grantsTier() {
        return this != DISCOUNT_PERCENT;
    }
}
