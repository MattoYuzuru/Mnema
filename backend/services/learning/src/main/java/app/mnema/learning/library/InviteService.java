package app.mnema.learning.library;

import app.mnema.learning.library.AccessRepository.RedeemTarget;
import app.mnema.learning.library.AccessRepository.Request;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ProblemExtension;
import app.mnema.learning.platform.api.ResourceLimitExceededException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.id.UuidPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Invitation links of a shared deck (Share/13, #435; contract {@code contracts/decks/access.json}). The owner creates, lists and revokes them; any
 * signed-in account redeems one.
 *
 * <p><b>The secret.</b> A token is 32 random bytes shown once, in the answer of {@link #create}; only its SHA-256 digest is stored and nothing in this class
 * logs, returns or keeps the token afterwards. It is not part of any URL path of the API: redeem takes it in a request body.
 *
 * <p><b>Generation.</b> An invitation is created under the deck's current code ({@code deck_publication.code_rotated_at}); every move to a more restrictive level
 * rotates the code, and an invitation of an older generation opens nothing for anybody (the canon: lowering the level changes the links).
 *
 * <p><b>Redeem</b> is one transaction that locks the invitation row ({@code FOR UPDATE}), so concurrent redemptions are serialized per invitation and
 * {@code uses < max_uses} is checked and advanced under the lock (the column CHECK is the backstop). Everything that is not a valid invitation of a live,
 * published, non-private deck of the current generation is the same {@link ResourceNotFoundException}, so a token cannot be probed for how it failed. The owner and an
 * account that already holds the grant are answered with the code and consume nothing, even when the invitation has been used up or revoked since; on a LINK or PUBLIC
 * deck a valid invitation is answered with the code as a no-op (nothing to grant). A person the owner revoked is refused for 30 days. The grant is written first
 * and a use is consumed only when a grant was actually created.
 *
 * <p><b>Limits.</b> 20 active and 200 stored invitations per deck, counted under an advisory lock of the deck; invitations that died more than 30 days ago
 * (revoked, expired, used up, or of an older code generation) are pruned on creation. Load: every statement is a key or an index range of one deck; a viral invitation costs one row lock per redemption and nothing per reader.
 */
@Service
public class InviteService {
    static final int ACTIVE_LIMIT = 20;
    static final int STORED_LIMIT = 200;
    static final Duration PRUNE_AFTER = Duration.ofDays(30);
    static final int MAX_USES = 1000;
    static final int MAX_EXPIRY_DAYS = 365;

    /** The answer of a creation: the invitation, its one-time token and the link the owner shares. The token is a secret: it never prints. */
    public record Created(Invite invite, String token, String link) {
        @Override
        public String toString() { return "Created[invite=" + invite + ", token=<redacted>, link=<redacted>]"; }
    }

    /** A page of invitations: at most {@code limit} items and the cursor of the next page, or null. */
    public record Page(List<Invite> items, String nextCursor) { }

    private final AccessRepository access;
    private final LibraryRepository library;
    private final DeckPurposeGuard purposes;
    private final AccessMetrics metrics;
    private final Clock clock;
    private final SecureRandom random;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnly;

    @Autowired
    InviteService(AccessRepository access, LibraryRepository library, AccessMetrics metrics, PlatformTransactionManager transactions) {
        this(access, library, metrics, transactions, Clock.systemUTC(), new SecureRandom());
    }

    InviteService(AccessRepository access, LibraryRepository library, AccessMetrics metrics, PlatformTransactionManager transactions, Clock clock,
                  SecureRandom random) {
        this.access = access;
        this.library = library;
        this.purposes = new DeckPurposeGuard(library);
        this.metrics = metrics;
        this.clock = clock;
        this.random = random;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(10);
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.readOnly.setTimeout(10);
    }

    /**
     * @param maxUses       {@code null} for unlimited, else 1..1000 ({@code 1} is a one-time link)
     * @param expiresInDays {@code null} for no expiry, else 1..365
     * @throws ResourceNotFoundException the deck is not the owner's or is deleted
     * @throws PublicationRequiredException the deck has never been shared, or is private
     * @throws ResourceLimitExceededException 20 active or 200 stored invitations
     */
    public Created create(UUID owner, UUID deck, Integer maxUses, Integer expiresInDays) {
        requireIds(owner, deck);
        if ((maxUses != null && (maxUses < 1 || maxUses > MAX_USES)) || (expiresInDays != null && (expiresInDays < 1 || expiresInDays > MAX_EXPIRY_DAYS))) {
            throw new InvalidRequestException();
        }
        String token = InviteTokens.generate(random);
        return transaction.execute(status -> {
            requireOwned(owner, deck);
            purposes.requireShareable(deck);
            access.lock("deck.invites", deck);
            Publication publication = library.publication(owner, deck).orElseThrow(PublicationRequiredException::new);
            if (publication.visibility() == DeckVisibility.PRIVATE || publication.publishedRevisionId() == null) throw new PublicationRequiredException();
            Instant now = clock.instant();
            access.pruneDeadInvites(deck, now.minus(PRUNE_AFTER));
            if (access.activeInvites(deck, now) >= ACTIVE_LIMIT) throw new ResourceLimitExceededException(ProblemExtension.limit(ACTIVE_LIMIT));
            if (access.invites(deck) >= STORED_LIMIT) throw new ResourceLimitExceededException(ProblemExtension.limit(STORED_LIMIT));
            Invite invite = access.insertInvite(deck, InviteTokens.hash(token), maxUses, expiresInDays == null ? null : now.plus(Duration.ofDays(expiresInDays)), now);
            return new Created(invite, token, "/d/" + publication.publicCode() + "?invite=" + token);
        });
    }

    /** Newest first. @throws InvalidRequestException a malformed page size or cursor */
    public Page list(UUID owner, UUID deck, String limit, String cursor) {
        requireIds(owner, deck);
        int size = AccessCursor.pageSize(limit);
        AccessCursor after = AccessCursor.decode(cursor);
        return readOnly.execute(status -> {
            requireOwned(owner, deck);
            List<Invite> rows = access.pageInvites(deck, after, size);
            boolean more = rows.size() > size;
            List<Invite> items = more ? rows.subList(0, size) : rows;
            return new Page(List.copyOf(items), more ? new AccessCursor(items.getLast().createdAt(), items.getLast().inviteId()).encode() : null);
        });
    }

    /** The status of an invitation at the service's current time. */
    public Invite.Status status(Invite invite) { return invite.status(clock.instant()); }

    /** Stops new redemptions; the grants the invitation already made stay. Idempotent: a revoked invitation is returned as it is. */
    public Invite revoke(UUID owner, UUID deck, UUID invite) {
        requireIds(owner, deck);
        UuidPolicy.requireEntityId(invite, "inviteId");
        return transaction.execute(status -> {
            requireOwned(owner, deck);
            Invite current = access.lockInvite(deck, invite).orElseThrow(ResourceNotFoundException::new);
            return current.revokedAt() != null ? current : access.revokeInvite(invite, clock.instant());
        });
    }

    /**
     * Redeems the token for the signed-in account.
     *
     * @return the current public code of the deck
     * @throws ResourceNotFoundException the one answer for every token that does not open a deck for this account
     * @throws ResourceLimitExceededException the token is valid but the deck already has 1000 people with access
     */
    public String redeem(UUID account, String token) {
        UuidPolicy.requireEntityId(account, "account");
        if (!InviteTokens.wellFormed(token)) {
            metrics.count("redeem", "refused");
            throw new ResourceNotFoundException();
        }
        byte[] hash = InviteTokens.hash(token);
        try {
            Redeemed result = transaction.execute(status -> redeemOnce(account, hash));
            metrics.count("redeem", result.granted() ? "ok" : "noop");
            return result.code();
        } catch (ResourceNotFoundException refused) {
            metrics.count("redeem", "refused");
            throw refused;
        } catch (ResourceLimitExceededException full) {
            metrics.count("redeem", "limit");
            throw full;
        }
    }

    private record Redeemed(String code, boolean granted) { }

    private Redeemed redeemOnce(UUID account, byte[] hash) {
        RedeemTarget target = access.lockInviteByHash(hash).orElseThrow(ResourceNotFoundException::new);
        // A sample deck or the template is never shared: its token opens nothing, and the answer is the one of every token that does not open a deck.
        if (!purposes.purposeOf(target.deckId()).shareable()) throw new ResourceNotFoundException();
        Instant now = clock.instant();
        // A deck that cannot be opened (private, never published) has no link to hand out, whoever asks.
        if (!target.published() || target.visibility() == DeckVisibility.PRIVATE || target.publicCode() == null) throw new ResourceNotFoundException();
        // Lowering the level rotated the code: an invitation of an older generation opens nothing, for anybody (old links stop working).
        if (!target.currentGeneration()) throw new ResourceNotFoundException();
        // Hidden by a complaint decision (Share/15): nobody but the owner can open it, the grantees included.
        if (target.hidden() && !account.equals(target.ownerId())) throw new ResourceNotFoundException();
        // Whoever is already in (the owner, a grantee) is answered with the code and consumes nothing, even when the invitation has been used up
        // since: opening the same link twice must not turn a one-time link into a "not found".
        if (account.equals(target.ownerId()) || library.hasGrant(target.deckId(), account)) return new Redeemed(target.publicCode(), false);
        boolean valid = target.revokedAt() == null && (target.expiresAt() == null || now.isBefore(target.expiresAt()))
                && (target.maxUses() == null || target.uses() < target.maxUses());
        if (!valid) throw new ResourceNotFoundException();
        // A deck that anybody with the link can read has nobody to let in: the answer is the code, no grant and no use.
        if (target.visibility() != DeckVisibility.INVITE) return new Redeemed(target.publicCode(), false);
        access.lockGrants(target.deckId());
        Optional<Request> request = access.lockRequest(target.deckId(), account);
        // The owner revoked this person: the link must not undo it for 30 days (the owner can add them again by login).
        if (request.isPresent() && request.orElseThrow().revokedAt() != null && now.isBefore(request.orElseThrow().revokedAt().plus(AccessRequestService.RETRY_AFTER_REJECTION))) {
            throw new ResourceNotFoundException();
        }
        // The grant first: a use is consumed only when a grant was actually created.
        if (access.addGrant(target.deckId(), account, target.ownerId(), now)) access.consumeInvite(target.inviteId(), now);
        // A person who was waiting for an answer has one now: the request is closed without a notification (the invitation was the answer).
        if (request.isPresent()) access.decide(target.deckId(), account, "PENDING", "APPROVED", now);
        return new Redeemed(target.publicCode(), true);
    }

    private void requireOwned(UUID owner, UUID deck) {
        if (!library.ownsLiveDeck(owner, deck)) throw new ResourceNotFoundException();
    }

    private static void requireIds(UUID owner, UUID deck) {
        UuidPolicy.requireEntityId(owner, "owner");
        UuidPolicy.requireEntityId(deck, "deckId");
    }
}
