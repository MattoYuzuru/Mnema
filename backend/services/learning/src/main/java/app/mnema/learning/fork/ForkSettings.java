package app.mnema.learning.fork;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code learning.community.forks.*}. {@code enabled} [false] is the kill switch «создание копий» of the architecture (§14): with it off the creation route answers 404 as
 * if it did not exist. The route also needs {@code learning.community.public-routes.enabled} [false] (the copy is made from a deck found by its PUBLIC code, so the
 * public-routes switch is the parent of every route that takes one): with that off, forks on is the same 404, see {@link #creation()}. Copies that already exist keep materializing, retrying and detaching (the jobs have their own switch, {@code learning.jobs.deck.copy.materialize.enabled}).
 * {@code max-unfinished-total} [200, 1-100000] bounds the unfinished copies of ALL accounts together (the queue of the materialization job): one more is {@code 503 FORKS_BUSY}
 * with {@code Retry-After}. {@code slice-size} [200, 10-1000] is the number of manifest entries the job copies per transaction. {@code max-preparing} [10, 1-100] bounds the live copies of one account that
 * are not ready yet (a failed copy counts), so one account cannot queue an unbounded amount of work. {@code max-per-window} [20, 1-1000] copies per sliding
 * {@code window} [PT1H, 1 min-7 d] may be CREATED by one account (deleted copies count: the window reads {@code forked_at}); one more is 429 with the real {@code Retry-After}. Invalid values fail at startup.
 */
@Component
public final class ForkSettings {
    final boolean enabled;
    final boolean publicRoutes;
    final int sliceSize;
    final int maxPreparing;
    final int maxPerWindow;
    final int maxUnfinishedTotal;
    final java.time.Duration window;

    ForkSettings(@Value("${learning.community.forks.enabled:false}") boolean enabled,
                 @Value("${learning.community.public-routes.enabled:false}") boolean publicRoutes,
                 @Value("${learning.community.forks.slice-size:200}") int sliceSize,
                 @Value("${learning.community.forks.max-preparing:10}") int maxPreparing,
                 @Value("${learning.community.forks.max-per-window:20}") int maxPerWindow,
                 @Value("${learning.community.forks.max-unfinished-total:200}") int maxUnfinishedTotal,
                 @Value("${learning.community.forks.window:PT1H}") java.time.Duration window) {
        if (sliceSize < 10 || sliceSize > 1_000 || maxPreparing < 1 || maxPreparing > 100 || maxPerWindow < 1 || maxPerWindow > 1_000
                || maxUnfinishedTotal < 1 || maxUnfinishedTotal > 100_000
                || window.compareTo(java.time.Duration.ofMinutes(1)) < 0 || window.compareTo(java.time.Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("Invalid fork settings");
        }
        this.enabled = enabled;
        this.publicRoutes = publicRoutes;
        this.sliceSize = sliceSize;
        this.maxPreparing = maxPreparing;
        this.maxPerWindow = maxPerWindow;
        this.maxUnfinishedTotal = maxUnfinishedTotal;
        this.window = window;
    }

    /** Whether the creation route exists: its own switch AND the public routes' switch are on (otherwise 404 as if absent, before anything is read). */
    public boolean creation() { return enabled && publicRoutes; }
}
