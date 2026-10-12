package app.mnema.learning.update.history;

import app.mnema.learning.update.plan.UnitKind;

import java.util.Objects;
import java.util.UUID;

/**
 * One unit an apply changed in the copy, as «Что нового» lists it: what it was before ({@code previousRevisionId}, null for an addition) and what the apply made it
 * ({@code revisionId}, null for a removal), and where it stands now. METADATA and ORDER carry no revisions and no state.
 *
 * <p>{@link State}: {@code CURRENT} the copy still holds exactly what the apply made (the only state that can be returned), {@code RETURNED} it is back at what it was before the
 * apply (returned by the owner, or deleted/recreated that way), {@code CHANGED_SINCE} the owner edited it after the apply (returning it would lose his edit, so it is refused).
 */
record HistoryUnit(UnitKind kind, UUID key, Change change, UUID previousRevisionId, UUID revisionId, State state) {
    enum Change { ADDED, CHANGED, REMOVED, REORDERED }

    enum State { CURRENT, RETURNED, CHANGED_SINCE }

    HistoryUnit {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(key);
        Objects.requireNonNull(change);
    }

    String unitId() { return kind.unitId(key); }

    /** Only a material or an exercise can be returned on its own (an objective moves with its exercises; the title and the order are not units of the copy's content). */
    static boolean returnable(UnitKind kind) { return kind == UnitKind.MATERIAL || kind == UnitKind.EXERCISE; }

    /** The unit of a journal row: {@code head} is the copy's head revision of the unit NOW (null: it has none). */
    static HistoryUnit of(UnitKind kind, UUID key, UUID previous, UUID revision, UUID head) {
        Change change = previous == null ? Change.ADDED : revision == null ? Change.REMOVED : Change.CHANGED;
        State state;
        if (Objects.equals(head, revision)) state = State.CURRENT;
        else if (Objects.equals(head, previous)) state = State.RETURNED;
        else state = State.CHANGED_SINCE;
        return new HistoryUnit(kind, key, change, previous, revision, state);
    }

    /** Whether the owner can return it now: a material or an exercise that is exactly as the apply made it. */
    boolean revertible(boolean detached) { return !detached && returnable(kind) && state == State.CURRENT; }
}
