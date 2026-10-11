package app.mnema.learning.update;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Where the keys a command touched end up. The operations of a command are sequential, each ordinal valid after the previous operations, so the position a key had when
 * its own operation ran is not the position it has in the result: a later insertion before it moves it one up. The tracker replays only the position effect of the
 * operations (an insertion, a removal, a move; a replacement changes no position) over the keys the command touched, so the journal can record the final ordinal of each.
 *
 * <p>Cost: O(touched keys) per operation that shifts positions and O(1) per replacement, so O(touched keys x shifting operations) in the worst case; the engine bounds
 * a command to {@code learning.updates.max-units}, and an ordinary update is a few shifting operations over many replacements. Untouched keys are never listed.
 */
final class OrdinalTracker {
    private final Map<UUID, Integer> index = new HashMap<>();
    private final List<UUID> keys = new ArrayList<>();
    private int[] positions = new int[16];

    /** Notes a key at {@code ordinal} (a replacement or the start of a move); a key that is already tracked keeps its current position. */
    void touch(UUID key, int ordinal) {
        if (index.containsKey(key)) return;
        add(key, ordinal);
    }

    /** An entry was inserted at {@code ordinal}: later positions move up, the key is tracked. */
    void inserted(UUID key, int ordinal) {
        shift(ordinal, Integer.MAX_VALUE, 1);
        add(key, ordinal);
    }

    /** The entry at {@code ordinal} was removed: later positions move down and the key has no position any more. */
    void removed(UUID key, int ordinal) {
        shift(ordinal + 1, Integer.MAX_VALUE, -1);
        Integer at = index.get(key);
        if (at != null) positions[at] = -1;
    }

    /** The entry at {@code from} moved to {@code to} (its position after the move). */
    void moved(UUID key, int from, int to) {
        touch(key, from);
        if (from == to) return;
        Integer self = index.get(key);
        for (int i = 0; i < keys.size(); i++) {
            int position = positions[i];
            if (position < 0 || i == self) continue;
            if (from < to && position > from && position <= to) positions[i] = position - 1;
            else if (from > to && position >= to && position < from) positions[i] = position + 1;
        }
        positions[self] = to;
    }

    /** The final ordinal of a tracked key, or -1 when it was removed or never touched. */
    int finalOrdinal(UUID key) {
        Integer at = index.get(key);
        return at == null ? -1 : positions[at];
    }

    private void add(UUID key, int ordinal) {
        if (keys.size() == positions.length) positions = java.util.Arrays.copyOf(positions, positions.length * 2);
        index.put(key, keys.size());
        positions[keys.size()] = ordinal;
        keys.add(key);
    }

    private void shift(int from, int to, int delta) {
        for (int i = 0; i < keys.size(); i++) {
            int position = positions[i];
            if (position >= from && position <= to) positions[i] = position + delta;
        }
    }
}
