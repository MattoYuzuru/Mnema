package app.mnema.learning.catalog.item;

import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.UUID;

/**
 * The deck-local «Эталон» flag. It is a user preference on a LearningItem, not content: setting it creates no Deck
 * revision or publication and never touches {@code itemVersion}. At most {@value #MAX_EXEMPLARS} materials per Deck are
 * exemplars; the limit is enforced under a per-Deck advisory lock inside the command transaction.
 */
@Service
public class ItemExemplarService {
    /** Maximum exemplars per Deck ({@code contracts/decks/hub.json} {@code constants.exemplarLimit}). */
    public static final int MAX_EXEMPLARS = 10;

    private final ItemRepository repository;
    private final CommandReceiptService receipts;

    public ItemExemplarService(ItemRepository repository, CommandReceiptService receipts) {
        this.repository = repository;
        this.receipts = receipts;
    }

    /**
     * Sets the desired flag value. Order: ACL (404), receipt replay, stale item revision (412), limit (422). Setting the
     * value an item already has succeeds with {@code changed=false}. Receipt and flag commit together.
     */
    @Transactional(timeout = 10)
    public ItemService.WriteResult set(UUID actor, UUID deckId, UUID memberKey, ExemplarCommand command) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(deckId, "deckId");
        UuidPolicy.requireEntityId(memberKey, "memberKey");
        repository.deck(actor, deckId).orElseThrow(ResourceNotFoundException::new);
        CommandIdentity identity = new CommandIdentity(command.commandId(), actor, "deck.items", "item.exemplar");
        ObjectNode envelope = command.envelope(deckId, memberKey);
        var replay = receipts.replay(identity, envelope);
        if (replay.isPresent()) return new ItemService.WriteResult(replay.orElseThrow(), true);
        boolean[] applied = {false};
        JsonNode acknowledgement = receipts.execute(identity, envelope, () -> {
            applied[0] = true;
            return apply(actor, deckId, memberKey, command);
        });
        return new ItemService.WriteResult(acknowledgement, !applied[0]);
    }

    private ObjectNode apply(UUID actor, UUID deckId, UUID memberKey, ExemplarCommand command) {
        repository.lockExemplars(deckId);
        ItemRecord item = repository.headItem(actor, deckId, memberKey).orElseThrow(ResourceNotFoundException::new);
        if (!item.revisionId().equals(command.expectedItemRevisionId())) throw new VersionConflictException();
        List<UUID> marked = repository.exemplars(deckId);
        boolean already = marked.contains(memberKey);
        boolean changed = already != command.exemplar();
        int count = marked.size();
        if (changed && command.exemplar()) {
            if (count >= MAX_EXEMPLARS) throw new ExemplarLimitReachedException();
            repository.insertExemplar(deckId, memberKey, repository.now());
            count++;
        } else if (changed) {
            repository.deleteExemplar(deckId, memberKey);
            count--;
        }
        return JsonNodeFactory.instance.objectNode().put("commandId", command.commandId().toString())
                .put("deckId", deckId.toString()).put("memberKey", memberKey.toString())
                .put("itemRevisionId", item.revisionId().toString()).put("exemplar", command.exemplar())
                .put("changed", changed).put("exemplarCount", count);
    }
}
