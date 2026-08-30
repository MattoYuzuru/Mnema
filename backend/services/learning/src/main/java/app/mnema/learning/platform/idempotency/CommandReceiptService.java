package app.mnema.learning.platform.idempotency;

import app.mnema.learning.platform.json.CanonicalJsonHasher;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Executes a command and stores its result atomically with all JDBC work performed by the action.
 * A PostgreSQL transaction-scoped advisory lock serializes the same command identifier without
 * leaving an in-progress receipt behind after rollback.
 */
@Service
public class CommandReceiptService {

    private final CommandReceiptRepository repository;
    private final CanonicalJsonHasher canonicalJsonHasher;

    public CommandReceiptService(
            CommandReceiptRepository repository,
            CanonicalJsonHasher canonicalJsonHasher
    ) {
        this.repository = repository;
        this.canonicalJsonHasher = canonicalJsonHasher;
    }

    @Transactional
    public JsonNode execute(CommandIdentity identity, JsonNode payload, Supplier<JsonNode> action) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(action, "action");
        byte[] payloadHash = canonicalJsonHasher.hash(payload).sha256();

        repository.lock(identity.commandId());
        var existing = repository.find(identity.commandId());
        if (existing.isPresent()) {
            if (!existing.get().matches(identity, payloadHash)) {
                throw new IdempotencyConflictException();
            }
            return existing.get().result().deepCopy();
        }

        JsonNode result = Objects.requireNonNull(action.get(), "Command result must not be null");
        return repository.insert(identity, payloadHash, result);
    }
}
