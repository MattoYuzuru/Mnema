package app.mnema.learning.catalog.authoring;

import app.mnema.learning.platform.api.ResourceLimitExceededException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.CompareAndSetExecutor;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.platform.json.CanonicalJsonHasher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

@Service
public class CaptureService {
    static final int MAX_NOTES = 10_000;
    static final long MAX_ACCOUNT_BYTES = 64L * 1024 * 1024;

    private final AuthoringRepository repository;
    private final CommandReceiptService receipts;
    private final CompareAndSetExecutor cas;
    private final CaptureItemPublisher items;
    private final CanonicalJsonHasher canonical;

    public CaptureService(AuthoringRepository repository, CommandReceiptService receipts, CompareAndSetExecutor cas,
                          CaptureItemPublisher items, CanonicalJsonHasher canonical) {
        this.repository = repository;
        this.receipts = receipts;
        this.cas = cas;
        this.items = items;
        this.canonical = canonical;
    }

    @Transactional(readOnly = true, timeout = 10)
    public ObjectNode list(UUID actor, String limit, String cursor) {
        actor(actor);
        int size = AuthoringCursor.pageSize(limit);
        List<CaptureRecord> rows = repository.captures(actor, AuthoringCursor.decode(cursor), size);
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        var items = result.putArray("items");
        rows.stream().limit(size).forEach(row -> items.add(row.summary()));
        if (rows.size() > size) {
            CaptureRecord last = rows.get(size - 1);
            result.put("nextCursor", new AuthoringCursor(last.createdAt(), last.noteId()).encode());
        } else result.putNull("nextCursor");
        return result;
    }

    @Transactional(readOnly = true, timeout = 10)
    public ObjectNode read(UUID actor, UUID noteId) {
        return own(actor, noteId).summary();
    }

    @Transactional(timeout = 10)
    public WriteResult create(UUID actor, AuthoringCommands.CaptureCreate command) {
        actor(actor);
        ownDeck(actor, command.deckId());
        boolean[] applied = {false};
        JsonNode result = receipts.execute(new CommandIdentity(command.commandId(), actor,
                "authoring.capture", "capture.create"), command.envelope(), () -> {
            applied[0] = true;
            repository.lockOwner(actor);
            requireCaptureCapacity(actor, null, bytes(command.source(), command.text()), true);
            UUID id = UuidPolicy.newPortableId();
            repository.insertCapture(id, actor, command, repository.now());
            ObjectNode acknowledgement = JsonNodeFactory.instance.objectNode()
                    .put("commandId", command.commandId().toString());
            acknowledgement.set("capture", repository.capture(actor, id).orElseThrow().summary());
            return acknowledgement;
        });
        UUID id = UUID.fromString(result.path("capture").path("noteId").textValue());
        own(actor, id);
        return new WriteResult(result, !applied[0]);
    }

    @Transactional(timeout = 10)
    public ObjectNode update(UUID actor, UUID noteId, long expected, AuthoringCommands.CaptureUpdate command) {
        CaptureRecord before = own(actor, noteId);
        if (before.conversionCommandId() != null) throw new VersionConflictException();
        repository.lockOwner(actor);
        requireCaptureCapacity(actor, noteId, bytes(command.source(), command.text()), false);
        cas.updateOne(expected, () -> repository.updateCapture(actor, noteId, expected,
                command.source(), command.text(), repository.now()));
        return own(actor, noteId).summary();
    }

    @Transactional(timeout = 10)
    public ObjectNode archive(UUID actor, UUID noteId, long expected, AuthoringCommands.CaptureArchive command) {
        own(actor, noteId);
        cas.updateOne(expected, () -> repository.archiveCapture(actor, noteId, expected,
                command.archived(), repository.now()));
        return own(actor, noteId).summary();
    }

    @Transactional(timeout = 10)
    public void delete(UUID actor, UUID noteId, long expected) {
        own(actor, noteId);
        cas.updateOne(expected, () -> repository.deleteCapture(actor, noteId, expected));
    }

