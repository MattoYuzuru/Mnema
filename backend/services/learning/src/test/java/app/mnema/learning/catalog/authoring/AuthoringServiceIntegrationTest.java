package app.mnema.learning.catalog.authoring;

import app.mnema.learning.catalog.content.NativeDocument;
import app.mnema.learning.catalog.content.NativeDocumentReader;
import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.item.ItemPublicationCommand;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.platform.api.ResourceLimitExceededException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.concurrency.VersionConflictException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import app.mnema.learning.platform.json.CanonicalJsonHasher;
import app.mnema.learning.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
