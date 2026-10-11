package app.mnema.learning.fork;

import java.util.UUID;

/**
 * What the owner's deck read carries of a copy before the source is looked at: the source deck, the code the copier used, and the copy's flags.
 * {@code failed} implies {@code preparing}. {@code baseRevisionId} is the published revision of the source the copy stands on, {@code pendingUpdates} whether a unit the
 * learner left for later ({@code PENDING} in {@code fork_unit_decision}) is still on offer (always false for a copy that is preparing or detached: the read asks nothing
 * there), {@code autoUpdate} the stored setting. The badge itself needs the source's published revision too, which only {@link ForkReadModel} knows.
 */
public record ForkLink(UUID upstreamDeckId, String upstreamCode, boolean preparing, boolean failed, boolean detached, UUID baseRevisionId, boolean pendingUpdates,
                       boolean autoUpdate) {
    /**
     * Builds the link from the columns of the read: {@code jobState} is the state of the copy's materialization job ({@code null} when its record is gone). An unfinished copy whose
     * job ended {@code FAILED} or {@code CANCELLED}, or was purged, is failed.
     */
    public static ForkLink of(UUID upstreamDeckId, String upstreamCode, boolean preparing, String jobState, boolean detached, UUID baseRevisionId, boolean pendingUpdates,
                              boolean autoUpdate) {
        return new ForkLink(upstreamDeckId, upstreamCode, preparing, preparing && endedWithoutSuccess(jobState), detached, baseRevisionId, pendingUpdates, autoUpdate);
    }

    /** Whether a job in this state (or without a record) can no longer finish a copy by itself. */
    public static boolean endedWithoutSuccess(String jobState) {
        return jobState == null || jobState.equals("FAILED") || jobState.equals("CANCELLED");
    }
}