    public WriteResult convert(UUID actor, UUID noteId, long expected, AuthoringCommands.CaptureConvert command) {
        actor(actor);
        CaptureRecord note = own(actor, noteId);
        ownDeck(actor, note.deckId());
        byte[] hash = conversionHash(command, noteId, expected);
        if (note.conversionCommandId() != null) {
            requireSameConversion(note, command.commandId(), hash);
            return new WriteResult(note.conversionResult(), true);
        }
        if (note.rowVersion() != expected) throw new VersionConflictException();
        boolean[] applied = {false};
        JsonNode publication = items.create(actor, note.deckId(), command.expectedDeckVersion(), command.commandId(),
                command.expectedDeckRevisionId(), command.ordinal(), command.document(),
                result -> applied[0] = commitConversion(actor, noteId, expected, command.commandId(), hash, result));
        CaptureRecord converted = own(actor, noteId);
        requireSameConversion(converted, command.commandId(), hash);
        if (!converted.conversionResult().path("publication").equals(publication)) {
            throw new IllegalStateException("Capture conversion publication mismatch");
        }
        return new WriteResult(converted.conversionResult(), !applied[0]);
    }

    private boolean commitConversion(UUID actor, UUID noteId, long expected, UUID commandId, byte[] hash,
                                     JsonNode publication) {
        CaptureRecord note = repository.lockedCapture(actor, noteId).orElseThrow(ResourceNotFoundException::new);
        if (note.conversionCommandId() != null) {
            requireSameConversion(note, commandId, hash);
            return false;
        }
        if (note.rowVersion() != expected) throw new VersionConflictException();
        JsonNode first = publication.path("changes").path(0);
        if (!first.path("memberKey").isTextual() || !first.path("itemRevisionId").isTextual()) {
            throw new IllegalStateException("Item creation acknowledgement is incomplete");
        }
        UUID member = AuthoringIds.entity(first.path("memberKey").textValue());
        UUID revision = AuthoringIds.entity(first.path("itemRevisionId").textValue());
        ObjectNode result = JsonNodeFactory.instance.objectNode().put("commandId", commandId.toString())
                .put("noteId", noteId.toString()).put("noteVersion", Long.toString(expected + 1))
                .put("sourcePreserved", true);
        result.set("publication", publication.deepCopy());
        cas.updateOne(expected, () -> repository.convertCapture(actor, noteId, expected, commandId, hash,
                member, revision, result, repository.now()));
        return true;
    }

    private byte[] conversionHash(AuthoringCommands.CaptureConvert command, UUID noteId, long expected) {
        ObjectNode envelope = command.envelope();
        envelope.put("noteId", noteId.toString()).put("expectedNoteVersion", Long.toString(expected));
        return canonical.hash(envelope).sha256();
    }

    private static void requireSameConversion(CaptureRecord note, UUID commandId, byte[] hash) {
        if (!commandId.equals(note.conversionCommandId())
                || !MessageDigest.isEqual(note.conversionHash(), hash)) throw new IdempotencyConflictException();
    }

    private void requireCaptureCapacity(UUID actor, UUID except, int bytes, boolean creating) {
        if ((creating && repository.captureCount(actor) >= MAX_NOTES)
                || repository.captureBytes(actor, except) + bytes > MAX_ACCOUNT_BYTES) {
            throw new ResourceLimitExceededException();
        }
    }

    private CaptureRecord own(UUID actor, UUID noteId) {
        actor(actor);
        UuidPolicy.requireEntityId(noteId, "noteId");
        return repository.capture(actor, noteId).orElseThrow(ResourceNotFoundException::new);
    }

    private void ownDeck(UUID actor, UUID deck) {
        if (!repository.ownsDeck(actor, deck)) throw new ResourceNotFoundException();
    }

    private static int bytes(String source, String text) {
        return source.getBytes(StandardCharsets.UTF_8).length + text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static UUID actor(UUID actor) {
        return UuidPolicy.requireEntityId(actor, "actor");
    }

    public record WriteResult(JsonNode acknowledgement, boolean replayed) {
        public WriteResult { acknowledgement = acknowledgement.deepCopy(); }
        @Override public JsonNode acknowledgement() { return acknowledgement.deepCopy(); }
    }
}
