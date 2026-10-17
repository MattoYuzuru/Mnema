package app.mnema.learning.fork;

import app.mnema.learning.community.DeckEngagement;
import app.mnema.learning.community.CatalogEvents;
import app.mnema.learning.community.InterestService;
import app.mnema.learning.library.AccessLevel;
import app.mnema.learning.library.DeckAccess;
import app.mnema.learning.library.DeckInviteOnlyException;
import app.mnema.learning.library.DeckRef;
import app.mnema.learning.library.DeckPurpose;
import app.mnema.learning.library.DeckPurposeGuard;
import app.mnema.learning.library.Denial;
import app.mnema.learning.library.Resolution;
import app.mnema.learning.library.Viewer;
import app.mnema.learning.platform.api.CapabilityUnavailableException;
import app.mnema.learning.platform.api.ProblemExtension;
import app.mnema.learning.platform.api.RateLimitedException;
import app.mnema.learning.platform.api.ResourceLimitExceededException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import app.mnema.learning.platform.jobs.LearningJobs;
import app.mnema.learning.platform.security.IdentityClaims.AccountSignals;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.storage.StorageTypes.PinOwner;
import app.mnema.learning.storage.StorageTypes.StageBatch;
import app.mnema.learning.storage.StorageTypes.StagedRoot;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
    private final DeckEngagement engagement;
    private final CatalogEvents events;
    private final InterestService interests;
    private final DeckPurposeGuard purposes;

    ForkService(ForkRepository repository, ForkSettings settings, DeckAccess access, CommandReceiptService receipts, ImmutableStorage storage,
                LearningJobs jobs, MeterRegistry meters, DeckEngagement engagement, CatalogEvents events, InterestService interests,
                DeckPurposeGuard purposes) {
        this.repository = repository;
        this.settings = settings;
        this.access = access;
        this.receipts = receipts;
        this.storage = storage;
        this.jobs = jobs;
        this.meters = meters;
        this.engagement = engagement;
        this.events = events;
        this.interests = interests;
        this.purposes = purposes;
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

    /**
     * @param signals the anti-abuse signals of the caller's account from the Identity answer that authorized the request ({@code IdentityClaims.signals}); they weigh the copy in the community
     *                rank and flag a burst of copies by new accounts, and are stored with the first copy of the account only
     */
    @Transactional(timeout = 10)
    public WriteResult create(UUID actor, ForkCommand command, AccountSignals signals) {
        requireEnabled();
        UuidPolicy.requireEntityId(actor, "actor");
        boolean[] applied = {false};
        JsonNode acknowledgement = receipts.execute(new CommandIdentity(command.commandId(), actor, "deck.forks", "fork.create"), command.envelope(), () -> {
            applied[0] = true;
            return copy(actor, command, signals);
        });
        return new WriteResult(acknowledgement, !applied[0]);
    }

    private ObjectNode copy(UUID actor, ForkCommand command, AccountSignals signals) {
        Viewer viewer = Viewer.account(actor, null);
        Resolution first = decide(viewer, command.sourceCode());
        // A sample copy and the template are never copied by a user (OB-201); the owner of the deck got FORK_OWN_DECK above, everybody else a 404 if it is unreadable.
        purposes.requireShareable(first.deck().deckId());
        repository.lockActor(actor);
        // From here the publication of the source cannot move (a publication or a level change waits for this transaction): decide again, on frozen state.
        UUID frozen = repository.lockPublished(first.deck().deckId()).orElseThrow(ResourceNotFoundException::new);
        DeckRef source = decide(viewer, command.sourceCode()).deck();
        if (!frozen.equals(source.published().revisionId())) throw new ResourceNotFoundException();
        Optional<ForkRepository.Live> live = repository.live(actor, source.deckId());
        if (live.isPresent()) {
            // The re-link: this code opens the source of a copy the viewer already holds (decided above by DeckAccess, like every copy command), so if the copy stored another code (the
            // author lowered the level and rotated it, and the copy reads «источник недоступен»), it now holds the code that works. Under the actor's lock, never on the read path.
            boolean relinked = repository.relink(live.get().deckId(), source.code());
            meters.counter("mnema_deck_forks_total", "outcome", relinked ? "relinked" : "existing").increment();
            return acknowledgement(command, live.get().deckId(), live.get().preparing(), true);
        }
        UUID deck = write(actor, command.commandId(), source.deckId(), source.scopeId(), command.sourceCode(), frozen, signals, DeckPurpose.USER);
        return acknowledgement(command, deck, true, false);
    }

    /**
     * The copy itself, in the caller's transaction and under the actor's lock: the account's window and caps (429, 422, 503), the roots of the published revision {@code frozen} with
     * durable pins of the copy's own, the deck (inserted with its {@code purpose}), its revision 0, the materialization job and the {@code deck_fork} row. The engagement and the
     * catalogue events are asked for here and decide for themselves ({@link DeckEngagement}, {@link CatalogEvents} write nothing for a source that is not an ordinary deck).
     */
    private UUID write(UUID actor, UUID commandId, UUID sourceDeck, UUID scope, String code, UUID frozen, AccountSignals signals, DeckPurpose purpose) {
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
        interests.lockSignal(actor);
        ForkRepository.Published published = repository.published(sourceDeck, frozen);
        UUID deck = UUID.randomUUID();
        UUID genesis = UUID.randomUUID();
        // The storage pattern of every deck revision: stage the roots, retain them durably for the revision, release the staging pins.
        List<StagedRoot> roots = storage.stageBatch(new StageBatch(scope, actor, List.of(),
                List.of(published.membersRootId(), published.exercisesRootId())), Duration.ofMinutes(1));
        PinOwner owner = new PinOwner("deck.revision", genesis, actor);
        UUID membersPin = storage.retain(roots.getFirst(), owner);
        UUID exercisesPin = storage.retain(roots.getLast(), owner);
        repository.insertDeck(deck, actor, scope, genesis, purpose);
        repository.insertGenesis(deck, genesis, scope, actor, commandId, published, membersPin, exercisesPin);
        roots.forEach(root -> storage.release(scope, root.stagingPinId()));
        UUID job = jobs.enqueue(QUEUE, deck.toString(), actor, payload(deck));
        repository.insertFork(deck, scope, actor, sourceDeck, code, frozen, job);
        // Community/3: the metric of the direct source, in this very transaction (the first copy of an account counts; the owner never does).
        engagement.forked(sourceDeck, actor, signals);
        events.forked(actor, sourceDeck);
        meters.counter("mnema_deck_forks_total", "outcome", "created").increment();
        return deck;
    }

    /** The result of {@link #copyTemplate}: the account's copy and whether this call made it. */
    public record TemplateCopy(UUID deckId, boolean created) { }

    /** Whether {@code template} is ready to be copied right now: a live deck marked {@code SAMPLE_TEMPLATE}, published at {@code LINK}, not hidden by moderation. */
    public boolean templateReady(UUID template) {
        UuidPolicy.requireEntityId(template, "templateId");
        return repository.template(template, false).isPresent();
    }

    /**
     * The system copy of the onboarding sample template (OB-201): the same O(1) copy and materialization job as {@link #create}, for a source that does not come from a public code
     * but from the configuration ({@code learning.onboarding.sample-deck.template-id}), so there is no access decision of a guest and the owner of the template may copy it too. The copy
     * is inserted with {@code purpose = SAMPLE}. It runs in the caller's transaction (the receipt of {@code SampleDeckService}) under the account's copy lock, freezes the template's
     * publication like the ordinary copy and keeps its limits (the account's window and caps, the global cap of unfinished copies). It writes no engagement, catalogue event or interest signal
     * ({@link DeckEngagement}, {@link CatalogEvents}, {@link InterestService} refuse a source that is not an ordinary deck). The caller has already looked for the account's live sample deck;
     * a live sample copy of the template found here (it is the same copy) is returned as it is, while a live ordinary copy of it (made by the public route before the deck was marked)
     * is refused like an unready template.
     *
     * @return the copy, or empty when the template is not ready (missing, deleted, not marked, not published or not at LINK) or the account holds an ordinary live copy of it
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<TemplateCopy> copyTemplate(UUID actor, UUID template, UUID commandId, AccountSignals signals) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(template, "templateId");
        repository.lockActor(actor);
        // The publication row is frozen from here: a republication or a level change of the template waits for this transaction, so what is copied is what was ready.
        Optional<ForkRepository.Template> found = repository.template(template, true);
        if (found.isEmpty()) return Optional.empty();
        ForkRepository.Template source = found.orElseThrow();
        Optional<ForkRepository.Live> live = repository.live(actor, source.deckId());
        if (live.isPresent()) {
            // A live copy made by the ordinary route before the deck was marked is the account's own deck, not its sample deck: answering it would hand the client a deck it never sees as the sample.
            return repository.isSample(live.get().deckId()) ? Optional.of(new TemplateCopy(live.get().deckId(), false)) : Optional.empty();
        }
        UUID deck = write(actor, commandId, source.deckId(), source.scopeId(), source.code(), source.publishedRevisionId(), signals, DeckPurpose.SAMPLE);
        return Optional.of(new TemplateCopy(deck, true));
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

    /**
     * The owner's auto-update setting of a copy (idempotent: the stored value is answered without a write). Owner only (the opaque 404 for anything that is not the caller's live copy);
     * a copy that is preparing is {@code 409 DECK_PREPARING} and a detached one {@code 409 CAPABILITY_UNAVAILABLE} (capability {@code forkAutoUpdate}, reason {@code DETACHED}): a detached copy
     * never gets updates. The kill switch {@code learning.updates.auto.enabled} is read by the job that applies updates, not here: the choice is stored either way.
     */
    @Transactional(timeout = 10)
    public ObjectNode setAutoUpdate(UUID actor, UUID deck, boolean value) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(deck, "deckId");
        ForkRepository.Own own = repository.own(actor, deck).orElseThrow(ResourceNotFoundException::new);
        if (!own.materialized()) throw new DeckPreparingException();
        if (own.detached()) {
            throw new CapabilityUnavailableException(ProblemExtension.builder().put("capability", "forkAutoUpdate").put("reason", "DETACHED").build());
        }
        if (own.autoUpdate() != value && !repository.setAutoUpdate(deck, value)) {
            // a detach or a delete between the read and the write: answer what the row is now
            ForkRepository.Own now = repository.own(actor, deck).orElseThrow(ResourceNotFoundException::new);
            if (now.detached()) throw new CapabilityUnavailableException(ProblemExtension.builder().put("capability", "forkAutoUpdate").put("reason", "DETACHED").build());
            if (now.autoUpdate() != value) throw new IllegalStateException("The auto-update setting was not stored");
        }
        return JsonNodeFactory.instance.objectNode().put("deckId", deck.toString()).put("autoUpdate", value);
    }

    /** {@code myCopy} of the public summary: the viewer's live copy of exactly this deck, or null. */
    @Transactional(readOnly = true, timeout = 10)
    public ObjectNode myCopy(UUID viewer, UUID scope, UUID source) {
        return repository.myCopy(viewer, scope, source)
                .map(live -> JsonNodeFactory.instance.objectNode().put("deckId", live.deckId().toString()).put("preparing", live.preparing()))
                .orElse(null);
    }
}
