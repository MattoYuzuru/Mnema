package app.mnema.learning.update.plan;

import java.util.UUID;

/**
 * A row of {@code fork_unit_decision} (V61): what the owner of a copy said about one unit. {@code resolvedRevisionId == null} with {@code resolvedAbsent} is "the author had
 * removed the unit"; for PENDING the pair is the author's state that was offered. {@code pendingBaseRevisionId == null} with {@code pendingBaseAbsent} is "the unit did not exist at
 * the old base". For METADATA and ORDER the revision ids are deck revisions of the SOURCE; otherwise revisions of the unit's kind.
 */
public record Decision(UnitKind kind, UUID key, Verdict verdict, UUID resolvedRevisionId, boolean resolvedAbsent, UUID pendingBaseRevisionId, boolean pendingBaseAbsent) {
    public enum Verdict { KEEP_MINE, DECLINE_NEW, KEEP_DELETED, PENDING }

    boolean pending() { return verdict == Verdict.PENDING; }

    /** The author's state the row covers, as a revision id ({@code null}: removed). */
    UUID resolved() { return resolvedAbsent ? null : resolvedRevisionId; }

    /** The old base of a PENDING row ({@code null}: absent at the base). */
    UUID pendingBase() { return pendingBaseAbsent ? null : pendingBaseRevisionId; }
}
