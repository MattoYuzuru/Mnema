package app.mnema.learning.catalog.item;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.media.MediaManifestCatalog;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.SharedScopeFixture;
import app.mnema.learning.support.SharedScopeFixture.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Share/4 (#426): two decks of one lineage (a copy made like Share/10 will make it) read and edit the shared materials
 * through the normal services, each deck sees only its own head, and no deck reaches a revision it does not own.
 */
@SpringBootTest
class LineageSharedScopeIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private ItemService items;
    @Autowired private ItemRepository repository;
    @Autowired private ItemBulkDeleteService deletions;
    @Autowired private DeckService decks;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private MediaCatalog mediaCatalog;
    @Autowired private MediaManifestCatalog manifests;
    private SharedScopeFixture fixture;

    @BeforeEach
    void fixture() {
        fixture = new SharedScopeFixture(decks, items, storage, jdbc, transactions);
    }

    @Test
    void aCopyListsAndReadsInheritedMaterialsWithItsOwnDeckIdentity() {
        Scenario s = fixture.fork();
        var second = fixture.create(s.author(), s.source(), fixture.document("two"));
        // the second material is created after the fork: the copy has only the first
        JsonNode list = items.list(s.copyOwner(), s.copyDeck(), "20", null);
        assertThat(list.path("deckId").stringValue(null)).isEqualTo(s.copyDeck().toString());
        assertThat(list.path("deckRevisionId").stringValue(null)).isEqualTo(s.copy().genesisRevisionId().toString());
        assertThat(list.path("deckVersion").stringValue(null)).isEqualTo("0");
        assertThat(list.path("items").get(0).path("title").stringValue(null)).isNotBlank();
        assertThat(list.path("items").get(0).path("itemRevisionId").stringValue(null)).isEqualTo(s.material().revision().toString());

        JsonNode head = items.read(s.copyOwner(), s.copyDeck(), s.material().member(), null);
        assertThat(head.path("deckId").stringValue(null)).isEqualTo(s.copyDeck().toString());
        assertThat(head.path("deckRevisionId").stringValue(null)).isEqualTo(s.copy().genesisRevisionId().toString());
        assertThat(head.path("ordinal").intValue()).isZero();

        // by id, the inherited head is the same revision, projected from the copy: its own deck and first revision
        JsonNode byId = items.read(s.copyOwner(), s.copyDeck(), s.material().member(), s.material().revision());
        assertThat(byId.path("deckId").stringValue(null)).isEqualTo(s.copyDeck().toString());
        assertThat(byId.path("deckRevisionId").stringValue(null)).isEqualTo(s.copy().genesisRevisionId().toString());
        assertThat(byId.path("deckVersion").stringValue(null)).isEqualTo("0");
        assertThat(byId.path("ordinal").intValue()).isZero();
        assertThat(byId.path("document")).isEqualTo(head.path("document"));

        // the source's later material is not in the copy: not listed, not readable
        assertThatThrownBy(() -> items.read(s.copyOwner(), s.copyDeck(), second.member(), null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> items.read(s.copyOwner(), s.copyDeck(), second.member(), second.revision()))
                .isInstanceOf(ResourceNotFoundException.class);
        // the cached title is one row per lineage revision, shared by both decks
        items.list(s.author(), s.source(), "20", null);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.item_preview WHERE reuse_scope_id=:scope AND member_key=:member "
                        + "AND revision_id=:revision").param("scope", s.copy().scope()).param("member", s.material().member())
                .param("revision", s.material().revision()).query(Long.class).single()).isOne();
    }

    @Test
    void aCopyEditsAnInheritedMaterialAndTheSourceKeepsItsOwnHeadThroughLaterEditsOfBoth() {
        Scenario s = fixture.fork();
        UUID member = s.material().member();
        UUID fork = s.material().revision();

        UUID copyRevision = fixture.editCopy(s, "copy edit");
        var row = jdbc.sql("SELECT deck_id,owner_id,reuse_scope_id,parent_revision_id,item_sequence,deck_revision_id,deck_sequence "
                        + "FROM app_learning.item_revision WHERE reuse_scope_id=:scope AND member_key=:member AND revision_id=:revision")
                .param("scope", s.copy().scope()).param("member", member).param("revision", copyRevision)
                .query((result, ignored) -> new Object[] {result.getObject(1, UUID.class), result.getObject(2, UUID.class),
                        result.getObject(3, UUID.class), result.getObject(4, UUID.class), result.getLong(5),
                        result.getObject(6, UUID.class), result.getLong(7)}).single();
        assertThat(row[0]).isEqualTo(s.copyDeck());
        assertThat(row[1]).isEqualTo(s.copyOwner());
        assertThat(row[2]).isEqualTo(s.copy().scope());
        assertThat(row[3]).isEqualTo(fork);
        assertThat(row[4]).isEqualTo(1L);
        assertThat(row[6]).isEqualTo(1L);
        // the source did not move
        JsonNode sourceHead = items.read(s.author(), s.source(), member, null);
        assertThat(sourceHead.path("itemRevisionId").stringValue(null)).isEqualTo(fork.toString());
        assertThat(decks.read(s.author(), s.source()).path("rowVersion").stringValue(null)).isEqualTo("1");

        // the copy's own read, by id and as head, with the copy's own projections
        JsonNode copyHead = items.read(s.copyOwner(), s.copyDeck(), member, null);
        assertThat(copyHead.path("itemRevisionId").stringValue(null)).isEqualTo(copyRevision.toString());
        JsonNode own = items.read(s.copyOwner(), s.copyDeck(), member, copyRevision);
        assertThat(own.path("deckId").stringValue(null)).isEqualTo(s.copyDeck().toString());
        assertThat(own.path("deckRevisionId").stringValue(null)).isEqualTo(copyHead.path("deckRevisionId").stringValue(null));
        assertThat(own.path("deckVersion").stringValue(null)).isEqualTo("1");
        assertThat(own.path("ordinal").intValue()).isZero();
        // the inherited base the copy's own change replaced was the copy's head: still readable, from the copy's view
        JsonNode base = items.read(s.copyOwner(), s.copyDeck(), member, fork);
        assertThat(base.path("itemRevisionId").stringValue(null)).isEqualTo(fork.toString());
        assertThat(base.path("deckId").stringValue(null)).isEqualTo(s.copyDeck().toString());
        assertThat(base.path("deckRevisionId").stringValue(null)).isEqualTo(s.copy().genesisRevisionId().toString());
        assertThat(base.path("deckVersion").stringValue(null)).isEqualTo("0");
        assertThat(base.path("ordinal").intValue()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_item_change WHERE deck_id=:deck AND reuse_scope_id=:scope")
                .param("deck", s.copyDeck()).param("scope", s.copy().scope()).query(Long.class).single()).isOne();

        // the source edits the same material afterwards: sequence 1 again, a different lineage revision
        UUID sourceRevision = fixture.editSource(s, "author edit");
        assertThat(jdbc.sql("SELECT item_sequence FROM app_learning.item_revision WHERE reuse_scope_id=:scope AND revision_id=:revision")
                .param("scope", s.copy().scope()).param("revision", sourceRevision).query(Long.class).single()).isEqualTo(1L);
        assertThat(items.read(s.author(), s.source(), member, null).path("itemRevisionId").stringValue(null))
                .isEqualTo(sourceRevision.toString());
        assertThat(items.read(s.copyOwner(), s.copyDeck(), member, null).path("itemRevisionId").stringValue(null))
                .isEqualTo(copyRevision.toString());
        assertThat(items.read(s.copyOwner(), s.copyDeck(), member, null).path("document"))
                .isNotEqualTo(items.read(s.author(), s.source(), member, null).path("document"));

        // visibility: neither deck reaches the other's revisions by id, whatever the scope
        assertThatThrownBy(() -> items.read(s.copyOwner(), s.copyDeck(), member, sourceRevision))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> items.read(s.author(), s.source(), member, copyRevision))
                .isInstanceOf(ResourceNotFoundException.class);
        // the author's history stays readable by the author: the fork revision (journal) and the new head
        assertThat(items.read(s.author(), s.source(), member, fork).path("itemRevisionId").stringValue(null)).isEqualTo(fork.toString());
        assertThat(items.read(s.author(), s.source(), member, sourceRevision).path("deckId").stringValue(null))
                .isEqualTo(s.source().toString());
        // a foreign actor learns nothing: the same opaque answer for a copy deck that exists
        assertThatThrownBy(() -> items.read(s.author(), s.copyDeck(), member, null)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> items.read(s.author(), s.copyDeck(), member, copyRevision)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theSharedVisibilityPredicateIsTheOnlyAnswerOfEveryDeckRepositoryRead() {
        Scenario s = fixture.fork();
        UUID member = s.material().member();
        UUID fork = s.material().revision();
        UUID copyRevision = fixture.editCopy(s, "copy edit");
        UUID sourceRevision = fixture.editSource(s, "author edit");
        // direct statement over the fragment: the matrix of (deck, revision)
        assertThat(visible(s.copyDeck(), member, copyRevision)).isTrue();
        assertThat(visible(s.copyDeck(), member, fork)).isTrue();   // replaced by the copy's own change
        assertThat(visible(s.copyDeck(), member, sourceRevision)).isFalse();
        assertThat(visible(s.source(), member, sourceRevision)).isTrue();
        assertThat(visible(s.source(), member, fork)).isTrue();
        assertThat(visible(s.source(), member, copyRevision)).isFalse();
        assertThat(repository.revision(s.copyOwner(), s.copyDeck(), member, sourceRevision)).isEmpty();
        assertThat(repository.revision(s.author(), s.source(), member, copyRevision)).isEmpty();
        assertThat(repository.revision(s.copyOwner(), s.copyDeck(), member, copyRevision)).isPresent();
        assertThat(ItemRevisionVisibility.visibleTo(":deck", "r")).contains("deck_head_item").contains("deck_item_change");
        assertThatThrownBy(() -> ItemRevisionVisibility.visibleTo("deck; DROP TABLE x", "r"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ItemRevisionVisibility.visibleTo(":deck", "r; --")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDeletedOrReorderedMaterialKeepsItsPublishedRevisionsVisibleToItsDeckOnly() {
        Scenario s = fixture.fork();
        UUID member = s.material().member();
        UUID fork = s.material().revision();
        UUID copyRevision = fixture.editCopy(s, "copy edit");
        // the copy deletes the material: its head is gone, the revision it published stays readable by id (journal)
        JsonNode head = decks.read(s.copyOwner(), s.copyDeck());
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", head.path("revisionId").stringValue(null));
        ArrayNode changes = body.putArray("changes");
        changes.addObject().put("operation", "delete").put("memberKey", member.toString())
                .put("expectedItemRevisionId", copyRevision.toString()).put("expectedOrdinal", 0);
        items.publish(s.copyOwner(), s.copyDeck(), Long.parseLong(head.path("rowVersion").stringValue(null)),
                ItemPublicationCommand.readBulk(new java.io.ByteArrayInputStream(bytes(body))));
        assertThat(items.list(s.copyOwner(), s.copyDeck(), "20", null).path("items")).isEmpty();
        assertThat(items.read(s.copyOwner(), s.copyDeck(), member, copyRevision).path("itemRevisionId").stringValue(null))
                .isEqualTo(copyRevision.toString());
        assertThatThrownBy(() -> items.read(s.copyOwner(), s.copyDeck(), member, null)).isInstanceOf(ResourceNotFoundException.class);
        // the source still has the material at the fork revision
        assertThat(items.read(s.author(), s.source(), member, null).path("itemRevisionId").stringValue(null)).isEqualTo(fork.toString());
        assertThat(items.list(s.author(), s.source(), "20", null).path("items")).hasSize(1);
    }

    @Test
    void aCopyRevisionMayKeepTheAuthorsImageAtTheDatabaseAndTheOwnersMediaViewsAreUnchanged() throws Exception {
        UUID author = UUID.randomUUID();
        UUID source = fixture.deck(author, "С картинками");
        List<UUID> assets = assets(author);
        var rich = fixture.create(author, source, richWith(assets));
        var plain = fixture.create(author, source, fixture.document("plain"));
        UUID owner = UUID.randomUUID();
        var copy = fixture.copy(source, owner);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.content_media_ref WHERE reuse_scope_id=:scope AND member_key=:member "
                        + "AND revision_id=:revision AND asset_owner_id=:owner").param("scope", copy.scope())
                .param("member", rich.member()).param("revision", rich.revision()).param("owner", author)
                .query(Long.class).single()).isEqualTo(3L);
        String authorManifest = manifests.current(author, source).body();
        assets.forEach(asset -> assertThat(authorManifest).contains(asset.toString()));

        // the copy owner edits the plain material (the editor cannot attach another account's asset yet)
        UUID copyRevision = fixture.save(owner, copy.deckId(), plain.member(), plain.revision(), fixture.document("copy edit"));
        // the database accepts the author's image on a revision written by the copy's owner: the FK is on the ASSET owner
        insertReference(copy.deckId(), copy.scope(), plain.member(), copyRevision, author, assets.getFirst());
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.content_media_ref WHERE revision_id=:revision AND asset_owner_id=:author")
                .param("revision", copyRevision).param("author", author).query(Long.class).single()).isOne();
        // a pair that is not the asset's real owner is still rejected
        assertThatThrownBy(() -> insertReference(copy.deckId(), copy.scope(), plain.member(), copyRevision, owner, assets.get(1)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("content_media_ref_asset_id_owner_id_fkey");

        // media views: the author's manifest is exactly what it was; the copy owner's has no asset of the author
        assertThat(manifests.current(author, source).body()).isEqualTo(authorManifest);
        assets.forEach(asset -> assertThat(manifests.current(owner, copy.deckId()).body()).doesNotContain(asset.toString()));
        // the app-level rule is unchanged for now (TODO Share/9, #431): the editor refuses another account's asset...
        assertThatThrownBy(() -> fixture.save(owner, copy.deckId(), rich.member(), rich.revision(), richWith(assets)))
                .isInstanceOf(ResourceNotFoundException.class);
        // ...and accepts the same document with the copy owner's own assets
        List<UUID> own = assets(owner);
        UUID withOwn = fixture.save(owner, copy.deckId(), rich.member(), rich.revision(), richWith(own));
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.content_media_ref WHERE reuse_scope_id=:scope AND revision_id=:revision "
                        + "AND asset_owner_id=:owner AND deck_id=:deck").param("scope", copy.scope()).param("revision", withOwn)
                .param("owner", owner).param("deck", copy.deckId()).query(Long.class).single()).isEqualTo(3L);
        own.forEach(asset -> assertThat(manifests.current(owner, copy.deckId()).body()).contains(asset.toString()));
        assets.forEach(asset -> assertThat(manifests.current(author, source).body()).contains(asset.toString()));
    }

    private List<UUID> assets(UUID owner) {
        List<UUID> assets = new ArrayList<>();
        for (int index = 0; index < 3; index++) assets.add(mediaCatalog.reserve(owner, UUID.randomUUID(), MediaCatalog.Origin.UPLOAD));
        return assets;
    }

    private static ObjectNode richWith(List<UUID> assets) throws Exception {
        ObjectNode document = (ObjectNode) rich().deepCopy();
        ArrayNode nodes = (ArrayNode) document.path("root").path("content");
        for (int index = 0; index < assets.size(); index++) {
            ((ObjectNode) nodes.get(index).path("attrs")).put("assetId", assets.get(index).toString());
        }
        return document;
    }

    private void insertReference(UUID deck, UUID scope, UUID member, UUID revision, UUID assetOwner, UUID asset) {
        jdbc.sql("INSERT INTO app_learning.content_media_ref(deck_id,reuse_scope_id,member_key,revision_id,node_id,asset_owner_id,asset_id) "
                        + "VALUES (:deck,:scope,:member,:revision,:node,:assetOwner,:asset)")
                .param("deck", deck).param("scope", scope).param("member", member).param("revision", revision)
                .param("node", UUID.randomUUID()).param("assetOwner", assetOwner).param("asset", asset).update();
    }

    @Test
    void aCopyBulkDeletesInheritedAndOwnEditedMaterialsWhileTheSourceKeepsAllOfThem() {
        UUID author = UUID.randomUUID();
        UUID source = fixture.deck(author, "Источник");
        var first = fixture.create(author, source, fixture.document("one"));
        var second = fixture.create(author, source, fixture.document("two"));
        var third = fixture.create(author, source, fixture.document("three"));
        UUID owner = UUID.randomUUID();
        var copy = fixture.copy(source, owner);
        UUID editedFirst = fixture.save(owner, copy.deckId(), first.member(), first.revision(), fixture.document("copy one"));
        fixture.save(author, source, second.member(), second.revision(), fixture.document("author two"));

        JsonNode head = decks.read(owner, copy.deckId());
        String revision = head.path("revisionId").stringValue(null);
        long version = Long.parseLong(head.path("rowVersion").stringValue(null));
        ObjectNode selection = JSON.createObjectNode().put("expectedDeckRevisionId", revision);
        selection.putArray("itemIds").add(first.member().toString()).add(second.member().toString());
        assertThat(deletions.preview(owner, copy.deckId(), selection).path("materialCount").intValue()).isEqualTo(2);
        ObjectNode everything = JSON.createObjectNode().put("expectedDeckRevisionId", revision).put("allInDeck", true);
        everything.putArray("except").add(third.member().toString());
        assertThat(deletions.preview(owner, copy.deckId(), everything).path("materialCount").intValue()).isEqualTo(2);

        ObjectNode command = selection.deepCopy().put("commandId", UUID.randomUUID().toString());
        var result = deletions.delete(owner, copy.deckId(), version,
                BulkDeleteCommand.read(new java.io.ByteArrayInputStream(bytes(command))));
        assertThat(result.acknowledgement().path("status").stringValue(null)).isEqualTo("COMPLETED");
        assertThat(items.list(owner, copy.deckId(), "20", null).path("items")).hasSize(1);
        assertThat(items.list(owner, copy.deckId(), "20", null).path("items").get(0).path("memberKey").stringValue(null))
                .isEqualTo(third.member().toString());
        // the journal names the revisions each material had IN THE COPY: its own edit and the inherited one
        assertThat(jdbc.sql("SELECT previous_revision_id FROM app_learning.deck_item_change WHERE deck_id=:deck AND member_key=:member "
                        + "AND change_kind='delete'").param("deck", copy.deckId()).param("member", first.member())
                .query(UUID.class).single()).isEqualTo(editedFirst);
        assertThat(jdbc.sql("SELECT previous_revision_id FROM app_learning.deck_item_change WHERE deck_id=:deck AND member_key=:member "
                        + "AND change_kind='delete'").param("deck", copy.deckId()).param("member", second.member())
                .query(UUID.class).single()).isEqualTo(second.revision());
        // the source is untouched: three materials, its own heads
        assertThat(items.list(author, source, "20", null).path("items")).hasSize(3);
        assertThat(items.read(author, source, first.member(), null).path("itemRevisionId").stringValue(null))
                .isEqualTo(first.revision().toString());
    }

    private boolean visible(UUID deck, UUID member, UUID revision) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM app_learning.item_revision r WHERE r.reuse_scope_id="
                        + "(SELECT reuse_scope_id FROM app_learning.deck WHERE deck_id=:deck) AND r.member_key=:member "
                        + "AND r.revision_id=:revision AND " + ItemRevisionVisibility.visibleTo(":deck", "r") + ")")
                .param("deck", deck).param("member", member).param("revision", revision).query(Boolean.class).single();
    }

    private static byte[] bytes(JsonNode value) {
        try { return JSON.writeValueAsBytes(value); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static JsonNode rich() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("contracts/content/native-v1/valid/rich.json"))) root = root.getParent();
        return JSON.readTree(Files.readString(root.resolve("contracts/content/native-v1/valid/rich.json")));
    }
}
