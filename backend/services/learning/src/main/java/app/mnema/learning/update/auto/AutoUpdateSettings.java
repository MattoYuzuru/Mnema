package app.mnema.learning.update.auto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * {@code learning.updates.auto.*} (Updates/6 part B, #445): the automatic mode of a copy's update (community decks architecture sections 7, 12 and 14). Invalid values fail at startup.
 * <ul>
 *   <li>{@code enabled} [false]: the kill switch of the automatic mode (§14). Off: a publication enqueues no fan-out, a fan-out job that is already queued ends without enqueueing
 *       anything, and an automatic apply job that is already queued ends without writing. The owner's own apply and the badge are not affected.</li>
 *   <li>{@code per-minute} [50, 1-10000]: at most this many copies are handed to the automatic apply per rolling minute by one instance, however many sources publish at once
 *       (§12: a viral source with 20 000 automatic copies is about 7 hours). The limit is in memory, so it is per instance; a restart forgets the minute.</li>
 *   <li>{@code active-window} [P30D, P1D-P366D]: a copy is handed to the automatic apply only when it was made inside the window or its owner studied it inside the window.</li>
 *   <li>{@code slice-size} [25, 1-500]: the copies one fan-out slice hands over; one slice is one short transaction.</li>
 *   <li>{@code scan-size} [200, 10-2000]: the copies one slice looks at (the active ones are chosen among them), so a source whose copies are all idle costs one bounded page per slice.</li>
 * </ul>
 * The queues are {@code learning.jobs.deck.update.fanout.*} and {@code learning.jobs.deck.update.auto.*}.
 */
@Component
public final class AutoUpdateSettings {
    private final boolean enabled;
    private final int perMinute;
    private final Duration activeWindow;
    private final int sliceSize;
    private final int scanSize;

    AutoUpdateSettings(@Value("${learning.updates.auto.enabled:false}") boolean enabled,
                       @Value("${learning.updates.auto.per-minute:50}") int perMinute,
                       @Value("${learning.updates.auto.active-window:P30D}") Duration activeWindow,
                       @Value("${learning.updates.auto.slice-size:25}") int sliceSize,
                       @Value("${learning.updates.auto.scan-size:200}") int scanSize) {
        if (perMinute < 1 || perMinute > 10_000 || activeWindow == null || activeWindow.compareTo(Duration.ofDays(1)) < 0 || activeWindow.compareTo(Duration.ofDays(366)) > 0
                || sliceSize < 1 || sliceSize > 500 || scanSize < 10 || scanSize > 2_000) {
            throw new IllegalArgumentException("Invalid learning.updates.auto settings");
        }
        this.enabled = enabled;
        this.perMinute = perMinute;
        this.activeWindow = activeWindow;
        this.sliceSize = sliceSize;
        this.scanSize = scanSize;
    }

    public boolean enabled() { return enabled; }

    int perMinute() { return perMinute; }

    Duration activeWindow() { return activeWindow; }

    int sliceSize() { return sliceSize; }

    int scanSize() { return scanSize; }
}
