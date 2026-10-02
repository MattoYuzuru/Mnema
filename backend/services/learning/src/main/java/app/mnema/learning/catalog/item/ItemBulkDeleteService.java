package app.mnema.learning.catalog.item;

import app.mnema.learning.catalog.content.pages.CountedPageTypes.Entry;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.idempotency.CommandIdentity;
import app.mnema.learning.platform.idempotency.CommandReceiptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bulk deletion of materials and its consequences preview.
 *
 * <p>The selection is resolved against the Deck revision named by the client, in snapshot ordinal order, and deleted
 * through {@link ItemService#publish} in chunks of at most {@value #CHUNK} {@code delete} changes. Each chunk is one
 * atomic publication: all of its materials or none. Chunk 1 uses the client's preconditions (stale: 412, nothing
 * deleted); every later chunk expects the revision the previous chunk produced, so only a foreign publication in between
 * can stop the run, which is reported as {@code PARTIAL} with the untouched {@code notDeleted} members. Chunk
 * publication command IDs derive deterministically from {@code (commandId, chunk index)} over a selection fixed by the
 * named revision, so a retry after a crash between chunks replays the finished chunks (inner receipts) and continues.
 * The outer receipt, including a {@code PARTIAL} result, is stored once at the end.
 */
@Service
public class ItemBulkDeleteService {
    /** Deletions per atomic publication; the publication limit ({@link ItemPublicationCommand#MAX_CHANGES}). */
    static final int CHUNK = ItemPublicationCommand.MAX_CHANGES;
    private static final Logger log = LoggerFactory.getLogger(ItemBulkDeleteService.class);

    private final ItemService items;
    private final ItemRepository repository;
    private final CommandReceiptService receipts;

    public ItemBulkDeleteService(ItemService items, ItemRepository repository, CommandReceiptService receipts) {
        this.items = items;
        this.repository = repository;
        this.receipts = receipts;
    }

    /** One selected material at the named snapshot: key, ordinal and the item revision published there. */
    private record Member(UUID key, int ordinal, UUID itemRevision) { }

    /**
     * What deleting the selection would remove: {@code materialCount} materials and {@code affectedExerciseCount}, ALL
     * current exercises (enabled and disabled) that assess them. Writes nothing. A stale revision is 412.
     */
    // Not readOnly: reading immutable storage takes a FOR KEY SHARE lock on the member root.
    @Transactional(timeout = 10)
    public ObjectNode preview(UUID actor, UUID deckId, JsonNode body) {
        BulkSelection.fields(body, Set.of("expectedDeckRevisionId", "itemIds", "allInDeck", "except"));
        BulkSelection selection = BulkSelection.read(body);
        ItemRepository.DeckHead head = items.head(actor, deckId);
        if (!head.revisionId().equals(selection.expectedDeckRevisionId())) throw new VersionConflictException();
        List<Member> members = resolve(actor, deckId, selection);
        int exercises = repository.affectedExercises(deckId, members.stream().map(Member::key).toList());
        return JsonNodeFactory.instance.objectNode().put("deckId", deckId.toString())
                .put("deckRevisionId", head.revisionId().toString()).put("deckVersion", Long.toString(head.version()))
                .put("materialCount", members.size()).put("affectedExerciseCount", exercises);
    }

    /** Runs the deletion; see the class comment. Order: ACL (404), replay, validation, 428/412, 422. */
    public ItemService.WriteResult delete(UUID actor, UUID deckId, long expectedDeckVersion, BulkDeleteCommand command) {
        items.head(actor, deckId);
        CommandIdentity identity = new CommandIdentity(command.commandId(), actor, "deck.items", "item.bulk-delete");
        ObjectNode envelope = command.envelope(deckId, expectedDeckVersion);
        var replay = receipts.replay(identity, envelope);
        if (replay.isPresent()) return new ItemService.WriteResult(replay.orElseThrow(), true);

        List<Member> members = resolve(actor, deckId, command.selection());
        UUID revision = command.selection().expectedDeckRevisionId();
        long version = expectedDeckVersion;
        JsonNode last = null;
        int deleted = 0;
        for (int start = 0, index = 0; start < members.size(); start += CHUNK, index++) {
            List<Member> chunk = members.subList(start, Math.min(members.size(), start + CHUNK));
            // Ordinals of one publication refer to its starting revision: earlier chunks removed `start` lower members.
            final int shift = start;
            var changes = chunk.stream().map(member -> new ItemPublicationCommand.Delete(member.key(),
                    member.itemRevision(), member.ordinal() - shift)).toList();
            try {
                last = items.publish(actor, deckId, version,
                        ItemPublicationCommand.deletes(chunkCommandId(command.commandId(), index), revision, changes))
                        .acknowledgement();
            } catch (VersionConflictException | ResourceNotFoundException conflict) {
                if (index == 0) throw conflict;
                log.warn("Bulk delete stopped by a concurrent change deleted={} requested={}", deleted, members.size());
                break;
            }
            deleted += chunk.size();
            revision = UUID.fromString(last.path("deckRevisionId").stringValue(null));
            version = Long.parseLong(last.path("deckVersion").stringValue(null));
        }
        ObjectNode result = result(command.commandId(), deckId, members, deleted, last);
        boolean[] applied = {false};
        JsonNode stored = receipts.execute(identity, envelope, () -> {
            applied[0] = true;
            return result;
        });
        return new ItemService.WriteResult(stored, !applied[0]);
    }

