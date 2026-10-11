package app.mnema.learning.library;

import app.mnema.learning.platform.id.UuidPolicy;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The one decision of who may read a deck (CD-3, community decks architecture sections 1 and 6). It is read-only and decides nothing about writes:
 * every existing mutation keeps its owner predicate and never asks this class.
 *
 * <p>Resolution rules:
 * <ul>
 *   <li>A deleted deck resolves to {@link Denial#NOT_FOUND} for everyone, the owner included (the owner's own routes answer for themselves).</li>
 *   <li>The owner resolves to {@link AccessLevel#OWNER} at any level, by code or by id.</li>
 *   <li>A deck HIDDEN by a complaint decision (Share/15, {@code deck_publication.hidden_by_moderation}) resolves to {@link Denial#NOT_FOUND} for everybody but its owner,
 *       by code and by id, at every level (an invitation deck's {@link Denial#INVITE_ONLY} would confirm that it exists).</li>
 *   <li>The public code is the only entry point of a non-owner, and a non-owner who has not been granted never resolves by deck id.
 *       A non-owner needs a published revision at every level: without one the answer is {@code NOT_FOUND}.</li>
 *   <li>By code: {@code PUBLIC} gives {@link AccessLevel#PUBLIC}, {@code LINK} gives {@link AccessLevel#LINK} (guests included),
 *       {@code INVITE} gives {@link AccessLevel#GRANTEE} with a grant and the distinct {@link Denial#INVITE_ONLY} without one (guests too),
 *       {@code PRIVATE}, a code that does not exist and a code that has been rotated away give {@code NOT_FOUND}.</li>
 *   <li>By id: only a grantee of an {@code INVITE} deck resolves ({@link AccessLevel#GRANTEE}); every other non-owner gets {@code NOT_FOUND}.</li>
 * </ul>
 * The answers for an unknown and a private deck are produced by the same single lookup, so they cannot be told apart by their cost.
 */
@Service
public class DeckAccess {
    private final LibraryRepository repository;

    DeckAccess(LibraryRepository repository) { this.repository = repository; }

    /** Resolves a viewer's access to the deck behind {@code code}. */
    public Resolution resolve(Viewer viewer, String code) {
        if (!PublicCodes.valid(code)) return Resolution.denied(Denial.NOT_FOUND);
        Optional<DeckRef> found = repository.byCode(code);
        if (found.isEmpty()) return Resolution.denied(Denial.NOT_FOUND);
        DeckRef deck = found.orElseThrow();
        return decide(viewer, deck, () -> granted(viewer, deck));
    }

    /**
     * Resolves a viewer's access to several decks by their codes with the SAME decision as {@link #resolve(Viewer, String)}, but with one statement for the
     * decks and one for the grants (a page of copies reads the state of its sources without a query per copy). Every requested code has an entry.
     */
    public Map<String, Resolution> resolveAll(Viewer viewer, Collection<String> codes) {
        Map<String, DeckRef> found = new HashMap<>();
        repository.byCodes(codes.stream().filter(PublicCodes::valid).distinct().toList()).forEach(deck -> found.put(deck.code(), deck));
        Set<UUID> invitations = new HashSet<>();
        found.values().forEach(deck -> {
            if (deck.visibility() == DeckVisibility.INVITE) invitations.add(deck.deckId());
        });
        Set<UUID> grants = viewer.accountId() == null ? Set.of() : repository.grantedAmong(invitations, viewer.accountId());
        Map<String, Resolution> result = new HashMap<>();
        for (String code : codes) {
            DeckRef deck = found.get(code);
            result.put(code, deck == null ? Resolution.denied(Denial.NOT_FOUND) : decide(viewer, deck, () -> grants.contains(deck.deckId())));
        }
        return result;
    }

    /** The one decision by code: what a viewer is, given the deck, and whether he holds a grant (asked only for an invitation deck). */
    private static Resolution decide(Viewer viewer, DeckRef deck, java.util.function.BooleanSupplier granted) {
        if (viewer.accountId() != null && viewer.accountId().equals(deck.ownerId())) return Resolution.granted(AccessLevel.OWNER, deck);
        // HIDDEN by a complaint decision (Share/15): exactly the answer of an unknown code, at every level, for everybody but the owner.
        if (deck.published() == null || deck.hidden()) return Resolution.denied(Denial.NOT_FOUND);
        return switch (deck.visibility()) {
            case PRIVATE -> Resolution.denied(Denial.NOT_FOUND);
            case LINK -> Resolution.granted(AccessLevel.LINK, deck);
            case PUBLIC -> Resolution.granted(AccessLevel.PUBLIC, deck);
            case INVITE -> granted.getAsBoolean() ? Resolution.granted(AccessLevel.GRANTEE, deck) : Resolution.denied(Denial.INVITE_ONLY);
        };
    }

    /** Resolves a viewer's access to a deck by its id: the owner, or a grantee of an invitation-only deck. */
    public Resolution resolve(Viewer viewer, UUID deckId) {
        UuidPolicy.requireEntityId(deckId, "deckId");
        if (viewer.accountId() == null) return Resolution.denied(Denial.NOT_FOUND);
        Optional<DeckRef> found = repository.byId(deckId);
        if (found.isEmpty()) return Resolution.denied(Denial.NOT_FOUND);
        DeckRef deck = found.orElseThrow();
        if (viewer.accountId().equals(deck.ownerId())) return Resolution.granted(AccessLevel.OWNER, deck);
        if (deck.published() != null && !deck.hidden() && deck.visibility() == DeckVisibility.INVITE && granted(viewer, deck)) {
            return Resolution.granted(AccessLevel.GRANTEE, deck);
        }
        return Resolution.denied(Denial.NOT_FOUND);
    }

    private boolean granted(Viewer viewer, DeckRef deck) {
        return viewer.accountId() != null && repository.hasGrant(deck.deckId(), viewer.accountId());
    }
}
