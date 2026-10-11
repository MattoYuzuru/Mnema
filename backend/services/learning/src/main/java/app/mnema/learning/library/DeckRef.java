package app.mnema.learning.library;

import java.time.Instant;
import java.util.UUID;

/**
 * A deck as access sees it. {@code hidden} is the HIDDEN decision of a complaint (Share/15): everybody but the owner gets the answer of an unknown deck. {@code code} is null while the deck has never left «private» (no publication row); {@code published} is null until the
 * first publication. The published revision carries what a non-owner may read: its title, description, manifests and counts. Nothing here is shown to
 * a viewer before {@link DeckAccess} granted access.
 */
public record DeckRef(UUID deckId, UUID ownerId, UUID scopeId, DeckVisibility visibility, String code, Published published, boolean hidden) {
    /** A deck that is not hidden by moderation. */
    public DeckRef(UUID deckId, UUID ownerId, UUID scopeId, DeckVisibility visibility, String code, Published published) {
        this(deckId, ownerId, scopeId, visibility, code, published, false);
    }

    /** The revision non-owners read, with its immutable manifests. */
    public record Published(UUID revisionId, Instant publishedAt, String title, String description, UUID membersRootId,
                            UUID exercisesRootId, int memberCount, int exerciseCount) { }
}
