package app.mnema.learning.moderation;

import java.time.Instant;
import java.util.UUID;

/** One row of {@code content_complaint}: the target snapshot, the reason, the reporter and the decision. */
record Complaint(UUID id, String targetKind, UUID deckId, String publicCode, UUID profileAccountId, String profileUsername, String reason, String text,
                 UUID reporterId, String status, String decision, Instant createdAt, Instant dueAt, Instant decidedAt, UUID decidedBy, Instant retainUntil) {
    static final String DECK = "DECK";
    static final String PROFILE = "PROFILE";
    static final String OPEN = "OPEN";
    static final String DECIDED = "DECIDED";

    boolean isDeck() { return DECK.equals(targetKind); }

    boolean open() { return OPEN.equals(status); }
}
