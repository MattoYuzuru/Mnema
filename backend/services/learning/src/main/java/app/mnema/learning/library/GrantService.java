package app.mnema.learning.library;

import app.mnema.learning.library.AccessRepository.Grant;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.IdentityUnavailableException;
import app.mnema.learning.platform.api.RateLimitedException;
import app.mnema.learning.platform.api.ResourceLimitExceededException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.security.ProfileDirectory;
import app.mnema.learning.platform.security.ProfileLookupBudget;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The people with access to an «По приглашению» deck (Share/13, #435): the owner lists them, adds one by login and revokes one. A grant changes who may READ
 * the published revision and nothing else; a deck holds at most {@value AccessRepository#GRANT_LIMIT} of them.
 *
 * <p><b>By login.</b> Learning cannot read Identity, so the login is resolved server-side through {@link ProfileDirectory} (the public-profile lookup, only
 * profiles with consent). The order is the point: the owner predicate first (a stranger never makes Learning call Identity), then the deck must be a
 * published INVITE deck (a grant means nothing at another level, so it costs no lookup), then the shape of the login and the two budgets of
 * {@link ProfileLookupBudget} (pool {@code GRANTS}: per owner, and the instance total shared with profile complaints, because Identity's lookup limit is a 15-minute
 * window shared by every caller behind one address; a refusal, and an Identity 429, answer 429 with the rest of that window), then the Identity call
 * OUTSIDE any transaction (no connection is held across a network call), then the write in a short transaction. An unknown login, a malformed one and a hidden
 * profile are the same {@link ResourceNotFoundException}. The grant is idempotent, closes a pending request of that person as approved without a notification,
 * and, being the owner's deliberate act, lifts the 30-day block that a revocation puts on the person's invitation links.
 *
 * <p><b>Revoking</b> records a rejected request marked as a revocation (so an invitation link still alive cannot undo it for 30 days; asking again waits the usual 30
 * days too) and then deletes the grant, so the next read is refused at once. Copies the person already made stay (Share/10).
 *
 * <p><b>Lock order</b>, outermost first, for every path that writes grants: the invitation row (redeem only), the deck's grants lock, the request row, the grant row.
 */
@Service
public class GrantService {
    /** A page of grants: at most {@code limit} items and the cursor of the next page, or null. */
    public record Page(List<Grant> items, String nextCursor) { }

    private final AccessRepository repository;
    private final LibraryRepository library;
    private final ProfileDirectory profiles;
    private final ProfileLookupBudget budget;
    private final AccessMetrics metrics;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnly;

    @Autowired
    GrantService(AccessRepository repository, LibraryRepository library, ProfileDirectory profiles, ProfileLookupBudget budget, AccessMetrics metrics,
                 PlatformTransactionManager transactions) {
        this(repository, library, profiles, budget, metrics, transactions, Clock.systemUTC());
    }

    GrantService(AccessRepository repository, LibraryRepository library, ProfileDirectory profiles, ProfileLookupBudget budget, AccessMetrics metrics,
                 PlatformTransactionManager transactions, Clock clock) {
        this.repository = repository;
        this.library = library;
        this.profiles = profiles;
        this.budget = budget;
        this.metrics = metrics;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(10);
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.readOnly.setTimeout(10);
    }

    /** Newest first. */
    public Page list(UUID owner, UUID deck, String limit, String cursor) {
        requireIds(owner, deck);
        int size = AccessCursor.pageSize(limit);
        AccessCursor after = AccessCursor.decode(cursor);
        return readOnly.execute(status -> {
            requireOwned(owner, deck);
            List<Grant> rows = repository.pageGrants(deck, after, size);
            boolean more = rows.size() > size;
            List<Grant> items = more ? rows.subList(0, size) : rows;
            return new Page(List.copyOf(items), more ? new AccessCursor(items.getLast().grantedAt(), items.getLast().granteeId()).encode() : null);
        });
    }

    /**
     * @throws ResourceNotFoundException the deck is not the owner's, or no public profile has this login
     * @throws PublicationRequiredException the deck is not a published invitation-only deck
     * @throws InvalidRequestException the login is the owner's own
     * @throws RateLimitedException the owner or the instance used its lookups of this 15-minute window, or Identity limits lookups; {@code Retry-After} is the rest of the window
     * @throws IdentityUnavailableException Identity did not answer
     * @throws ResourceLimitExceededException the deck already has 1000 people with access
     */
    public Grant grantByLogin(UUID owner, UUID deck, String username) {
        requireIds(owner, deck);
        try {
            Grant grant = grantOnce(owner, deck, username);
            metrics.count("grant", "ok");
            return grant;
        } catch (ResourceNotFoundException | InvalidRequestException | PublicationRequiredException refused) {
            metrics.count("grant", "refused");
            throw refused;
        } catch (RateLimitedException | ResourceLimitExceededException limited) {
            metrics.count("grant", "limited");
            throw limited;
        } catch (IdentityUnavailableException unavailable) {
            metrics.count("grant", "unavailable");
            throw unavailable;
        }
    }

    private Grant grantOnce(UUID owner, UUID deck, String username) {
        readOnly.executeWithoutResult(status -> {
            requireOwned(owner, deck);
            Publication publication = library.publication(owner, deck).orElseThrow(PublicationRequiredException::new);
            if (publication.visibility() != DeckVisibility.INVITE || publication.publishedRevisionId() == null) throw new PublicationRequiredException();
        });
        if (!ProfileDirectory.validUsername(username)) throw new ResourceNotFoundException();
        UUID grantee = budget.lookup(ProfileLookupBudget.Pool.GRANTS, owner, () -> profiles.accountOf(username)).orElseThrow(ResourceNotFoundException::new);
        if (grantee.equals(owner)) throw new InvalidRequestException();
        return transaction.execute(status -> {
            requireOwned(owner, deck);
            Instant now = clock.instant();
            repository.lockGrants(deck);
            boolean hasRequestRow = repository.lockRequest(deck, grantee).isPresent();
            repository.addGrant(deck, grantee, owner, now);
            if (hasRequestRow) {
                repository.decide(deck, grantee, AccessRequestService.PENDING, "APPROVED", now);
                repository.clearRevocation(deck, grantee);
            }
            return repository.grant(deck, grantee);
        });
    }

    /** Idempotent: revoking a grant that does not exist is the same success. */
    public void revoke(UUID owner, UUID deck, UUID grantee) {
        requireIds(owner, deck);
        UuidPolicy.requireEntityId(grantee, "granteeId");
        transaction.executeWithoutResult(status -> {
            requireOwned(owner, deck);
            if (library.hasGrant(deck, grantee)) {
                // the request row first, then the grant: the order of every other path that touches both
                repository.stickRevocation(deck, grantee, clock.instant());
                library.revoke(deck, grantee);
            }
        });
        metrics.count("revoke", "ok");
    }

    private void requireOwned(UUID owner, UUID deck) {
        if (!library.ownsLiveDeck(owner, deck)) throw new ResourceNotFoundException();
    }

    private static void requireIds(UUID owner, UUID deck) {
        UuidPolicy.requireEntityId(owner, "owner");
        UuidPolicy.requireEntityId(deck, "deckId");
    }
}
