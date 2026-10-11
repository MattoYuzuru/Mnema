package app.mnema.learning.fork;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code learning.community.forks.*}. {@code enabled} [false] is the kill switch «создание копий» of the architecture (§14): with it off the creation route answers 404 as
 * if it did not exist; copies that already exist keep materializing, retrying and detaching (the jobs have their own switch, {@code learning.jobs.deck.copy.materialize.enabled}).
 * {@code slice-size} [200, 10-1000] is the number of manifest entries the job copies per transaction. {@code max-preparing} [10, 1-100] bounds the live copies of one account that
 * are not ready yet (a failed copy counts), so one account cannot queue an unbounded amount of work. Invalid values fail at startup.
 */
@Component
public final class ForkSettings {
    final boolean enabled;
    final int sliceSize;
    final int maxPreparing;

    ForkSettings(@Value("${learning.community.forks.enabled:false}") boolean enabled,
                 @Value("${learning.community.forks.slice-size:200}") int sliceSize,
                 @Value("${learning.community.forks.max-preparing:10}") int maxPreparing) {
        if (sliceSize < 10 || sliceSize > 1_000 || maxPreparing < 1 || maxPreparing > 100) {
            throw new IllegalArgumentException("Invalid fork settings");
        }
        this.enabled = enabled;
        this.sliceSize = sliceSize;
        this.maxPreparing = maxPreparing;
    }
}
