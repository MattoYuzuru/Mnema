package app.mnema.learning.library;

import app.mnema.learning.platform.id.UuidPolicy;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The one place that decides whether a deck may leave its owner (OB-201): publication, access level, invitations, grants, access requests and copying. A deck that is not
 * {@link DeckPurpose#USER} is refused with {@link SampleDeckNotShareableException} ({@code 409 SAMPLE_DECK_NOT_SHAREABLE}); every command service that widens who can read or take a
 * deck calls it AFTER its ownership check, so a stranger still gets the opaque 404 and learns nothing of a deck's purpose. Narrowing commands (revoke, reject, withdraw) are not guarded:
 * taking access away is always allowed. {@code SharingInventoryIntegrationTest} lists every mutating route of the library and fork packages and fails for one that is not classified, so a
 * new sharing path cannot silently skip this class.
 *
 * <p>The one exception is the template's own content: its owner publishes new revisions of it through the ordinary publication command (that is how an update reaches the copies,
 * CD-5), and the template is always at {@link DeckVisibility#LINK} (the only level a copy can read an update through without a grant, and a level the catalogue does not list). So for
 * {@link DeckPurpose#SAMPLE_TEMPLATE} the publication command is allowed exactly when it asks for {@code LINK}; any other level (a lowering to private included) is refused.
 */
@Component
public final class DeckPurposeGuard {
    /** The level a sample template is published at. */
    public static final DeckVisibility TEMPLATE_LEVEL = DeckVisibility.LINK;

    private final LibraryRepository repository;

    DeckPurposeGuard(LibraryRepository repository) {
        this.repository = repository;
    }

    /** The purpose of a deck; a deck that does not exist is {@link DeckPurpose#USER}, so the caller's own lookup answers its own 404. */
    public DeckPurpose purposeOf(UUID deck) {
        UuidPolicy.requireEntityId(deck, "deckId");
        return repository.purposeOf(deck).orElse(DeckPurpose.USER);
    }

    /**
     * @throws SampleDeckNotShareableException the deck is a sample deck or the template
     */
    public void requireShareable(UUID deck) {
        if (!purposeOf(deck).shareable()) throw new SampleDeckNotShareableException();
    }

    /** As {@link #requireShareable(UUID)} for a deck known by its public code (a code that opens no deck is left to the caller's own 404). */
    void requireShareableByCode(String code) {
        if (!PublicCodes.valid(code)) return;
        if (!repository.purposeOfCode(code).orElse(DeckPurpose.USER).shareable()) throw new SampleDeckNotShareableException();
    }

    /**
     * The publication command of the owner: a sample copy is never published; the template only at {@link #TEMPLATE_LEVEL}.
     *
     * @param requested the level the command asks for
     * @throws SampleDeckNotShareableException the command is refused
     */
    void requirePublication(UUID deck, DeckVisibility requested) {
        switch (purposeOf(deck)) {
            case USER -> { }
            case SAMPLE_TEMPLATE -> {
                if (requested != TEMPLATE_LEVEL) throw new SampleDeckNotShareableException();
            }
            case SAMPLE -> throw new SampleDeckNotShareableException();
        }
    }
}
