package app.mnema.learning.fork;

import app.mnema.learning.library.AccessLevel;
import app.mnema.learning.library.DeckAccess;
import app.mnema.learning.library.DeckInviteOnlyException;
import app.mnema.learning.library.DeckRef;
import app.mnema.learning.library.Denial;
import app.mnema.learning.library.Resolution;
import app.mnema.learning.library.Viewer;
import app.mnema.learning.platform.api.ProblemExtension;
import app.mnema.learning.platform.api.RateLimitedException;
import app.mnema.learning.platform.api.ResourceLimitExceededException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import app.mnema.learning.platform.jobs.LearningJobs;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.storage.StorageTypes.PinOwner;
import app.mnema.learning.storage.StorageTypes.StageBatch;
import app.mnema.learning.storage.StorageTypes.StagedRoot;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * «Добавить к себе» (community decks architecture §5, contract {@code contracts/decks/forks.json}): the O(1) copy of the PUBLISHED revision of a deck the caller may read, and the two
 * small owner commands on a copy (retry and detach).
 *
 * <p><b>The copy command</b> runs in one transaction with the house receipt: it resolves the source by its public code through {@link DeckAccess} (the same decision as the public
 * read; the owner is {@code 409 FORK_OWN_DECK}), takes the account's advisory lock (the window and the cap are counts, so the account's creations are serialized), returns the live copy when there already is one, checks the limits (the account's window 429 and unfinished copies 422, then the global cap of unfinished copies, {@code 503 FORKS_BUSY}), and otherwise writes a deck in the source's
 * lineage, its revision 0 on the roots of the published revision with durable pins of its own, the {@code deck_fork} row and the enqueued materialization job. It reads
 * <i>only</i> the published revision, and it freezes the publication row ({@code FOR SHARE}) before it resolves the second time, so a republication or a level change cannot interleave: what is
 * copied is exactly what the viewer was allowed to read at that moment. The heads of the copy are not touched here; the job writes them from the manifest ({@link ForkMaterializer}).
 */
@Service
public class ForkService {
    /** The queue of the materialization job. */
    public static final String QUEUE = "deck.copy.materialize";

    private final ForkRepository repository;
    private final ForkSettings settings;
    private final DeckAccess access;
    private final CommandReceiptService receipts;
    private final ImmutableStorage storage;
    private final LearningJobs jobs;
    private final MeterRegistry meters;

    ForkService(ForkRepository repository, ForkSettings settings, DeckAccess access, CommandReceiptService receipts, ImmutableStorage storage,
                LearningJobs jobs, MeterRegistry meters) {
        this.repository = repository;
        this.settings = settings;
        this.access = access;
        this.receipts = receipts;
        this.storage = storage;
        this.jobs = jobs;
        this.meters = meters;
    }

    /** The acknowledgement of a copy command; {@code replayed} is an exact retry of an earlier command. */
    public record WriteResult(JsonNode acknowledgement, boolean replayed) {
        public WriteResult { acknowledgement = acknowledgement.deepCopy(); }
        @Override public JsonNode acknowledgement() { return acknowledgement.deepCopy(); }
        /** Whether the answer names a copy that already existed (200), not a new one (201). */
        public boolean existing() { return acknowledgement.path("existing").asBoolean(false); }
    }

    /**
     * The kill switches of «создание копий»: the creation route answers 404 as if it did not exist, before it reads anything, unless BOTH
     * {@code learning.community.forks.enabled} and {@code learning.community.public-routes.enabled} are on (a copy is made from a deck found by its public code).
     */
    public void requireEnabled() {
        if (!settings.creation()) throw new ResourceNotFoundException();
    }

    @Transactional(timeout = 10)
    public WriteResult create(UUID actor, ForkCommand command) {
        requireEnabled();
        UuidPolicy.requireEntityId(actor, "actor");
        boolean[] applied = {false};
        JsonNode acknowledgement = receipts.execute(new CommandIdentity(command.commandId(), actor, "deck.forks", "fork.create"), command.envelope(), () -> {
            applied[0] = true;
            return copy(actor, command);
        });
        return new WriteResult(acknowledgement, !applied[0]);
    }

    private ObjectNode copy(UUID actor, ForkCommand command) {
        Viewer viewer = Viewer.account(actor, null);
        Resolution first = decide(viewer, command.sourceCode());
        repository.lockActor(actor);
        // From here the publication of the source cannot move (a publication or a level change waits for this transaction): decide again, on frozen state.
        UUID frozen = repository.lockPublished(first.deck().deckId()).orElseThrow(ResourceNotFoundException::new);
        DeckRef source = decide(viewer, command.sourceCode()).deck();
        if (!frozen.equals(source.published().revisionId())) throw new ResourceNotFoundException();
        Optional<ForkRepository.Live> live = repository.live(actor, source.deckId());
        if (live.isPresent()) {
            meters.counter("mnema_deck_forks_total", "outcome", "existing").increment();
            return acknowledgement(command, live.get().deckId(), live.get().preparing(), true);
        }
        ForkRepository.Window window = repository.window(actor, settings.window);
        if (window.count() >= settings.maxPerWindow) {
            meters.counter("mnema_deck_forks_total", "outcome", "throttled").increment();
            throw new RateLimitedException(window.retryAfterSeconds());
        }
        if (repository.unfinished(actor) >= settings.maxPreparing) {
            meters.counter("mnema_deck_forks_total", "outcome", "limit").increment();
            throw new ResourceLimitExceededException(ProblemExtension.limit(settings.maxPreparing));
        }
        // The queue of the materialization job is shared by every account: past the global cap a new copy waits (the owner retries later). A SOFT cap: copies of
        // different accounts are created concurrently (only one account's creations are serialized), so it can be overshot by the number of requests in flight.
        if (repository.unfinishedTotal(settings.maxUnfinishedTotal) >= settings.maxUnfinishedTotal) {
            meters.counter("mnema_deck_forks_total", "outcome", "busy").increment();
            throw new ForkQueueBusyException();
        }
        ForkRepository.Published published = repository.published(source.deckId(), frozen);
        UUID deck = UUID.randomUUID();
        UUID genesis = UUID.randomUUID();
        // The storage pattern of every deck revision: stage the roots, retain them durably for the revision, release the staging pins.
        List<StagedRoot> roots = storage.stageBatch(new StageBatch(source.scopeId(), actor, List.of(),
                List.of(published.membersRootId(), published.exercisesRootId())), Duration.ofMinutes(1));
        PinOwner owner = new PinOwner("deck.revision", genesis, actor);
        UUID membersPin = storage.retain(roots.getFirst(), owner);
        UUID exercisesPin = storage.retain(roots.getLast(), owner);
        repository.insertDeck(deck, actor, source.scopeId(), genesis);
        repository.insertGenesis(deck, genesis, source.scopeId(), actor, command.commandId(), published, membersPin, exercisesPin);
        roots.forEach(root -> storage.release(source.scopeId(), root.stagingPinId()));
        UUID job = jobs.enqueue(QUEUE, deck.toString(), actor, payload(deck));
        repository.insertFork(deck, source.scopeId(), actor, source.deckId(), command.sourceCode(), frozen, job);
        meters.counter("mnema_deck_forks_total", "outcome", "created").increment();
        return acknowledgement(command, deck, true, false);
    }

    /** The access decision for a copy: one 404 for everything unreadable, 403 for an invitation without a grant, 409 for the owner. */
    private Resolution decide(Viewer viewer, String code) {
        Resolution resolution = access.resolve(viewer, code);
        if (resolution.denial() == Denial.INVITE_ONLY) throw new DeckInviteOnlyException();
        if (!resolution.granted()) throw new ResourceNotFoundException();
        if (resolution.level() == AccessLevel.OWNER) throw new ForkOwnDeckException();
        if (!resolution.canFork()) throw new ResourceNotFoundException();
        return resolution;
    }

    private static ObjectNode acknowledgement(ForkCommand command, UUID deck, boolean preparing, boolean existing) {
        return JsonNodeFactory.instance.objectNode().put("commandId", command.commandId().toString()).put("deckId", deck.toString())
                .put("preparing", preparing).put("existing", existing);
    }

    private static ObjectNode payload(UUID deck) {
        return JsonNodeFactory.instance.objectNode().put("deckId", deck.toString());
    }

    /**
     * Requeues a failed copy under its own job (its cursor is kept, so it resumes); a job whose record was purged by retention is enqueued again; a copy that is queued, running
     * or ready is left alone. Owner only: a deck that is not the caller's live copy is the opaque 404.
     */
    @Transactional(timeout = 10)
    public ObjectNode retry(UUID actor, UUID deck) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(deck, "deckId");
        ForkRepository.Own own = repository.own(actor, deck).orElseThrow(ResourceNotFoundException::new);
        ObjectNode state = JsonNodeFactory.instance.objectNode().put("deckId", deck.toString());
        if (own.materialized()) return state.put("preparing", false).put("failed", false);
        if (ForkLink.endedWithoutSuccess(own.jobState())) {
            if (!jobs.retry(own.jobId())) {
                UUID job = jobs.enqueue(QUEUE, deck.toString(), actor, payload(deck));
                repository.replaceJob(deck, job);
            }
        }
        return state.put("preparing", true).put("failed", false);
    }

    /** Detaches a copy from its author (idempotent). Owner only; allowed while the copy is preparing. */
    @Transactional(timeout = 10)
    public ObjectNode detach(UUID actor, UUID deck) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(deck, "deckId");
        repository.own(actor, deck).orElseThrow(ResourceNotFoundException::new);
        repository.detach(deck);
        return JsonNodeFactory.instance.objectNode().put("deckId", deck.toString()).put("detached", true);
    }

    /** {@code myCopy} of the public summary: the viewer's live copy of exactly this deck, or null. */
    @Transactional(readOnly = true, timeout = 10)
    public ObjectNode myCopy(UUID viewer, UUID scope, UUID source) {
        return repository.myCopy(viewer, scope, source)
                .map(live -> JsonNodeFactory.instance.objectNode().put("deckId", live.deckId().toString()).put("preparing", live.preparing()))
                .orElse(null);
    }
}
