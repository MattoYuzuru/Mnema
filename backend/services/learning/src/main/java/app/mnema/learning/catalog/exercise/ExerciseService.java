package app.mnema.learning.catalog.exercise;

import app.mnema.learning.catalog.content.pages.CountedPageTypes;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.Entry;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.PageEdit;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.Profile;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.TreeRoot;
import app.mnema.learning.catalog.content.pages.CountedPages;
import app.mnema.learning.catalog.content.NativeNodeIndex;
import app.mnema.learning.catalog.content.storage.NativeStorageBatches;
import app.mnema.learning.capability.LearningCapabilities;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.CompareAndSetExecutor;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.storage.StorageTypes.NewObject;
import app.mnema.learning.storage.StorageTypes.ObjectKind;
import app.mnema.learning.storage.StorageTypes.ObjectRef;
import app.mnema.learning.storage.StorageTypes.PinOwner;
import app.mnema.learning.storage.StorageTypes.StageBatch;
import app.mnema.learning.storage.StorageTypes.StagedRoot;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ExerciseService {
    private static final int MAX_EXERCISES = 100_000;
    private static final Duration PREPARATION_LEASE = Duration.ofMinutes(1);

    private final ExerciseRepository repository;
    private final CommandReceiptService receipts;
    private final CompareAndSetExecutor cas;
    private final ImmutableStorage storage;
    private final MediaCatalog mediaCatalog;
    private final NativeStorageBatches nativeBatches;
    private final LearningCapabilities capabilities;
    private final ExerciseNewMarks newMarks;
    private final TransactionTemplate publication;
    private final TransactionTemplate cleanup;

    public ExerciseService(ExerciseRepository repository, CommandReceiptService receipts, CompareAndSetExecutor cas,
                           ImmutableStorage storage, MediaCatalog mediaCatalog, LearningCapabilities capabilities,
                           ExerciseNewMarks newMarks, PlatformTransactionManager transactions) {
        this.repository = repository;
        this.receipts = receipts;
        this.cas = cas;
        this.storage = storage;
        this.mediaCatalog = mediaCatalog;
        this.nativeBatches = new NativeStorageBatches(storage);
        this.capabilities = capabilities;
        this.newMarks = newMarks;
        this.publication = new TransactionTemplate(transactions);
        publication.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        publication.setTimeout(10);
        this.cleanup = new TransactionTemplate(transactions);
        cleanup.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        cleanup.setTimeout(10);
    }

    @Transactional(readOnly = true, timeout = 10)
    public ObjectNode list(UUID actor, UUID deckId, String limit, String cursor) {
        return list(actor, deckId, null, limit, cursor);
    }

    @Transactional(readOnly = true, timeout = 10)
    public ObjectNode list(UUID actor, UUID deckId, UUID memberKey, String limit, String cursor) {
        ExerciseRepository.DeckHead deck = own(actor, deckId);
        ExerciseCursor position = ExerciseCursor.decode(cursor);
        if (position != null && !position.deckRevisionId().equals(deck.revisionId())) throw new VersionConflictException();
        int start = position == null ? 0 : position.nextOrdinal();
        if (start > deck.exerciseCount()) throw new InvalidRequestException();
        int size = ExerciseCursor.pageSize(limit);
        List<ExerciseRepository.ExerciseListRow> fetched = repository.page(actor, deckId, memberKey, start, size + 1);
        boolean more = fetched.size() > size;
        List<ExerciseRepository.ExerciseListRow> rows = more ? fetched.subList(0, size) : fetched;
        int total = memberKey == null ? deck.exerciseCount() : repository.count(actor, deckId, memberKey);
        ObjectNode result = JsonNodeFactory.instance.objectNode().put("deckId", deckId.toString())
                .put("deckRevisionId", deck.revisionId().toString()).put("deckVersion", Long.toString(deck.version()))
                .put("total", total);
        ArrayNode values = result.putArray("exercises");
        // «Новое»: a mark within the TTL, written by an approval of generated exercises and cleared when the exercise is opened or answered
        Set<UUID> fresh = newMarks.fresh(actor, deckId, rows.stream().map(row -> row.exercise().exerciseId()).toList());
        rows.forEach(row -> {
            ObjectNode entry = summary(row.exercise());
            entry.put("isNew", fresh.contains(row.exercise().exerciseId()));
            entry.set("objective", objective(row.objective()));
            values.add(entry);
        });
        if (more) result.put("nextCursor", new ExerciseCursor(deck.revisionId(), fetched.get(size).exercise().ordinal()).encode());
        else result.putNull("nextCursor");
        return result;
    }

    @Transactional(readOnly = true, timeout = 10)
    public ObjectNode read(UUID actor, UUID deckId, UUID exerciseId, UUID revisionId) {
        ExerciseRepository.DeckHead deck = own(actor, deckId);
        ExerciseRepository.ExerciseRow exercise = (revisionId == null
                ? repository.exerciseHead(actor, deckId, exerciseId)
                : repository.exerciseRevision(actor, deckId, exerciseId, revisionId))
                .orElseThrow(ResourceNotFoundException::new);
        ExerciseRepository.SubjectRow subject = repository.subject(deckId, exerciseId, exercise.revisionId())
                .orElseThrow(() -> new IllegalStateException("Exercise has no assessed binding"));
        ExerciseRepository.ObjectiveRow objective = repository.objectiveRevision(actor, deckId,
                subject.objectiveId(), subject.objectiveRevisionId()).orElseThrow(IllegalStateException::new);
        ObjectNode result = summary(exercise);
        result.set("objective", objective(objective));
        result.put("deckId", deckId.toString()).put("deckRevisionId", deck.revisionId().toString())
                .put("deckVersion", Long.toString(deck.version()));
        result.putObject("subject").put("memberKey", subject.memberKey().toString())
                .put("itemRevisionId", subject.itemRevisionId().toString());
        result.set("content", exercise.content().deepCopy());
        result.set("answerKey", exercise.answerKey().deepCopy());
        result.set("evaluatorPolicy", exercise.evaluator().deepCopy());
        return result;
    }

    /**
     * Clears the «Новое» mark of an exercise of the owner's deck: the editor calls it when the exercise is opened. Idempotent (an
     * exercise without a mark answers the same); a foreign deck or an exercise that is not on the roster is the opaque 404.
     */
    @Transactional(timeout = 10)
    public void clearNewMark(UUID actor, UUID deckId, UUID exerciseId) {
        own(actor, deckId);
        UuidPolicy.requireEntityId(exerciseId, "exerciseId");
        repository.exerciseHead(actor, deckId, exerciseId).orElseThrow(ResourceNotFoundException::new);
        newMarks.clear(actor, deckId, exerciseId);
    }

    /** Removes only the current exercise roster entry; pinned history and completed attempts remain valid. */
    public void delete(UUID actor, UUID deckId, UUID exerciseId, long expectedDeckVersion) {
        ExerciseRepository.DeckHead deck = own(actor, deckId);
        UuidPolicy.requireEntityId(exerciseId, "exerciseId");
        ExerciseRepository.ExerciseRow previous = repository.exerciseHead(actor, deckId, exerciseId)
                .orElseThrow(ResourceNotFoundException::new);
        if (deck.version() != expectedDeckVersion) throw new VersionConflictException();
        UUID nextRevision = UUID.randomUUID();
        List<StagedRoot> pins = new ArrayList<>();
        try {
            TreeRoot root = tree(deck.scopeId(), deck.exercisesRootId(), deck.exerciseCount());
            TreeRoot exercises = stagePage(exercisePages().delete(root, previous.ordinal(), exerciseId),
                    deck.scopeId(), actor, pins);
            StagedRoot members = storage.stageBatch(new StageBatch(deck.scopeId(), actor, List.of(),
                    List.of(deck.membersRootId())), PREPARATION_LEASE).getFirst();
            pins.add(members);
            publication.executeWithoutResult(ignored -> {
                ExerciseRepository.DeckHead current = own(actor, deckId);
                ExerciseRepository.ExerciseRow currentExercise = repository.exerciseHead(actor, deckId, exerciseId)
                        .orElseThrow(ResourceNotFoundException::new);
                if (!sameHead(current, deck) || !currentExercise.revisionId().equals(previous.revisionId())
                        || currentExercise.ordinal() != previous.ordinal()) throw new VersionConflictException();
                long sequence = cas.updateOne(expectedDeckVersion,
                        () -> repository.advance(actor, deckId, nextRevision, expectedDeckVersion));
                if (repository.deleteExerciseHead(deckId, exerciseId, previous.revisionId()) != 1) {
                    throw new VersionConflictException();
                }
                repository.shiftExerciseOrdinals(deckId, previous.ordinal());
                Instant now = repository.now();
                PinOwner owner = new PinOwner("deck.revision", nextRevision, actor);
                UUID membersPin = storage.retain(members, owner);
                UUID exercisesPin = storage.retain(findPin(pins, exercises.ref()), owner);
                repository.insertDeckRevision(deck, nextRevision, UUID.randomUUID(), now, membersPin,
                        exercises.ref().objectId(), exercisesPin, deck.exerciseCount() - 1);
                repository.insertRemoval(deckId, nextRevision, sequence, exerciseId, previous.revisionId(),
                        previous.ordinal());
            });
        } finally {
            cleanup.executeWithoutResult(ignored -> pins.forEach(pin -> storage.release(deck.scopeId(), pin.stagingPinId())));
        }
    }

    public WriteResult publish(UUID actor, UUID deckId, UUID pathExerciseId, long expectedDeckVersion,
                               ExerciseCommand command) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(deckId, "deckId");
        if (pathExerciseId != null) UuidPolicy.requireEntityId(pathExerciseId, "exerciseId");
        own(actor, deckId);
        CommandIdentity identity = new CommandIdentity(command.commandId(), actor, "deck.exercises", "exercise.publish");
        ObjectNode envelope = command.envelope(deckId, pathExerciseId, expectedDeckVersion);
        var replay = receipts.replay(identity, envelope);
        if (replay.isPresent()) return new WriteResult(replay.orElseThrow(), true);
        // Structural errors were already rejected as INVALID_REQUEST; a valid command that needs a
        // disabled or provider-less capability is a state conflict, never silently downgraded.
        if (command.exercise().requiresSemanticAssessment()) capabilities.requireAiAssessment();
        if (command.exercise().requiresSpeechToText()) capabilities.requireSpeechToText();
        Prepared prepared = prepare(actor, deckId, pathExerciseId, expectedDeckVersion, command);
        boolean[] applied = {false};
        try {
            JsonNode result = publication.execute(ignored -> receipts.execute(identity, envelope, () -> {
                applied[0] = true;
                return apply(actor, deckId, pathExerciseId, expectedDeckVersion, command, prepared);
            }));
            if (!applied[0]) cleanup(prepared, false);
            return new WriteResult(result, !applied[0]);
        } catch (RuntimeException failure) {
            try { cleanup(prepared, true); }
            catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    private Prepared prepare(UUID actor, UUID deckId, UUID pathExerciseId, long expectedDeckVersion,
                             ExerciseCommand command) {
        ExerciseRepository.DeckHead deck = own(actor, deckId);
        if (deck.version() != expectedDeckVersion || !deck.revisionId().equals(command.expectedDeckRevisionId())) {
            throw new VersionConflictException();
        }
        boolean create = pathExerciseId == null;
        if (create && command.expectedExerciseRevisionId() != null) throw new InvalidRequestException();
        if (!create && command.expectedExerciseRevisionId() == null) throw new InvalidRequestException();
        if (create && deck.exerciseCount() >= MAX_EXERCISES) throw new InvalidRequestException();

        ExerciseRepository.ExerciseRow previous = null;
        UUID exerciseId = pathExerciseId == null ? UUID.randomUUID() : pathExerciseId;
        int ordinal = deck.exerciseCount();
        long exerciseSequence = 0;
        if (!create) {
            previous = repository.exerciseHead(actor, deckId, exerciseId).orElseThrow(ResourceNotFoundException::new);
            if (!previous.revisionId().equals(command.expectedExerciseRevisionId())) throw new VersionConflictException();
            ordinal = previous.ordinal();
            exerciseSequence = previous.sequence() + 1;
        }

        ExerciseCommand.Exercise exercise = command.exercise();
        validateSubjectAndMaterials(actor, deckId, exercise);
        ObjectivePlan objective = prepareObjective(actor, deckId, command.objective(), exercise.subject().memberKey());

        UUID exerciseRevision = UUID.randomUUID();
        UUID deckRevision = UUID.randomUUID();
        List<StagedRoot> pins = new ArrayList<>();
        StagedRoot descriptor = stageDescriptor(deck.scopeId(), actor, exerciseId, exerciseRevision,
                command.exercise().type().name(), pins);
        CountedPages pages = exercisePages();
        TreeRoot root = tree(deck.scopeId(), deck.exercisesRootId(), deck.exerciseCount());
        PageEdit edit = create ? pages.insert(root, ordinal, new Entry(exerciseId, descriptor.root()))
                : pages.replace(root, ordinal, exerciseId, new Entry(exerciseId, descriptor.root()));
        TreeRoot exercises = stagePage(edit, deck.scopeId(), actor, pins);
        StagedRoot members = storage.stageBatch(new StageBatch(deck.scopeId(), actor, List.of(),
                List.of(deck.membersRootId())), PREPARATION_LEASE).getFirst();
        pins.add(members);
        return new Prepared(deck, deckRevision, exerciseId, exerciseRevision, exerciseSequence, ordinal, previous,
                objective, descriptor, exercises, List.copyOf(pins), members);
    }

    private ObjectivePlan prepareObjective(UUID actor, UUID deckId, ExerciseCommand.Objective command,
                                           UUID assessedMember) {
        return switch (command) {
            case ExerciseCommand.CreateObjective create -> new ObjectivePlan(UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), 0, null, assessedMember, create.title(), true, false);
            case ExerciseCommand.ReuseObjective reuse -> {
                ExerciseRepository.ObjectiveRow current = repository.objectiveHead(actor, deckId, reuse.objectiveId())
                        .orElseThrow(ResourceNotFoundException::new);
                if (!current.revisionId().equals(reuse.objectiveRevisionId())) throw new VersionConflictException();
                if (!current.memberKey().equals(assessedMember)) throw new InvalidRequestException();
                yield new ObjectivePlan(current.objectiveId(), current.objectiveKey(), current.revisionId(),
                        current.sequence(), current.revisionId(), current.memberKey(), current.title(), false, false);
            }
            case ExerciseCommand.ReviseObjective revise -> {
                ExerciseRepository.ObjectiveRow current = repository.objectiveHead(actor, deckId, revise.objectiveId())
                        .orElseThrow(ResourceNotFoundException::new);
                if (!current.revisionId().equals(revise.expectedObjectiveRevisionId())) throw new VersionConflictException();
                if (!current.memberKey().equals(assessedMember)) throw new InvalidRequestException();
                yield new ObjectivePlan(current.objectiveId(), current.objectiveKey(), UUID.randomUUID(),
                        current.sequence() + 1, current.revisionId(), current.memberKey(), revise.title(), false, true);
            }
        };
    }

    /**
     * The subject and every quoted material must be the current revision of a member of this deck (a foreign
     * or stale reference is an opaque 404). Quoted nodes must exist in the pinned revision, have a plain-text
     * projection and fit the limit of the slot that quotes them (an invalid reference is a 400).
     */
    private void validateSubjectAndMaterials(UUID actor, UUID deckId, ExerciseCommand.Exercise exercise) {
        ExerciseCommand.Subject subject = exercise.subject();
        itemRevision(actor, deckId, new ItemKey(subject.memberKey(), subject.itemRevisionId()));
        Map<ItemKey, NativeNodeIndex> indexes = new LinkedHashMap<>();
        for (Block.Material material : exercise.materials()) {
            ItemKey key = new ItemKey(material.memberKey(), material.itemRevisionId());
            NativeNodeIndex index = indexes.computeIfAbsent(key, ignored -> {
                ExerciseRepository.ItemRevision revision = itemRevision(actor, deckId, key);
                return NativeNodeIndex.load(nativeBatches, new ObjectRef(revision.scopeId(), revision.contentRootId()));
            });
            String text = index.text(material.nodeId()).orElseThrow(InvalidRequestException::new);
            if (text.isBlank() || text.length() > material.maxText()) throw new InvalidRequestException();
        }
    }

    private ExerciseRepository.ItemRevision itemRevision(UUID actor, UUID deckId, ItemKey key) {
        return repository.itemRevision(actor, deckId, key.member(), key.revision())
                .orElseThrow(ResourceNotFoundException::new);
    }

    private ObjectNode apply(UUID actor, UUID deckId, UUID pathExerciseId, long expectedDeckVersion,
                             ExerciseCommand command, Prepared prepared) {
        ExerciseRepository.DeckHead deck = own(actor, deckId);
        if (!sameHead(deck, prepared.deck()) || deck.version() != expectedDeckVersion
                || !deck.revisionId().equals(command.expectedDeckRevisionId())) throw new VersionConflictException();
        if (pathExerciseId != null) {
            ExerciseRepository.ExerciseRow current = repository.exerciseHead(actor, deckId, pathExerciseId)
                    .orElseThrow(ResourceNotFoundException::new);
            if (!current.revisionId().equals(command.expectedExerciseRevisionId())
                    || current.sequence() + 1 != prepared.exerciseSequence()) throw new VersionConflictException();
        }
        recheckObjective(actor, deckId, prepared.objective());
        long deckSequence = cas.updateOne(expectedDeckVersion,
                () -> repository.advance(actor, deckId, prepared.deckRevision(), expectedDeckVersion));
        Instant time = repository.now();
        ObjectivePlan objective = prepared.objective();
        if (objective.createIdentity()) {
            repository.insertObjective(deckId, objective.objectiveId(), objective.objectiveKey(), objective.memberKey(),
                    actor, deck.scopeId(), time);
            repository.insertObjectiveRevision(deckId, objective.objectiveId(), objective.revisionId(), 0, null,
                    prepared.deckRevision(), deckSequence, command.commandId(), objective.title(), time);
            repository.insertObjectiveHead(deckId, objective.objectiveId(), objective.revisionId(), 0, time);
        } else if (objective.createRevision()) {
            repository.insertObjectiveRevision(deckId, objective.objectiveId(), objective.revisionId(),
                    objective.sequence(), objective.parentRevisionId(), prepared.deckRevision(), deckSequence,
                    command.commandId(), objective.title(), time);
            repository.updateObjectiveHead(deckId, objective.objectiveId(), objective.revisionId(),
                    objective.sequence(), time);
        }

        if (pathExerciseId == null) {
            repository.insertExercise(deckId, prepared.exerciseId(), actor, deck.scopeId(), time);
        }
        repository.insertExerciseRevision(deckId, prepared.exerciseId(), prepared.exerciseRevision(), deck.scopeId(),
                prepared.exerciseSequence(), prepared.previous() == null ? null : prepared.previous().revisionId(),
                prepared.deckRevision(), deckSequence, command.commandId(), command.exercise(),
                prepared.descriptor().root().objectId(), time);
        List<MediaCatalog.ExerciseAsset> assets = command.exercise().assets();
        if (!assets.isEmpty()) {
            mediaCatalog.attachExerciseRevision(actor, deckId, prepared.exerciseId(), prepared.exerciseRevision(),
                    assets);
        }
        insertBindings(deckId, prepared, command.exercise(), objective);
        if (pathExerciseId == null) {
            repository.insertExerciseHead(deckId, prepared.exerciseId(), prepared.exerciseRevision(), 0,
                    prepared.ordinal(), time);
        } else {
            repository.updateExerciseHead(deckId, prepared.exerciseId(), prepared.exerciseRevision(),
                    prepared.exerciseSequence(), time);
        }

        PinOwner owner = new PinOwner("deck.revision", prepared.deckRevision(), actor);
        UUID membersPin = storage.retain(prepared.members(), owner);
        UUID exercisesPin = storage.retain(findPin(prepared.pins(), prepared.exercises().ref()), owner);
        repository.insertDeckRevision(deck, prepared.deckRevision(), command.commandId(), time, membersPin,
                prepared.exercises().ref().objectId(), exercisesPin,
                pathExerciseId == null ? deck.exerciseCount() + 1 : deck.exerciseCount());
        repository.insertChange(deckId, prepared.deckRevision(), deckSequence, prepared.exerciseId(),
                prepared.previous() == null ? null : prepared.previous().revisionId(), prepared.exerciseRevision(),
                prepared.ordinal());
        release(prepared);

        return JsonNodeFactory.instance.objectNode().put("commandId", command.commandId().toString())
                .put("deckId", deckId.toString()).put("deckRevisionId", prepared.deckRevision().toString())
                .put("deckVersion", Long.toString(deckSequence)).put("objectiveId", objective.objectiveId().toString())
                .put("objectiveKey", objective.objectiveKey().toString())
                .put("objectiveRevisionId", objective.revisionId().toString())
                .put("exerciseId", prepared.exerciseId().toString())
                .put("exerciseRevisionId", prepared.exerciseRevision().toString())
                .put("enabled", command.exercise().enabled());
    }

    /** The ASSESSED subject plus one CONTEXT row per distinct quoted material revision, with its node IDs. */
    private void insertBindings(UUID deckId, Prepared prepared, ExerciseCommand.Exercise exercise,
                                ObjectivePlan objective) {
        ExerciseCommand.Subject subject = exercise.subject();
        int ordinal = 0;
        repository.insertBinding(deckId, prepared.exerciseId(), prepared.exerciseRevision(),
                new ExerciseRepository.BindingInsert(UUID.randomUUID(), ordinal++, "ASSESSED", subject.memberKey(),
                        subject.itemRevisionId(), List.of(), objective.objectiveId(), objective.revisionId()));
        Map<ItemKey, Set<UUID>> context = new LinkedHashMap<>();
        for (Block.Material material : exercise.materials()) {
            context.computeIfAbsent(new ItemKey(material.memberKey(), material.itemRevisionId()),
                    ignored -> new LinkedHashSet<>()).add(material.nodeId());
        }
        for (var entry : context.entrySet()) {
            repository.insertBinding(deckId, prepared.exerciseId(), prepared.exerciseRevision(),
                    new ExerciseRepository.BindingInsert(UUID.randomUUID(), ordinal++, "CONTEXT",
                            entry.getKey().member(), entry.getKey().revision(), List.copyOf(entry.getValue()), null, null));
        }
    }

    private void recheckObjective(UUID actor, UUID deckId, ObjectivePlan plan) {
        if (plan.createIdentity()) return;
        ExerciseRepository.ObjectiveRow current = repository.objectiveHead(actor, deckId, plan.objectiveId())
                .orElseThrow(ResourceNotFoundException::new);
        if (!current.revisionId().equals(plan.parentRevisionId())) throw new VersionConflictException();
    }

    private StagedRoot stageDescriptor(UUID scope, UUID actor, UUID exercise, UUID revision, String type,
                                       List<StagedRoot> pins) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("codec", 1).put("role", "exercise")
                .put("formatVersion", 1).put("exerciseId", exercise.toString())
                .put("exerciseRevisionId", revision.toString()).put("type", type);
        NewObject value = new NewObject(UUID.randomUUID(), ObjectKind.PAGE, (short) 1, (short) 9,
                payload, List.of());
        StagedRoot result = storage.stageBatch(new StageBatch(scope, actor, List.of(value), List.of(value.objectId())),
                PREPARATION_LEASE).getFirst();
        pins.add(result);
        return result;
    }

    private TreeRoot stagePage(PageEdit edit, UUID scope, UUID actor, List<StagedRoot> pins) {
        StagedRoot staged = storage.stageBatch(new StageBatch(scope, actor, edit.additions(),
                List.of(edit.root().ref().objectId())), PREPARATION_LEASE).getFirst();
        pins.add(staged);
        return edit.root();
    }

    private CountedPages exercisePages() {
        CountedPageTypes.ObjectSource source = ref -> storage.readBatch(ref.reuseScopeId(),
                List.of(ref.objectId())).getFirst().value();
        return new CountedPages(Profile.exercises(MAX_EXERCISES), source);
    }

    private TreeRoot tree(UUID scope, UUID root, int count) {
        NewObject page = storage.readBatch(scope, List.of(root)).getFirst().value();
        if (!page.payload().path("role").isString() || !page.payload().path("role").stringValue(null).equals("exercises")
                || !page.payload().path("treeHeight").canConvertToInt()) {
            throw new IllegalStateException("Invalid exercise root");
        }
        return new TreeRoot(new ObjectRef(scope, root), page.payload().path("treeHeight").intValue(), count);
    }

    /**
     * Releases the staging pins of a preparation that was not used. Outside a transaction that is a transaction of its own, so the
     * release survives the failure. Inside one (an approval publishes several exercises in one transaction) a second connection
     * would be needed, and the pins were staged in the same transaction: after a failure its rollback removes them, and after a replay
     * they are released in it.
     */
    private void cleanup(Prepared prepared, boolean failed) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            if (!failed) release(prepared);
            return;
        }
        cleanup.executeWithoutResult(ignored -> release(prepared));
    }

    private void release(Prepared prepared) {
        prepared.pins().forEach(pin -> storage.release(prepared.deck().scopeId(), pin.stagingPinId()));
    }

    private static StagedRoot findPin(List<StagedRoot> pins, ObjectRef root) {
        for (int index = pins.size() - 1; index >= 0; index--) {
            if (pins.get(index).root().equals(root)) return pins.get(index);
        }
        throw new IllegalStateException("Prepared exercise root is not pinned");
    }

    private ExerciseRepository.DeckHead own(UUID actor, UUID deck) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(deck, "deckId");
        return repository.deck(actor, deck).orElseThrow(ResourceNotFoundException::new);
    }

    private static boolean sameHead(ExerciseRepository.DeckHead current, ExerciseRepository.DeckHead prepared) {
        return current.deckId().equals(prepared.deckId()) && current.ownerId().equals(prepared.ownerId())
                && current.scopeId().equals(prepared.scopeId()) && current.revisionId().equals(prepared.revisionId())
                && current.version() == prepared.version() && current.membersRootId().equals(prepared.membersRootId())
                && current.exercisesRootId().equals(prepared.exercisesRootId())
                && current.memberCount() == prepared.memberCount() && current.exerciseCount() == prepared.exerciseCount();
    }

    private static ObjectNode summary(ExerciseRepository.ExerciseRow row) {
        return JsonNodeFactory.instance.objectNode().put("exerciseId", row.exerciseId().toString())
                .put("exerciseRevisionId", row.revisionId().toString())
                .put("exerciseVersion", Long.toString(row.sequence())).put("ordinal", row.ordinal())
                .put("type", row.type()).put("enabled", row.enabled()).put("schemaVersion", ExerciseCommand.SCHEMA_VERSION)
                .put("createdAt", row.createdAt().toString()).put("updatedAt", row.updatedAt().toString());
    }

    private static ObjectNode objective(ExerciseRepository.ObjectiveRow row) {
        return JsonNodeFactory.instance.objectNode().put("objectiveId", row.objectiveId().toString())
                .put("objectiveKey", row.objectiveKey().toString())
                .put("objectiveRevisionId", row.revisionId().toString())
                .put("objectiveVersion", Long.toString(row.sequence())).put("memberKey", row.memberKey().toString())
                .put("title", row.title());
    }

    private record ItemKey(UUID member, UUID revision) { }
    private record ObjectivePlan(UUID objectiveId, UUID objectiveKey, UUID revisionId, long sequence,
                                 UUID parentRevisionId, UUID memberKey, String title,
                                 boolean createIdentity, boolean createRevision) { }
    private record Prepared(ExerciseRepository.DeckHead deck, UUID deckRevision, UUID exerciseId,
                            UUID exerciseRevision, long exerciseSequence, int ordinal,
                            ExerciseRepository.ExerciseRow previous, ObjectivePlan objective, StagedRoot descriptor,
                            TreeRoot exercises, List<StagedRoot> pins, StagedRoot members) {
        private Prepared { pins = List.copyOf(pins); }
    }
    public record WriteResult(JsonNode acknowledgement, boolean replayed) {
        public WriteResult { acknowledgement = acknowledgement.deepCopy(); }
        @Override public JsonNode acknowledgement() { return acknowledgement.deepCopy(); }
    }
}
