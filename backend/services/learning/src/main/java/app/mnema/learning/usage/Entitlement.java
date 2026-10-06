package app.mnema.learning.usage;

import java.time.Instant;
import java.util.Objects;

/**
 * What an account may consume right now: a plan and the instant the snapshot it came from stops being valid.
 *
 * @param source {@code CONFIG} when no inbox snapshot is valid; {@code BILLING} or {@code PROMO} for the snapshot of
 *               {@code entitlement_inbox} that granted the plan
 * @param period {@code YEAR} for a snapshot that covers a year; the allowance is still granted per calendar month
 */
public record Entitlement(Plan plan, Source source, Period period, Instant validUntil) {
    public enum Source { CONFIG, BILLING, PROMO }

    public enum Period { MONTH, YEAR }

    public Entitlement {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(validUntil, "validUntil");
    }

    public Entitlement(Plan plan, Source source, Instant validUntil) {
        this(plan, source, Period.MONTH, validUntil);
    }
}
