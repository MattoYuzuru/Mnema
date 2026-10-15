package app.mnema.learning.catalog.item;

import app.mnema.learning.catalog.content.NativeDocumentReader;
import app.mnema.learning.catalog.content.NativeMediaReferences;
import app.mnema.learning.catalog.content.pages.CountedPages;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.Entry;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.Profile;
import app.mnema.learning.catalog.content.pages.CountedPageTypes.TreeRoot;
import app.mnema.learning.catalog.content.storage.NativeSnapshotCodec;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.storage.StorageTypes.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Large, media-free preconditions for community-decks §12 tests. Uses the real native codec,
 * path-copy page editor, storage kernel and repository with every FK/trigger enabled. Only the
 * final reachable DAG is staged, in bounded batches inside one transaction, rather than thousands
 * of preparation transactions and intermediate manifests. This is not an authoring-command test:
 * those remain in ItemServiceIntegrationTest; every operation under test still uses real services.
 */
public final class BulkItemFixture {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public record Created(UUID member, UUID revision) { }
    public record Input(UUID member, JsonNode document) { }
    private record Material(Created ids, UUID content, UUID descriptor) { }
    private final ImmutableStorage storage;
    private final ItemRepository repository;
    private final TransactionTemplate tx;

    public BulkItemFixture(ImmutableStorage storage, JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.storage = storage;
        repository = new ItemRepository(jdbc);
        tx = new TransactionTemplate(transactions);
        tx.setTimeout(120);
    }

    public List<Created> append(UUID owner, UUID deck, List<? extends JsonNode> documents) {
        return appendMembers(owner, deck, documents.stream().map(value -> new Input(UUID.randomUUID(), value)).toList());
    }

    public List<Created> appendMembers(UUID owner, UUID deck, List<Input> documents) {
        if (documents.isEmpty()) return List.of();
        return tx.execute(ignored -> appendInTransaction(owner, deck, documents));
    }

    private List<Created> appendInTransaction(UUID owner, UUID deck, List<Input> documents) {
        var before = repository.deck(owner, deck).orElseThrow();
        Map<UUID, NewObject> objects = new HashMap<>();
        Map<UUID, NewObject> reads = new HashMap<>();
        var pages = new CountedPages(Profile.members(ItemService.MAX_MEMBERS), ref -> {
            NewObject added = objects.get(ref.objectId());
            return added != null ? added : reads.computeIfAbsent(ref.objectId(), id ->
                    storage.readBatch(ref.reuseScopeId(), List.of(id)).getFirst().value());
        });
        var oldRoot = storage.readBatch(before.scopeId(), List.of(before.membersRootId())).getFirst().value();
        var root = new TreeRoot(new ObjectRef(before.scopeId(), before.membersRootId()),
                oldRoot.payload().path("treeHeight").intValue(), before.memberCount());
        var materials = new ArrayList<Material>();
        var codec = new NativeSnapshotCodec();
        var reader = new NativeDocumentReader();
        for (Input value : documents) {
            var document = reader.read(JSON.writeValueAsBytes(value.document()));
            if (!NativeMediaReferences.from(document).isEmpty()) {
                throw new IllegalArgumentException("Bulk fixture accepts only media-free preconditions");
            }
            var plan = codec.encode(before.scopeId(), document);
            plan.additions().forEach(object -> objects.put(object.objectId(), object));
            var ids = new Created(value.member(), UUID.randomUUID());
            var payload = JSON.createObjectNode().put("codec", 1).put("role", "item").put("formatVersion", 1)
                    .put("memberKey", ids.member().toString()).put("itemRevisionId", ids.revision().toString());
            var descriptor = new NewObject(UUID.randomUUID(), ObjectKind.PAGE, (short) 1, (short) 9,
                    payload, List.of(new NewEdge(0, null, plan.snapshot().root())));
            objects.put(descriptor.objectId(), descriptor);
            var edit = pages.insert(root, root.count(), new Entry(ids.member(),
                    new ObjectRef(before.scopeId(), descriptor.objectId())));
            edit.additions().forEach(object -> objects.put(object.objectId(), object));
            root = edit.root();
            materials.add(new Material(ids, plan.snapshot().root().objectId(), descriptor.objectId()));
        }

        Map<UUID, NewObject> reachable = new LinkedHashMap<>();
        visit(root.ref().objectId(), objects, reachable);
        List<NewObject> ordered = List.copyOf(reachable.values());
        var pins = new ArrayList<StagedRoot>();
        for (int start = 0; start < ordered.size(); start += 64) {
            List<NewObject> batch = ordered.subList(start, Math.min(start + 64, ordered.size()));
            pins.addAll(storage.stageBatch(new StageBatch(before.scopeId(), owner, batch,
                    List.of(batch.getLast().objectId())), Duration.ofMinutes(5)));
        }
        StagedRoot members = pins.getLast();
        StagedRoot exercises = storage.stageBatch(new StageBatch(before.scopeId(), owner, List.of(),
                List.of(before.exercisesRootId())), Duration.ofMinutes(5)).getFirst();
        pins.add(exercises);
        UUID revision = UUID.randomUUID(), command = UUID.randomUUID();
        var time = repository.now();
        if (repository.advance(owner, deck, revision, before.version()) != 1) throw new IllegalStateException("Fixture CAS failed");
        for (int index = 0; index < materials.size(); index++) {
            Material material = materials.get(index);
            var ids = material.ids();
            repository.insertItem(deck, ids.member(), owner, before.scopeId(), time);
            repository.insertItemRevision(deck, ids.member(), ids.revision(), before.scopeId(), owner, 0,
                    null, revision, before.version() + 1, command, material.content(), material.descriptor(), time);
            repository.insertHead(deck, before.scopeId(), ids.member(), ids.revision(), 0, time);
        }
        var pinOwner = new PinOwner("deck.revision", revision, owner);
        repository.insertDeckRevision(before, revision, owner, command, time, root.ref().objectId(),
                before.exercisesRootId(), storage.retain(members, pinOwner), storage.retain(exercises, pinOwner), root.count());
        for (int index = 0; index < materials.size(); index++) {
            var ids = materials.get(index).ids();
            repository.change(deck, before.scopeId(), revision, before.version() + 1, index, ids.member(),
                    "create", null, ids.revision(), null, before.memberCount() + index);
        }
        pins.forEach(pin -> storage.release(before.scopeId(), pin.stagingPinId()));
        return materials.stream().map(Material::ids).toList();
    }

    private static void visit(UUID id, Map<UUID, NewObject> objects, Map<UUID, NewObject> ordered) {
        NewObject object = objects.get(id);
        if (object == null || ordered.containsKey(id)) return;
        object.edges().forEach(edge -> visit(edge.child().objectId(), objects, ordered));
        ordered.put(id, object);
    }
}
