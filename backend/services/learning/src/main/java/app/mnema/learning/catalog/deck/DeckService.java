package app.mnema.learning.catalog.deck;

import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.CompareAndSetExecutor;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import app.mnema.learning.storage.ImmutableStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static app.mnema.learning.storage.StorageTypes.*;

@Service
public class DeckService {
    private final DeckRepository repository;
    private final CommandReceiptService receipts;
    private final CompareAndSetExecutor cas;
    private final ImmutableStorage storage;

    public DeckService(DeckRepository repository, CommandReceiptService receipts,
                       CompareAndSetExecutor cas, ImmutableStorage storage) {
        this.repository = repository;
        this.receipts = receipts;
        this.cas = cas;
        this.storage = storage;
    }

    @Transactional(readOnly = true, timeout = 10)
    public ObjectNode read(UUID actor, UUID deckId) {
        return own(actor, deckId).toJson();
    }

    @Transactional(readOnly = true, timeout = 10)
    public ObjectNode list(UUID actor, String limit, String cursor) {
        UuidPolicy.requireEntityId(actor, "actor");
        int size = DeckCursor.pageSize(limit);
        List<DeckRecord> rows = repository.page(actor, DeckCursor.decode(cursor), size);
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        var items = result.putArray("items");
        rows.stream().limit(size).forEach(row -> items.add(row.toJson()));
        if (rows.size() > size) {
            DeckRecord last = rows.get(size - 1);
            result.put("nextCursor", new DeckCursor(last.createdAt(), last.deckId()).encode());
        } else result.putNull("nextCursor");
        return result;
    }

    @Transactional(timeout = 10)
    public WriteResult create(UUID actor, DeckCommand command) {
        UuidPolicy.requireEntityId(actor, "actor");
        return execute(actor, command, "deck.create", command.envelope(null, null), () -> {
            UUID deck = UUID.randomUUID();
            UUID revision = UUID.randomUUID();
            UUID scope = UUID.randomUUID();
            NewObject members = emptyRoot("members");
            NewObject exercises = emptyRoot("exercises");
            var time = repository.now();
            DeckRecord row = new DeckRecord(deck, revision, scope, 0, command.title(), command.description(), time, time,
                    members.objectId(), exercises.objectId(), 0, 0);
            repository.insertDeck(deck, actor, scope, revision, time);
            publish(row, actor, null, command, List.of(members, exercises));
            return row.toJson();
        });
    }

    @Transactional(timeout = 10)
    public WriteResult save(UUID actor, UUID deck, long expected, DeckCommand command) {
        // Receipt callbacks are not run on replay: current ACL must be checked outside them.
        own(actor, deck);
        return execute(actor, command, "deck.metadata", command.envelope(deck, expected), () -> {
            DeckRecord previous = own(actor, deck);
            if (previous.rowVersion() != expected) throw new VersionConflictException();
            UUID revision = UUID.randomUUID();
            // Acquire the logical head first, before allocating pins/history. The deferred
            // exact-head FK closes at commit; losing concurrent commands cannot collide on sequence.
            long next = cas.updateOne(expected, () -> repository.advance(actor, deck, revision, expected));
            DeckRecord row = new DeckRecord(deck, revision, previous.scopeId(), next, command.title(), command.description(),
                    previous.createdAt(), repository.now(), previous.membersRootId(), previous.exercisesRootId(),
                    previous.memberCount(), previous.exerciseCount());
            publish(row, actor, previous.revisionId(), command, List.of());
            return row.toJson();
        });
    }

    private WriteResult execute(UUID actor, DeckCommand command, String type, ObjectNode envelope, Supplier<ObjectNode> action) {
        boolean[] applied = {false};
        JsonNode result = receipts.execute(new CommandIdentity(command.commandId(), actor, "deck.catalog", type), envelope, () -> {
            applied[0] = true;
            ObjectNode acknowledgement = JsonNodeFactory.instance.objectNode().put("commandId", command.commandId().toString());
            acknowledgement.set("deck", action.get());
            return acknowledgement;
        });
        return new WriteResult(result, !applied[0]);
    }

    private DeckRecord own(UUID actor, UUID deck) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(deck, "deckId");
        return repository.find(actor, deck).orElseThrow(ResourceNotFoundException::new);
    }

    private void publish(DeckRecord row, UUID actor, UUID parent, DeckCommand command, List<NewObject> objects) {
        List<StagedRoot> roots = storage.stageBatch(new StageBatch(row.scopeId(), actor, objects,
                List.of(row.membersRootId(), row.exercisesRootId())), Duration.ofMinutes(1));
        PinOwner owner = new PinOwner("deck.revision", row.revisionId(), actor);
        UUID membersPin = storage.retain(roots.getFirst(), owner);
        UUID exercisesPin = storage.retain(roots.getLast(), owner);
        repository.insertRevision(row, actor, parent, command, membersPin, exercisesPin);
        roots.forEach(root -> storage.release(row.scopeId(), root.stagingPinId()));
    }

    private static NewObject emptyRoot(String role) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("codec", 1).put("role", role).put("treeHeight", 0);
        payload.putArray("counts");
        return new NewObject(UUID.randomUUID(), ObjectKind.PAGE, (short) 1, (short) 10, payload, List.of());
    }

    public record WriteResult(JsonNode acknowledgement, boolean replayed) {
        public WriteResult { acknowledgement = acknowledgement.deepCopy(); }
        @Override public JsonNode acknowledgement() { return acknowledgement.deepCopy(); }
    }
}
