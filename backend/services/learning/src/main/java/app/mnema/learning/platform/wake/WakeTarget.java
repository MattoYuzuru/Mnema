package app.mnema.learning.platform.wake;

/**
 * Something that has work to claim and can be told, by a PostgreSQL {@code NOTIFY}, that there may be more. The worker half of a split topology
 * ({@code learning.runtime.roles=worker}) wakes its targets from {@link PostgresWakeListener}; in {@code all} the creating transaction wakes the
 * in-process target itself. A wake-up is only a hint: the target's own sweeper reads the table, so a lost one costs at most one sweep.
 */
public interface WakeTarget {
    /** The channel a trigger of {@code app_learning.notify_work} notifies, for example {@code mnema_generation_steps}. */
    String channel();

    /** Asks for a pass over the queue. Coalesced and never blocking: it may be called from the listener thread at any rate. */
    void wake();
}
