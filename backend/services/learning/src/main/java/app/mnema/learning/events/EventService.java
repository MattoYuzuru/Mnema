package app.mnema.learning.events;

import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.admin.AdminAudit;
import app.mnema.learning.platform.concurrency.CompareAndSetExecutor;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;
import java.util.function.Supplier;

@Service
public class EventService {
    private final AdminAudit audit;
    private final EventRepository repository;
    private final EventAdminAccess access;
    private final CommandReceiptService receipts;
    private final CompareAndSetExecutor cas;

    EventService(EventRepository repository, EventAdminAccess access, CommandReceiptService receipts, CompareAndSetExecutor cas, AdminAudit audit) {
        this.audit = audit;
        this.repository = repository;
        this.access = access;
        this.receipts = receipts;
        this.cas = cas;
    }

    @Transactional(readOnly = true, timeout = 10)
    public ObjectNode publicPage(String cursor) { return page(true, cursor); }

    @Transactional(readOnly = true, timeout = 10)
    public ObjectNode adminPage(UUID actor, String cursor) {
        access.require(actor);
        return page(false, cursor);
    }

    @Transactional(timeout = 10)
    public WriteResult create(UUID actor, EventRequests.Command command) {
        access.require(actor);
        return execute(actor, command.commandId(), "event.create", command.envelope(null, null),
                () -> {
                    EventRecord row = repository.create(UUID.randomUUID(), command);
                    audit.append(actor, "EVENT_CREATE", row.eventId(), command.commandId());
                    return acknowledgement(command.commandId(), row);
                });
    }

    @Transactional(timeout = 10)
    public WriteResult replace(UUID actor, UUID id, long version, EventRequests.Command command) {
        access.require(actor);
        return execute(actor, command.commandId(), "event.replace", command.envelope(id, version), () -> {
            repository.find(id).orElseThrow(ResourceNotFoundException::new);
            cas.updateOne(version, () -> repository.replace(id, version, command));
            audit.append(actor, "EVENT_REPLACE", id, command.commandId());
            return acknowledgement(command.commandId(), repository.find(id).orElseThrow(ResourceNotFoundException::new));
        });
    }

    @Transactional(timeout = 10)
    public WriteResult delete(UUID actor, UUID id, long version, UUID commandId) {
        access.require(actor);
        ObjectNode envelope = JsonNodeFactory.instance.objectNode().put("eventId", id.toString())
                .put("expectedVersion", Long.toString(version));
        return execute(actor, commandId, "event.delete", envelope, () -> {
            repository.find(id).orElseThrow(ResourceNotFoundException::new);
            cas.updateOne(version, () -> repository.delete(id, version));
            audit.append(actor, "EVENT_DELETE", id, commandId);
            return JsonNodeFactory.instance.objectNode().put("commandId", commandId.toString()).put("eventId", id.toString());
        });
    }

    private ObjectNode page(boolean publicOnly, String encoded) {
        var rows = repository.page(publicOnly, EventRequests.cursor(encoded));
        ObjectNode page = JsonNodeFactory.instance.objectNode();
        var items = page.putArray("items");
        rows.stream().limit(EventRequests.PAGE_SIZE).forEach(row -> items.add(publicOnly ? row.publicView() : row.adminView()));
        if (rows.size() > EventRequests.PAGE_SIZE) {
            EventRecord last = rows.get(EventRequests.PAGE_SIZE - 1);
            page.put("nextCursor", new EventRequests.Cursor(last.eventDate(), last.eventId()).encode());
        } else page.putNull("nextCursor");
        return page;
    }

    private WriteResult execute(UUID actor, UUID commandId, String type, ObjectNode envelope, Supplier<ObjectNode> action) {
        boolean[] applied = {false};
        JsonNode result = receipts.execute(new CommandIdentity(commandId, actor, "product.events", type), envelope, () -> {
            applied[0] = true;
            return action.get();
        });
        return new WriteResult(result, !applied[0]);
    }

    private static ObjectNode acknowledgement(UUID commandId, EventRecord row) {
        ObjectNode value = JsonNodeFactory.instance.objectNode().put("commandId", commandId.toString());
        value.set("event", row.adminView());
        return value;
    }

    public record WriteResult(JsonNode acknowledgement, boolean replayed) {
        public WriteResult { acknowledgement = acknowledgement.deepCopy(); }
        @Override public JsonNode acknowledgement() { return acknowledgement.deepCopy(); }
    }
}
