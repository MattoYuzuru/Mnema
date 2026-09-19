package app.mnema.learning.catalog.authoring;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceLimitExceededException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.CompareAndSetExecutor;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class DraftService {
    static final int MAX_ACTIVE_DRAFTS = 200;
    static final long MAX_ACCOUNT_BYTES = 20L * 1024 * 1024;

    private final AuthoringRepository repository;
    private final CommandReceiptService receipts;
    private final CompareAndSetExecutor cas;

    public DraftService(AuthoringRepository repository, CommandReceiptService receipts, CompareAndSetExecutor cas) {
        this.repository = repository;
        this.receipts = receipts;
        this.cas = cas;
    }

    @Transactional(readOnly = true, timeout = 10)
    public ObjectNode list(UUID actor, String limit, String cursor) {
        actor(actor);
        int size = AuthoringCursor.pageSize(limit);
        List<DraftRecord> rows = repository.drafts(actor, AuthoringCursor.decode(cursor), size);
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        var items = result.putArray("items");
        rows.stream().limit(size).forEach(row -> items.add(row.summary()));
        if (rows.size() > size) {
            DraftRecord last = rows.get(size - 1);
            result.put("nextCursor", new AuthoringCursor(last.createdAt(), last.draftId()).encode());
        } else result.putNull("nextCursor");
        return result;
    }

    @Transactional(readOnly = true, timeout = 10)
    public ObjectNode read(UUID actor, UUID draftId) {
        return own(actor, draftId).detail();
    }

    @Transactional(timeout = 10)
    public WriteResult create(UUID actor, AuthoringCommands.DraftCreate command) {
        actor(actor);
        ownDeck(actor, command.deckId());
        if (command.memberKey() != null
                && !repository.ownsBase(actor, command.deckId(), command.memberKey(), command.baseRevisionId())) {
            throw new ResourceNotFoundException();
        }
        boolean[] applied = {false};
        JsonNode stored = receipts.execute(new CommandIdentity(command.commandId(), actor,
                "authoring.drafts", "draft.create"), command.envelope(), () -> {
            applied[0] = true;
            repository.lockOwner(actor);
            repository.deleteExpiredDrafts(actor);
            int bytes = repository.storedDocumentBytes(command.document().toJson());
            if (bytes > 1_048_576) throw new InvalidRequestException();
            requireDraftCapacity(actor, null, bytes, true);
            UUID id = UuidPolicy.newPortableId();
            repository.insertDraft(id, actor, command, repository.now());
            ObjectNode result = JsonNodeFactory.instance.objectNode().put("commandId", command.commandId().toString());
            result.set("draft", repository.draft(actor, id).orElseThrow().summary());
            return result;
        });
        ObjectNode acknowledgement = withDocument(stored, command.document().toJson());
        UUID id = UUID.fromString(acknowledgement.path("draft").path("draftId").textValue());
        own(actor, id);
        return new WriteResult(acknowledgement, !applied[0]);
    }

    @Transactional(timeout = 10)
    public WriteResult update(UUID actor, UUID draftId, long expected, AuthoringCommands.DraftUpdate command) {
        own(actor, draftId);
        boolean[] applied = {false};
        JsonNode stored = receipts.execute(new CommandIdentity(command.commandId(), actor,
                "authoring.drafts", "draft.update"), command.envelope(draftId, expected), () -> {
            applied[0] = true;
            own(actor, draftId);
            repository.lockOwner(actor);
            int bytes = repository.storedDocumentBytes(command.document().toJson());
            if (bytes > 1_048_576) throw new InvalidRequestException();
            requireDraftCapacity(actor, draftId, bytes, false);
            long next = cas.updateOne(expected,
                    () -> repository.updateDraft(actor, draftId, expected, command.document().toJson(), repository.now()));
            DraftRecord row = repository.draft(actor, draftId).orElseThrow();
            if (row.rowVersion() != next) throw new IllegalStateException("Draft CAS result mismatch");
            ObjectNode result = JsonNodeFactory.instance.objectNode().put("commandId", command.commandId().toString());
            result.set("draft", row.summary());
            return result;
        });
        return new WriteResult(withDocument(stored, command.document().toJson()), !applied[0]);
    }

    @Transactional(timeout = 10)
    public void delete(UUID actor, UUID draftId, long expected) {
        own(actor, draftId);
        cas.updateOne(expected, () -> repository.deleteDraft(actor, draftId, expected));
    }

    private void requireDraftCapacity(UUID actor, UUID except, int bytes, boolean creating) {
        if ((creating && repository.activeDraftCount(actor) >= MAX_ACTIVE_DRAFTS)
                || repository.activeDraftBytes(actor, except) + bytes > MAX_ACCOUNT_BYTES) {
            throw new ResourceLimitExceededException();
        }
    }

    private DraftRecord own(UUID actor, UUID draft) {
        actor(actor);
        UuidPolicy.requireEntityId(draft, "draftId");
        return repository.draft(actor, draft).orElseThrow(ResourceNotFoundException::new);
    }

    private void ownDeck(UUID actor, UUID deck) {
        if (!repository.ownsDeck(actor, deck)) throw new ResourceNotFoundException();
    }

    private static UUID actor(UUID actor) {
        return UuidPolicy.requireEntityId(actor, "actor");
    }

    private static ObjectNode withDocument(JsonNode stored, JsonNode document) {
        ObjectNode result = (ObjectNode) stored.deepCopy();
        ((ObjectNode) result.path("draft")).set("document", document.deepCopy());
        return result;
    }

    public record WriteResult(JsonNode acknowledgement, boolean replayed) {
        public WriteResult { acknowledgement = acknowledgement.deepCopy(); }
        @Override public JsonNode acknowledgement() { return acknowledgement.deepCopy(); }
    }
}
