package app.mnema.learning.catalog.authoring;

import app.mnema.learning.catalog.content.NativeDocument;
import app.mnema.learning.catalog.content.NativeDocumentReader;
import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.item.ItemPublicationCommand;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.api.ResourceLimitExceededException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.platform.json.CanonicalJsonHasher;
import app.mnema.learning.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class AuthoringServiceIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final CanonicalJsonHasher CANONICAL = new CanonicalJsonHasher();

    @Autowired DraftService drafts;
    @Autowired CaptureService captures;
    @Autowired DeckService decks;
    @Autowired ItemService items;
    @Autowired MediaCatalog mediaCatalog;
    @Autowired AuthoringRepository repository;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void severalDraftsRestoreWhileCompetingTabsConflictAndAutosaveNeverPublishes() throws Exception {
        UUID actor = UUID.randomUUID();
        DeckHead deck = createDeck(actor);
        ObjectNode published = document("published");
        ItemHead item = createItem(actor, deck, published);
        long itemRevisions = count("item_revision", "deck_id", deck.id());
        NativeDocument leftDocument = nativeDocument("left draft");
        NativeDocument rightDocument = nativeDocument("right draft");
        AuthoringCommands.DraftCreate leftCommand = draftCreate(deck.id(), item.member(), item.revision(), leftDocument);
        DraftService.WriteResult leftCreated = drafts.create(actor, leftCommand);
        assertThat(drafts.create(actor, leftCommand).replayed()).isTrue();
        DraftRecord left = draft(actor, leftCreated.acknowledgement());
        DraftRecord right = draft(actor, drafts.create(actor, draftCreate(deck.id(), item.member(), item.revision(),
                rightDocument)).acknowledgement());

        assertThat(drafts.list(actor, "100", null).path("items")).hasSize(2);
        assertNativeJson(drafts.read(actor, left.draftId()).path("document"), leftDocument.toJson());
        assertNativeJson(drafts.read(actor, right.draftId()).path("document"), rightDocument.toJson());

        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> saveDraft(start, actor, left.draftId(), nativeDocument("tab a")));
            var b = executor.submit(() -> saveDraft(start, actor, left.draftId(), nativeDocument("tab b")));
            start.countDown();
            assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("acknowledged", "conflict");
        }
        assertThat(drafts.read(actor, left.draftId()).path("rowVersion").textValue()).isEqualTo("1");
        assertThat(count("item_revision", "deck_id", deck.id())).isEqualTo(itemRevisions);
        assertNativeJson(items.read(actor, deck.id(), item.member(), null).path("document"), published);
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.editing_draft SET base_revision_id=NULL,"
                        + "row_version=row_version+1 WHERE draft_id=:draft").param("draft", left.draftId()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> drafts.read(UUID.randomUUID(), left.draftId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void draftMediaHoldsFollowSavedDocumentAndFailedSavePreservesPreviousHolds() {
        UUID actor = UUID.randomUUID();
        DeckHead deck = createDeck(actor);
        UUID first = mediaCatalog.reserve(actor, UUID.randomUUID(), MediaCatalog.Origin.UPLOAD);
        UUID second = mediaCatalog.reserve(actor, UUID.randomUUID(), MediaCatalog.Origin.UPLOAD);
        UUID foreign = mediaCatalog.reserve(UUID.randomUUID(), UUID.randomUUID(), MediaCatalog.Origin.UPLOAD);
        NativeDocument firstDocument = imageDocument(first);
        DraftRecord draft = draft(actor, drafts.create(actor,
                draftCreate(deck.id(), null, null, firstDocument)).acknowledgement());
        assertThat(draftAssets(draft.draftId())).containsExactly(first);

        NativeDocument secondDocument = imageDocument(second);
        drafts.update(actor, draft.draftId(), 0,
                new AuthoringCommands.DraftUpdate(UUID.randomUUID(), secondDocument));
        assertThat(draftAssets(draft.draftId())).containsExactly(second);
        assertNativeJson(drafts.read(actor, draft.draftId()).path("document"), secondDocument.toJson());

        assertThatThrownBy(() -> drafts.update(actor, draft.draftId(), 1,
                new AuthoringCommands.DraftUpdate(UUID.randomUUID(), imageDocument(foreign))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(draftAssets(draft.draftId())).containsExactly(second);
        assertNativeJson(drafts.read(actor, draft.draftId()).path("document"), secondDocument.toJson());
        assertThat(drafts.read(actor, draft.draftId()).path("rowVersion").textValue()).isEqualTo("1");

        drafts.delete(actor, draft.draftId(), 1);
        assertThat(draftAssets(draft.draftId())).isEmpty();
    }

    @Test
    void captureOutlivesDraftPolicyAndSupportsEditArchiveDeleteWithoutStudyState() {
        UUID actor = UUID.randomUUID();
        DeckHead deck = createDeck(actor);
        Instant old = repository.now().minus(Duration.ofDays(31));
        AuthoringCommands.CaptureCreate oldCapture = captureCreate(deck.id(), "Книга", "Изначальный текст");
        UUID note = UUID.randomUUID();
        repository.insertCapture(note, actor, oldCapture, old);
        AuthoringCommands.DraftCreate expired = draftCreate(deck.id(), null, null, nativeDocument("expired"));
        UUID draft = UUID.randomUUID();
        repository.insertDraft(draft, actor, expired, old);

        JsonNode restored = captures.read(actor, note);
        assertThat(restored.path("createdAt").textValue()).isEqualTo(old.toString());
        assertThat(restored.toString()).doesNotContain("expiresAt", "due", "studyState", "objective");
        assertThatThrownBy(() -> drafts.read(actor, draft)).isInstanceOf(ResourceNotFoundException.class);

        JsonNode edited = captures.update(actor, note, 0, new AuthoringCommands.CaptureUpdate("Лекция", "Исправлено"));
        assertThat(edited.path("createdAt").textValue()).isEqualTo(old.toString());
        JsonNode archived = captures.archive(actor, note, 1, new AuthoringCommands.CaptureArchive(true));
        assertThat(archived.path("archived").booleanValue()).isTrue();
        assertThat(captures.list(actor, "1", null).path("items")).hasSize(1);
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.capture_note SET created_at=statement_timestamp(),"
                        + "row_version=row_version+1 WHERE note_id=:note").param("note", note).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        captures.delete(actor, note, 2);
        assertThatThrownBy(() -> captures.read(actor, note)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deckScopedCapturePageAndCountExcludeOtherDecksArchivedAndDeletedNotes() {
        UUID actor = UUID.randomUUID();
        DeckHead first = createDeck(actor);
        DeckHead second = createDeck(actor);
        Instant base = repository.now().minusSeconds(60);
        for (int index = 0; index < 25; index++) {
            repository.insertCapture(UUID.randomUUID(), actor,
                    captureCreate(second.id(), "other", "note " + index), base.plusSeconds(index));
        }
        UUID oldest = UUID.randomUUID(), middle = UUID.randomUUID(), newest = UUID.randomUUID();
        repository.insertCapture(oldest, actor, captureCreate(first.id(), "first", "oldest"), base.plusSeconds(26));
        repository.insertCapture(middle, actor, captureCreate(first.id(), "first", "middle"), base.plusSeconds(27));
        repository.insertCapture(newest, actor, captureCreate(first.id(), "first", "newest"), base.plusSeconds(28));
        captures.archive(actor, middle, 0, new AuthoringCommands.CaptureArchive(true));

        JsonNode page = captures.list(actor, first.id().toString(), "1", null);
        assertThat(page.path("total").longValue()).isEqualTo(2);
        assertThat(page.path("items")).hasSize(1);
        assertThat(page.path("items").get(0).path("noteId").textValue()).isEqualTo(newest.toString());
        JsonNode next = captures.list(actor, first.id().toString(), "1", page.path("nextCursor").textValue());
        assertThat(next.path("items").get(0).path("noteId").textValue()).isEqualTo(oldest.toString());
        assertThat(next.path("nextCursor").isNull()).isTrue();
        assertThat(captures.list(actor, "1", null).path("total").isMissingNode()).isTrue();
        assertThatThrownBy(() -> captures.list(UUID.randomUUID(), first.id().toString(), "1", null))
                .isInstanceOf(ResourceNotFoundException.class);
        captures.delete(actor, newest, 0);
        assertThat(captures.list(actor, first.id().toString(), "1", null).path("total").longValue()).isOne();
    }

    @Test
    void deletedDeckHidesItsNotesAndDraftsButPreservesTheirRows() {
        UUID actor = UUID.randomUUID();
        DeckHead deck = createDeck(actor);
        UUID note = capture(actor, captures.create(actor,
                captureCreate(deck.id(), "book", "keep history")).acknowledgement()).noteId();
        UUID draft = draft(actor, drafts.create(actor,
                draftCreate(deck.id(), null, null, nativeDocument("unfinished"))).acknowledgement()).draftId();

        decks.delete(actor, deck.id(), 0);
        assertThatThrownBy(() -> captures.read(actor, note)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> drafts.read(actor, draft)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> captures.list(actor, deck.id().toString(), "20", null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(captures.list(actor, "20", null).path("items")).isEmpty();
        assertThat(drafts.list(actor, "20", null).path("items")).isEmpty();
        assertThat(count("capture_note", "note_id", note)).isOne();
        assertThat(count("editing_draft", "draft_id", draft)).isOne();
    }

    @Test
    void conversionIsAtomicIdempotentPreservesSourceAndLeavesRecoverableStateOnFailure() {
        UUID actor = UUID.randomUUID();
        DeckHead deck = createDeck(actor);
        NativeDocument draftDocument = nativeDocument("draft remains");
        UUID draft = draft(actor, drafts.create(actor, draftCreate(deck.id(), null, null, draftDocument))
                .acknowledgement()).draftId();
        CaptureRecord note = capture(actor, captures.create(actor,
                captureCreate(deck.id(), "Видео 12:30", "Разобрать термин")).acknowledgement());
        UUID conversionId = UUID.randomUUID();
        AuthoringCommands.CaptureConvert conversion = conversion(conversionId, deck, document("converted"), null);

        CaptureService.WriteResult first = captures.convert(actor, note.noteId(), 0, conversion);
        CaptureService.WriteResult retry = captures.convert(actor, note.noteId(), 0, conversion);
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.acknowledgement()).isEqualTo(first.acknowledgement());
        assertThat(captures.list(actor, deck.id().toString(), "20", null).path("total").longValue()).isZero();
        assertThat(count("learning_item", "deck_id", deck.id())).isOne();
        JsonNode preserved = captures.read(actor, note.noteId());
        assertThat(preserved.path("source").textValue()).isEqualTo("Видео 12:30");
        assertThat(preserved.path("text").textValue()).isEqualTo("Разобрать термин");
        assertThat(preserved.path("createdAt").textValue()).isEqualTo(note.createdAt().toString());
        assertThatThrownBy(() -> captures.update(actor, note.noteId(), 1,
                new AuthoringCommands.CaptureUpdate("changed", "changed")))
                .isInstanceOf(VersionConflictException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.capture_note SET note_text='changed',"
                        + "row_version=row_version+1 WHERE note_id=:note").param("note", note.noteId()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> captures.convert(actor, note.noteId(), 0,
                conversion(conversionId, deck, document("different"), null)))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> captures.convert(actor, note.noteId(), 1, conversion))
                .isInstanceOf(IdempotencyConflictException.class);

        DeckHead current = deck(actor, deck.id());
        CaptureRecord failed = capture(actor, captures.create(actor,
                captureCreate(deck.id(), "Источник", "Не потерять")).acknowledgement());
        UUID failedCommand = UUID.randomUUID();
        AuthoringCommands.CaptureConvert stale = conversion(failedCommand,
                new DeckHead(deck.id(), current.revision(), current.version() - 1), document("stale"), null);
        assertThatThrownBy(() -> captures.convert(actor, failed.noteId(), 0, stale))
                .isInstanceOf(VersionConflictException.class);
        assertThat(captures.read(actor, failed.noteId()).path("conversion").isNull()).isTrue();
        assertNativeJson(drafts.read(actor, draft).path("document"), draftDocument.toJson());
        assertThat(count("command_receipt", "command_id", failedCommand)).isZero();

        CaptureRecord rollback = capture(actor, captures.create(actor,
                captureCreate(deck.id(), "rollback", "rollback")).acknowledgement());
        DeckHead rollbackHead = deck(actor, deck.id());
        UUID rollbackCommand = UUID.randomUUID();
        long before = count("learning_item", "deck_id", deck.id());
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            captures.convert(actor, rollback.noteId(), 0,
                    conversion(rollbackCommand, rollbackHead, document("rollback"), null));
            status.setRollbackOnly();
        });
        assertThat(count("learning_item", "deck_id", deck.id())).isEqualTo(before);
        assertThat(captures.read(actor, rollback.noteId()).path("conversion").isNull()).isTrue();
        assertThat(count("command_receipt", "command_id", rollbackCommand)).isZero();
    }

    /**
     * A database clock that steps backwards makes the next transaction see rows created "in the future".
     * Writes must stay monotonic instead of violating the unchanged created_at ordering constraints.
     */
    @Test
    void draftAndCaptureWritesStayMonotonicWhenRowsAreAheadOfTheDatabaseClock() {
        UUID actor = UUID.randomUUID();
        DeckHead deck = createDeck(actor);
        Instant ahead = repository.now().plus(Duration.ofHours(1));

        UUID draft = UUID.randomUUID();
        repository.insertDraft(draft, actor, draftCreate(deck.id(), null, null, nativeDocument("ahead")), ahead);
        drafts.update(actor, draft, 0, new AuthoringCommands.DraftUpdate(UUID.randomUUID(), nativeDocument("saved")));
        assertThat(jdbc.sql("SELECT acknowledged_at = created_at "
                        + "AND expires_at = acknowledged_at + interval '30 days' "
                        + "FROM app_learning.editing_draft WHERE draft_id=:draft")
                .param("draft", draft).query(Boolean.class).single()).isTrue();
        assertThat(drafts.read(actor, draft).path("rowVersion").textValue()).isEqualTo("1");

        UUID edited = UUID.randomUUID();
        repository.insertCapture(edited, actor, captureCreate(deck.id(), "source", "text"), ahead);
        captures.update(actor, edited, 0, new AuthoringCommands.CaptureUpdate("source", "changed"));
        captures.archive(actor, edited, 1, new AuthoringCommands.CaptureArchive(true));
        assertThat(captures.read(actor, edited).path("rowVersion").textValue()).isEqualTo("2");

        UUID converted = UUID.randomUUID();
        repository.insertCapture(converted, actor, captureCreate(deck.id(), "source", "convert me"), ahead);
        captures.convert(actor, converted, 0, conversion(UUID.randomUUID(), deck(actor, deck.id()),
                document("converted"), null));
        assertThat(jdbc.sql("SELECT updated_at >= created_at AND converted_at >= created_at AND created_at > "
                        + "statement_timestamp() FROM app_learning.capture_note WHERE note_id=:note")
                .param("note", converted).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.capture_note WHERE note_id IN (:edited,:converted) "
                        + "AND updated_at >= created_at AND created_at > statement_timestamp()")
                .param("edited", edited).param("converted", converted).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void foreignIdsAndAccountEntryLimitsFailClosedWithoutDeletingExistingData() {
        UUID actor = UUID.randomUUID();
        DeckHead deck = createDeck(actor);
        CaptureRecord note = capture(actor, captures.create(actor, captureCreate(deck.id(), "source", "text"))
                .acknowledgement());
        assertThatThrownBy(() -> captures.read(UUID.randomUUID(), note.noteId()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> drafts.create(UUID.randomUUID(), draftCreate(deck.id(), null, null,
                nativeDocument("foreign")))).isInstanceOf(ResourceNotFoundException.class);

        UUID draftActor = UUID.randomUUID();
        DeckHead draftDeck = createDeck(draftActor);
        String document = AuthoringCommandsTest.document("quota").toString();
        jdbc.sql("""
                INSERT INTO app_learning.editing_draft(draft_id,owner_id,deck_id,row_version,document,
                    created_at,acknowledged_at,expires_at)
                SELECT md5(:actor::text || value::text)::uuid,:actor,:deck,0,CAST(:document AS jsonb),
                    statement_timestamp(),statement_timestamp(),statement_timestamp()+interval '30 days'
                  FROM generate_series(1,200) value
                """).param("actor", draftActor).param("deck", draftDeck.id()).param("document", document).update();
        assertThatThrownBy(() -> drafts.create(draftActor, draftCreate(draftDeck.id(), null, null,
                nativeDocument("one too many")))).isInstanceOf(ResourceLimitExceededException.class);
        assertThat(repository.activeDraftCount(draftActor)).isEqualTo(200);

        UUID byteActor = UUID.randomUUID();
        DeckHead byteDeck = createDeck(byteActor);
        jdbc.sql("""
                INSERT INTO app_learning.editing_draft(draft_id,owner_id,deck_id,row_version,document,
                    created_at,acknowledged_at,expires_at)
                SELECT md5(:actor::text || value::text)::uuid,:actor,:deck,0,
                    jsonb_build_object('padding',repeat('x',1048560)),statement_timestamp(),
                    statement_timestamp(),statement_timestamp()+interval '30 days'
                  FROM generate_series(1,20) value
                """).param("actor", byteActor).param("deck", byteDeck.id()).update();
        assertThat(repository.activeDraftBytes(byteActor, null)).isGreaterThan(20L * 1024 * 1024 - 512);
        assertThatThrownBy(() -> drafts.create(byteActor, draftCreate(byteDeck.id(), null, null,
                nativeDocument("account byte limit")))).isInstanceOf(ResourceLimitExceededException.class);
        assertThat(repository.activeDraftCount(byteActor)).isEqualTo(20);

        UUID captureActor = UUID.randomUUID();
        DeckHead captureDeck = createDeck(captureActor);
        jdbc.sql("""
                INSERT INTO app_learning.capture_note(note_id,owner_id,deck_id,row_version,source,note_text,
                    archived,created_at,updated_at)
                SELECT md5(:actor::text || value::text)::uuid,:actor,:deck,0,'source','note',false,
                    statement_timestamp(),statement_timestamp() FROM generate_series(1,10000) value
                """).param("actor", captureActor).param("deck", captureDeck.id()).update();
        assertThatThrownBy(() -> captures.create(captureActor, captureCreate(captureDeck.id(), "source", "extra")))
                .isInstanceOf(ResourceLimitExceededException.class);
        assertThat(repository.captureCount(captureActor)).isEqualTo(10_000);
    }

    private String saveDraft(CountDownLatch start, UUID actor, UUID draft, NativeDocument document)
            throws InterruptedException {
        start.await();
        try {
            drafts.update(actor, draft, 0, new AuthoringCommands.DraftUpdate(UUID.randomUUID(), document));
            return "acknowledged";
        } catch (VersionConflictException expected) {
            return "conflict";
        }
    }

    private DeckHead createDeck(UUID actor) {
        JsonNode value = decks.create(actor, new DeckCommand(UUID.randomUUID(), "Deck", "Description"))
                .acknowledgement().path("deck");
        return new DeckHead(UUID.fromString(value.path("deckId").textValue()),
                UUID.fromString(value.path("revisionId").textValue()), Long.parseLong(value.path("rowVersion").textValue()));
    }

    private DeckHead deck(UUID actor, UUID deck) {
        JsonNode value = decks.read(actor, deck);
        return new DeckHead(deck, UUID.fromString(value.path("revisionId").textValue()),
                Long.parseLong(value.path("rowVersion").textValue()));
    }

    private ItemHead createItem(UUID actor, DeckHead deck, ObjectNode document) {
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", deck.revision().toString());
        body.set("document", document);
        JsonNode change = items.publish(actor, deck.id(), deck.version(),
                ItemPublicationCommand.readCreate(AuthoringCommandsTest.bytes(body)))
                .acknowledgement().path("changes").get(0);
        return new ItemHead(UUID.fromString(change.path("memberKey").textValue()),
                UUID.fromString(change.path("itemRevisionId").textValue()));
    }

    private static AuthoringCommands.DraftCreate draftCreate(UUID deck, UUID member, UUID base, NativeDocument document) {
        return new AuthoringCommands.DraftCreate(UUID.randomUUID(), deck, member, base, document);
    }

    private static AuthoringCommands.CaptureCreate captureCreate(UUID deck, String source, String text) {
        return new AuthoringCommands.CaptureCreate(UUID.randomUUID(), deck, source, text);
    }

    private static AuthoringCommands.CaptureConvert conversion(UUID command, DeckHead deck,
                                                                 ObjectNode document, Integer ordinal) {
        ObjectNode body = JSON.createObjectNode().put("commandId", command.toString())
                .put("expectedDeckVersion", Long.toString(deck.version()))
                .put("expectedDeckRevisionId", deck.revision().toString());
        if (ordinal != null) body.put("ordinal", ordinal);
        body.set("document", document);
        return AuthoringCommands.captureConvert(AuthoringCommandsTest.bytes(body));
    }

    private static NativeDocument nativeDocument(String text) {
        return new NativeDocumentReader().read(CANONICAL.canonicalBytes(document(text)));
    }

    private static NativeDocument imageDocument(UUID asset) {
        ObjectNode json = document("media");
        ArrayNode content = (ArrayNode) json.path("root").path("content");
        content.removeAll();
        ObjectNode image = content.addObject();
        image.put("id", UUID.randomUUID().toString());
        image.put("type", "image");
        image.put("version", 1);
        image.putObject("attrs").put("assetId", asset.toString()).put("alt", "Схема сервиса")
                .put("description", "Схема соединяет API и базу данных.");
        image.putArray("content");
        return new NativeDocumentReader().read(CANONICAL.canonicalBytes(json));
    }

    private List<UUID> draftAssets(UUID draft) {
        return jdbc.sql("SELECT asset_id FROM app_learning.draft_media_ref WHERE draft_id=:draft")
                .param("draft", draft).query(UUID.class).list();
    }

    private static ObjectNode document(String text) { return AuthoringCommandsTest.document(text); }

    private DraftRecord draft(UUID actor, JsonNode acknowledgement) {
        return repository.draft(actor, UUID.fromString(acknowledgement.path("draft").path("draftId").textValue()))
                .orElseThrow();
    }

    private CaptureRecord capture(UUID actor, JsonNode acknowledgement) {
        return repository.capture(actor, UUID.fromString(acknowledgement.path("capture").path("noteId").textValue()))
                .orElseThrow();
    }

    private long count(String table, String column, UUID value) {
        return jdbc.sql("SELECT count(*) FROM app_learning." + table + " WHERE " + column + "=:value")
                .param("value", value).query(Long.class).single();
    }

    private static void assertNativeJson(JsonNode actual, JsonNode expected) {
        assertThat(CANONICAL.canonicalBytes(actual)).containsExactly(CANONICAL.canonicalBytes(expected));
    }

    private record DeckHead(UUID id, UUID revision, long version) { }
    private record ItemHead(UUID member, UUID revision) { }
}
