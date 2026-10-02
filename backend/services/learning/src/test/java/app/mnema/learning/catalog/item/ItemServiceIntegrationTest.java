package app.mnema.learning.catalog.item;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.platform.json.CanonicalJsonHasher;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.support.PostgresIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
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
    private static JsonNode richDocument;
    private static JsonNode codeDocument;

    @Autowired private ItemService service;
    @Autowired private DeckService decks;
    @Autowired private ItemRepository repository;
    @Autowired private JdbcClient jdbc;
    @Autowired private ImmutableStorage storage;
    @Autowired private MediaCatalog mediaCatalog;
    @Autowired private PlatformTransactionManager transactions;

    @BeforeAll
    static void fixture() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("contracts/content/native-v1/valid/mixed.json"))) root = root.getParent();
        nativeDocument = JSON.readTree(Files.readString(root.resolve("contracts/content/native-v1/valid/mixed.json")));
        richDocument = JSON.readTree(Files.readString(root.resolve("contracts/content/native-v1/valid/rich.json")));
        codeDocument = JSON.readTree(Files.readString(root.resolve("contracts/content/native-v1/valid/code.json")));
    }

    @Test
    void materialListsProjectFirstTextAndReuseTheImmutableRevisionPreview() {
        UUID actor = UUID.randomUUID(); UUID deck = createDeck(actor);
        service.publish(actor, deck, 0, create(UUID.randomUUID(), decks.read(actor, deck), nativeDocument, null));
        var first = service.list(actor, deck, "20", null).path("items").get(0);
        assertThat(first.path("title").stringValue(null)).isEqualTo("Память, письмо и проверяемые знания");
        assertThat(first.has("document")).isFalse();
        assertThat(service.list(actor, deck, "20", null).path("items").get(0).path("title"))
                .isEqualTo(first.path("title"));
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.item_preview WHERE deck_id=:deck")
                .param("deck", deck).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void publicationPinsOnlySupportedOwnedMediaInTheSameTransaction() {
        UUID actor = UUID.randomUUID();
        UUID deck = createDeck(actor);
        ObjectNode document = (ObjectNode) richDocument.deepCopy();
        ArrayNode nodes = (ArrayNode) document.path("root").path("content");
        for (int index = 0; index < 3; index++) {
            UUID asset = mediaCatalog.reserve(actor, UUID.randomUUID(), MediaCatalog.Origin.UPLOAD);
            ((ObjectNode) nodes.get(index).path("attrs")).put("assetId", asset.toString());
        }
        JsonNode before = decks.read(actor, deck);
        var created = service.publish(actor, deck, 0, create(UUID.randomUUID(), before, document, null));
        UUID member = UUID.fromString(created.acknowledgement().path("changes").get(0).path("memberKey").stringValue(null));
        UUID revision = UUID.fromString(created.acknowledgement().path("changes").get(0).path("itemRevisionId").stringValue(null));
        assertNative(service.read(actor, deck, member, revision).path("document"), document);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.content_media_ref WHERE deck_id=:deck "
                        + "AND member_key=:member AND revision_id=:revision")
                .param("deck", deck).param("member", member).param("revision", revision)
                .query(Long.class).single()).isEqualTo(3L);

        ObjectNode next = document.deepCopy();
        ((ObjectNode) next.path("root").path("content").get(0)).put("version", 2);
        JsonNode head = decks.read(actor, deck);
        var saved = service.publish(actor, deck, 1,
                save(UUID.randomUUID(), head, member, revision, next, null));
        UUID nextRevision = UUID.fromString(saved.acknowledgement().path("changes").get(0)
                .path("itemRevisionId").stringValue(null));
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.content_media_ref WHERE revision_id=:revision")
                .param("revision", nextRevision).query(Long.class).single()).isEqualTo(2L);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.content_media_ref WHERE revision_id=:revision")
                .param("revision", revision).query(Long.class).single()).isEqualTo(3L);

        ObjectNode foreign = document.deepCopy();
        UUID otherAsset = mediaCatalog.reserve(UUID.randomUUID(), UUID.randomUUID(), MediaCatalog.Origin.UPLOAD);
        ((ObjectNode) foreign.path("root").path("content").get(0).path("attrs"))
                .put("assetId", otherAsset.toString());
        JsonNode current = decks.read(actor, deck);
        assertThatThrownBy(() -> service.publish(actor, deck, 2,
                save(UUID.randomUUID(), current, member, nextRevision, foreign, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(decks.read(actor, deck).path("rowVersion").stringValue(null)).isEqualTo("2");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.item_revision WHERE deck_id=:deck")
                .param("deck", deck).query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void codeBlocksPublishReadBackByteForByteAndInvalidOnesNeverReachStorage() {
        UUID actor = UUID.randomUUID();
        UUID deck = createDeck(actor);
        JsonNode before = decks.read(actor, deck);
        var created = service.publish(actor, deck, 0, create(UUID.randomUUID(), before, codeDocument, null));
        UUID member = UUID.fromString(created.acknowledgement().path("changes").get(0).path("memberKey").stringValue(null));
        JsonNode read = service.read(actor, deck, member, null).path("document");
        assertNative(read, codeDocument);
        assertThat(read.path("root").path("content").get(2).path("attrs").path("source").stringValue(null))
                .isEqualTo("int main() {\n\treturn 0;   \n}\n");
        assertThat(service.list(actor, deck, "20", null).path("items").get(0).path("title").stringValue(null))
                .isEqualTo("Как PostgreSQL выбирает план");

        for (String[] invalid : new String[][]{{"lang", "SQL"}, {"lang", "a".repeat(33)}, {"source", "x".repeat(16_385)},
                {"source", "a\r\nb"}, {"source", "  "}}) {
            ObjectNode broken = (ObjectNode) codeDocument.deepCopy();
            ((ObjectNode) broken.path("root").path("content").get(1).path("attrs")).put(invalid[0], invalid[1]);
            JsonNode head = decks.read(actor, deck);
            assertThatThrownBy(() -> create(UUID.randomUUID(), head, broken, null)).isInstanceOf(InvalidRequestException.class);
        }
        assertThat(count("learning_item", "deck_id", deck)).isOne();
    }

    @Test
    void ownerCreatesPagesReadsSavesAndReloadsNativeContentWithImmutableHistory() {
        UUID actor = UUID.randomUUID();
        UUID deck = createDeck(actor);
        JsonNode deckBefore = decks.read(actor, deck);
        UUID commandId = UUID.randomUUID();
        var created = service.publish(actor, deck, 0, create(commandId, deckBefore, nativeDocument, null));
        JsonNode createdChange = created.acknowledgement().path("changes").get(0);
        UUID member = UUID.fromString(createdChange.path("memberKey").stringValue(null));
        UUID firstRevision = UUID.fromString(createdChange.path("itemRevisionId").stringValue(null));

        assertThat(created.replayed()).isFalse();
        assertThat(service.list(actor, deck, "1", null).path("items")).hasSize(1);
        JsonNode first = service.read(actor, deck, member, null);
        assertNative(first.path("document"), nativeDocument);
        assertThat(first.path("itemVersion").stringValue(null)).isEqualTo("0");
        assertThat(first.toString()).doesNotContain("rootId", "reuseScope", "pinId");
        assertThat(service.publish(actor, deck, 0, create(commandId, deckBefore, nativeDocument, null)).replayed()).isTrue();
        assertThat(count("learning_item", "deck_id", deck)).isOne();

        ObjectNode changed = (ObjectNode) nativeDocument.deepCopy();
        ((ObjectNode) changed.path("root").path("content").get(0).path("content").get(0).path("attrs"))
                .put("text", "Изменённый заголовок");
        JsonNode head = decks.read(actor, deck);
        UUID saveCommand = UUID.randomUUID();
        var saved = service.publish(actor, deck, 1, save(saveCommand, head, member, firstRevision, changed, null));
        UUID secondRevision = UUID.fromString(saved.acknowledgement().path("changes").get(0)
                .path("itemRevisionId").stringValue(null));
        assertNative(service.read(actor, deck, member, null).path("document"), changed);
        JsonNode historical = service.read(actor, deck, member, firstRevision);
        assertNative(historical.path("document"), nativeDocument);
        assertThat(historical.path("deckVersion").stringValue(null)).isEqualTo("1");
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
        for (UUID member : members) creates.add(change("create", member, null, null, nativeDocument, null));
        var created = service.publish(actor, deck, 0, bulk(UUID.randomUUID(), before, creates));
        UUID removedRevision = UUID.fromString(created.acknowledgement().path("changes").get(1)
                .path("itemRevisionId").stringValue(null));
        UUID movedRevision = UUID.fromString(created.acknowledgement().path("changes").get(2)
                .path("itemRevisionId").stringValue(null));
        assertThat(service.list(actor, deck, "2", null).path("nextCursor").isString()).isTrue();

        JsonNode currentDeck = decks.read(actor, deck);
        ArrayNode changes = JSON.createArrayNode();
        changes.add(change("reorder", members.get(2), movedRevision, 2, null, 0));
        changes.add(change("delete", members.get(1), removedRevision, 1, null, null));
        service.publish(actor, deck, 1, bulk(UUID.randomUUID(), currentDeck, changes));

        JsonNode page = service.list(actor, deck, "100", null);
        assertThat(page.path("items")).hasSize(2);
        assertThat(page.path("items").get(0).path("memberKey").stringValue(null)).isEqualTo(members.get(2).toString());
        assertThat(page.path("items").get(1).path("memberKey").stringValue(null)).isEqualTo(members.get(0).toString());
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
        UUID member = UUID.fromString(created.acknowledgement().path("changes").get(0).path("memberKey").stringValue(null));
        UUID revision = UUID.fromString(created.acknowledgement().path("changes").get(0).path("itemRevisionId").stringValue(null));
        assertThatThrownBy(() -> service.publish(actor, deck, 0,
                create(createCommand, before, nativeDocument, 0))).isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> service.publish(actor, deck, 0,
                create(UUID.randomUUID(), before, nativeDocument, null))).isInstanceOf(VersionConflictException.class);
        assertThatThrownBy(() -> service.read(UUID.randomUUID(), deck, member, null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.read(actor, deck, UUID.randomUUID(), revision))
                .isInstanceOf(ResourceNotFoundException.class);

        JsonNode currentDeck = decks.read(actor, deck);
        ObjectNode wrongPosition = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", currentDeck.path("revisionId").stringValue(null))
                .put("expectedItemRevisionId", revision.toString()).put("expectedOrdinal", 1);
        wrongPosition.set("document", nativeDocument.deepCopy());
        assertThatThrownBy(() -> service.publish(actor, deck, 1,
                ItemPublicationCommand.readSave(bytes(wrongPosition), member)))
                .isInstanceOf(VersionConflictException.class);
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
    void singleDeleteChecksOwnerAndSnapshotAndReplaysAfterRemovingTheCurrentMember() {
        UUID actor = UUID.randomUUID(); UUID deck = createDeck(actor);
        var created = service.publish(actor, deck, 0,
                create(UUID.randomUUID(), decks.read(actor, deck), nativeDocument, null));
        JsonNode item = created.acknowledgement().path("changes").get(0);
        UUID member = UUID.fromString(item.path("memberKey").asString());
        UUID revision = UUID.fromString(item.path("itemRevisionId").asString());
        JsonNode snapshot = decks.read(actor, deck); UUID commandId = UUID.randomUUID();
        ArrayNode changes = JSON.createArrayNode().add(change("delete", member, revision, 0, null, null));
        ItemPublicationCommand command = bulk(commandId, snapshot, changes);
        assertThatThrownBy(() -> service.publish(UUID.randomUUID(), deck, 1, command))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.publish(actor, deck, 0, command))
                .isInstanceOf(VersionConflictException.class);
        assertThatThrownBy(() -> service.publish(actor, deck, 1, bulk(UUID.randomUUID(), snapshot,
                JSON.createArrayNode().add(change("delete", member, revision, 1, null, null)))))
                .isInstanceOf(VersionConflictException.class);
        assertThatThrownBy(() -> service.publish(actor, deck, 1, bulk(UUID.randomUUID(), snapshot,
                JSON.createArrayNode().add(change("delete", member, UUID.randomUUID(), 0, null, null)))))
                .isInstanceOf(VersionConflictException.class);
        assertThat(service.list(actor, deck, null, null).path("items")).hasSize(1);
        var result = service.publish(actor, deck, 1, command);
        assertThat(result.replayed()).isFalse();
        assertThat(result.acknowledgement().path("memberCount").asInt()).isZero();
        assertThat(result.acknowledgement().path("changes").get(0).path("itemRevisionId").isNull()).isTrue();
        assertThat(result.acknowledgement().path("changes").get(0).path("ordinal").isNull()).isTrue();
        var replay = service.publish(actor, deck, 1, command);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.acknowledgement()).isEqualTo(result.acknowledgement());
        assertThatThrownBy(() -> service.publish(UUID.randomUUID(), deck, 1, command))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.publish(actor, deck, 0, command))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(service.list(actor, deck, null, null).path("items")).isEmpty();
        assertNative(service.read(actor, deck, member, revision).path("document"), nativeDocument);
        assertThat(count("deck_revision", "deck_id", deck)).isEqualTo(3);
    }

    @Test
    void directCurrentOrdinalUsesCountedSnapshotAcrossMovesDeletesAndContentRevisions() {
        UUID actor = UUID.randomUUID(); UUID deck = createDeck(actor);
        ArrayNode creates = JSON.createArrayNode(); List<UUID> members = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            UUID member = UUID.randomUUID(); members.add(member);
            creates.add(change("create", member, null, null, nativeDocument, null));
        }
        var created = service.publish(actor, deck, 0, bulk(UUID.randomUUID(), decks.read(actor, deck), creates));
        UUID selected = members.get(20);
        UUID revision = UUID.fromString(created.acknowledgement().path("changes").get(20).path("itemRevisionId").asString());
        assertThat(service.read(actor, deck, selected, null).path("ordinal").asInt()).isEqualTo(20);
        ItemRepository.DeckHead initial = repository.deck(actor, deck).orElseThrow();
        service.publish(actor, deck, 1, bulk(UUID.randomUUID(), decks.read(actor, deck),
                JSON.createArrayNode().add(change("reorder", selected, revision, 20, null, 2))));
        assertThat(service.read(actor, deck, selected, null).path("ordinal").asInt()).isEqualTo(2);
        UUID firstRevision = UUID.fromString(created.acknowledgement().path("changes").get(0).path("itemRevisionId").asString());
        service.publish(actor, deck, 2, bulk(UUID.randomUUID(), decks.read(actor, deck),
                JSON.createArrayNode().add(change("delete", members.get(0), firstRevision, 0, null, null))));
        JsonNode direct = service.read(actor, deck, selected, null);
        assertThat(direct.path("ordinal").asInt()).isEqualTo(1);
        assertThat(direct.path("deckVersion").asString()).isEqualTo("3");
        assertThat(service.read(actor, deck, selected, revision).path("ordinal").asInt()).isEqualTo(20);
        assertThat(repository.currentOrdinal(initial, repository.headItem(actor, deck, selected).orElseThrow())).contains(20);
        ObjectNode changed = (ObjectNode) nativeDocument.deepCopy();
        ((ObjectNode) changed.path("root").path("content").get(0).path("content").get(0).path("attrs"))
                .put("text", "Current content changed");
        service.publish(actor, deck, 3, bulk(UUID.randomUUID(), decks.read(actor, deck),
                JSON.createArrayNode().add(change("save", selected, revision, 1, changed, null))));
        assertThat(repository.currentOrdinal(initial, repository.headItem(actor, deck, selected).orElseThrow())).isEmpty();
        assertThat(service.read(actor, deck, selected, null).path("ordinal").asInt()).isEqualTo(1);
        assertThat(count("item_preview", "deck_id", deck)).isZero();
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
        assertThat(decks.read(actor, deck).path("rowVersion").stringValue(null)).isEqualTo("0");
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
    void orderedStructuralEditsPublishAtomicallyAndPreserveOldSnapshot() {
        UUID actor = UUID.randomUUID();
        UUID deck = createDeck(actor);
        JsonNode before = decks.read(actor, deck);
        var created = service.publish(actor, deck, 0, create(UUID.randomUUID(), before, nativeDocument, null));
        UUID member = UUID.fromString(created.acknowledgement().path("changes").get(0).path("memberKey").stringValue(null));
        UUID oldRevision = UUID.fromString(created.acknowledgement().path("changes").get(0)
                .path("itemRevisionId").stringValue(null));
        ObjectNode next = (ObjectNode) nativeDocument.deepCopy();
        ArrayNode content = (ArrayNode) next.path("root").path("content");
        UUID deleted = UUID.fromString(content.get(1).path("id").stringValue(null));
        UUID movedId = UUID.fromString(content.get(2).path("id").stringValue(null));
        content.remove(1);
        UUID insertedId = UUID.randomUUID();
        ObjectNode paragraph = JSON.createObjectNode().put("id", insertedId.toString())
                .put("type", "paragraph").put("version", 1);
        paragraph.putObject("attrs");
        ObjectNode insertedText = paragraph.putArray("content").addObject().put("id", UUID.randomUUID().toString())
                .put("type", "text").put("version", 1);
        insertedText.putObject("attrs").put("text", "Atomic subtree"); insertedText.putArray("content");
        content.insert(1, paragraph);
        JsonNode moved = content.remove(2);
        content.insert(0, moved);
        ((ObjectNode) content.get(1).path("content").get(0).path("attrs")).put("text", "Value and topology");
        JsonNode deckHead = decks.read(actor, deck);
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", deckHead.path("revisionId").stringValue(null))
                .put("expectedItemRevisionId", oldRevision.toString()).put("expectedOrdinal", 0);
        body.set("document", next);
        ArrayNode edits = body.putArray("edits");
        edits.addObject().put("type", "delete").put("nodeId", deleted.toString());
        edits.addObject().put("type", "insert").put("nodeId", insertedId.toString())
                .put("parentId", nativeDocument.path("root").path("id").stringValue(null)).put("childIndex", 1);
        edits.addObject().put("type", "move").put("nodeId", movedId.toString())
                .put("parentId", nativeDocument.path("root").path("id").stringValue(null)).put("childIndex", 0);
        service.publish(actor, deck, 1, ItemPublicationCommand.readSave(bytes(body), member));
        assertNative(service.read(actor, deck, member, null).path("document"), next);
        assertNative(service.read(actor, deck, member, oldRevision).path("document"), nativeDocument);
    }

    @Test
    void databaseRejectsMutableLogicalHistoryAndCrossItemProjection() {
        assertThat(jdbc.sql("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema='app_learning' AND table_name='deck_head_item' AND column_name='ordinal'
                """).query(Long.class).single()).isZero();
        UUID actor = UUID.randomUUID();
        UUID deck = createDeck(actor);
        JsonNode before = decks.read(actor, deck);
        var created = service.publish(actor, deck, 0, create(UUID.randomUUID(), before, nativeDocument, null));
        UUID member = UUID.fromString(created.acknowledgement().path("changes").get(0).path("memberKey").stringValue(null));
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
            ObjectNode changed = (ObjectNode) nativeDocument.deepCopy();
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
                .acknowledgement().path("deck").path("deckId").stringValue(null));
    }

    private static ItemPublicationCommand create(UUID command, JsonNode deck, JsonNode document, Integer ordinal) {
        ObjectNode body = JSON.createObjectNode().put("commandId", command.toString())
                .put("expectedDeckRevisionId", deck.path("revisionId").stringValue(null));
        body.set("document", document.deepCopy());
        if (ordinal != null) body.put("ordinal", ordinal);
        return ItemPublicationCommand.readCreate(bytes(body));
    }

    private static ItemPublicationCommand save(UUID command, JsonNode deck, UUID member, UUID revision,
                                               JsonNode document, Integer ordinal) {
        ObjectNode body = JSON.createObjectNode().put("commandId", command.toString())
                .put("expectedDeckRevisionId", deck.path("revisionId").stringValue(null))
                .put("expectedItemRevisionId", revision.toString()).put("expectedOrdinal", 0);
        body.set("document", document.deepCopy());
        if (ordinal != null) body.put("ordinal", ordinal);
        return ItemPublicationCommand.readSave(bytes(body), member);
    }

    private static ItemPublicationCommand bulk(UUID command, JsonNode deck, ArrayNode changes) {
        ObjectNode body = JSON.createObjectNode().put("commandId", command.toString())
                .put("expectedDeckRevisionId", deck.path("revisionId").stringValue(null));
        body.set("changes", changes);
        return ItemPublicationCommand.readBulk(bytes(body));
    }

    private static ObjectNode change(String operation, UUID member, UUID revision, Integer expectedOrdinal,
                                     JsonNode document, Integer ordinal) {
        ObjectNode result = JSON.createObjectNode().put("operation", operation).put("memberKey", member.toString());
        if (revision != null) result.put("expectedItemRevisionId", revision.toString());
        if (expectedOrdinal != null) result.put("expectedOrdinal", expectedOrdinal);
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
