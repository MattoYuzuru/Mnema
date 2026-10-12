package app.mnema.learning.update.plan;

import app.mnema.learning.platform.jobs.JobState;
import app.mnema.learning.update.CombinedPublication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * What the plan route says about an apply command that runs as a job (Updates/4, #443). A read: it writes nothing. The newest job of the owner for this copy on the queue of the
 * combined publication decides:
 * <ul>
 *   <li>{@code QUEUED} or {@code RUNNING}: the plan answers {@code state: APPLYING} with the command id (no plan is computed while the copy is changing);</li>
 *   <li>{@code FAILED} or {@code CANCELLED} and no apply command of the copy has succeeded since it ended ({@code fork_apply_log}, written by the final writer of every apply,
 *       also one that creates no revision): the plan is read as usual with {@code lastApplyFailed: {code}}, the code the job ended with ({@code stale_head},
 *       {@code plan_invalid}, {@code taken_down}, ...; {@code cancelled}). The next successful apply, or the retention of the job (7 days), ends it;</li>
 *   <li>{@code SUCCEEDED}, or no job: nothing.</li>
 * </ul>
 * One indexed statement ({@code learning_job_combined_owner}, V63): the jobs of the owner on that queue, newest first.
 */
@Component
public class ApplyProgress {
    /**
     * @param commandId the command of the job (the id the client sent)
     * @param running whether the job still runs (or waits)
     * @param failureCode the code a failed or cancelled job ended with; {@code null} for a running one and for a failure the copy has moved past
     */
    public record Progress(UUID commandId, boolean running, String failureCode) { }

    private final JdbcClient jdbc;

    ApplyProgress(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** @param owner the caller; another account's copy has no job of the caller's, so this answers empty for it */
    @Transactional(readOnly = true, timeout = 5)
    public Optional<Progress> of(UUID owner, UUID deck) {
        return jdbc.sql("""
                SELECT j.dedupe_key,j.state,j.last_error,(j.finished_at IS NOT NULL AND (l.applied_at IS NULL OR j.finished_at >= l.applied_at)) AS not_applied_since
                  FROM app_learning.deck d
                  LEFT JOIN app_learning.fork_apply_log l ON l.deck_id=d.deck_id
                  JOIN app_learning.learning_job j ON j.queue=:queue AND j.account_id=:owner AND j.payload->>'deckId'=:text
                 WHERE d.deck_id=:deck AND d.owner_id=:owner AND d.deleted_at IS NULL
                 ORDER BY j.created_at DESC, j.job_id DESC LIMIT 1
                """).param("queue", CombinedPublication.QUEUE).param("owner", owner).param("deck", deck).param("text", deck.toString())
                .query((row, ignored) -> progress(UUID.fromString(row.getString("dedupe_key")), JobState.valueOf(row.getString("state")), row.getString("last_error"),
                        row.getBoolean("not_applied_since"))).optional().flatMap(found -> found);
    }

    private static Optional<Progress> progress(UUID command, JobState state, String error, boolean notAppliedSince) {
        if (state == JobState.QUEUED || state == JobState.RUNNING) return Optional.of(new Progress(command, true, null));
        if ((state == JobState.FAILED || state == JobState.CANCELLED) && notAppliedSince) {
            return Optional.of(new Progress(command, false, error != null ? error : state == JobState.CANCELLED ? "cancelled" : "failed"));
        }
        return Optional.empty();
    }
}
