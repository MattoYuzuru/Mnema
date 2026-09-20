package app.mnema.learning.catalog.exercise;

import app.mnema.learning.catalog.content.pages.CountedPageTypes;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.Entry;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.PageEdit;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.Profile;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.TreeRoot;
import app.mnema.learning.catalog.content.pages.CountedPages;
import app.mnema.learning.catalog.content.storage.NativeSnapshot;
import app.mnema.learning.catalog.content.storage.NativeSnapshotDecoder;
import app.mnema.learning.catalog.content.storage.NativeStorageBatches;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.CompareAndSetExecutor;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.storage.StorageTypes.NewObject;
import app.mnema.learning.storage.StorageTypes.ObjectKind;
import app.mnema.learning.storage.StorageTypes.ObjectRef;
import app.mnema.learning.storage.StorageTypes.PinOwner;
import app.mnema.learning.storage.StorageTypes.StageBatch;
import app.mnema.learning.storage.StorageTypes.StagedRoot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
    private final NativeStorageBatches nativeBatches;
    private final TransactionTemplate publication;
    private final TransactionTemplate cleanup;

    public ExerciseService(ExerciseRepository repository, CommandReceiptService receipts, CompareAndSetExecutor cas,
                           ImmutableStorage storage, PlatformTransactionManager transactions) {
        this.repository = repository;
        this.receipts = receipts;
        this.cas = cas;
        this.storage = storage;
        this.nativeBatches = new NativeStorageBatches(storage);
        this.publication = new TransactionTemplate(transactions);
        publication.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        publication.setTimeout(10);
        this.cleanup = new TransactionTemplate(transactions);
        cleanup.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        cleanup.setTimeout(10);
    }

    @Transactional(readOnly = true, timeout = 10)
    public ObjectNode list(UUID actor, UUID deckId, String limit, String cursor) {
        ExerciseRepository.DeckHead deck = own(actor, deckId);
        ExerciseCursor position = ExerciseCursor.decode(cursor);
        if (position != null && !position.deckRevisionId().equals(deck.revisionId())) throw new VersionConflictException();
        int start = position == null ? 0 : position.nextOrdinal();
        if (start > deck.exerciseCount()) throw new InvalidRequestException();
        int size = ExerciseCursor.pageSize(limit);
        List<ExerciseRepository.ExerciseRow> rows = repository.page(actor, deckId, start, size);
        ObjectNode result = JsonNodeFactory.instance.objectNode().put("deckId", deckId.toString())
                .put("deckRevisionId", deck.revisionId().toString()).put("deckVersion", Long.toString(deck.version()))
                .put("total", deck.exerciseCount());
        ArrayNode values = result.putArray("exercises");
        rows.forEach(row -> values.add(summary(row)));
        int next = start + rows.size();
        if (next < deck.exerciseCount()) result.put("nextCursor", new ExerciseCursor(deck.revisionId(), next).encode());
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
        List<ExerciseRepository.BindingRow> bindings = repository.bindings(deckId, exerciseId, exercise.revisionId());
        ExerciseRepository.BindingRow assessed = bindings.stream().filter(row -> row.role().equals("ASSESSED"))
                .findFirst().orElseThrow(() -> new IllegalStateException("Exercise has no assessed binding"));
        ExerciseRepository.ObjectiveRow objective = repository.objectiveRevision(actor, deckId,
                assessed.objectiveId(), assessed.objectiveRevisionId()).orElseThrow(IllegalStateException::new);
        ObjectNode result = summary(exercise).put("deckId", deckId.toString())
                .put("deckRevisionId", deck.revisionId().toString()).put("deckVersion", Long.toString(deck.version()));
        result.set("prompt", exercise.prompt().deepCopy());
        result.set("evaluatorPolicy", exercise.evaluator().deepCopy());
        result.set("objective", objective(objective));
        ArrayNode bindingValues = result.putArray("bindings");
        bindings.forEach(row -> bindingValues.add(binding(row)));
        return result;
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
        Prepared prepared = prepare(actor, deckId, pathExerciseId, expectedDeckVersion, command);
        boolean[] applied = {false};
        try {
            JsonNode result = publication.execute(ignored -> receipts.execute(identity, envelope, () -> {
                applied[0] = true;
                return apply(actor, deckId, pathExerciseId, expectedDeckVersion, command, prepared);
            }));
            if (!applied[0]) cleanup(prepared);
            return new WriteResult(result, !applied[0]);
        } catch (RuntimeException failure) {
            try { cleanup(prepared); }
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

        ExerciseCommand.Binding assessed = command.exercise().bindings().stream()
                .filter(binding -> binding.role().equals("ASSESSED")).findFirst().orElseThrow();
        Map<ItemKey, Set<UUID>> nodes = validateBindings(actor, deckId, command.exercise());
        validatePrompt(command.exercise().prompt(), nodes);
        ObjectivePlan objective = prepareObjective(actor, deckId, command.objective(), assessed.memberKey());

        UUID exerciseRevision = UUID.randomUUID();
        UUID deckRevision = UUID.randomUUID();
        List<StagedRoot> pins = new ArrayList<>();
        StagedRoot descriptor = stageDescriptor(deck.scopeId(), actor, exerciseId, exerciseRevision,
                command.exercise().type(), pins);
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
                    UUID.randomUUID(), 0, null, assessedMember, create.answerContract(), true, false);
            case ExerciseCommand.ReuseObjective reuse -> {
                ExerciseRepository.ObjectiveRow current = repository.objectiveHead(actor, deckId, reuse.objectiveId())
                        .orElseThrow(ResourceNotFoundException::new);
                if (!current.revisionId().equals(reuse.objectiveRevisionId())) throw new VersionConflictException();
                if (!current.memberKey().equals(assessedMember)) throw new InvalidRequestException();
                yield new ObjectivePlan(current.objectiveId(), current.objectiveKey(), current.revisionId(),
                        current.sequence(), current.revisionId(), current.memberKey(), current.answerContract(), false, false);
            }
            case ExerciseCommand.ReviseObjective revise -> {
                ExerciseRepository.ObjectiveRow current = repository.objectiveHead(actor, deckId, revise.objectiveId())
                        .orElseThrow(ResourceNotFoundException::new);
                if (!current.revisionId().equals(revise.expectedObjectiveRevisionId())) throw new VersionConflictException();
                if (!current.memberKey().equals(assessedMember)) throw new InvalidRequestException();
                yield new ObjectivePlan(current.objectiveId(), current.objectiveKey(), UUID.randomUUID(),
                        current.sequence() + 1, current.revisionId(), current.memberKey(), revise.answerContract(), false, true);
            }
        };
    }

    private Map<ItemKey, Set<UUID>> validateBindings(UUID actor, UUID deckId, ExerciseCommand.Exercise exercise) {
        Map<ItemKey, Set<UUID>> result = new HashMap<>();
        for (ExerciseCommand.Binding binding : exercise.bindings()) {
            ItemKey key = new ItemKey(binding.memberKey(), binding.itemRevisionId());
            Set<UUID> nodes = result.computeIfAbsent(key, ignored -> loadNodes(actor, deckId, key));
            if (!nodes.containsAll(binding.nodeIds())) throw new InvalidRequestException();
        }
        return result;
    }

    private void validatePrompt(JsonNode prompt, Map<ItemKey, Set<UUID>> loaded) {
        if (!prompt.path("kind").textValue().equals("NODE_TEXT")) return;
        ItemKey key = new ItemKey(UUID.fromString(prompt.path("memberKey").textValue()),
                UUID.fromString(prompt.path("itemRevisionId").textValue()));
        Set<UUID> nodes = loaded.get(key);
        if (nodes == null || !nodes.contains(UUID.fromString(prompt.path("nodeId").textValue()))) {
            throw new InvalidRequestException();
        }
    }

    private Set<UUID> loadNodes(UUID actor, UUID deckId, ItemKey key) {
        ExerciseRepository.ItemRevision revision = repository.itemRevision(actor, deckId, key.member(), key.revision())
                .orElseThrow(ResourceNotFoundException::new);
        NativeSnapshotDecoder decoder = new NativeSnapshotDecoder(new ObjectRef(revision.scopeId(), revision.contentRootId()));
        while (!decoder.isComplete()) nativeBatches.readNext(decoder);
        NativeSnapshot snapshot = decoder.snapshot();
        Set<UUID> result = new HashSet<>();
        ArrayDeque<JsonNode> pending = new ArrayDeque<>();
        pending.add(snapshot.document().toJson().path("root"));
        while (!pending.isEmpty()) {
            JsonNode node = pending.removeLast();
            result.add(UUID.fromString(node.path("id").textValue()));
            node.path("content").forEach(pending::add);
        }
        return Set.copyOf(result);
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
                    prepared.deckRevision(), deckSequence, command.commandId(), objective.answerContract(), time);
            repository.insertObjectiveHead(deckId, objective.objectiveId(), objective.revisionId(), 0, time);
        } else if (objective.createRevision()) {
            repository.insertObjectiveRevision(deckId, objective.objectiveId(), objective.revisionId(),
                    objective.sequence(), objective.parentRevisionId(), prepared.deckRevision(), deckSequence,
                    command.commandId(), objective.answerContract(), time);
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
        for (ExerciseCommand.Binding binding : command.exercise().bindings()) {
            boolean assessed = binding.role().equals("ASSESSED");
            repository.insertBinding(deckId, prepared.exerciseId(), prepared.exerciseRevision(), binding,
                    assessed ? objective.objectiveId() : null, assessed ? objective.revisionId() : null);
        }
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
        if (!page.payload().path("role").isTextual() || !page.payload().path("role").textValue().equals("exercises")
                || !page.payload().path("treeHeight").canConvertToInt()) {
            throw new IllegalStateException("Invalid exercise root");
        }
        return new TreeRoot(new ObjectRef(scope, root), page.payload().path("treeHeight").intValue(), count);
    }

    private void cleanup(Prepared prepared) {
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
                .put("type", row.type()).put("enabled", row.enabled()).put("schemaVersion", 1)
                .put("createdAt", row.createdAt().toString()).put("updatedAt", row.updatedAt().toString());
    }

    private static ObjectNode objective(ExerciseRepository.ObjectiveRow row) {
        ObjectNode result = JsonNodeFactory.instance.objectNode().put("objectiveId", row.objectiveId().toString())
                .put("objectiveKey", row.objectiveKey().toString())
                .put("objectiveRevisionId", row.revisionId().toString())
                .put("objectiveVersion", Long.toString(row.sequence())).put("memberKey", row.memberKey().toString());
        result.set("answerContract", row.answerContract().deepCopy());
        return result;
    }

    private static ObjectNode binding(ExerciseRepository.BindingRow row) {
        ObjectNode result = JsonNodeFactory.instance.objectNode().put("bindingId", row.bindingId().toString())
                .put("role", row.role()).put("memberKey", row.memberKey().toString())
                .put("itemRevisionId", row.itemRevisionId().toString()).put("ordinal", row.ordinal());
        ArrayNode nodes = result.putArray("nodeIds");
        row.nodeIds().forEach(id -> nodes.add(id.toString()));
        result.set("display", row.display().deepCopy());
        return result;
    }

    private record ItemKey(UUID member, UUID revision) { }
    private record ObjectivePlan(UUID objectiveId, UUID objectiveKey, UUID revisionId, long sequence,
                                 UUID parentRevisionId, UUID memberKey, JsonNode answerContract,
                                 boolean createIdentity, boolean createRevision) {
        private ObjectivePlan { answerContract = answerContract.deepCopy(); }
        @Override public JsonNode answerContract() { return answerContract.deepCopy(); }
    }
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
