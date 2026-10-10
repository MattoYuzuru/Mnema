package app.mnema.learning.platform.jobs;

import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.UUID;

/**
 * What a caller may know of a job: enough for the "Готовим…" line and for a decision to cancel. The payload is not part of it; {@code lastError} is a
 * short code.
 *
 * @param progress the opaque cursor of the handler; {@code null} before the first slice
 * @param cancelRequested a running job was asked to stop and ends {@code CANCELLED} before its next slice
 */
public record JobStatus(UUID jobId, String queue, UUID accountId, JobState state, int attempts, int maxAttempts, long progressCount, ObjectNode progress,
                        boolean cancelRequested, String lastError, Instant createdAt, Instant updatedAt, Instant finishedAt) {
}
