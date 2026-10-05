package app.mnema.learning.usage;

import app.mnema.learning.platform.id.UuidPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The contract for the future billing context (#79, AI-19, AI-21): an entitlement snapshot (plan, period, allowances,
 * {@code valid_until}) arrives idempotently by {@code snapshotId}. {@link InboxEntitlementSource} reads the newest
 * valid snapshot of an owner. There is no HTTP endpoint, and a browser return URL never reaches it: this method is the
 * only writer.
 */
@Service
public class EntitlementInbox {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:+@/-]{0,199}");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_ALLOWANCES_BYTES = 8_192;

    /**
     * @param source     {@code BILLING} or {@code PROMO}
     * @param allowances the plan's allowance document as published by billing; an object, at most 8 KiB
     */
    public record Snapshot(String snapshotId, UUID owner, Plan plan, String source, Instant periodStart, Instant periodEnd,
                           JsonNode allowances, Instant validUntil) { }

    private final UsageRepository repository;
    private final UsageClock clock;

    EntitlementInbox(UsageRepository repository, UsageClock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Stores a snapshot once. An identical repeat is a no-op.
     *
     * @return whether the snapshot was new
     * @throws IllegalArgumentException a malformed snapshot, or a different one under an existing {@code snapshotId}
     */
    @Transactional
    public boolean accept(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.snapshotId() == null || !ID.matcher(snapshot.snapshotId()).matches()
                || snapshot.plan() == null || snapshot.periodStart() == null || snapshot.periodEnd() == null
                || snapshot.validUntil() == null || !snapshot.periodEnd().isAfter(snapshot.periodStart())
                || !(snapshot.source().equals("BILLING") || snapshot.source().equals("PROMO"))
                || snapshot.allowances() == null || !snapshot.allowances().isObject()) {
            throw new IllegalArgumentException("Invalid entitlement snapshot");
        }
        UuidPolicy.requireEntityId(snapshot.owner(), "owner");
        String allowances = JSON.writeValueAsString(snapshot.allowances());
        if (allowances.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_ALLOWANCES_BYTES) {
            throw new IllegalArgumentException("Invalid entitlement snapshot");
        }
        if (repository.insertSnapshot(snapshot.snapshotId(), snapshot.owner(), snapshot.plan(), snapshot.source(),
                snapshot.periodStart(), snapshot.periodEnd(), allowances, snapshot.validUntil(), clock.now())) {
            return true;
        }
        if (!repository.snapshotMatches(snapshot.snapshotId(), snapshot.owner(), snapshot.plan(), snapshot.source(),
                snapshot.periodStart(), snapshot.periodEnd(), allowances, snapshot.validUntil())) {
            throw new IllegalArgumentException("Entitlement snapshot id reused for a different snapshot");
        }
        return false;
    }
}
