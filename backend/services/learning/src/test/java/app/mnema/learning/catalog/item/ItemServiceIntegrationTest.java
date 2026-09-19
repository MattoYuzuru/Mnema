package app.mnema.learning.catalog.item;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.platform.json.CanonicalJsonHasher;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ItemServiceIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static JsonNode nativeDocument;

    @Autowired private ItemService service;
    @Autowired private DeckService decks;
    @Autowired private ItemRepository repository;
    @Autowired private JdbcClient jdbc;
    @Autowired private ImmutableStorage storage;
    @Autowired private PlatformTransactionManager transactions;

    @BeforeAll
    static void fixture() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("contracts/content/native-v1/valid/mixed.json"))) root = root.getParent();
        nativeDocument = JSON.readTree(Files.readString(root.resolve("contracts/content/native-v1/valid/mixed.json")));
    }

    @Test
    void ownerCreatesPagesReadsSavesAndReloadsNativeContentWithImmutableHistory() {
        UUID actor = UUID.randomUUID();
        UUID deck = createDeck(actor);
        JsonNode deckBefore = decks.read(actor, deck);
        UUID commandId = UUID.randomUUID();
        var created = service.publish(actor, deck, 0, create(commandId, deckBefore, nativeDocument, null));
        JsonNode createdChange = created.acknowledgement().path("changes").get(0);
        UUID member = UUID.fromString(createdChange.path("memberKey").textValue());
        UUID firstRevision = UUID.fromString(createdChange.path("itemRevisionId").textValue());

        assertThat(created.replayed()).isFalse();
        assertThat(service.list(actor, deck, "1", null).path("items")).hasSize(1);
        JsonNode first = service.read(actor, deck, member, null);
        assertNative(first.path("document"), nativeDocument);
        assertThat(first.path("itemVersion").textValue()).isEqualTo("0");
        assertThat(first.toString()).doesNotContain("rootId", "reuseScope", "pinId");
        assertThat(service.publish(actor, deck, 0, create(commandId, deckBefore, nativeDocument, null)).replayed()).isTrue();
        assertThat(count("learning_item", "deck_id", deck)).isOne();

        ObjectNode changed = nativeDocument.deepCopy();
        ((ObjectNode) changed.path("root").path("content").get(0).path("content").get(0).path("attrs"))
                .put("text", "Изменённый заголовок");
        JsonNode head = decks.read(actor, deck);
        UUID saveCommand = UUID.randomUUID();
        var saved = service.publish(actor, deck, 1, save(saveCommand, head, member, firstRevision, changed, null));
        UUID secondRevision = UUID.fromString(saved.acknowledgement().path("changes").get(0)
                .path("itemRevisionId").textValue());
        assertNative(service.read(actor, deck, member, null).path("document"), changed);
        JsonNode historical = service.read(actor, deck, member, firstRevision);
        assertNative(historical.path("document"), nativeDocument);
        assertThat(historical.path("deckVersion").textValue()).isEqualTo("1");
        assertThat(secondRevision).isNotEqualTo(firstRevision);
        assertThat(count("item_revision", "deck_id", deck)).isEqualTo(2);
        assertThat(count("deck_revision", "deck_id", deck)).isEqualTo(3);

        ItemRepository.DeckHead itemHead = repository.deck(actor, deck).orElseThrow();
        decks.save(actor, deck, 2, new DeckCommand(UUID.randomUUID(), "metadata only", "unchanged roots"));
        ItemRepository.DeckHead metadataHead = repository.deck(actor, deck).orElseThrow();
        assertThat(metadataHead.membersRootId()).isEqualTo(itemHead.membersRootId());
        assertThat(metadataHead.exerciseCount()).isEqualTo(itemHead.exerciseCount());
        assertThat(storage.collectBatch(itemHead.scopeId(), Instant.now().plusSeconds(1), 8).deleted()).isZero();
    }

    @Test
    void boundedBulkPublicationPreservesOrderDeletesProjectionAndKeepsOldRevisionReadable() {
        UUID actor = UUID.randomUUID();
        UUID deck = createDeck(actor);
        JsonNode before = decks.read(actor, deck);
        List<UUID> members = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        ArrayNode creates = JSON.createArrayNode();
        for (UUID member : members) creates.add(change("create", member, null, nativeDocument, null));
        var created = service.publish(actor, deck, 0, bulk(UUID.randomUUID(), before, creates));
        UUID removedRevision = UUID.fromString(created.acknowledgement().path("changes").get(1)
                .path("itemRevisionId").textValue());
        UUID movedRevision = UUID.fromString(created.acknowledgement().path("changes").get(2)
                .path("itemRevisionId").textValue());
        assertThat(service.list(actor, deck, "2", null).path("nextCursor").isTextual()).isTrue();

        JsonNode currentDeck = decks.read(actor, deck);
        ArrayNode changes = JSON.createArrayNode();
        changes.add(change("reorder", members.get(2), movedRevision, null, 0));
        changes.add(change("delete", members.get(1), removedRevision, null, null));
        service.publish(actor, deck, 1, bulk(UUID.randomUUID(), currentDeck, changes));

        JsonNode page = service.list(actor, deck, "100", null);
        assertThat(page.path("items")).hasSize(2);
        assertThat(page.path("items").get(0).path("memberKey").textValue()).isEqualTo(members.get(2).toString());
        assertThat(page.path("items").get(1).path("memberKey").textValue()).isEqualTo(members.get(0).toString());
        assertThatThrownBy(() -> service.read(actor, deck, members.get(1), null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertNative(service.read(actor, deck, members.get(1), removedRevision).path("document"), nativeDocument);
        assertThat(jdbc.sql("SELECT string_agg(change_kind,',' ORDER BY change_ordinal) "
                        + "FROM app_learning.deck_item_change WHERE deck_id=:deck AND deck_sequence=2")
                .param("deck", deck).query(String.class).single()).isEqualTo("reorder,delete");
    }

    @Test
    void staleChangedReplayAndForeignIdsFailClosedWithoutPartialPublication() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID deck = createDeck(actor);
        JsonNode before = decks.read(actor, deck);
        UUID createCommand = UUID.randomUUID();
        var created = service.publish(actor, deck, 0, create(createCommand, before, nativeDocument, null));
        UUID member = UUID.fromString(created.acknowledgement().path("changes").get(0).path("memberKey").textValue());
        UUID revision = UUID.fromString(created.acknowledgement().path("changes").get(0).path("itemRevisionId").textValue());
        assertThatThrownBy(() -> service.publish(actor, deck, 0,
                create(createCommand, before, nativeDocument, 0))).isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> service.publish(actor, deck, 0,
                create(UUID.randomUUID(), before, nativeDocument, null))).isInstanceOf(VersionConflictException.class);
        assertThatThrownBy(() -> service.read(UUID.randomUUID(), deck, member, null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.read(actor, deck, UUID.randomUUID(), revision))
                .isInstanceOf(ResourceNotFoundException.class);

        JsonNode currentDeck = decks.read(actor, deck);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var left = executor.submit(() -> race(start, actor, deck, currentDeck, member, revision));
            var right = executor.submit(() -> race(start, actor, deck, currentDeck, member, revision));
            start.countDown();
            assertThat(List.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("applied", "conflict");
        }
        assertThat(count("item_revision", "deck_id", deck)).isEqualTo(2);
        assertThat(jdbc.sql("SELECT pin_id,root_id,owner_kind,owner_id,expires_at FROM app_learning.storage_pin "
                        + "WHERE pin_kind='staging' AND actor_id=:actor ORDER BY pin_id")
                .param("actor", actor).query().listOfRows()).isEmpty();
    }

    @Test
    void preparationSurvivesOuterRollbackWhilePublicationRemainsAtomicAndRetryable() {
        UUID actor = UUID.randomUUID();
        UUID deck = createDeck(actor);
        JsonNode before = decks.read(actor, deck);
        UUID command = UUID.randomUUID();
        long objects = count("storage_object", "reuse_scope_id", repository.deck(actor, deck).orElseThrow().scopeId());
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.publish(actor, deck, 0, create(command, before, nativeDocument, null));
            status.setRollbackOnly();
        });
        assertThat(decks.read(actor, deck).path("rowVersion").textValue()).isEqualTo("0");
        assertThat(service.list(actor, deck, null, null).path("items")).isEmpty();
        assertThat(count("item_revision", "deck_id", deck)).isZero();
        assertThat(count("command_receipt", "command_id", command)).isZero();
        UUID scope = repository.deck(actor, deck).orElseThrow().scopeId();
        assertThat(count("storage_object", "reuse_scope_id", scope)).isGreaterThan(objects);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.storage_pin "
                        + "WHERE reuse_scope_id=:scope AND pin_kind='staging' AND actor_id=:actor")
                .param("scope", scope).param("actor", actor).query(Long.class).single()).isPositive();

        var retried = service.publish(actor, deck, 0, create(command, before, nativeDocument, null));
        assertThat(retried.replayed()).isFalse();
        assertThat(service.list(actor, deck, null, null).path("items")).hasSize(1);
        assertThat(count("command_receipt", "command_id", command)).isOne();
    }

    @Test
    void explicitStructuralInsertPublishesNewTopologyAndPreservesOldSnapshot() {
        UUID actor = UUID.randomUUID();
        UUID deck = createDeck(actor);
        JsonNode before = decks.read(actor, deck);
        var created = service.publish(actor, deck, 0, create(UUID.randomUUID(), before, nativeDocument, null));
        UUID member = UUID.fromString(created.acknowledgement().path("changes").get(0).path("memberKey").textValue());
        UUID oldRevision = UUID.fromString(created.acknowledgement().path("changes").get(0)
                .path("itemRevisionId").textValue());
        ObjectNode next = nativeDocument.deepCopy();
        ObjectNode paragraph = ((ArrayNode) next.path("root").path("content")).addObject()
                .put("id", UUID.randomUUID().toString()).put("type", "paragraph").put("version", 1);
        paragraph.putObject("attrs"); paragraph.putArray("content");
        JsonNode deckHead = decks.read(actor, deck);
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", deckHead.path("revisionId").textValue())
                .put("expectedItemRevisionId", oldRevision.toString());
        body.set("document", next);
        body.putObject("edit").put("type", "insert")
                .put("parentId", nativeDocument.path("root").path("id").textValue())
                .put("childIndex", nativeDocument.path("root").path("content").size());
        service.publish(actor, deck, 1, ItemPublicationCommand.readSave(bytes(body), member));
        assertNative(service.read(actor, deck, member, null).path("document"), next);
        assertNative(service.read(actor, deck, member, oldRevision).path("document"), nativeDocument);
    }

    @Test
    void databaseRejectsMutableLogicalHistoryAndCrossItemProjection() {
        UUID actor = UUID.randomUUID();
        UUID deck = createDeck(actor);
        JsonNode before = decks.read(actor, deck);
        var created = service.publish(actor, deck, 0, create(UUID.randomUUID(), before, nativeDocument, null));
        UUID member = UUID.fromString(created.acknowledgement().path("changes").get(0).path("memberKey").textValue());
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.learning_item SET created_at=created_at "
                        + "WHERE deck_id=:deck AND member_key=:member").param("deck", deck).param("member", member).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.item_revision SET format_version=1 "
                        + "WHERE deck_id=:deck AND member_key=:member").param("deck", deck).param("member", member).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM app_learning.deck_item_change "
                        + "WHERE deck_id=:deck AND member_key=:member").param("deck", deck).param("member", member).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                jdbc.sql("UPDATE app_learning.deck_head_item SET revision_id=:revision "
                                + "WHERE deck_id=:deck AND member_key=:member")
                        .param("revision", UUID.randomUUID()).param("deck", deck).param("member", member).update()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private String race(CountDownLatch start, UUID actor, UUID deck, JsonNode deckHead, UUID member, UUID revision)
            throws InterruptedException {
        start.await();
        try {
            ObjectNode changed = nativeDocument.deepCopy();
            ((ObjectNode) changed.path("root").path("content").get(0).path("content").get(0).path("attrs"))
                    .put("text", UUID.randomUUID().toString());
            service.publish(actor, deck, 1, save(UUID.randomUUID(), deckHead, member, revision, changed, null));
            return "applied";
        } catch (VersionConflictException expected) {
            return "conflict";
        }
    }

    private UUID createDeck(UUID actor) {
        return UUID.fromString(decks.create(actor, new DeckCommand(UUID.randomUUID(), "Deck", "Description"))
                .acknowledgement().path("deck").path("deckId").textValue());
    }

    private static ItemPublicationCommand create(UUID command, JsonNode deck, JsonNode document, Integer ordinal) {
        ObjectNode body = JSON.createObjectNode().put("commandId", command.toString())
                .put("expectedDeckRevisionId", deck.path("revisionId").textValue());
        body.set("document", document.deepCopy());
        if (ordinal != null) body.put("ordinal", ordinal);
        return ItemPublicationCommand.readCreate(bytes(body));
    }

    private static ItemPublicationCommand save(UUID command, JsonNode deck, UUID member, UUID revision,
                                               JsonNode document, Integer ordinal) {
        ObjectNode body = JSON.createObjectNode().put("commandId", command.toString())
                .put("expectedDeckRevisionId", deck.path("revisionId").textValue())
                .put("expectedItemRevisionId", revision.toString());
        body.set("document", document.deepCopy());
        if (ordinal != null) body.put("ordinal", ordinal);
        return ItemPublicationCommand.readSave(bytes(body), member);
    }

    private static ItemPublicationCommand bulk(UUID command, JsonNode deck, ArrayNode changes) {
        ObjectNode body = JSON.createObjectNode().put("commandId", command.toString())
                .put("expectedDeckRevisionId", deck.path("revisionId").textValue());
        body.set("changes", changes);
        return ItemPublicationCommand.readBulk(bytes(body));
    }

    private static ObjectNode change(String operation, UUID member, UUID revision, JsonNode document, Integer ordinal) {
        ObjectNode result = JSON.createObjectNode().put("operation", operation).put("memberKey", member.toString());
        if (revision != null) result.put("expectedItemRevisionId", revision.toString());
        if (document != null) result.set("document", document.deepCopy());
        if (ordinal != null) result.put("ordinal", ordinal);
        return result;
    }

    private static ByteArrayInputStream bytes(JsonNode value) {
        try { return new ByteArrayInputStream(JSON.writeValueAsBytes(value)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static void assertNative(JsonNode actual, JsonNode expected) {
        CanonicalJsonHasher canonical = new CanonicalJsonHasher();
        assertThat(canonical.canonicalBytes(actual)).containsExactly(canonical.canonicalBytes(expected));
    }

    private long count(String table, String column, UUID value) {
        return jdbc.sql("SELECT count(*) FROM app_learning." + table + " WHERE " + column + "=:value")
                .param("value", value).query(Long.class).single();
    }
}
