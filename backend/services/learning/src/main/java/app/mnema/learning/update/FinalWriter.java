package app.mnema.learning.update;

import java.util.UUID;

/**
 * The hook of the last transaction of a combined publication (Updates/4 implements it: it moves {@code deck_fork.base_revision_id} and writes the per-unit
 * decisions atomically with the revision). The engine calls it inside that transaction, after the heads and the journal are written and before the receipt, with
 * the {@link FinalWrites} of the command; it is called only when the command carries some, and also when the command changed nothing and created no revision.
 *
 * <p>There is at most one bean of this type (without one the engine writes nothing extra). The call runs in the engine's transaction: write through the ordinary
 * {@code JdbcClient}, never from another transaction or thread, and throw to refuse (the whole publication rolls back: no revision, no head, no journal row, no receipt).
 */
@FunctionalInterface
public interface FinalWriter {
    /** The default when no bean is registered: writes nothing. */
    FinalWriter NONE = (writes, context) -> { };

    /**
     * @param writes the writes the command asked for
     * @param context the deck and the result so far
     */
    void write(FinalWrites writes, Context context);

    /**
     * @param baseRevisionId the head the command was built against ({@code expectedHeadRevisionId})
     * @param resultRevisionId the new revision, or the unchanged head when {@code changed} is false
     * @param resultVersion the deck version of {@code resultRevisionId}
     */
    record Context(UUID deckId, UUID actorId, UUID commandId, UUID baseRevisionId, UUID resultRevisionId, long resultVersion, boolean changed) { }
}
