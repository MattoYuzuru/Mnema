package app.mnema.learning.generation;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
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

    /**
     * One image a search of an IMAGE slot found and staged ({@code generation_media_candidate}, #296): its own media asset and its attribution.
     * {@code state} is VERIFYING (the media pipeline works on it), READY or FAILED (rejected).
     */
    record Candidate(UUID candidateId, UUID artifactId, String slotKey, UUID assetId, String source, String sourceId, String title,
                     String author, String license, String licenseUrl, String sourcePageUrl, boolean shareAlike, int width, int height,
                     String state, Instant createdAt) { }

    /**
     * One user instruction on an artifact ({@code generation_artifact_turn}); {@code instruction} is the user's own text and
     * {@code voice} ({@code female} or {@code male}) the voice of an {@code AUDIO_REGENERATE} turn of an exercise, else null.
     */
    record Turn(UUID turnId, UUID artifactId, UUID sessionId, UUID ownerId, String status, String action, String preset,
                String instruction, List<UUID> targetNodeIds, UUID stepId, UUID resultRevisionId, String errorCode,
                boolean countsTowardLimit, Instant createdAt, String voice) {
        /** QUEUED or RUNNING: the turn that makes the artifact REVISING. */
        boolean open() {
            return status.equals("QUEUED") || status.equals("RUNNING");
        }
    }

    record Source(int ordinal, String role, String type, UUID noteId, Long noteRowVersion, UUID memberKey,
                  UUID itemRevisionId) { }

    record Step(UUID stepId, UUID sessionId, UUID artifactId, UUID ownerId, String kind, String capability, String state,
                int attempts, UUID leaseToken, Instant leaseUntil, Instant nextAttemptAt, Instant deadlineAt,
                Instant startedAt, Instant firstClaimedAt, boolean cancelRequested, JsonNode input, String errorCode,
                Instant createdAt) { }

    record Event(long seq, String type, UUID artifactId, JsonNode payload, Instant occurredAt) { }

    /** An event not yet stored: its {@code seq} is allocated when the transaction writes it. */
    record EventDraft(String type, UUID artifactId, JsonNode payload) { }
}