    private static ObjectNode result(UUID commandId, UUID deckId, List<Member> members, int deleted, JsonNode last) {
        ObjectNode result = JsonNodeFactory.instance.objectNode().put("commandId", commandId.toString())
                .put("deckId", deckId.toString()).put("status", deleted == members.size() ? "COMPLETED" : "PARTIAL")
                .put("requested", members.size()).put("deleted", deleted);
        var notDeleted = result.putArray("notDeleted");
        members.subList(deleted, members.size()).forEach(member -> notDeleted.add(member.key().toString()));
        if (deleted == members.size()) result.putNull("stopReason"); else result.put("stopReason", "VERSION_CONFLICT");
        result.put("deckRevisionId", last.path("deckRevisionId").stringValue(null))
                .put("deckVersion", last.path("deckVersion").stringValue(null))
                .put("memberCount", last.path("memberCount").intValue());
        return result;
    }

    /** The selection at its named revision, sorted by ordinal; empty, unknown or oversized selections are rejected. */
    private List<Member> resolve(UUID actor, UUID deckId, BulkSelection selection) {
        ItemRepository.DeckHead at = items.deckAt(actor, deckId, selection.expectedDeckRevisionId());
        List<ItemRepository.Position> positions;
        if (selection.allInDeck()) {
            if ((long) at.memberCount() - selection.except().size() > BulkSelection.MAX_SELECTION) {
                throw new BulkSelectionTooLargeException();
            }
            List<Entry> entries = items.membersAt(at);
            Set<UUID> keys = new HashSet<>();
            entries.forEach(entry -> keys.add(entry.key()));
            if (!keys.containsAll(selection.except())) throw new ResourceNotFoundException();
            Set<UUID> except = new HashSet<>(selection.except());
            positions = new ArrayList<>();
            for (int ordinal = 0; ordinal < entries.size(); ordinal++) {
                Entry entry = entries.get(ordinal);
                if (!except.contains(entry.key())) positions.add(new ItemRepository.Position(entry.key(), ordinal,
                        entry.target().objectId()));
            }
            if (positions.isEmpty()) throw new InvalidRequestException();
            if (positions.size() > BulkSelection.MAX_SELECTION) throw new BulkSelectionTooLargeException();
        } else {
            positions = new ArrayList<>(repository.memberPositions(at, selection.itemIds()));
            if (positions.size() != selection.itemIds().size()) throw new ResourceNotFoundException();
            positions.sort(Comparator.comparingInt(ItemRepository.Position::ordinal));
        }
        Map<UUID, UUID> revisions = repository.revisionsByDescriptor(deckId,
                positions.stream().map(ItemRepository.Position::descriptorRootId).toList());
        List<Member> result = new ArrayList<>(positions.size());
        for (ItemRepository.Position position : positions) {
            UUID itemRevision = revisions.get(position.descriptorRootId());
            if (itemRevision == null) throw new IllegalStateException("Member projection is inconsistent");
            Member member = new Member(position.memberKey(), position.ordinal(), itemRevision);
            result.add(member);
        }
        return result;
    }

    /** A valid command UUID (v4 layout) derived from {@code (commandId, chunk)}; the same input always yields it. */
    static UUID chunkCommandId(UUID commandId, int chunk) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((commandId + "/bulk-delete/" + chunk).getBytes(StandardCharsets.US_ASCII));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x40);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            ByteBuffer buffer = ByteBuffer.wrap(hash);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
