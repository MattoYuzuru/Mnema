package app.mnema.learning.platform.jobs;

import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

/**
 * What a {@link JobHandler} sees of the job it works on, fresh for every slice.
 *
 * @param id the job
 * @param queue the queue it was enqueued on
 * @param accountId the subject of the per-account limit; {@code null} for a system job
 * @param payload the immutable input given to {@link LearningJobs#enqueue}
 * @param progress the cursor of the last committed slice ({@link Slice.Continue#progress()}); {@code null} before the first one
 * @param progressCount the figure of the last committed slice; 0 before the first one
 * @param attempt the number of this claim, starting at 1; a retry or a lost lease makes it larger, the progress is kept
 */
public record Job(UUID id, String queue, UUID accountId, ObjectNode payload, ObjectNode progress, long progressCount, int attempt) {
}
