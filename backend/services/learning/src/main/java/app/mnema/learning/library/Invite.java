package app.mnema.learning.library;

import java.time.Instant;
import java.util.UUID;

/**
 * An invitation link as the owner sees it. The token is not here: it exists only in the answer that created the invitation. {@code stale} means the public
 * code was rotated (the level was lowered) after the invitation was created, so it opens nothing any more.
 */
public record Invite(UUID inviteId, UUID deckId, Integer maxUses, int uses, Instant expiresAt, Instant revokedAt, Instant createdAt, boolean stale) {
    /** {@code REVOKED}, else {@code STALE}, else {@code EXPIRED}, else {@code EXHAUSTED}, else {@code ACTIVE}. */
    public enum Status { ACTIVE, EXPIRED, EXHAUSTED, REVOKED, STALE }

    public Status status(Instant now) {
        if (revokedAt != null) return Status.REVOKED;
        if (stale) return Status.STALE;
        if (expiresAt != null && !now.isBefore(expiresAt)) return Status.EXPIRED;
        if (maxUses != null && uses >= maxUses) return Status.EXHAUSTED;
        return Status.ACTIVE;
    }
}
