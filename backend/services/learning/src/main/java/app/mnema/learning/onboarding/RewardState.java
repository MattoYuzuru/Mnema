package app.mnema.learning.onboarding;

import java.time.Instant;

/**
 * The reward of an account as the view carries it ({@code reward} of {@code contracts/onboarding/journey.json}): its personal code's state, the code (display form) only while
 * it is {@code ACTIVE}, the offer with the terms of the campaign it was issued under, and the offer's window.
 *
 * @param code the code in the form the recipient reads; null unless {@code ACTIVE} (and null while the promo secret is unusable)
 */
record RewardState(State state, String code, Offer offer, Instant issuedAt, Instant expiresAt) {
    /** {@code ACTIVE} until it is redeemed or its time is up; {@code REDEEMED} wins over {@code EXPIRED}. */
    enum State { ACTIVE, REDEEMED, EXPIRED }

    /** The terms: {@code DISCOUNT_PERCENT} has a percent (and perhaps a plan), {@code TIER_DAYS} a plan and days; the other members are null. */
    record Offer(String type, Integer percent, String plan, Integer days) { }
}
