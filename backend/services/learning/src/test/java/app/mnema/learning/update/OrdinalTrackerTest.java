package app.mnema.learning.update;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The tracker against a plain list: after any sequence of insertions, removals, moves and replacements, the final ordinal of every touched key is its index in the list. */
class OrdinalTrackerTest {
    @Test
    void theFinalOrdinalOfEveryTouchedKeyIsItsIndexInTheListTheOperationsProduced() {
        Random random = new Random(441);
        for (int round = 0; round < 300; round++) {
            List<UUID> list = new ArrayList<>();
            for (int index = 0; index < 1 + random.nextInt(40); index++) list.add(UUID.randomUUID());
            OrdinalTracker tracker = new OrdinalTracker();
            Set<UUID> touched = new HashSet<>();
            Set<UUID> used = new HashSet<>();
            for (int step = 0; step < 1 + random.nextInt(60); step++) {
                int kind = random.nextInt(4);
                if (kind == 0 || list.isEmpty()) {
                    UUID key = UUID.randomUUID();
                    int at = random.nextInt(list.size() + 1);
                    list.add(at, key);
                    tracker.inserted(key, at);
                    touched.add(key);
                    used.add(key);
                } else {
                    int at = random.nextInt(list.size());
                    UUID key = list.get(at);
                    if (kind == 1) {
                        tracker.touch(key, at);
                        touched.add(key);
                    } else if (kind == 2) {
                        // a key is removed at most once per command, and not touched again
                        if (used.contains(key)) continue;
                        list.remove(at);
                        tracker.removed(key, at);
                        touched.remove(key);
                        used.add(key);
                        assertThat(tracker.finalOrdinal(key)).isEqualTo(-1);
                    } else {
                        int to = random.nextInt(list.size());
                        list.remove(at);
                        list.add(to, key);
                        tracker.moved(key, at, to);
                        touched.add(key);
                    }
                }
            }
            for (UUID key : touched) {
                int expected = list.indexOf(key);
                if (expected >= 0) assertThat(tracker.finalOrdinal(key)).as("round %d", round).isEqualTo(expected);
            }
        }
    }

    @Test
    void anUntouchedKeyHasNoPosition() {
        OrdinalTracker tracker = new OrdinalTracker();
        assertThat(tracker.finalOrdinal(UUID.randomUUID())).isEqualTo(-1);
    }
}
