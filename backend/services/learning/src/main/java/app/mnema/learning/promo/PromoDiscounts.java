package app.mnema.learning.promo;

import app.mnema.learning.usage.UsageClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** The pending discount an account earned with a promo code: read by the paywall and by billing (#79, #389) when it prices a purchase; billing consumes it when the purchase is paid. */
@Service
public class PromoDiscounts {
    /**
     * @param plan   {@code PLUS} or {@code PRO}, or null when the discount applies to either
     * @param codeId the promo code that earned it: billing hands it back to {@link #consume} once the order is paid
     */
    public record Pending(int percent, String plan, java.time.Instant validUntil, UUID codeId) { }

    private final PromoRepository repository;
    private final UsageClock clock;

    PromoDiscounts(PromoRepository repository, UsageClock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<Pending> pending(UUID owner) {
        return repository.pendingDiscount(owner, clock.now()).map(row -> new Pending(row.percent(), row.plan(), row.validUntil(), row.codeId()));
    }

    /**
     * Spends the discount of {@code codeId} after the order that used it was paid. It deletes the row only while the account's discount still is that
     * code's, so a better discount redeemed between checkout and payment survives.
     *
     * @return whether a row was deleted
     */
    @Transactional
    public boolean consume(UUID owner, UUID codeId) {
        return repository.deleteDiscount(owner, codeId);
    }
}
