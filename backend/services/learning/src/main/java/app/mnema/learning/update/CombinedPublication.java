package app.mnema.learning.update;

import app.mnema.learning.catalog.content.pages.CountedPageTypes.TreeRoot;
import app.mnema.learning.fork.ForkGuard;
import app.mnema.learning.platform.api.ContentUnavailableException;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ProblemExtension;
import app.mnema.learning.platform.api.ResourceLimitExceededException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.CompareAndSetExecutor;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.platform.jobs.Job;
import app.mnema.learning.platform.jobs.JobIdentityConflictException;
import app.mnema.learning.platform.jobs.JobState;
import app.mnema.learning.platform.jobs.JobStatus;
import app.mnema.learning.platform.jobs.LearningJobs;
import app.mnema.learning.platform.jobs.PermanentJobException;
import app.mnema.learning.platform.jobs.Slice;
import app.mnema.learning.platform.json.CanonicalJsonHasher;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.storage.StorageTypes.PinOwner;
import app.mnema.learning.storage.StorageTypes.StagedRoot;
import app.mnema.learning.update.CombinedPublicationRepository.DeckState;
import app.mnema.learning.update.CombinedPublicationRepository.ExerciseChange;
import app.mnema.learning.update.CombinedPublicationRepository.Head;
import app.mnema.learning.update.CombinedPublicationRepository.HeadWrite;
import app.mnema.learning.update.CombinedPublicationRepository.ItemChange;
import app.mnema.learning.update.CombinedPublicationRepository.Ref;
import app.mnema.learning.update.CombinedPublicationRepository.Revision;
import app.mnema.learning.update.CombinedUnits.ExerciseOp;
import app.mnema.learning.update.CombinedUnits.ItemOp;
import app.mnema.learning.update.CombinedUnits.ObjectiveOp;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The combined publication (Updates/2, #441; community decks architecture §4 and §7): many heads of a deck move, in ONE new deck revision, to revisions that already exist in its
 * lineage, together with removals, member order, objective heads and the title and description ({@link CombinedUnits}). It is the engine under the apply command of Updates/4: it
 * creates no lineage revision, no content and no media reference (the adopted revisions carry theirs), and it has no HTTP route.
 *
 * <p><b>Two paths, one result.</b> {@link #publish} runs a command of at most {@code learning.updates.sync-max-units} (100) units in the caller's request: one transaction that
 * resolves the operations, edits the two manifests in memory, writes the pages that are reachable from the result, moves the heads, writes the journal and the new revision
 * and stores the receipt. {@link #enqueue} stores a bigger command in {@code combined_publication(_unit)} and enqueues a job ({@value #QUEUE}) whose slices each apply at most
 * {@code learning.updates.slice-size} (200) page edits to STAGED roots (new pages pinned by renewed staging pins; the cursor is the next unit and the staged roots) and whose
 * last slice runs the same final transaction. Heads, journal, revision and receipt are written ONLY there, so a crash or a failure anywhere leaves no partial revision; a
 * deck whose head moved meanwhile ends the job {@code FAILED} with code {@code stale_head} and writes nothing.
 *
 * <p><b>Semantics.</b> An adoption moves the head of a key to a revision of the deck's lineage (never invented: it must exist for that key, and not be taken down) and
 * replaces the manifest entry with the revision's descriptor; the previous head revision stays in the lineage and in the journal. Every adopted revision is journaled
 * ({@code create} for an insertion, {@code save} for a replacement, one row per member and revision even when the member also moves) so the deck can read it by id afterwards;
 * a removal is {@code delete}, a bare move {@code reorder}. An operation that changes nothing (the head is already there, a move to the same place, the same title) is dropped,
 * and a command with nothing left creates no revision. The result is verified before it is stored: head counts equal the manifests' counts, exercise ordinals are exactly
 * {@code 0..n-1}, every binding of an adopted exercise names a material of the resulting heads (a presentation would otherwise quote a non-member) and every ASSESSED
 * objective of an adopted exercise has a head. Any violation rolls the whole command back.
 *
 * <p><b>Side effects.</b> See the guide, «Combined publication»: «Новое» marks, previews, the media manifest and published-media counts, Study generations, notifications and
 * the outbox are deliberately not touched, with the reason for each.
 */
@Service
public class CombinedPublication {
    /** The queue of the job that applies a command above the synchronous limit. */
    public static final String QUEUE = "deck.combined.publish";
    private static final Logger LOG = LoggerFactory.getLogger(CombinedPublication.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SCOPE = "deck.combined";
    private static final String TYPE = "publish";
    private static final int UNIT_READ = 2_000;
    private static final int PURGE_ON_ENQUEUE = 20;

    /** The answer of a combined publication. */
    public sealed interface Outcome permits Applied, Queued { }

    /** The command was applied (now, or by an earlier identical request when {@code replayed}). */
    public record Applied(CombinedResult result, boolean replayed) implements Outcome { }

    /** The command is a job; its progress is {@code LearningJobs.status(jobId)} and its result is the receipt, replayed by sending the same command again. */
    public record Queued(UUID jobId) implements Outcome { }

    private final CombinedPublicationRepository repository;
    private final CommandReceiptService receipts;
    private final CompareAndSetExecutor cas;
    private final ImmutableStorage storage;
    private final ForkGuard forkGuard;
    private final UpdateSettings settings;
    private final LearningJobs jobs;
    private final CanonicalJsonHasher canonical;
    private final ObjectProvider<FinalWriter> writers;
    private final MeterRegistry meters;
    private final TransactionTemplate transaction;
    private volatile List<FinalWriter> allWriters;

    CombinedPublication(CombinedPublicationRepository repository, CommandReceiptService receipts, CompareAndSetExecutor cas, ImmutableStorage storage,
                        ForkGuard forkGuard, UpdateSettings settings, LearningJobs jobs, CanonicalJsonHasher canonical, ObjectProvider<FinalWriter> writers,
                        MeterRegistry meters, PlatformTransactionManager transactions) {
        this.repository = repository;
        this.receipts = receipts;
        this.cas = cas;
        this.storage = storage;
        this.forkGuard = forkGuard;
        this.settings = settings;
        this.jobs = jobs;
        this.canonical = canonical;
        this.writers = writers;
        this.meters = meters;
        this.transaction = new TransactionTemplate(transactions);
        transaction.setTimeout(30);
    }

    // ---- the public API ----

    /**
     * Applies the command inside the caller's request (one transaction).
     *
     * @throws InvalidRequestException when the command is larger than {@code learning.updates.sync-max-units} (use {@link #enqueue}, or {@link #submit}) or than {@code max-units}
     * @throws ResourceNotFoundException when the deck is not the actor's (or is deleted)
     * @throws app.mnema.learning.fork.DeckPreparingException when the deck is a copy that is not materialized yet
     * @throws VersionConflictException when the deck head is not {@code expectedHeadRevisionId}, or an operation does not describe the manifest or the heads it meets
     * @throws IdempotencyConflictException when the command id was used for another command, or is the id of a stored command that a job has not finished yet
     */
    public Applied publish(CombinedUnits units) {
        admit(units, true);
        long started = System.nanoTime();
        DeckState deck = own(units);
        forkGuard.requireReady(units.actorId(), units.deckId());
        CommandIdentity identity = identity(units);
        ObjectNode envelope = units.envelope();
        Optional<JsonNode> replay = receipts.replay(identity, envelope);
        if (replay.isPresent()) {
            meters.counter("mnema_deck_combined_publication_total", "path", "sync", "outcome", "replayed").increment();
            return new Applied(CombinedResult.fromJson(replay.get()), true);
        }
        requireCopy(deck);
        // a command id that is already a stored command (a job that has not ended) is that job's: a body that runs here under its id is another command
        if (repository.header(units.commandId()).isPresent()) throw new IdempotencyConflictException();
        boolean[] applied = {false};
        JsonNode stored = transaction.execute(status -> receipts.execute(identity, envelope, () -> {
            applied[0] = true;
            if (repository.header(units.commandId()).isPresent()) throw new IdempotencyConflictException();
            return applySynchronously(units).toJson();
        }));
        CombinedResult result = CombinedResult.fromJson(stored);
        if (applied[0]) record("sync", result, units.unitCount(), started);
        else meters.counter("mnema_deck_combined_publication_total", "path", "sync", "outcome", "replayed").increment();
        return new Applied(result, !applied[0]);
    }

    /**
     * Stores the command and enqueues its job (one transaction): the same command id with the same command is the same job, with another command a conflict. When a receipt of the
     * command exists already (the job finished) the stored result is answered at once, as {@link Applied}. A job that ended {@code FAILED} or {@code CANCELLED} is not restarted:
     * its command is gone, send a new plan with a new command id.
     */
    public Outcome enqueue(CombinedUnits units) {
        admit(units, false);
        DeckState deck = own(units);
        forkGuard.requireReady(units.actorId(), units.deckId());
        CommandIdentity identity = identity(units);
        ObjectNode envelope = units.envelope();
        byte[] digest = canonical.hash(envelope).sha256();
        return transaction.execute(status -> {
            // the replay is looked up in the transaction that stores the command, so a job that finished a moment ago is found here; the end of this method covers the
            // moment between this lookup and the insert
            Optional<JsonNode> replay = receipts.replay(identity, envelope);
            if (replay.isPresent()) {
                meters.counter("mnema_deck_combined_publication_total", "path", "job", "outcome", "replayed").increment();
                return new Applied(CombinedResult.fromJson(replay.get()), true);
            }
            requireCopy(deck);
            if (!deck.revisionId().equals(units.expectedHeadRevisionId())) throw new VersionConflictException();
            // commands that no live job holds any more (a cancelled queued job runs no hook) are removed on the way, a few at a time
            int purged = repository.purgeOrphans(PURGE_ON_ENQUEUE);
            if (purged > 0) meters.counter("mnema_deck_combined_publication_total", "path", "job", "outcome", "purged").increment(purged);
            return store(units, digest);
        });
    }

    /**
     * Stores the command and enqueues its job in the current transaction; answers the receipt's result when the job has already finished (a lost race with the end of an identical
     * command) and removes the stored copy of a command whose job ended without applying it.
     */
    Outcome store(CombinedUnits units, byte[] digest) {
        String finalWrites = units.finalWrites() == null ? null : JSON.writeValueAsString(units.finalWrites().toJson());
        if (repository.insertHeader(units, digest, units.items().size() + units.exercises().size(), finalWrites)) {
            repository.insertUnits(units.commandId(), units.rows());
        } else {
            CombinedPublicationRepository.Header stored = repository.header(units.commandId()).orElseThrow(IdempotencyConflictException::new);
            if (!Arrays.equals(stored.digest(), digest) || !stored.actorId().equals(units.actorId())) throw new IdempotencyConflictException();
        }
        ObjectNode payload = JSON.createObjectNode().put("commandId", units.commandId().toString()).put("deckId", units.deckId().toString())
                .put("digest", hex(digest));
        UUID job;
        try {
            job = jobs.enqueue(QUEUE, units.commandId().toString(), units.actorId(), payload);
        } catch (JobIdentityConflictException conflict) {
            throw new IdempotencyConflictException();
        }
        Optional<JobStatus> state = jobs.status(job);
        if (state.isPresent() && state.get().state().terminal()) {
            // the job that holds this command id has ended: it took (or should have taken) its command with it, so do not leave the copy just written behind
            repository.deleteCommand(units.commandId());
            if (state.get().state() == JobState.SUCCEEDED) {
                Optional<JsonNode> applied = receipts.replay(identity(units), units.envelope());
                if (applied.isPresent()) return new Applied(CombinedResult.fromJson(applied.get()), true);
            }
        }
        return new Queued(job);
    }

    /** Removes the stored commands of up to {@code limit} orphans (no live job holds them); returns how many were removed. Also run on a timer by {@link CombinedPublicationPurge}. */
    public int purgeOrphans(int limit) {
        Integer purged = transaction.execute(status -> repository.purgeOrphans(limit));
        int count = purged == null ? 0 : purged;
        if (count > 0) meters.counter("mnema_deck_combined_publication_total", "path", "job", "outcome", "purged").increment(count);
        return count;
    }

    /** {@link #publish} for a command within the synchronous limit, {@link #enqueue} for a bigger one. */
    public Outcome submit(CombinedUnits units) {
        return units.unitCount() <= settings.syncMaxUnits ? publish(units) : enqueue(units);
    }

    // ---- the synchronous transaction ----

    private CombinedResult applySynchronously(CombinedUnits units) {
        DeckState deck = repository.deck(units.actorId(), units.deckId()).orElseThrow(ResourceNotFoundException::new);
        requireCopy(deck);
        if (!deck.revisionId().equals(units.expectedHeadRevisionId())) throw new VersionConflictException();
        Plan plan = plan(deck, units);
        if (plan.empty()) return complete(units, deck, plan, null, null, List.of());
        ManifestEditor editor = editor(deck);
        stageItems(editor, plan.items());
        stageExercises(editor, plan.exercises());
        List<StagedRoot> pins = editor.flush(settings.stagingLease);
        return complete(units, deck, plan, editor, pins, List.of());
    }

    private Plan plan(DeckState deck, CombinedUnits units) {
        boolean metadata = units.metadata() != null
                && (!units.metadata().title().equals(deck.title()) || !units.metadata().description().equals(deck.description()));
        return new Plan(resolveItems(deck, units.items(), true), resolveExercises(deck, units.exercises()), resolveObjectives(deck, units.objectives()), metadata);
    }

    private ManifestEditor editor(DeckState deck) {
        return new ManifestEditor(storage, deck.scopeId(), deck.ownerId(),
                ManifestEditor.root(storage, deck.scopeId(), deck.membersRootId(), deck.memberCount(), "members"),
                ManifestEditor.root(storage, deck.scopeId(), deck.exercisesRootId(), deck.exerciseCount(), "exercises"));
    }

    // ---- resolution: what each operation means against the heads and the lineage ----

    private List<ItemStep> resolveItems(DeckState deck, List<ItemOp> ops, boolean whole) {
        if (ops.isEmpty()) return List.of();
        Set<UUID> keys = new LinkedHashSet<>();
        Set<UUID> inserted = new HashSet<>();
        Set<Ref> adopted = new LinkedHashSet<>();
        for (ItemOp op : ops) {
            keys.add(op.memberKey());
            switch (op) {
                case ItemOp.AdoptReplace replace -> adopted.add(new Ref(replace.memberKey(), replace.revisionId()));
                case ItemOp.AdoptInsert insert -> {
                    adopted.add(new Ref(insert.memberKey(), insert.revisionId()));
                    inserted.add(insert.memberKey());
                }
                case ItemOp.Remove ignored -> { }
                case ItemOp.Move ignored -> { }
            }
        }
        Map<UUID, Head> heads = repository.itemHeads(deck.deckId(), keys);
        Map<Ref, Revision> revisions = repository.itemRevisions(deck, adopted);
        List<ItemStep> steps = new ArrayList<>();
        for (ItemOp op : ops) {
            Head head = heads.get(op.memberKey());
            switch (op) {
                case ItemOp.AdoptReplace replace -> {
                    if (head == null) throw new VersionConflictException();
                    Revision target = target(revisions, replace.memberKey(), replace.revisionId());
                    if (!head.revisionId().equals(replace.revisionId())) steps.add(new ItemStep(op, head, target));
                }
                case ItemOp.AdoptInsert insert -> {
                    if (head != null) throw new VersionConflictException();
                    steps.add(new ItemStep(op, null, target(revisions, insert.memberKey(), insert.revisionId())));
                }
                case ItemOp.Remove ignored -> {
                    if (head == null) throw new VersionConflictException();
                    steps.add(new ItemStep(op, head, null));
                }
                case ItemOp.Move move -> {
                    // a member moved after it was inserted by this very command has no head yet; a slice cannot know, the whole command checks
                    if (head == null && whole && !inserted.contains(move.memberKey())) throw new VersionConflictException();
                    if (move.fromOrdinal() != move.toOrdinal()) steps.add(new ItemStep(op, head, null));
                }
            }
        }
        return steps;
    }

    private List<ExerciseStep> resolveExercises(DeckState deck, List<ExerciseOp> ops) {
        if (ops.isEmpty()) return List.of();
        Set<UUID> keys = new LinkedHashSet<>();
        Set<Ref> adopted = new LinkedHashSet<>();
        for (ExerciseOp op : ops) {
            keys.add(op.exerciseId());
            switch (op) {
                case ExerciseOp.AdoptReplace replace -> adopted.add(new Ref(replace.exerciseId(), replace.revisionId()));
                case ExerciseOp.AdoptInsert insert -> adopted.add(new Ref(insert.exerciseId(), insert.revisionId()));
                case ExerciseOp.Remove ignored -> { }
            }
        }
        Map<UUID, Head> heads = repository.exerciseHeads(deck.deckId(), keys);
        Map<Ref, Revision> revisions = repository.exerciseRevisions(deck, adopted);
        List<ExerciseStep> steps = new ArrayList<>();
        for (ExerciseOp op : ops) {
            Head head = heads.get(op.exerciseId());
            switch (op) {
                case ExerciseOp.AdoptReplace replace -> {
                    if (head == null) throw new VersionConflictException();
                    Revision target = target(revisions, replace.exerciseId(), replace.revisionId());
                    if (!head.revisionId().equals(replace.revisionId())) steps.add(new ExerciseStep(op, head, target));
                }
                case ExerciseOp.AdoptInsert insert -> {
                    if (head != null) throw new VersionConflictException();
                    steps.add(new ExerciseStep(op, null, target(revisions, insert.exerciseId(), insert.revisionId())));
                }
                case ExerciseOp.Remove ignored -> {
                    if (head == null) throw new VersionConflictException();
                    steps.add(new ExerciseStep(op, head, null));
                }
            }
        }
        return steps;
    }

    private List<ObjectiveStep> resolveObjectives(DeckState deck, List<ObjectiveOp> ops) {
        if (ops.isEmpty()) return List.of();
        Set<UUID> keys = new LinkedHashSet<>();
        Set<Ref> adopted = new LinkedHashSet<>();
        for (ObjectiveOp op : ops) {
            keys.add(op.objectiveId());
            switch (op) {
                case ObjectiveOp.Adopt adopt -> adopted.add(new Ref(adopt.objectiveId(), adopt.revisionId()));
            }
        }
        Map<UUID, Head> heads = repository.objectiveHeads(deck.deckId(), keys);
        Map<Ref, Revision> revisions = repository.objectiveRevisions(deck, adopted);
        List<ObjectiveStep> steps = new ArrayList<>();
        for (ObjectiveOp op : ops) {
            switch (op) {
                case ObjectiveOp.Adopt adopt -> {
                    target(revisions, adopt.objectiveId(), adopt.revisionId());
                    Head head = heads.get(adopt.objectiveId());
                    if (head == null || !head.revisionId().equals(adopt.revisionId())) steps.add(new ObjectiveStep(adopt));
                }
            }
        }
        return steps;
    }

    /**
     * The lineage revision an adoption names. One that does not exist for that key, or that neither the deck (its head, its journal) nor its upstream can see, is the opaque
     * 404 of a foreign resource, so another copy's revision of the same lineage is indistinguishable from a made-up id. A taken-down one is the takedown's 410.
     */
    private static Revision target(Map<Ref, Revision> revisions, UUID key, UUID revision) {
        Revision found = revisions.get(new Ref(key, revision));
        if (found == null || !found.visible()) throw new ResourceNotFoundException();
        if (found.blocked()) throw new ContentUnavailableException();
        return found;
    }

    /** Only a materialized, attached copy adopts revisions: the revisions it may take are those of its upstream and its own. */
    private static void requireCopy(DeckState deck) {
        if (deck.fork() == null) throw InvalidRequestException.because("not_a_copy");
        if (deck.fork().detached()) throw InvalidRequestException.because("detached");
        if (!deck.fork().materialized()) throw new app.mnema.learning.fork.DeckPreparingException();
    }

    // ---- staging: the operations on the two manifests ----

    private static void stageItems(ManifestEditor editor, List<ItemStep> steps) {
        for (ItemStep step : steps) {
            switch (step.op()) {
                case ItemOp.AdoptReplace replace -> editor.replaceMember(replace.ordinal(), replace.memberKey(), step.target().descriptorRootId());
                case ItemOp.AdoptInsert insert -> editor.insertMember(insert.ordinal(), insert.memberKey(), step.target().descriptorRootId());
                case ItemOp.Remove remove -> editor.deleteMember(remove.ordinal(), remove.memberKey());
                case ItemOp.Move move -> editor.moveMember(move.fromOrdinal(), move.toOrdinal(), move.memberKey());
            }
        }
    }

    private static void stageExercises(ManifestEditor editor, List<ExerciseStep> steps) {
        for (ExerciseStep step : steps) {
            switch (step.op()) {
                case ExerciseOp.AdoptReplace replace -> editor.replaceExercise(replace.ordinal(), replace.exerciseId(), step.target().descriptorRootId());
                case ExerciseOp.AdoptInsert insert -> editor.insertExercise(insert.ordinal(), insert.exerciseId(), step.target().descriptorRootId());
                case ExerciseOp.Remove remove -> editor.deleteExercise(remove.ordinal(), remove.exerciseId());
            }
        }
    }

    // ---- the final transaction: heads, journal, revision, hook ----

    /**
     * Writes everything of the result in the current transaction: the compare-and-set advance of the deck, the heads, the journal, the verification, the durable pins and the new
     * revision, the {@link FinalWriter} hook. With nothing to change it creates no revision (only the hook runs). The staging pins in {@code rootPins} (members, exercises) become
     * the revision's durable pins; they and {@code otherPins} are released.
     */
    private CombinedResult complete(CombinedUnits units, DeckState deck, Plan plan, ManifestEditor editor, List<StagedRoot> rootPins, List<UUID> otherPins) {
        List<StagedRoot> pins = rootPins == null ? List.of() : rootPins;
        if (plan.empty()) {
            releasePins(deck.scopeId(), pins, otherPins);
            CombinedResult unchanged = new CombinedResult(units.commandId(), units.deckId(), deck.revisionId(), deck.version(), false, 0, 0, 0, false);
            hook(units, deck, unchanged);
            return unchanged;
        }
        TreeRoot members = editor.members();
        TreeRoot exercises = editor.exercises();
        UUID revision = UUID.randomUUID();
        long sequence = cas.updateOne(deck.version(), () -> repository.advance(deck.ownerId(), deck.deckId(), revision, deck.version()));
        Instant time = repository.now();
        UUID scope = deck.scopeId();
        UUID id = deck.deckId();

        // materials
        OrdinalTracker itemPositions = new OrdinalTracker();
        List<HeadWrite> replaced = new ArrayList<>();
        List<HeadWrite> inserted = new ArrayList<>();
        List<UUID> removed = new ArrayList<>();
        for (ItemStep step : plan.items()) {
            switch (step.op()) {
                case ItemOp.AdoptReplace replace -> {
                    itemPositions.touch(replace.memberKey(), replace.ordinal());
                    replaced.add(new HeadWrite(replace.memberKey(), replace.revisionId(), 0));
                }
                case ItemOp.AdoptInsert insert -> {
                    itemPositions.inserted(insert.memberKey(), insert.ordinal());
                    inserted.add(new HeadWrite(insert.memberKey(), insert.revisionId(), 0));
                }
                case ItemOp.Remove remove -> {
                    itemPositions.removed(remove.memberKey(), remove.ordinal());
                    removed.add(remove.memberKey());
                }
                case ItemOp.Move move -> itemPositions.moved(move.memberKey(), move.fromOrdinal(), move.toOrdinal());
            }
        }
        List<ItemChange> itemJournal = itemJournal(plan.items(), itemPositions);
        if (repository.updateItemHeads(id, scope, replaced, time) != replaced.size()
                || repository.insertItemHeads(id, scope, inserted, time) != inserted.size()
                || repository.deleteItemHeads(id, removed) != removed.size()) throw new VersionConflictException();
        int memberCount = deck.memberCount() + inserted.size() - removed.size();

        // exercises
        OrdinalTracker exercisePositions = new OrdinalTracker();
        for (ExerciseStep step : plan.exercises()) {
            switch (step.op()) {
                case ExerciseOp.AdoptReplace replace -> exercisePositions.touch(replace.exerciseId(), replace.ordinal());
                case ExerciseOp.AdoptInsert insert -> exercisePositions.inserted(insert.exerciseId(), insert.ordinal());
                case ExerciseOp.Remove remove -> exercisePositions.removed(remove.exerciseId(), remove.ordinal());
            }
        }
        List<HeadWrite> exerciseReplaced = new ArrayList<>();
        List<HeadWrite> exerciseInserted = new ArrayList<>();
        List<UUID> exerciseRemoved = new ArrayList<>();
        List<ExerciseChange> exerciseJournal = new ArrayList<>();
        List<Ref> adoptedExercises = new ArrayList<>();
        for (ExerciseStep step : plan.exercises()) {
            switch (step.op()) {
                case ExerciseOp.AdoptReplace replace -> {
                    int ordinal = exercisePositions.finalOrdinal(replace.exerciseId());
                    exerciseReplaced.add(new HeadWrite(replace.exerciseId(), replace.revisionId(), ordinal));
                    exerciseJournal.add(new ExerciseChange(replace.exerciseId(), step.head().revisionId(), replace.revisionId(), ordinal));
                    adoptedExercises.add(new Ref(replace.exerciseId(), replace.revisionId()));
                }
                case ExerciseOp.AdoptInsert insert -> {
                    int ordinal = exercisePositions.finalOrdinal(insert.exerciseId());
                    exerciseInserted.add(new HeadWrite(insert.exerciseId(), insert.revisionId(), ordinal));
                    exerciseJournal.add(new ExerciseChange(insert.exerciseId(), null, insert.revisionId(), ordinal));
                    adoptedExercises.add(new Ref(insert.exerciseId(), insert.revisionId()));
                }
                case ExerciseOp.Remove remove -> {
                    exerciseRemoved.add(remove.exerciseId());
                    exerciseJournal.add(new ExerciseChange(remove.exerciseId(), step.head().revisionId(), null, remove.ordinal()));
                }
            }
        }
        int exerciseCount = deck.exerciseCount() + exerciseInserted.size() - exerciseRemoved.size();
        if (repository.deleteExerciseHeads(id, exerciseRemoved) != exerciseRemoved.size()
                || repository.updateExerciseHeads(id, scope, exerciseReplaced, time) != exerciseReplaced.size()
                || repository.insertExerciseHeads(id, scope, exerciseInserted, time) != exerciseInserted.size()) throw new VersionConflictException();
        if (!exerciseInserted.isEmpty() || !exerciseRemoved.isEmpty()) {
            List<UUID> touched = new ArrayList<>();
            List<Integer> taken = new ArrayList<>();
            for (HeadWrite write : exerciseReplaced) { touched.add(write.key()); taken.add(write.ordinal()); }
            for (HeadWrite write : exerciseInserted) { touched.add(write.key()); taken.add(write.ordinal()); }
            repository.reassignExerciseOrdinals(id, touched, taken, exerciseCount);
        }

        // objectives
        List<HeadWrite> objectiveWrites = new ArrayList<>();
        for (ObjectiveStep step : plan.objectives()) objectiveWrites.add(new HeadWrite(step.op().objectiveId(), step.op().revisionId(), 0));
        if (repository.upsertObjectiveHeads(id, scope, objectiveWrites, time) != objectiveWrites.size()) throw new InvalidRequestException();

        verify(deck, members, exercises, memberCount, exerciseCount, adoptedExercises, removed, plan.objectives());

        // the revision
        PinOwner owner = new PinOwner("deck.revision", revision, deck.ownerId());
        UUID membersPin = storage.retain(pins.get(0), owner);
        UUID exercisesPin = storage.retain(pins.get(1), owner);
        String title = units.metadata() == null ? deck.title() : units.metadata().title();
        String description = units.metadata() == null ? deck.description() : units.metadata().description();
        repository.insertDeckRevision(deck, revision, units.commandId(), time, title, description, members.ref().objectId(), membersPin, memberCount,
                exercises.ref().objectId(), exercisesPin, exerciseCount);
        repository.insertItemChanges(id, scope, revision, sequence, itemJournal);
        repository.insertExerciseChanges(id, scope, revision, sequence, exerciseJournal);
        releasePins(scope, pins, otherPins);

        CombinedResult result = new CombinedResult(units.commandId(), id, revision, sequence, true, itemJournal.size(), exerciseJournal.size(),
                objectiveWrites.size(), plan.metadata());
        hook(units, deck, result);
        return result;
    }

    /** The item journal: one row per member and revision, in operation order; a move of a member the command also adopted is folded into the adoption's row. */
    private static List<ItemChange> itemJournal(List<ItemStep> steps, OrdinalTracker positions) {
        Set<UUID> adopted = new HashSet<>();
        List<ItemChange> rows = new ArrayList<>();
        for (ItemStep step : steps) {
            UUID member = step.op().memberKey();
            switch (step.op()) {
                case ItemOp.AdoptReplace replace -> {
                    adopted.add(member);
                    rows.add(new ItemChange(rows.size(), member, "save", step.head().revisionId(), replace.revisionId(), replace.ordinal(), positions.finalOrdinal(member)));
                }
                case ItemOp.AdoptInsert insert -> {
                    adopted.add(member);
                    rows.add(new ItemChange(rows.size(), member, "create", null, insert.revisionId(), null, positions.finalOrdinal(member)));
                }
                case ItemOp.Remove remove -> rows.add(new ItemChange(rows.size(), member, "delete", step.head().revisionId(), null, remove.ordinal(), null));
                case ItemOp.Move move -> {
                    if (!adopted.contains(member)) {
                        rows.add(new ItemChange(rows.size(), member, "reorder", step.head().revisionId(), null, move.fromOrdinal(), positions.finalOrdinal(member)));
                    }
                }
            }
        }
        return rows;
    }

    /** The result is proven before it is stored: any disagreement rolls the whole command back. */
    private void verify(DeckState deck, TreeRoot members, TreeRoot exercises, int memberCount, int exerciseCount, List<Ref> adoptedExercises, List<UUID> removedMembers,
                        List<ObjectiveStep> objectiveSteps) {
        if (members.count() != memberCount || exercises.count() != exerciseCount) throw new IllegalStateException("Combined publication counts disagree with the manifests");
        if (repository.itemHeadCount(deck.deckId()) != memberCount) throw new IllegalStateException("Combined publication item heads disagree with the manifest");
        CombinedPublicationRepository.Ordinals ordinals = repository.exerciseOrdinals(deck.deckId());
        boolean contiguous = ordinals.count() == exerciseCount && (exerciseCount == 0 || (ordinals.min() == 0 && ordinals.max() == exerciseCount - 1));
        if (!contiguous) throw new IllegalStateException("Combined publication exercise ordinals disagree with the manifest");
        // a presentation must never quote a non-member: an adopted exercise names only materials of the resulting heads, and no head exercise (untouched ones included)
        // keeps a material the command removed
        if (repository.bindingsOutsideTheHeads(deck.deckId(), deck.scopeId(), adoptedExercises) != 0
                || repository.headExercisesBoundTo(deck.deckId(), deck.scopeId(), removedMembers) != 0) throw new InvalidRequestException();
        // the objective heads the command touched (adopted, or pinned by an adopted exercise) are what the materializer would have written for this set of head exercises
        Set<UUID> touched = new LinkedHashSet<>();
        objectiveSteps.forEach(step -> touched.add(step.op().objectiveId()));
        touched.addAll(repository.assessedObjectivesOf(deck.scopeId(), adoptedExercises));
        if (repository.objectivesOffTheRule(deck.deckId(), touched) != 0) throw new InvalidRequestException();
    }

    private void hook(CombinedUnits units, DeckState deck, CombinedResult result) {
        if (units.finalWrites() == null) return;
        writerFor(units.finalWrites().type()).write(units.finalWrites(), new FinalWriter.Context(deck.deckId(), units.actorId(), units.commandId(), deck.revisionId(),
                result.deckRevisionId(), result.deckVersion(), result.changed()));
    }

    /**
     * The writer of this type of final writes: with no {@link FinalWriter} bean at all the writes are dropped (a bare engine), otherwise exactly one bean must handle the type, so that
     * a type nobody handles is a loud failure and never a silent loss of what the command asked to be written with its revision.
     */
    private FinalWriter writerFor(String type) {
        List<FinalWriter> all = allWriters;
        if (all == null) {
            all = writers.orderedStream().toList();
            allWriters = all;
        }
        if (all.isEmpty()) return FinalWriter.NONE;
        List<FinalWriter> handling = all.stream().filter(candidate -> candidate.handles(type)).toList();
        if (handling.size() != 1) throw new IllegalStateException(handling.isEmpty() ? "No FinalWriter handles the type of the final writes" : "More than one FinalWriter handles the type of the final writes");
        return handling.getFirst();
    }

    private void releasePins(UUID scope, List<StagedRoot> pins, List<UUID> otherPins) {
        pins.forEach(pin -> storage.release(scope, pin.stagingPinId()));
        otherPins.forEach(pin -> storage.release(scope, pin));
    }

    // ---- the job ----

    /** One slice of the job {@value #QUEUE}: at most {@code slice-size} page edits on the staged roots, and, in the slice that finishes them, the final transaction. */
    Slice slice(Job job) {
        try {
            return sliceBody(job);
        } catch (ContentUnavailableException failure) {
            throw new PermanentJobException("taken_down");
        } catch (ResourceNotFoundException failure) {
            throw new PermanentJobException("not_found");
        } catch (InvalidRequestException | ResourceLimitExceededException | IdempotencyConflictException failure) {
            throw new PermanentJobException("plan_invalid");
        } catch (app.mnema.learning.fork.DeckPreparingException failure) {
            throw new PermanentJobException("deck_preparing");
        } catch (VersionConflictException failure) {
            // a conflict is either the deck that moved on (the compare-and-set lost to a writer between the check and the advance) or a plan that does not describe it;
            // neither is cured by trying again
            throw new PermanentJobException(headMoved(job) ? "stale_head" : "plan_invalid");
        }
    }

    private boolean headMoved(Job job) {
        UUID command = uuidOrNull(job.payload().path("commandId"));
        if (command == null) return false;
        return repository.header(command).map(header -> repository.deck(header.actorId(), header.deckId())
                .map(deck -> !deck.revisionId().equals(header.expectedHeadRevisionId())).orElse(true)).orElse(false);
    }

    private Slice sliceBody(Job job) {
        UUID command = uuid(job.payload(), "commandId");
        CombinedPublicationRepository.Header header = repository.header(command).orElseThrow(() -> new PermanentJobException("command_missing"));
        DeckState deck = repository.deck(header.actorId(), header.deckId()).orElseThrow(() -> new PermanentJobException("deck_gone"));
        if (forkGuard.preparing(header.actorId(), header.deckId())) throw new PermanentJobException("deck_preparing");
        requireCopy(deck);
        // a deck that moved on since the plan was built: nothing written, the plan has to be built again
        if (!deck.revisionId().equals(header.expectedHeadRevisionId())) throw new PermanentJobException("stale_head");

        Cursor cursor = Cursor.read(job.progress());
        // The staged roots of the cursor must still be stored. If they are gone (the staging lease ran out while the job waited, and the pages were collected) the pages
        // are only the cache of a plan the stored command can rebuild: start the staging again from the deck. A missing page is checked, never read: a failed read
        // would doom the slice's transaction even when caught.
        if (cursor != null && repository.sealedObjects(deck.scopeId(), List.of(cursor.members().ref().objectId(), cursor.exercises().ref().objectId())) != 2) cursor = null;
        ManifestEditor editor = cursor == null ? editor(deck) : new ManifestEditor(storage, deck.scopeId(), deck.ownerId(),
                ManifestEditor.root(storage, deck.scopeId(), cursor.members().ref().objectId(), cursor.members().count(), "members"),
                ManifestEditor.root(storage, deck.scopeId(), cursor.exercises().ref().objectId(), cursor.exercises().count(), "exercises"));
        int next = cursor == null ? 0 : cursor.next();
        List<UUID> oldPins = cursor == null ? List.of() : cursor.pins();
        if (next < 0 || next > header.manifestUnitCount()) throw new PermanentJobException("cursor_invalid");
        int end = Math.min(header.manifestUnitCount(), next + settings.sliceSize);
        List<CombinedUnits.Row> rows = repository.units(command, next, end);
        List<ItemOp> itemOps = new ArrayList<>();
        List<ExerciseOp> exerciseOps = new ArrayList<>();
        for (CombinedUnits.Row row : rows) {
            if (row.kind().startsWith("ITEM_")) itemOps.add(CombinedUnits.itemOp(row));
            else exerciseOps.add(CombinedUnits.exerciseOp(row));
        }
        stageItems(editor, resolveItems(deck, itemOps, false));
        stageExercises(editor, resolveExercises(deck, exerciseOps));
        List<StagedRoot> pins = editor.flush(settings.stagingLease);
        oldPins.forEach(pin -> storage.release(deck.scopeId(), pin));

        if (end < header.manifestUnitCount()) {
            return Slice.proceed(new Cursor(end, editor.members(), editor.exercises(), pins.stream().map(StagedRoot::stagingPinId).toList()).write(deck.scopeId()), end);
        }
        // the last slice: the whole command, resolved afresh against the heads, in the final transaction
        CombinedUnits units = restore(header);
        Plan plan = plan(deck, units);
        CommandIdentity identity = identity(units);
        long started = System.nanoTime();
        ManifestEditor staged = editor;
        JsonNode stored = receipts.execute(identity, units.envelope(), () -> complete(units, deck, plan, staged, pins, List.of()).toJson());
        repository.deleteCommand(command);
        record("job", CombinedResult.fromJson(stored), units.unitCount(), started);
        return Slice.done(header.unitCount());
    }

    private CombinedUnits restore(CombinedPublicationRepository.Header header) {
        List<CombinedUnits.Row> rows = new ArrayList<>();
        int total = header.unitCount() - (header.metadata() == null ? 0 : 1);
        for (int at = 0; at < total; at += UNIT_READ) rows.addAll(repository.units(header.commandId(), at, Math.min(total, at + UNIT_READ)));
        return CombinedUnits.restore(header.commandId(), header.deckId(), header.actorId(), header.expectedHeadRevisionId(),
                header.metadata() == null ? null : JSON.readTree(header.metadata()),
                repository.finalWrites(header.commandId()).map(text -> JSON.readTree(text)).orElse(null), rows);
    }

    /** The job ended without applying the command ({@code FAILED} or {@code CANCELLED}): its staged pins and its stored command go with it, in the same transaction. */
    void ended(Job job, JobState state) {
        UUID command = uuidOrNull(job.payload().path("commandId"));
        UUID scope = job.progress() == null ? null : uuidOrNull(job.progress().path("scope"));
        if (scope != null) {
            for (JsonNode pin : job.progress().path("pins")) {
                UUID id = uuidOrNull(pin);
                if (id != null) storage.release(scope, id);
            }
        }
        if (command != null) repository.deleteCommand(command);
        meters.counter("mnema_deck_combined_publication_total", "path", "job", "outcome", state.name().toLowerCase(Locale.ROOT)).increment();
        LOG.warn("deck_combined_publication_ended job_id={} command_id={} state={} progress_count={}", job.id(), command, state, job.progressCount());
    }

    // ---- small things ----

    private void admit(CombinedUnits units, boolean synchronous) {
        if (units.unitCount() > settings.maxUnits) throw new ResourceLimitExceededException(ProblemExtension.limit(settings.maxUnits));
        if (synchronous && units.unitCount() > settings.syncMaxUnits) throw InvalidRequestException.because("units_limit_sync");
    }

    private DeckState own(CombinedUnits units) {
        return repository.deck(units.actorId(), units.deckId()).orElseThrow(ResourceNotFoundException::new);
    }

    private static CommandIdentity identity(CombinedUnits units) {
        return new CommandIdentity(units.commandId(), units.actorId(), SCOPE, TYPE);
    }

    private void record(String path, CombinedResult result, int units, long startedNanos) {
        long nanos = System.nanoTime() - startedNanos;
        long micros = nanos / 1_000;
        meters.counter("mnema_deck_combined_publication_total", "path", path, "outcome", result.changed() ? "applied" : "unchanged").increment();
        meters.timer("mnema_deck_combined_publication_seconds", "path", path).record(Duration.ofNanos(nanos));
        LOG.info("deck_combined_publication path={} deck_id={} command_id={} units={} changed={} items={} exercises={} objectives={} micros={}", path, result.deckId(),
                result.commandId(), units, result.changed(), result.itemsChanged(), result.exercisesChanged(), result.objectivesChanged(), micros);
    }

    private static UUID uuid(ObjectNode node, String field) {
        UUID found = uuidOrNull(node.path(field));
        if (found == null) throw new PermanentJobException("payload_invalid");
        return found;
    }

    private static UUID uuidOrNull(JsonNode node) {
        try {
            return node.isString() ? UUID.fromString(node.stringValue(null)) : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
        return text.toString();
    }

    // ---- types ----

    private record ItemStep(ItemOp op, Head head, Revision target) { }

    private record ExerciseStep(ExerciseOp op, Head head, Revision target) { }

    private record ObjectiveStep(ObjectiveOp.Adopt op) { }

    private record Plan(List<ItemStep> items, List<ExerciseStep> exercises, List<ObjectiveStep> objectives, boolean metadata) {
        boolean empty() { return items.isEmpty() && exercises.isEmpty() && objectives.isEmpty() && !metadata; }
    }

    /** The cursor of the job: the next unit, the staged roots (with their counts) and the staging pins that keep the staged pages until the next slice. */
    private record Cursor(int next, TreeRoot members, TreeRoot exercises, List<UUID> pins) {
        static Cursor read(ObjectNode progress) {
            if (progress == null) return null;
            try {
                UUID scope = UUID.fromString(progress.path("scope").stringValue(""));
                List<UUID> pins = new ArrayList<>();
                for (JsonNode pin : progress.path("pins")) pins.add(UUID.fromString(pin.stringValue("")));
                return new Cursor(progress.path("next").intValue(-1), root(scope, progress.path("members")), root(scope, progress.path("exercises")), pins);
            } catch (IllegalArgumentException invalid) {
                throw new PermanentJobException("cursor_invalid");
            }
        }

        private static TreeRoot root(UUID scope, JsonNode node) {
            return new TreeRoot(new app.mnema.learning.storage.StorageTypes.ObjectRef(scope, UUID.fromString(node.path("root").stringValue(""))),
                    node.path("height").intValue(-1), node.path("count").intValue(-1));
        }

        ObjectNode write(UUID scope) {
            ObjectNode node = JSON.createObjectNode().put("scope", scope.toString()).put("next", next);
            node.putObject("members").put("root", members.ref().objectId().toString()).put("height", members.height()).put("count", members.count());
            node.putObject("exercises").put("root", exercises.ref().objectId().toString()).put("height", exercises.height()).put("count", exercises.count());
            ArrayNode ids = node.putArray("pins");
            pins.forEach(pin -> ids.add(pin.toString()));
            return node;
        }
    }
}
