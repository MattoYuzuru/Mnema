package app.mnema.learning.library;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.id.UuidPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.UUID;

/**
 * The owner's operations on who can read a deck: its visibility level and its grants. They are repository-level (the commands and screens of Share/8
 * and Share/13 call them); every one of them is the owner's alone and answers the opaque 404 for any other deck.
 *
 * <p>A level change is a compare-and-set of the {@code deck_publication} row and never touches {@code deck.row_version}: an open editor gets no 412
 * when the owner changes who can read. Lowering the level rotates the public code ({@link DeckVisibility#lowersTo}, any step to a more restrictive level); the code of a lowered deck
 * therefore never resolves again. Codes are drawn from a CSPRNG and a collision with another deck's code (about 2^-58 per draw) is retried in a
 * fresh transaction.
 *
 * <p>{@code setVisibility} is the low-level compare-and-set of the level (raw row version, {@link #UNPUBLISHED} for no row; it writes no outbox row, no metadata and does not
 * check the «Публичная» checklist). It is package-private on purpose, so production code outside this package cannot bypass the checklist and the outbox: the owner's command
 * is {@link PublicationService}.
 */
@Service
public class DeckPublications {
    /** The expected version of a deck that has never left «private» and so has no publication row. */
    public static final long UNPUBLISHED = -1;
    private static final int CODE_ATTEMPTS = 5;

    private final LibraryRepository repository;
    private final TransactionTemplate transaction;
    private final SecureRandom random;

    @Autowired
    DeckPublications(LibraryRepository repository, PlatformTransactionManager transactions) {
        this(repository, transactions, new SecureRandom());
    }

    DeckPublications(LibraryRepository repository, PlatformTransactionManager transactions, SecureRandom random) {
        this.repository = repository;
        this.random = random;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(10);
    }

    /** The publication row of the owner's deck, or empty while the deck is private and has never left that level. */
    public Optional<Publication> find(UUID owner, UUID deck) {
        requireIds(owner, deck);
        return transaction.execute(status -> {
            requireOwned(owner, deck);
            return repository.publication(owner, deck);
        });
    }

    /**
     * Sets the level of the owner's deck.
     *
     * @param expectedRowVersion the {@code rowVersion} the caller read, or {@link #UNPUBLISHED} for a deck without a publication row
     * @return the row after the change; empty when the deck has no row and stays private
     * @throws ResourceNotFoundException the deck is not the owner's or is deleted
     * @throws VersionConflictException the row changed after the version was read
     */
    Optional<Publication> setVisibility(UUID owner, UUID deck, DeckVisibility level, long expectedRowVersion) {
        requireIds(owner, deck);
        if (level == null || expectedRowVersion < UNPUBLISHED) throw new InvalidRequestException();
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.execute(status -> change(owner, deck, level, expectedRowVersion));
            } catch (DuplicateKeyException collision) {
                // Either another request created the row of this deck first (a stale version) or the drawn code is taken: redraw.
                boolean rowExists = Boolean.TRUE.equals(transaction.execute(status -> repository.lockPublication(deck).isPresent()));
                if (rowExists && expectedRowVersion == UNPUBLISHED) throw new VersionConflictException();
                if (attempt == CODE_ATTEMPTS) throw collision;
            }
        }
    }

    private Optional<Publication> change(UUID owner, UUID deck, DeckVisibility level, long expected) {
        requireOwned(owner, deck);
        Optional<Publication> current = repository.lockPublication(deck);
        if (current.isEmpty()) {
            if (expected != UNPUBLISHED) throw new VersionConflictException();
            if (level == DeckVisibility.PRIVATE) return Optional.empty();
            return Optional.of(repository.insert(deck, level, PublicCodes.next(random)));
        }
        Publication row = current.orElseThrow();
        if (row.rowVersion() != expected) throw new VersionConflictException();
        if (row.visibility() == level) return current;
        String rotated = row.visibility().lowersTo(level) ? PublicCodes.next(random) : null;
        return Optional.of(repository.update(deck, level, rotated, expected).orElseThrow(VersionConflictException::new));
    }

    /** Invites an account to read the owner's deck. Idempotent: an existing grant is kept as it is. */
    public void grant(UUID owner, UUID deck, UUID grantee, GrantRole role) {
        requireIds(owner, deck);
        UuidPolicy.requireEntityId(grantee, "grantee");
        if (role != GrantRole.VIEWER || grantee.equals(owner)) throw new InvalidRequestException();
        transaction.executeWithoutResult(status -> {
            requireOwned(owner, deck);
            repository.grant(deck, grantee, role, owner);
        });
    }

    /** Withdraws an invitation. @return whether a grant existed */
    public boolean revoke(UUID owner, UUID deck, UUID grantee) {
        requireIds(owner, deck);
        UuidPolicy.requireEntityId(grantee, "grantee");
        return Boolean.TRUE.equals(transaction.execute(status -> {
            requireOwned(owner, deck);
            return repository.revoke(deck, grantee);
        }));
    }

    private void requireOwned(UUID owner, UUID deck) {
        if (!repository.ownsLiveDeck(owner, deck)) throw new ResourceNotFoundException();
    }

    private static void requireIds(UUID owner, UUID deck) {
        UuidPolicy.requireEntityId(owner, "owner");
        UuidPolicy.requireEntityId(deck, "deckId");
    }
}
