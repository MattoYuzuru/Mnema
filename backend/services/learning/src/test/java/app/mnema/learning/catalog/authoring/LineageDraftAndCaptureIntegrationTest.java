package app.mnema.learning.catalog.authoring;

import app.mnema.learning.catalog.content.NativeDocument;
import app.mnema.learning.catalog.content.NativeDocumentReader;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.json.CanonicalJsonHasher;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.SharedScopeFixture;
import app.mnema.learning.support.SharedScopeFixture.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Share/4 (#426): drafts and capture notes of a deck in a shared lineage carry the deck's scope and see only its revisions. */
@SpringBootTest
class LineageDraftAndCaptureIntegrationTest extends PostgresIntegrationTest {
    private static final CanonicalJsonHasher CANONICAL = new CanonicalJsonHasher();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private DraftService drafts;
    @Autowired private CaptureService captures;
    @Autowired private AuthoringRepository repository;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private app.mnema.learning.catalog.exercise.ExerciseService exercises;
    @Autowired private app.mnema.learning.media.MediaCatalog media;
    @Autowired private app.mnema.learning.study.session.StudySessionService studies;
    private SharedScopeFixture fixture;

    @BeforeEach
    void fixture() {
        fixture = new SharedScopeFixture(decks, items, storage, jdbc, transactions);
    }

    @Test
    void aDraftCanBeBasedOnlyOnARevisionVisibleToItsDeck() {
        Scenario s = fixture.fork();
        UUID member = s.material().member();
        UUID fork = s.material().revision();

        // the copy's inherited head is a valid base for the copy owner, and the draft carries the copy's scope
        var created = drafts.create(s.copyOwner(), draft(s.copyDeck(), member, fork));
        UUID draft = UUID.fromString(created.acknowledgement().path("draft").path("draftId").stringValue(null));
        assertThat(jdbc.sql("SELECT reuse_scope_id FROM app_learning.editing_draft WHERE draft_id=:draft").param("draft", draft)
                .query(UUID.class).single()).isEqualTo(s.copy().scope());

        UUID copyRevision = fixture.editCopy(s, "copy edit");
        UUID sourceRevision = fixture.editSource(s, "author edit");
        assertThat(repository.ownsBase(s.copyOwner(), s.copyDeck(), member, copyRevision)).isTrue();
        assertThat(repository.ownsBase(s.copyOwner(), s.copyDeck(), member, fork)).isTrue();   // replaced by its own change
        assertThat(repository.ownsBase(s.author(), s.source(), member, sourceRevision)).isTrue();
        assertThat(repository.ownsBase(s.author(), s.source(), member, fork)).isTrue();
        // neither deck may base a draft on the other's revision, an opaque 404
        assertThat(repository.ownsBase(s.copyOwner(), s.copyDeck(), member, sourceRevision)).isFalse();
        assertThat(repository.ownsBase(s.author(), s.source(), member, copyRevision)).isFalse();
        assertThatThrownBy(() -> drafts.create(s.copyOwner(), draft(s.copyDeck(), member, sourceRevision)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> drafts.create(s.author(), draft(s.source(), member, copyRevision)))
                .isInstanceOf(ResourceNotFoundException.class);
        // another account's deck is not enough either: ownership of the deck is still required
        assertThatThrownBy(() -> drafts.create(s.author(), draft(s.copyDeck(), member, copyRevision)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(drafts.create(s.copyOwner(), draft(s.copyDeck(), member, copyRevision)).acknowledgement()).isNotNull();
    }

    @Test
    void aRevisionOnlyPinnedByAnExerciseIsNotADraftBase() {
        var studyFixtures = new app.mnema.learning.support.StudyFixtures(decks, items, exercises, studies, media, jdbc);
        var s = app.mnema.learning.support.PinnedRevisionScenario.build(fixture, studyFixtures);
        // readable by Study through the copy's head exercise, but a draft base keeps head/journal/replaced semantics
        assertThat(repository.ownsBase(s.copy().owner(), s.copy().deckId(), s.member(), s.pinned())).isFalse();
        assertThat(repository.ownsBase(s.copy().owner(), s.copy().deckId(), s.member(), s.head())).isTrue();
        assertThatThrownBy(() -> drafts.create(s.copy().owner(), draft(s.copy().deckId(), s.member(), s.pinned())))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(repository.ownsBase(s.author(), s.source(), s.member(), s.pinned())).isTrue();   // the author's own replaced revision
    }

    @Test
    void captureNotesAndDraftsStoreTheirDecksScopeAndTheDatabaseKeepsDeckAndScopeTogether() {
        Scenario s = fixture.fork();
        var note = captures.create(s.copyOwner(), new AuthoringCommands.CaptureCreate(UUID.randomUUID(), s.copyDeck(), "src", "text"));
        UUID noteId = UUID.fromString(note.acknowledgement().path("capture").path("noteId").stringValue(null));
        assertThat(jdbc.sql("SELECT reuse_scope_id FROM app_learning.capture_note WHERE note_id=:note").param("note", noteId)
                .query(UUID.class).single()).isEqualTo(s.copy().scope());
        // the FK (deck_id, reuse_scope_id) -> deck refuses a row whose scope is not its deck's
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO app_learning.capture_note(note_id,owner_id,deck_id,reuse_scope_id,row_version,"
                        + "source,note_text,archived,created_at,updated_at) VALUES (:id,:owner,:deck,:scope,0,'s','t',false,now(),now())")
                .param("id", UUID.randomUUID()).param("owner", s.copyOwner()).param("deck", s.copyDeck())
                .param("scope", UUID.randomUUID()).update()).hasMessageContaining("capture_note_deck_scope_fkey");
    }

    @Test
    void aCopyConvertsACaptureNoteIntoAMaterialOfItsOwnDeckInTheSharedScope() {
        Scenario s = fixture.fork();
        var note = captures.create(s.copyOwner(), new AuthoringCommands.CaptureCreate(UUID.randomUUID(), s.copyDeck(), "src", "convert me"));
        UUID noteId = UUID.fromString(note.acknowledgement().path("capture").path("noteId").stringValue(null));
        var head = decks.read(s.copyOwner(), s.copyDeck());
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckVersion", head.path("rowVersion").stringValue(null))
                .put("expectedDeckRevisionId", head.path("revisionId").stringValue(null));
        body.set("document", AuthoringCommandsTest.document("converted in the copy"));
        captures.convert(s.copyOwner(), noteId, 0, AuthoringCommands.captureConvert(AuthoringCommandsTest.bytes(body)));

        // the converted pointer is a lineage revision written by the copy's own command, reached through the note's scope
        var converted = jdbc.sql("SELECT n.reuse_scope_id,r.deck_id,r.owner_id FROM app_learning.capture_note n "
                        + "JOIN app_learning.item_revision r ON r.reuse_scope_id=n.reuse_scope_id AND r.member_key=n.converted_member_key "
                        + "AND r.revision_id=n.converted_revision_id WHERE n.note_id=:note").param("note", noteId)
                .query((row, ignored) -> java.util.List.of(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                        row.getObject(3, UUID.class))).single();
        assertThat(converted).containsExactly(s.copy().scope(), s.copyDeck(), s.copyOwner());
        assertThat(jdbc.sql("SELECT convalidated FROM pg_constraint WHERE conname='capture_note_converted_fkey'")
                .query(Boolean.class).single()).isTrue();
        assertThat(items.list(s.copyOwner(), s.copyDeck(), "20", null).path("items")).hasSize(2);
        assertThat(items.list(s.author(), s.source(), "20", null).path("items")).hasSize(1);
    }

    private static AuthoringCommands.DraftCreate draft(UUID deck, UUID member, UUID base) {
        NativeDocument document = new NativeDocumentReader().read(CANONICAL.canonicalBytes(AuthoringCommandsTest.document("draft")));
        return new AuthoringCommands.DraftCreate(UUID.randomUUID(), deck, member, base, document);
    }
}
