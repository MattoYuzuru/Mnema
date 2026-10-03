package app.mnema.learning.generation;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/** Row projections of the generation tables; package-private, never serialized directly. */
final class Rows {
    private Rows() { }

    record Session(UUID sessionId, UUID ownerId, UUID deckId, String kind, String state, String endReason, JsonNode spec,
                   UUID reservationId, long rowVersion, long lastEventSeq, Instant createdAt, Instant lastActivityAt,
                   Instant expiresAt) { }

    record Artifact(UUID artifactId, UUID sessionId, UUID ownerId, String targetKind, int ordinal, String state,
                    String errorCode, String repinStatus, String title, UUID currentRevisionId, int revisionCount,
                    int draftGeneration, JsonNode sourceRefs, JsonNode publishedRef, long rowVersion) { }

    record Revision(UUID revisionId, UUID artifactId, int revisionNo, String cause, JsonNode payload, JsonNode handles,
                    String promptVersion, String modelRoute, JsonNode validation, Instant createdAt) { }

    record Slot(UUID artifactId, String slotKey, UUID revisionId, UUID nodeId, String kind, JsonNode spec, UUID assetId,
                String state, String errorCode) { }

    record Source(int ordinal, String role, String type, UUID noteId, Long noteRowVersion, UUID memberKey,
                  UUID itemRevisionId) { }

    record Step(UUID stepId, UUID sessionId, UUID artifactId, UUID ownerId, String kind, String capability, String state,
                int attempts, UUID leaseToken, Instant leaseUntil, Instant nextAttemptAt, Instant deadlineAt,
                Instant startedAt, Instant firstClaimedAt, boolean cancelRequested, JsonNode input, String errorCode) { }

    record Event(long seq, String type, UUID artifactId, JsonNode payload, Instant occurredAt) { }

    /** An event not yet stored: its {@code seq} is allocated when the transaction writes it. */
    record EventDraft(String type, UUID artifactId, JsonNode payload) { }
}
