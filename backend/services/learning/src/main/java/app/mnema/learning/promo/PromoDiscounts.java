package app.mnema.learning.promo;

import app.mnema.learning.usage.UsageClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/** The pending discount an account earned with a promo code: read by the paywall now and by billing (#79) when it prices a purchase. */
@Service
public class PromoDiscounts {
    /** @param plan {@code PLUS} or {@code PRO}, or null when the discount applies to either */
    public record Pending(int percent, String plan, java.time.Instant validUntil) { }

    private final PromoRepository repository;
    private final UsageClock clock;

    PromoDiscounts(PromoRepository repository, UsageClock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<Pending> pending(UUID owner) {
        return repository.pendingDiscount(owner, clock.now()).map(row -> new Pending(row.percent(), row.plan(), row.validUntil()));
    }
}
