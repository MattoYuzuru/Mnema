package app.mnema.importer.client.core;

import java.time.Instant;
import java.util.UUID;

public record CoreCardProgressRequest(
        UUID userCardId,
        double difficulty01,
        double stabilityDays,
        int reviewCount,
        Instant lastReviewAt,
        Instant nextReviewAt,
        boolean suspended
) {
}
