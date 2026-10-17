package app.mnema.learning.library;

import app.mnema.learning.library.AccessRepository.Request;
import app.mnema.learning.notification.NotificationKind;
import app.mnema.learning.notification.NotificationPublisher;
import app.mnema.learning.notification.NotificationRoute;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ProblemExtension;
import app.mnema.learning.platform.api.RateLimitedException;
import app.mnema.learning.platform.api.ResourceLimitExceededException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.id.UuidPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Requests for access to an «По приглашению» deck (Share/13, #435; contract {@code contracts/decks/access.json}): a signed-in account that cannot read the deck
 * asks, the author decides.
 *
 * <p><b>Asking</b> ({@link #send}) is one transaction under an advisory lock of the requester, so the limits (at most {@value #OPEN_LIMIT} open requests and
 * {@value #DAILY_LIMIT} new requests in a rolling 24 hours) are exact. The answer to every accepted call is the same {@link #PENDING}: a first request, a repeat while it is open and a
 * repeat within 30 days of a rejection (a rejection is silent) cannot be told apart, and only the first writes a row, counts and may notify. A deck whose
 * author switched requests off answers {@link DeckInviteOnlyException} (the body of the «по приглашению» screen, nothing about the deck). Requests ride
 * the kill switch of the public routes: switched off, they are a 404 like the reads.
 *
 * <p><b>Notifications</b> are published as the last write of the transaction (they lock the recipient's cursor): {@code DECK_ACCESS_REQUESTED} to the author
 * at most once per deck per UTC day (the dedupe key holds the date; {@code pendingCount} is the open requests of the deck then, capped at 1000, and is not
 * refreshed during the day), {@code DECK_ACCESS_GRANTED} to the requester on approval. Their params carry ids and the public code only, never who asked.
 *
 * <p><b>Decisions</b> lock the request row. {@code approve} creates the grant, marks the request and notifies in one transaction; {@code reject} is silent. The same decision repeated is the
 * same answer; the opposite decision of a decided request is a 404.
 *
 * <p><b>Withdrawing</b> ({@link #withdraw}) is the requester's way to free one of the 20 open slots.
 *
 * <p>Load: a request is the requester's advisory lock, a handful of key and index reads of the requester's own rows, one upsert and one notification; the author's
 * list is a keyset range of a partial index. A deck that is asked a million times holds one row per asker and notifies once a day.
 */
@Service
public class AccessRequestService {
    public static final String PENDING = "PENDING";
    static final int OPEN_LIMIT = 20;
    static final int DAILY_LIMIT = 5;
    static final Duration WINDOW = Duration.ofHours(24);
    static final Duration RETRY_AFTER_REJECTION = Duration.ofDays(30);
    static final int PENDING_COUNT_CAP = 1000;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    /** A page of open requests: at most {@code limit} items and the cursor of the next page, or null. */
    public record Page(List<Request> items, String nextCursor) { }

    /** The outcome of a decision. */
    public record Decision(UUID requesterId, String status, Instant decidedAt) { }

    private final PublicRouteSettings settings;
    private final DeckAccess access;
    private final AccessRepository repository;
    private final LibraryRepository library;
    private final DeckPurposeGuard purposes;
    private final NotificationPublisher notifications;
    private final AccessMetrics metrics;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnly;

    @Autowired
    AccessRequestService(PublicRouteSettings settings, DeckAccess access, AccessRepository repository, LibraryRepository library,
                         NotificationPublisher notifications, AccessMetrics metrics, PlatformTransactionManager transactions) {
        this(settings, access, repository, library, notifications, metrics, transactions, Clock.systemUTC());
    }

    AccessRequestService(PublicRouteSettings settings, DeckAccess access, AccessRepository repository, LibraryRepository library,
                         NotificationPublisher notifications, AccessMetrics metrics, PlatformTransactionManager transactions, Clock clock) {
        this.settings = settings;
        this.access = access;
        this.repository = repository;
        this.library = library;
        this.purposes = new DeckPurposeGuard(library);
        this.notifications = notifications;
        this.metrics = metrics;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(10);
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.readOnly.setTimeout(10);
    }

    /**
     * @return {@link #PENDING}, always
     * @throws ResourceNotFoundException the routes are off, or the code is unknown, private, rotated or the deck was never published
     * @throws InvalidRequestException the caller can already read the deck (owner, grantee, a LINK or PUBLIC deck): there is nothing to ask for
     * @throws DeckInviteOnlyException the author turned requests off
     * @throws ResourceLimitExceededException 20 open requests
     * @throws RateLimitedException 5 new requests in 24 hours
     */
    public String send(UUID account, String code) {
        UuidPolicy.requireEntityId(account, "account");
        if (!settings.enabled) {
            metrics.count("request", "refused");
            throw new ResourceNotFoundException();
        }
        try {
            boolean created = Boolean.TRUE.equals(transaction.execute(status -> sendOnce(account, code)));
            metrics.count("request", created ? "created" : "silent");
            return PENDING;
        } catch (ResourceLimitExceededException | RateLimitedException limited) {
            metrics.count("request", "limited");
            throw limited;
        } catch (ResourceNotFoundException | InvalidRequestException | DeckInviteOnlyException refused) {
            metrics.count("request", "refused");
            throw refused;
        }
    }

    /** @return whether a request was written (false: a silent repeat) */
    private boolean sendOnce(UUID account, String code) {
        purposes.requireShareableByCode(code);
        Resolution resolution = access.resolve(Viewer.account(account, null), code);
        if (resolution.granted()) throw new InvalidRequestException();
        if (resolution.denial() != Denial.INVITE_ONLY) throw new ResourceNotFoundException();
        DeckRef deck = library.byCode(code).orElseThrow(ResourceNotFoundException::new);
        if (!repository.requestsEnabled(deck.deckId())) throw new DeckInviteOnlyException();
        repository.lock("deck.access-request", account);
        Instant now = clock.instant();
        Optional<Request> existing = repository.lockRequest(deck.deckId(), account);
        if (existing.isPresent() && silent(existing.orElseThrow(), now)) return false;
        if (repository.openRequests(account) >= OPEN_LIMIT) throw new ResourceLimitExceededException(ProblemExtension.limit(OPEN_LIMIT));
        List<Instant> recent = repository.requestTimesSince(account, now.minus(WINDOW));
        if (recent.size() >= DAILY_LIMIT) {
            Instant free = recent.get(recent.size() - DAILY_LIMIT).plus(WINDOW);
            throw new RateLimitedException(Math.max(1, Duration.between(now, free).plusMillis(999).toSeconds()));
        }
        repository.openRequest(deck.deckId(), account, now);
        int pending = repository.pendingRequests(deck.deckId(), PENDING_COUNT_CAP);
        notifications.publish(deck.ownerId(), NotificationKind.DECK_ACCESS_REQUESTED, "deck-access:" + deck.deckId() + ":requested:" + DAY.format(now),
                Map.of("deckId", deck.deckId(), "pendingCount", pending), NotificationRoute.DECK);
        return true;
    }

    /**
     * The requester takes an open request back (PENDING to WITHDRAWN), which frees one of the 20 open slots. Idempotent and uniform: any request that is not
     * open, or none at all, is the same success; an unknown, private or rotated code is the opaque 404. A withdrawn request can be made again at once (the
     * daily limit still counts it).
     */
    public void withdraw(UUID account, String code) {
        UuidPolicy.requireEntityId(account, "account");
        if (!settings.enabled) {
            metrics.count("withdraw", "refused");
            throw new ResourceNotFoundException();
        }
        try {
            transaction.executeWithoutResult(status -> {
                Resolution resolution = access.resolve(Viewer.account(account, null), code);
                if (!resolution.granted() && resolution.denial() != Denial.INVITE_ONLY) throw new ResourceNotFoundException();
                DeckRef deck = library.byCode(code).orElseThrow(ResourceNotFoundException::new);
                repository.lock("deck.access-request", account);
                repository.decide(deck.deckId(), account, PENDING, "WITHDRAWN", clock.instant());
            });
            metrics.count("withdraw", "ok");
        } catch (ResourceNotFoundException refused) {
            metrics.count("withdraw", "refused");
            throw refused;
        }
    }

    /** A request that is open, or was decided less than 30 days ago, is answered like a new one and changes nothing. */
    private static boolean silent(Request request, Instant now) {
        if (PENDING.equals(request.status())) return true;
        boolean decided = "REJECTED".equals(request.status()) || "APPROVED".equals(request.status());
        return decided && now.isBefore(request.decidedAt().plus(RETRY_AFTER_REJECTION));
    }

    /** The open requests of the owner's deck, newest first. */
    public Page list(UUID owner, UUID deck, String limit, String cursor) {
        requireIds(owner, deck);
        int size = AccessCursor.pageSize(limit);
        AccessCursor after = AccessCursor.decode(cursor);
        return readOnly.execute(status -> {
            requireOwned(owner, deck);
            List<Request> rows = repository.pagePending(deck, after, size);
            boolean more = rows.size() > size;
            List<Request> items = more ? rows.subList(0, size) : rows;
            return new Page(List.copyOf(items), more ? new AccessCursor(items.getLast().createdAt(), items.getLast().requesterId()).encode() : null);
        });
    }

    /**
     * Grants {@code VIEWER} to the requester and tells them. Repeating it is the same answer; a rejected request is a 404.
     *
     * @throws ResourceLimitExceededException the deck already has 1000 people with access (the request stays open)
     */
    public Decision approve(UUID owner, UUID deck, UUID requester) {
        requireIds(owner, deck);
        UuidPolicy.requireEntityId(requester, "requesterId");
        try {
            Decision decision = transaction.execute(status -> {
                requireOwned(owner, deck);
                purposes.requireShareable(deck);
                // lock order: the deck's grants, then the request row, then the grant (the same as redeem, grant by login and revoke)
                repository.lockGrants(deck);
                Request request = repository.lockRequest(deck, requester).orElseThrow(ResourceNotFoundException::new);
                if ("APPROVED".equals(request.status())) return decision(request);
                if (!PENDING.equals(request.status())) throw new ResourceNotFoundException();
                Instant now = clock.instant();
                repository.addGrant(deck, requester, owner, now);
                repository.decide(deck, requester, PENDING, "APPROVED", now);
                Request decided = repository.lockRequest(deck, requester).orElseThrow();
                Optional<Publication> publication = library.publication(owner, deck);
                // A private deck has no link to open: the grant stands, the notification would point nowhere.
                if (publication.isPresent() && publication.orElseThrow().visibility() != DeckVisibility.PRIVATE) {
                    notifications.publish(requester, NotificationKind.DECK_ACCESS_GRANTED, "deck-access:" + deck + ":granted:" + decided.decidedAt().toEpochMilli(),
                            Map.of("code", publication.orElseThrow().publicCode()), NotificationRoute.DECK);
                }
                return decision(decided);
            });
            metrics.count("decision", "approved");
            return decision;
        } catch (ResourceLimitExceededException full) {
            metrics.count("decision", "limit");
            throw full;
        } catch (ResourceNotFoundException refused) {
            metrics.count("decision", "refused");
            throw refused;
        }
    }

    /** Silent: nobody is told. Repeating it is the same answer; an approved request is a 404 (revoke the person instead). */
    public Decision reject(UUID owner, UUID deck, UUID requester) {
        requireIds(owner, deck);
        UuidPolicy.requireEntityId(requester, "requesterId");
        try {
            Decision decision = transaction.execute(status -> {
                requireOwned(owner, deck);
                Request request = repository.lockRequest(deck, requester).orElseThrow(ResourceNotFoundException::new);
                if ("REJECTED".equals(request.status())) return decision(request);
                if (!PENDING.equals(request.status())) throw new ResourceNotFoundException();
                repository.decide(deck, requester, PENDING, "REJECTED", clock.instant());
                return decision(repository.lockRequest(deck, requester).orElseThrow());
            });
            metrics.count("decision", "rejected");
            return decision;
        } catch (ResourceNotFoundException refused) {
            metrics.count("decision", "refused");
            throw refused;
        }
    }

    private static Decision decision(Request request) { return new Decision(request.requesterId(), request.status(), request.decidedAt()); }

    private void requireOwned(UUID owner, UUID deck) {
        if (!library.ownsLiveDeck(owner, deck)) throw new ResourceNotFoundException();
    }

    private static void requireIds(UUID owner, UUID deck) {
        UuidPolicy.requireEntityId(owner, "owner");
        UuidPolicy.requireEntityId(deck, "deckId");
    }
}
