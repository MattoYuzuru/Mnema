package app.mnema.learning.promo;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The port through which a feature issues personal promo codes (OB-402): a code of an enabled campaign that only one account, its recipient, can redeem
 * ({@link PromoService} answers {@code PROMO_INVALID} to any other). The plain code is never stored: it is derived from the code row's id under the promo secret
 * ({@link PromoCodes#derive}), so the row holds only the keyed hash and the hint, and the recipient's view derives the code again with {@link #displayCode}.
 *
 * <p>The code is a {@code DISCOUNT_PERCENT} or {@code TIER_DAYS} code with the terms of the campaign, one redemption, once per account, channel {@value #CHANNEL}, valid from
 * a minute before {@code issuedAt} ({@link #CLOCK_SKEW}: the issuance takes the database clock, the redemption compares with the application's, and a code must never be refused
 * the moment it is shown) until {@code expiresAt}. Writing is {@link Propagation#MANDATORY}: the caller issues the code in the transaction of its own record of the issuance.
 */
@Service
public class PersonalPromoCodes {
    /** The validity starts this much before the issuance, so a skew between the database and the application clocks never refuses a fresh code. */
    static final Duration CLOCK_SKEW = Duration.ofMinutes(1);

    /** The channel of the codes of the {@code PATH_REWARD} campaign. */
    public static final String CHANNEL = "path-reward";
    /** A derived code collides with another code's hash with probability about 2^-59; the retry is for completeness, not an expected path. */
    private static final int ATTEMPTS = 5;

    /**
     * An enabled campaign as the issuer needs it.
     *
     * @param offerType {@code DISCOUNT_PERCENT} (percent, plan optional) or {@code TIER_DAYS} (plan and days)
     * @param createdBy the administrator who made the campaign: the creator of every code it issues
     */
    public record Campaign(UUID campaignId, PromoType offerType, Integer percent, String plan, Integer days, UUID createdBy) { }

    private final PromoCampaignRepository campaigns;
    private final PromoRepository codes;
    private final PromoSettings settings;

    PersonalPromoCodes(PromoCampaignRepository campaigns, PromoRepository codes, PromoSettings settings) {
        this.campaigns = campaigns;
        this.codes = codes;
        this.settings = settings;
    }

    /** False only in production without a usable promo secret (the same condition that switches promo codes off): no code can be issued or shown then. */
    public boolean available() {
        return settings.available;
    }

    /** The enabled campaign of the type, if the owner has switched one on. A plain read in the caller's transaction. */
    public Optional<Campaign> enabledCampaign(PromoCampaignType type) {
        return campaigns.enabled(type).map(row -> new Campaign(row.campaignId(), row.offerType(), row.percent(), row.plan(), row.days(), row.createdBy()));
    }

    /**
     * Inserts the personal code of {@code recipient} with the terms of {@code campaign}.
     *
     * @param issuedAt the start of the validity (whole seconds); {@code expiresAt} the end of it, after {@code issuedAt}
     * @return the id of the new code row
     * @throws IllegalStateException the promo secret is not available, or no free code was found in {@value #ATTEMPTS} attempts
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID issue(Campaign campaign, UUID recipient, Instant issuedAt, Instant expiresAt) {
        if (!settings.available) throw new IllegalStateException("Promo codes are unavailable");
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            UUID codeId = UUID.randomUUID();
            String normalized = PromoCodes.derive(settings.hashSecret, codeId);
            boolean tier = campaign.offerType().grantsTier();
            PromoRepository.Code row = new PromoRepository.Code(codeId, PromoCodes.hint(normalized), campaign.offerType(), campaign.plan(), tier ? campaign.days() : null, null,
                    tier ? null : campaign.percent(), issuedAt.minus(CLOCK_SKEW), expiresAt, 1, true, CHANNEL, true, issuedAt, campaign.createdBy(), campaign.campaignId(), recipient);
            if (codes.insertCode(row, PromoCodes.hash(settings.hashSecret, normalized))) return codeId;
        }
        throw new IllegalStateException("No free personal code");
    }

    /**
     * The code of a personal code row in the form the recipient reads (three groups of four). Empty when the promo secret is not available, or when the secret is no longer
     * the one the row was issued under (the derived code does not hash to {@code storedHash}; a development process without a configured secret draws a new one at every start):
     * a code that cannot be redeemed is never shown.
     *
     * @param storedHash the {@code code_hash} of the row
     */
    public Optional<String> displayCode(UUID codeId, byte[] storedHash) {
        if (!settings.available) return Optional.empty();
        String normalized = PromoCodes.derive(settings.hashSecret, codeId);
        return java.security.MessageDigest.isEqual(PromoCodes.hash(settings.hashSecret, normalized), storedHash) ? Optional.of(PromoCodes.display(normalized)) : Optional.empty();
    }
}
