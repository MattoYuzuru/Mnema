package app.mnema.learning.moderation;

import app.mnema.learning.library.AccessLevel;
import app.mnema.learning.library.DeckAccess;
import app.mnema.learning.library.DeckRef;
import app.mnema.learning.library.LoginLookupLimiter;
import app.mnema.learning.library.Resolution;
import app.mnema.learning.library.Viewer;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.RateLimitedException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.security.ProfileDirectory;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Filing a complaint about a public deck or a profile (Share/15, #437; contract {@code contracts/moderation/complaints.json}).
 *
 * <p>Both routes answer the same {@code 202 RECEIVED} for a new complaint and for a repeat. Filing is <b>idempotent</b> per (reporter, target): while the
 * reporter's complaint about that target is OPEN a repeat writes nothing and counts nothing, the first reason and text stand. The reporter must be able
 * to read the target now: a deck is resolved by {@link DeckAccess} with the reporter as viewer, so an unknown, private, rotated, never-published, deleted or
 * HIDDEN deck, and an invitation deck without a grant, are one opaque 404 (a complaint never confirms that a deck exists); the owner of the deck is 400. A
 * profile is resolved through Identity's public-profile lookup (a login that does not resolve is the same 404, the reporter's own is 400), under the budget
 * shared with the grants by login, OUTSIDE any transaction.
 *
 * <p>Each new complaint is one transaction under an advisory lock of the reporter: the open-complaint probe, the daily limit (at most
 * {@code reporter-per-day} new complaints in a rolling 24 hours, then {@code 429} with the time the oldest leaves the window) and the insert. The partial unique
 * index of V58 is the backstop. {@code due_at} is {@code created_at + 30 days} (the review promise), {@code retain_until} {@code created_at + 3 years} (the
 * privacy policy). Nobody is notified: the reporter gets no message about the decision (product rule).
 */
@Service
public class ComplaintService {
    static final Duration REVIEW = Duration.ofDays(30);
    static final Duration WINDOW = Duration.ofHours(24);
    static final int RETENTION_YEARS = 3;

    private final ComplaintSettings settings;
    private final DeckAccess access;
    private final ProfileDirectory profiles;
    private final LoginLookupLimiter lookups;
    private final ComplaintRepository repository;
    private final MeterRegistry meters;
    private final Clock clock;
    private final TransactionTemplate transaction;

    @Autowired
    ComplaintService(ComplaintSettings settings, DeckAccess access, ProfileDirectory profiles, LoginLookupLimiter lookups, ComplaintRepository repository,
                     MeterRegistry meters, PlatformTransactionManager transactions) {
        this(settings, access, profiles, lookups, repository, meters, transactions, Clock.systemUTC());
    }

    ComplaintService(ComplaintSettings settings, DeckAccess access, ProfileDirectory profiles, LoginLookupLimiter lookups, ComplaintRepository repository,
                     MeterRegistry meters, PlatformTransactionManager transactions, Clock clock) {
        this.settings = settings;
        this.access = access;
        this.profiles = profiles;
        this.lookups = lookups;
        this.repository = repository;
        this.meters = meters;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(10);
    }

    /** The deck route exists only when complaints and the public routes are on; otherwise it is a 404 before anything is read. */
    void requireDeckFiling() {
        if (!settings.deckFiling()) {
            count("refused");
            throw new ResourceNotFoundException();
        }
    }

    void requireProfileFiling() {
        if (!settings.profileFiling()) {
            count("refused");
            throw new ResourceNotFoundException();
        }
    }

    /**
     * @throws ResourceNotFoundException the routes are off, or the deck is not readable by the reporter
     * @throws InvalidRequestException the reporter owns the deck
     * @throws RateLimitedException the reporter filed too many new complaints today
     */
    public void fileDeck(UUID reporter, String code, ComplaintCommands.Filing filing) {
        UuidPolicy.requireEntityId(reporter, "reporter");
        requireDeckFiling();
        try {
            boolean created = Boolean.TRUE.equals(transaction.execute(status -> {
                // the reporter must be able to read the deck right now; whatever stops him is the same opaque 404
                Resolution resolution = access.resolve(Viewer.account(reporter, null), code);
                if (!resolution.granted()) throw new ResourceNotFoundException();
                if (resolution.level() == AccessLevel.OWNER) throw new InvalidRequestException();
                DeckRef deck = resolution.deck();
                return fileOnce(reporter, Complaint.DECK, deck.deckId(), filing, now -> repository.insertDeck(reporter, deck.deckId(), deck.code(),
                        filing.reason(), filing.text(), now, now.plus(REVIEW), retainUntil(now)));
            }));
            count(created ? "created" : "silent");
        } catch (ResourceNotFoundException | InvalidRequestException refused) {
            count("refused");
            throw refused;
        } catch (RateLimitedException limited) {
            count("limited");
            throw limited;
        }
    }

    /**
     * @throws ResourceNotFoundException the route is off, or no public profile has this login
     * @throws InvalidRequestException the login is the reporter's own
     * @throws RateLimitedException the reporter filed too many new complaints today, or the lookup budget is used (or Identity limits lookups)
     * @throws app.mnema.learning.platform.api.IdentityUnavailableException Identity did not answer
     */
    public void fileProfile(UUID reporter, String username, ComplaintCommands.Filing filing) {
        UuidPolicy.requireEntityId(reporter, "reporter");
        requireProfileFiling();
        try {
            if (!ProfileDirectory.validUsername(username)) throw new ResourceNotFoundException();
            // the Identity call is outside any transaction (no connection is held across a network call) and under the shared lookup budget
            lookups.admit(reporter);
            UUID account = profiles.accountOf(username).orElseThrow(ResourceNotFoundException::new);
            if (account.equals(reporter)) throw new InvalidRequestException();
            String login = username.toLowerCase(Locale.ROOT);
            boolean created = Boolean.TRUE.equals(transaction.execute(status -> fileOnce(reporter, Complaint.PROFILE, account, filing,
                    now -> repository.insertProfile(reporter, account, login, filing.reason(), filing.text(), now, now.plus(REVIEW), retainUntil(now)))));
            count(created ? "created" : "silent");
        } catch (ResourceNotFoundException | InvalidRequestException refused) {
            count("refused");
            throw refused;
        } catch (RateLimitedException limited) {
            count("limited");
            throw limited;
        } catch (app.mnema.learning.platform.api.IdentityUnavailableException unavailable) {
            count("unavailable");
            throw unavailable;
        }
    }

    /** @return whether a complaint was written (false: a silent repeat) */
    private boolean fileOnce(UUID reporter, String kind, UUID target, ComplaintCommands.Filing filing, java.util.function.Consumer<Instant> insert) {
        repository.lock("complaint.reporter", reporter);
        if (repository.openExists(reporter, kind, target)) return false;
        Instant now = clock.instant();
        List<Instant> recent = repository.createdSince(reporter, now.minus(WINDOW));
        if (recent.size() >= settings.reporterPerDay) {
            Instant free = recent.get(recent.size() - settings.reporterPerDay).plus(WINDOW);
            throw new RateLimitedException(Math.max(1, Duration.between(now, free).plusMillis(999).toSeconds()));
        }
        insert.accept(now);
        return true;
    }

    static Instant retainUntil(Instant createdAt) {
        return createdAt.atOffset(ZoneOffset.UTC).plusYears(RETENTION_YEARS).toInstant();
    }

    private void count(String outcome) {
        meters.counter("mnema_complaints_total", "op", "file", "outcome", outcome).increment();
    }
}
