package app.mnema.learning.usage;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * The effective {@link EntitlementSource}: the valid {@code entitlement_inbox} snapshot of the owner with the highest plan
 * (started, not expired; {@code BILLING} or {@code PROMO}; ties go to the latest received), else the configured entitlement. Nothing a request carries reaches this
 * class: the rows are written by {@link EntitlementInbox#accept} alone.
 *
 * <p>A billing snapshot that spans more than two months is a {@code YEAR} entitlement. Promo access always reports
 * {@code MONTH}, the quota cadence, rather than inventing an annual purchase from the length of a gift. This changes nothing about granting: the
 * allowance is a calendar month's ({@link UsageCalendar}), so a year snapshot yields the plan's monthly allowance in each
 * month until {@code validUntil}, never twelve at once.
 */
@Component
final class InboxEntitlementSource implements EntitlementSource {
    private static final int YEAR_THRESHOLD_MONTHS = 2;

    private final UsageRepository repository;
    private final ConfigEntitlementSource fallback;

    InboxEntitlementSource(UsageRepository repository, ConfigEntitlementSource fallback) {
        this.repository = repository;
        this.fallback = fallback;
    }

    @Override
    public Entitlement current(UUID owner, Instant now) {
        return repository.newestValidSnapshot(owner, now).map(InboxEntitlementSource::entitlement)
                .orElseGet(() -> fallback.current(owner, now));
    }

    private static Entitlement entitlement(UsageRepository.SnapshotRow snapshot) {
        Entitlement.Period period = snapshot.source().equals("BILLING") && snapshot.periodEnd().isAfter(
                snapshot.periodStart().atZone(ZoneOffset.UTC).plusMonths(YEAR_THRESHOLD_MONTHS).toInstant())
                ? Entitlement.Period.YEAR : Entitlement.Period.MONTH;
        return new Entitlement(snapshot.plan(), Entitlement.Source.valueOf(snapshot.source()), period, snapshot.validUntil());
    }
}
