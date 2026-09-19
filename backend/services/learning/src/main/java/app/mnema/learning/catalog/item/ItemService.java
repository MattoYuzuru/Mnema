package app.mnema.learning.catalog.item;

import app.mnema.learning.catalog.content.NativeDocument;
import app.mnema.learning.catalog.content.pages.CountedPages;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.Entry;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.PageEdit;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.Profile;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.TreeRoot;
import app.mnema.learning.catalog.content.storage.NativeEncodingPlan;
import app.mnema.learning.catalog.content.storage.NativeSnapshot;
import app.mnema.learning.catalog.content.storage.NativeSnapshotCodec;
import app.mnema.learning.catalog.content.storage.NativeSnapshotDecoder;
import app.mnema.learning.catalog.content.storage.NativeStorageBatches;
import app.mnema.learning.catalog.content.storage.NativeStorageFailure;
import app.mnema.learning.catalog.content.storage.NativeStructuralEditor;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.CompareAndSetExecutor;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.storage.StorageTypes.NewEdge;
import app.mnema.learning.storage.StorageTypes.NewObject;
import app.mnema.learning.storage.StorageTypes.ObjectKind;
import app.mnema.learning.storage.StorageTypes.ObjectRef;
import app.mnema.learning.storage.StorageTypes.PinOwner;
import app.mnema.learning.storage.StorageTypes.StageBatch;
import app.mnema.learning.storage.StorageTypes.StagedRoot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ItemService {
    private static final int MAX_MEMBERS = 100_000;
    private static final Duration PREPARATION_LEASE = Duration.ofMinutes(1);

    private final ItemRepository repository;
    private final CommandReceiptService receipts;
    private final CompareAndSetExecutor cas;
    private final ImmutableStorage storage;
    private final NativeSnapshotCodec codec = new NativeSnapshotCodec();
    private final NativeStructuralEditor structuralEditor = new NativeStructuralEditor();
    private final NativeStorageBatches nativeBatches;
    private final TransactionTemplate preparationBoundary;
    private final TransactionTemplate publicationTransaction;
    private final TransactionTemplate cleanupTransaction;

    public ItemService(ItemRepository repository, CommandReceiptService receipts, CompareAndSetExecutor cas,
                       ImmutableStorage storage, PlatformTransactionManager transactions) {
        this.repository = repository;
        this.receipts = receipts;
        this.cas = cas;
        this.storage = storage;
        this.nativeBatches = new NativeStorageBatches(storage);
        this.preparationBoundary = new TransactionTemplate(transactions);
        preparationBoundary.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        this.publicationTransaction = new TransactionTemplate(transactions);
        publicationTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        publicationTransaction.setTimeout(10);
        this.cleanupTransaction = new TransactionTemplate(transactions);
        cleanupTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        cleanupTransaction.setTimeout(10);
    }

    @Transactional(readOnly = true, timeout = 10)
    public ObjectNode list(UUID actor, UUID deckId, String limit, String cursor) {
        ItemRepository.DeckHead deck = own(actor, deckId);
        ItemCursor position = ItemCursor.decode(cursor);
        if (position != null && !position.deckRevisionId().equals(deck.revisionId())) throw new VersionConflictException();
        int size = ItemCursor.pageSize(limit);
        int start = position == null ? 0 : position.nextOrdinal();
        List<ItemRecord> rows = repository.page(actor, deckId, start, size);
        ObjectNode result = JsonNodeFactory.instance.objectNode().put("deckId", deckId.toString())
                .put("deckRevisionId", deck.revisionId().toString()).put("deckVersion", Long.toString(deck.version()))
                .put("total", deck.memberCount());
        var items = result.putArray("items");
        rows.stream().limit(size).forEach(row -> items.add(row.summary()));
        if (rows.size() > size) result.put("nextCursor", new ItemCursor(deck.revisionId(), rows.get(size).ordinal()).encode());
        else result.putNull("nextCursor");
        return result;
    }

    @Transactional(timeout = 10)
    public ObjectNode read(UUID actor, UUID deckId, UUID memberKey, UUID revisionId) {
        ItemRepository.DeckHead deck = own(actor, deckId);
        ItemRecord item = (revisionId == null ? repository.headItem(actor, deckId, memberKey)
                : repository.revision(actor, deckId, memberKey, revisionId)).orElseThrow(ResourceNotFoundException::new);
        UUID selectedDeckRevision = revisionId == null ? deck.revisionId() : item.publishedDeckRevisionId();
        long selectedDeckVersion = revisionId == null ? deck.version() : item.publishedDeckVersion();
        return item.detail(selectedDeckRevision, selectedDeckVersion,
                decode(item.scopeId(), item.contentRootId()).document());
    }

    public WriteResult publish(UUID actor, UUID deckId, long expectedDeckVersion, ItemPublicationCommand command) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(deckId, "deckId");
        // Current ACL is checked before command replay. A receipt is never an authorization capability.
        own(actor, deckId);
        CommandIdentity identity = new CommandIdentity(command.commandId(), actor, "deck.items", "item.publish");
        ObjectNode envelope = command.envelope(deckId, expectedDeckVersion);
        var replay = receipts.replay(identity, envelope);
        if (replay.isPresent()) return new WriteResult(replay.orElseThrow(), true);
        PreparedPublication prepared = preparationBoundary.execute(
                ignored -> prepare(actor, deckId, expectedDeckVersion, command));
        boolean[] applied = {false};
        try {
            JsonNode acknowledgement = publicationTransaction.execute(ignored -> receipts.execute(
                    identity, envelope, () -> {
                        applied[0] = true;
                        return apply(actor, deckId, expectedDeckVersion, command, prepared);
                    }));
            if (!applied[0]) cleanup(prepared);
            return new WriteResult(acknowledgement, !applied[0]);
        } catch (RuntimeException failure) {
            try {
                cleanup(prepared);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private PreparedPublication prepare(UUID actor, UUID deckId, long expectedDeckVersion,
                                        ItemPublicationCommand command) {
        ItemRepository.DeckHead before = own(actor, deckId);
        if (before.version() != expectedDeckVersion
                || !before.revisionId().equals(command.expectedDeckRevisionId())) throw new VersionConflictException();
        if (before.memberCount() >= MAX_MEMBERS
                && command.changes().stream().anyMatch(ItemPublicationCommand.Create.class::isInstance)) {
            throw new InvalidRequestException();
        }

        var pageSource = (CountedPageTypesSource) ref -> storage.readBatch(ref.reuseScopeId(),
                List.of(ref.objectId())).getFirst().value();
        CountedPages pages = new CountedPages(Profile.members(MAX_MEMBERS), pageSource::read);
        TreeRoot membership = tree(before.scopeId(), before.membersRootId(), before.memberCount());
        List<StagedRoot> temporaryPins = new ArrayList<>();
        List<PreparedChange> preparedChanges = new ArrayList<>();
        Map<UUID, ItemRecord> currentItems = new HashMap<>();
        Map<UUID, Integer> currentOrdinals = new HashMap<>();
        for (ItemPublicationCommand.Change change : command.changes()) {
            if (change instanceof ItemPublicationCommand.Create) continue;
            ItemRecord current = current(actor, deckId, change.memberKey(), expectedRevision(change));
            currentItems.put(change.memberKey(), current);
            currentOrdinals.put(change.memberKey(), current.ordinal());
        }

        for (ItemPublicationCommand.Change change : command.changes()) {
            UUID member = change.memberKey() == null ? UUID.randomUUID() : change.memberKey();
            switch (change) {
                case ItemPublicationCommand.Create create -> {
                    if (repository.itemExists(actor, deckId, member)) throw new VersionConflictException();
                    int ordinal = create.ordinal() == null ? membership.count() : create.ordinal();
                    if (ordinal < 0 || ordinal > membership.count() || membership.count() >= MAX_MEMBERS) {
                        throw new InvalidRequestException();
                    }
                    UUID itemRevision = UUID.randomUUID();
                    NativeEncodingPlan plan = codec.encode(before.scopeId(), create.document());
                    StagedRoot content = stageNative(plan, actor, null, temporaryPins);
                    StagedRoot descriptor = stageDescriptor(before.scopeId(), actor, member, itemRevision,
                            content.root(), temporaryPins);
                    membership = stagePage(pages.insert(membership, ordinal, new Entry(member, descriptor.root())),
                            before.scopeId(), actor, temporaryPins);
                    shiftForInsert(currentOrdinals, ordinal);
                    preparedChanges.add(new PreparedCreate(member, itemRevision, content.root().objectId(),
                            descriptor.root().objectId(), ordinal));
                }
                case ItemPublicationCommand.Save save -> {
                    ItemRecord current = currentItems.get(member);
                    int fromOrdinal = currentOrdinals.get(member);
                    NativeSnapshot previous = decode(current.scopeId(), current.contentRootId());
                    NativeEncodingPlan plan = plan(previous, save.document(), save.edit());
                    StagedRoot content = stageNative(plan, actor, current.contentRootId(), temporaryPins);
                    UUID itemRevision = UUID.randomUUID();
                    long itemSequence = current.itemSequence() + 1;
                    StagedRoot descriptor = stageDescriptor(before.scopeId(), actor, member, itemRevision,
                            content.root(), temporaryPins);
                    membership = stagePage(pages.replace(membership, fromOrdinal, member,
                            new Entry(member, descriptor.root())), before.scopeId(), actor, temporaryPins);
                    int ordinal = fromOrdinal;
                    if (save.ordinal() != null && save.ordinal() != ordinal) {
                        if (save.ordinal() < 0 || save.ordinal() >= membership.count()) throw new InvalidRequestException();
                        membership = stagePage(pages.move(membership, ordinal, save.ordinal(), member),
                                before.scopeId(), actor, temporaryPins);
                        shiftForMove(currentOrdinals, member, ordinal, save.ordinal());
                        ordinal = save.ordinal();
                    }
                    preparedChanges.add(new PreparedSave(member, current.revisionId(), itemRevision, itemSequence,
                            content.root().objectId(), descriptor.root().objectId(), fromOrdinal, ordinal));
                }
                case ItemPublicationCommand.Delete delete -> {
                    ItemRecord current = currentItems.get(member);
                    int ordinal = currentOrdinals.get(member);
                    membership = stagePage(pages.delete(membership, ordinal, member), before.scopeId(), actor,
                            temporaryPins);
                    shiftForDelete(currentOrdinals, member, ordinal);
                    preparedChanges.add(new PreparedDelete(member, current.revisionId(), current.itemSequence(), ordinal));
                }
                case ItemPublicationCommand.Reorder reorder -> {
                    ItemRecord current = currentItems.get(member);
                    int fromOrdinal = currentOrdinals.get(member);
                    if (reorder.ordinal() >= membership.count()) throw new InvalidRequestException();
                    membership = stagePage(pages.move(membership, fromOrdinal, reorder.ordinal(), member),
                            before.scopeId(), actor, temporaryPins);
                    shiftForMove(currentOrdinals, member, fromOrdinal, reorder.ordinal());
                    preparedChanges.add(new PreparedReorder(member, current.revisionId(), current.itemSequence(),
                            fromOrdinal, reorder.ordinal()));
                }
            }
        }

        StagedRoot exercises = storage.stageBatch(new StageBatch(before.scopeId(), actor, List.of(),
                List.of(before.exercisesRootId())), PREPARATION_LEASE).getFirst();
        temporaryPins.add(exercises);
        return new PreparedPublication(before, UUID.randomUUID(), membership, List.copyOf(preparedChanges),
                List.copyOf(temporaryPins), exercises);
    }

    private ObjectNode apply(UUID actor, UUID deckId, long expectedDeckVersion, ItemPublicationCommand command,
                             PreparedPublication prepared) {
        ItemRepository.DeckHead before = own(actor, deckId);
        if (before.version() != expectedDeckVersion || !before.revisionId().equals(command.expectedDeckRevisionId())
                || !sameHead(before, prepared.before())) throw new VersionConflictException();
        long deckSequence = cas.updateOne(expectedDeckVersion,
                () -> repository.advance(actor, deckId, prepared.deckRevision(), expectedDeckVersion));
        Instant time = repository.now();
        List<PendingChange> pendingChanges = new ArrayList<>();
        for (PreparedChange change : prepared.changes()) {
            switch (change) {
                case PreparedCreate create -> {
                    if (repository.itemExists(actor, deckId, create.member())) throw new VersionConflictException();
                    repository.insertItem(deckId, create.member(), actor, before.scopeId(), time);
                    repository.insertItemRevision(deckId, create.member(), create.revision(), before.scopeId(), actor, 0,
                            null, prepared.deckRevision(), deckSequence, command.commandId(), create.contentRoot(),
                            create.descriptorRoot(), time);
                    repository.insertHead(deckId, create.member(), create.revision(), 0, create.ordinal(), time);
                    pendingChanges.add(new PendingChange("create", create.member(), null, create.revision(),
                            create.revision(), 0, null));
                }
                case PreparedSave save -> {
                    ItemRecord current = current(actor, deckId, save.member(), save.expectedRevision());
                    if (current.ordinal() != save.fromOrdinal() || current.itemSequence() + 1 != save.itemSequence()) {
                        throw new VersionConflictException();
                    }
                    repository.insertItemRevision(deckId, save.member(), save.revision(), before.scopeId(), actor,
                            save.itemSequence(), current.revisionId(), prepared.deckRevision(), deckSequence,
                            command.commandId(), save.contentRoot(), save.descriptorRoot(), time);
                    repository.updateHead(deckId, save.member(), save.revision(), save.itemSequence(), time);
                    if (save.toOrdinal() != save.fromOrdinal()) {
                        repository.moveHead(deckId, save.member(), save.fromOrdinal(), save.toOrdinal());
                    }
                    pendingChanges.add(new PendingChange("save", save.member(), current.revisionId(), save.revision(),
                            save.revision(), save.itemSequence(), save.fromOrdinal()));
                }
                case PreparedDelete delete -> {
                    ItemRecord current = current(actor, deckId, delete.member(), delete.expectedRevision());
                    if (current.ordinal() != delete.fromOrdinal()) throw new VersionConflictException();
                    repository.deleteHead(deckId, delete.member(), delete.fromOrdinal());
                    pendingChanges.add(new PendingChange("delete", delete.member(), current.revisionId(), null,
                            null, delete.itemSequence(), delete.fromOrdinal()));
                }
                case PreparedReorder reorder -> {
                    ItemRecord current = current(actor, deckId, reorder.member(), reorder.expectedRevision());
                    if (current.ordinal() != reorder.fromOrdinal()) throw new VersionConflictException();
                    repository.moveHead(deckId, reorder.member(), reorder.fromOrdinal(), reorder.toOrdinal());
                    pendingChanges.add(new PendingChange("reorder", reorder.member(), current.revisionId(), null,
                            current.revisionId(), reorder.itemSequence(), reorder.fromOrdinal()));
                }
            }
        }

        PinOwner pinOwner = new PinOwner("deck.revision", prepared.deckRevision(), actor);
        UUID membersPin = storage.retain(findPin(prepared.pins(), prepared.membership().ref()), pinOwner);
        UUID exercisesPin = storage.retain(prepared.exercises(), pinOwner);
        repository.insertDeckRevision(before, prepared.deckRevision(), actor, command.commandId(), time,
                prepared.membership().ref().objectId(), before.exercisesRootId(), membersPin, exercisesPin,
                prepared.membership().count());
        var results = JsonNodeFactory.instance.arrayNode();
        for (int index = 0; index < pendingChanges.size(); index++) {
            PendingChange change = pendingChanges.get(index);
            Integer finalOrdinal = change.operation().equals("delete") ? null
                    : repository.headItem(actor, deckId, change.member()).orElseThrow().ordinal();
            repository.change(deckId, prepared.deckRevision(), deckSequence, index, change.member(), change.operation(),
                    change.previousRevision(), change.publishedRevision(), change.fromOrdinal(), finalOrdinal);
            results.add(result(change.operation(), change.member(), change.resultRevision(),
                    change.itemVersion(), finalOrdinal));
        }
        release(prepared);

        ObjectNode acknowledgement = JsonNodeFactory.instance.objectNode().put("commandId", command.commandId().toString())
                .put("deckId", deckId.toString()).put("deckRevisionId", prepared.deckRevision().toString())
                .put("deckVersion", Long.toString(deckSequence)).put("memberCount", prepared.membership().count());
        acknowledgement.set("changes", results);
        return acknowledgement;
    }

    private NativeEncodingPlan plan(NativeSnapshot previous, NativeDocument document,
                                    app.mnema.learning.catalog.content.storage.NativeStructuralEdit edit) {
        try {
            return edit == null ? codec.replace(previous, document) : structuralEditor.apply(previous, document, edit);
        } catch (NativeStorageFailure failure) {
            throw new InvalidRequestException();
        }
    }

    private StagedRoot stageNative(NativeEncodingPlan plan, UUID actor, UUID sourceRoot,
                                   List<StagedRoot> temporaryPins) {
        StagedRoot source = null;
        if (sourceRoot != null) {
            source = storage.stageBatch(new StageBatch(plan.snapshot().root().reuseScopeId(), actor, List.of(),
                    List.of(sourceRoot)), PREPARATION_LEASE).getFirst();
            temporaryPins.add(source);
        }
        NativeStorageBatches.Preparation preparation = nativeBatches.begin(plan, actor, PREPARATION_LEASE, source);
        while (!preparation.complete()) nativeBatches.stageNext(preparation);
        temporaryPins.addAll(preparation.pins());
        return preparation.root();
    }

    private StagedRoot stageDescriptor(UUID scope, UUID actor, UUID member, UUID revision, ObjectRef content,
                                       List<StagedRoot> temporaryPins) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("codec", 1).put("role", "item")
                .put("formatVersion", 1).put("memberKey", member.toString()).put("itemRevisionId", revision.toString());
        NewObject descriptor = new NewObject(UUID.randomUUID(), ObjectKind.PAGE, (short) 1, (short) 9, payload,
                List.of(new NewEdge(0, null, content)));
        StagedRoot staged = storage.stageBatch(new StageBatch(scope, actor, List.of(descriptor),
                List.of(descriptor.objectId())), PREPARATION_LEASE).getFirst();
        temporaryPins.add(staged);
        return staged;
    }

    private TreeRoot stagePage(PageEdit edit, UUID scope, UUID actor, List<StagedRoot> temporaryPins) {
        StagedRoot staged = storage.stageBatch(new StageBatch(scope, actor, edit.additions(),
                List.of(edit.root().ref().objectId())), PREPARATION_LEASE).getFirst();
        temporaryPins.add(staged);
        return edit.root();
    }

    private TreeRoot tree(UUID scope, UUID root, int expectedCount) {
        NewObject page = storage.readBatch(scope, List.of(root)).getFirst().value();
        if (!page.payload().path("role").isTextual() || !page.payload().path("role").textValue().equals("members")
                || !page.payload().path("treeHeight").canConvertToInt()) throw new IllegalStateException("Invalid member root");
        return new TreeRoot(new ObjectRef(scope, root), page.payload().path("treeHeight").intValue(), expectedCount);
    }

    private NativeSnapshot decode(UUID scope, UUID root) {
        NativeSnapshotDecoder decoder = new NativeSnapshotDecoder(new ObjectRef(scope, root));
        while (!decoder.isComplete()) nativeBatches.readNext(decoder);
        return decoder.snapshot();
    }

    private ItemRepository.DeckHead own(UUID actor, UUID deck) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(deck, "deckId");
        return repository.deck(actor, deck).orElseThrow(ResourceNotFoundException::new);
    }

    private ItemRecord current(UUID actor, UUID deck, UUID member, UUID expectedRevision) {
        ItemRecord item = repository.headItem(actor, deck, member).orElseThrow(ResourceNotFoundException::new);
        if (!item.revisionId().equals(expectedRevision)) throw new VersionConflictException();
        return item;
    }

    private void release(PreparedPublication prepared) {
        prepared.pins().forEach(pin -> storage.release(prepared.before().scopeId(), pin.stagingPinId()));
    }

    private void cleanup(PreparedPublication prepared) {
        cleanupTransaction.executeWithoutResult(ignored -> release(prepared));
    }

    private static UUID expectedRevision(ItemPublicationCommand.Change change) {
        return switch (change) {
            case ItemPublicationCommand.Save save -> save.expectedItemRevisionId();
            case ItemPublicationCommand.Delete delete -> delete.expectedItemRevisionId();
            case ItemPublicationCommand.Reorder reorder -> reorder.expectedItemRevisionId();
            case ItemPublicationCommand.Create ignored -> throw new IllegalArgumentException("Create has no revision");
        };
    }

    private static boolean sameHead(ItemRepository.DeckHead current, ItemRepository.DeckHead prepared) {
        return current.deckId().equals(prepared.deckId())
                && current.scopeId().equals(prepared.scopeId())
                && current.revisionId().equals(prepared.revisionId())
                && current.version() == prepared.version()
                && current.membersRootId().equals(prepared.membersRootId())
                && current.exercisesRootId().equals(prepared.exercisesRootId())
                && current.memberCount() == prepared.memberCount()
                && current.exerciseCount() == prepared.exerciseCount();
    }

    private static void shiftForInsert(Map<UUID, Integer> ordinals, int insertedAt) {
        ordinals.replaceAll((ignored, ordinal) -> ordinal >= insertedAt ? ordinal + 1 : ordinal);
    }

    private static void shiftForDelete(Map<UUID, Integer> ordinals, UUID deleted, int deletedAt) {
        ordinals.remove(deleted);
        ordinals.replaceAll((ignored, ordinal) -> ordinal > deletedAt ? ordinal - 1 : ordinal);
    }

    private static void shiftForMove(Map<UUID, Integer> ordinals, UUID moved, int from, int to) {
        if (from == to) return;
        ordinals.replaceAll((member, ordinal) -> {
            if (member.equals(moved)) return to;
            if (from < to && ordinal > from && ordinal <= to) return ordinal - 1;
            if (from > to && ordinal >= to && ordinal < from) return ordinal + 1;
            return ordinal;
        });
    }

    private static StagedRoot findPin(List<StagedRoot> pins, ObjectRef root) {
        for (int index = pins.size() - 1; index >= 0; index--) if (pins.get(index).root().equals(root)) return pins.get(index);
        throw new IllegalStateException("Prepared membership root is not pinned");
    }

    private static ObjectNode result(String operation, UUID member, UUID revision, long version, Integer ordinal) {
        ObjectNode value = JsonNodeFactory.instance.objectNode().put("operation", operation)
                .put("memberKey", member.toString()).put("itemVersion", Long.toString(version));
        if (revision == null) value.putNull("itemRevisionId"); else value.put("itemRevisionId", revision.toString());
        if (ordinal == null) value.putNull("ordinal"); else value.put("ordinal", ordinal);
        return value;
    }

    @FunctionalInterface
    private interface CountedPageTypesSource { NewObject read(ObjectRef ref); }

    private sealed interface PreparedChange permits PreparedCreate, PreparedSave, PreparedDelete, PreparedReorder {
        UUID member();
    }

    private record PreparedCreate(UUID member, UUID revision, UUID contentRoot, UUID descriptorRoot, int ordinal)
            implements PreparedChange { }

    private record PreparedSave(UUID member, UUID expectedRevision, UUID revision, long itemSequence,
                                UUID contentRoot, UUID descriptorRoot, int fromOrdinal, int toOrdinal)
            implements PreparedChange { }

    private record PreparedDelete(UUID member, UUID expectedRevision, long itemSequence, int fromOrdinal)
            implements PreparedChange { }

    private record PreparedReorder(UUID member, UUID expectedRevision, long itemSequence,
                                   int fromOrdinal, int toOrdinal) implements PreparedChange { }

    private record PreparedPublication(ItemRepository.DeckHead before, UUID deckRevision, TreeRoot membership,
                                       List<PreparedChange> changes, List<StagedRoot> pins, StagedRoot exercises) {
        private PreparedPublication {
            changes = List.copyOf(changes);
            pins = List.copyOf(pins);
        }
    }

    private record PendingChange(String operation, UUID member, UUID previousRevision, UUID publishedRevision,
                                 UUID resultRevision, long itemVersion, Integer fromOrdinal) { }

    public record WriteResult(JsonNode acknowledgement, boolean replayed) {
        public WriteResult { acknowledgement = acknowledgement.deepCopy(); }
        @Override public JsonNode acknowledgement() { return acknowledgement.deepCopy(); }
    }
}
