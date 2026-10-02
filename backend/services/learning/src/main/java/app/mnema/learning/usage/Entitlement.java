package app.mnema.learning.usage;

import java.time.Instant;
import java.util.Objects;

/**
 * What an account may consume right now: a plan and the instant the snapshot it came from stops being valid.
 *
 * @param source {@code CONFIG} until billing (#79) publishes snapshots to {@code entitlement_inbox}
 */
public record Entitlement(Plan plan, Source source, Instant validUntil) {
    public enum Source { CONFIG, BILLING }

    public Entitlement {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(validUntil, "validUntil");
    }
}
