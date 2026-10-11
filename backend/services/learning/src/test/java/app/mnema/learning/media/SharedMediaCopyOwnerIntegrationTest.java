package app.mnema.learning.media;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.attempt.AttemptService;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.MediaFixtures;
import app.mnema.learning.support.MediaFixtures.Variants;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.SharedScopeFixture;
import app.mnema.learning.support.SharedScopeFixture.Copy;
import app.mnema.learning.support.StudyFixtures;
import app.mnema.learning.support.StudyFixtures.Issued;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static app.mnema.learning.support.StudyFixtures.audio;
import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.text;
import static app.mnema.learning.support.StudyFixtures.textResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Share/9 (#431): the owner of a COPY uses the media his heads reference, whoever owns the assets: playback as derived variants only (never the original),
 * the offline manifest, Study of an inherited exercise with sound, and editing a material that keeps an inherited image. The heads decide: what a copy no
 * longer holds is not served, and an asset no head of the deck references cannot be attached.
 */
@SpringBootTest
class SharedMediaCopyOwnerIntegrationTest extends PostgresIntegrationTest {
    @Autowired private MediaCatalog catalog;
    @Autowired private MediaManifestCatalog manifests;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private AttemptService attempts;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    private StudyFixtures study;
    private SharedScopeFixture shared;

    private final UUID author = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    @BeforeEach
    void fixtures() {
        study = new StudyFixtures(decks, items, exercises, sessions, catalog, jdbc);
        shared = new SharedScopeFixture(decks, items, storage, jdbc, transactions);
    }

    /** The author's deck: a material with an image and an exercise with a sound (both READY with variants), and a copy owned by another account. */
    private record World(UUID deck, UUID image, Variants imageVariants, UUID sound, Variants soundVariants, SharedScopeFixture.Material material,
                         StudyFixtures.Material host, JsonNode exercise, Copy copy) { }

    private World world() {
        UUID image = study.readyAsset(author, "image/png");
        Variants imageVariants = MediaFixtures.variants(jdbc, image, "image/webp");
        UUID sound = study.readyAsset(author, "audio/mpeg");
        Variants soundVariants = MediaFixtures.variants(jdbc, sound, "audio/mp4", false);
        UUID deck = shared.deck(author, "Источник");
        SharedScopeFixture.Material material = shared.create(author, deck, MediaFixtures.imageDocument(image));
        StudyFixtures.Material host = study.addMaterial(author, deck, "memory", "forgetting");
        JsonNode exercise = study.publish(host, study.freeResponse(host, blocks(audio(sound, "Звук", null), text("Что слышно?")), blocks(), "memory"), "Слух");
        return new World(deck, image, imageVariants, sound, soundVariants, material, host, exercise, shared.copy(deck, UUID.randomUUID()));
    }

    @Test
    void aCopyOwnerPlaysInheritedMediaAsDerivedVariantsAndNeverTheOriginal() {
        World world = world();
        UUID copyOwner = world.copy().owner();
        // the author: variants and the original
        var own = catalog.playback(author, world.image());
        assertThat(own.original()).isNotNull();
        assertThat(own.original().objectKey()).isEqualTo(world.imageVariants().originalKey());

        for (UUID asset : List.of(world.image(), world.sound())) {
            var inherited = catalog.playback(copyOwner, asset);
            assertThat(inherited.state()).isEqualTo("READY");
            assertThat(inherited.playable().objectKey()).startsWith("variant/");
            assertThat(inherited.original()).as("the original is the owner's alone").isNull();
        }
        assertThat(catalog.playback(copyOwner, world.image()).poster().objectKey()).isEqualTo(world.imageVariants().posterKey());
        assertThat(catalog.playback(copyOwner, world.sound()).poster()).isNull();
        // a derived variant is resolvable, the source never is
        assertThat(catalog.resolve(copyOwner, world.image(), world.imageVariants().playback()).objectKey()).isEqualTo(world.imageVariants().playbackKey());
        assertThatThrownBy(() -> catalog.resolve(copyOwner, world.image(), null)).isInstanceOf(ResourceNotFoundException.class);
        // a variant of ANOTHER asset is not reachable through this asset
        assertThatThrownBy(() -> catalog.resolve(copyOwner, world.image(), world.soundVariants().playback())).isInstanceOf(ResourceNotFoundException.class);

        // a stranger owns no deck of the lineage: nothing, on every method
        assertThatThrownBy(() -> catalog.playback(stranger, world.image())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> catalog.resolve(stranger, world.image(), world.imageVariants().playback())).isInstanceOf(ResourceNotFoundException.class);
        // the author's lineage does not open the author's OTHER assets to the copy owner
        UUID unrelated = study.readyAsset(author, "image/png");
        MediaFixtures.variants(jdbc, unrelated, "image/webp");
        assertThatThrownBy(() -> catalog.playback(copyOwner, unrelated)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void whatTheHeadsOfTheCopyNoLongerHoldIsNotServedToItsOwner() {
        World world = world();
        UUID copyOwner = world.copy().owner();
        assertThat(catalog.playback(copyOwner, world.image()).state()).isEqualTo("READY");
        MediaFixtures.deleteMaterial(decks, items, copyOwner, world.copy().deckId(), world.material().member(), world.material().revision());
        assertThatThrownBy(() -> catalog.playback(copyOwner, world.image())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> catalog.resolve(copyOwner, world.image(), world.imageVariants().playback())).isInstanceOf(ResourceNotFoundException.class);
        // the exercise sound is still in his heads
        assertThat(catalog.playback(copyOwner, world.sound()).state()).isEqualTo("READY");
        // and the author's own copy of the lineage is unaffected
        assertThat(catalog.playback(author, world.image()).original()).isNotNull();
    }

    @Test
    void theOfflineManifestOfACopyListsInheritedAssetsWithoutTheirOriginals() {
        World world = world();
        String authors = manifests.current(author, world.deck()).body();
        assertThat(authors).contains(world.image().toString()).contains(world.sound().toString()).contains("\"purpose\":\"original\"");
        String copys = manifests.current(world.copy().owner(), world.copy().deckId()).body();
        assertThat(copys).contains(world.image().toString()).contains(world.sound().toString());
        assertThat(copys).contains(world.imageVariants().playback().toString()).contains(world.imageVariants().poster().toString());
        assertThat(copys).as("a derived variant is all the copy owner is offered").doesNotContain("\"purpose\":\"original\"");
        // the manifest of a deck is its owner's alone
        assertThatThrownBy(() -> manifests.current(stranger, world.copy().deckId())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> manifests.current(world.copy().owner(), world.deck())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theCopyOwnerStudiesAnInheritedExerciseWithSoundAndPlaysItsAudio() {
        World world = world();
        StudyFixtures.Material inCopy = new StudyFixtures.Material(world.copy().owner(), world.copy().deckId(), world.host().member(), world.host().itemRevision(),
                world.host().node(), world.host().distractor(), world.host().divider(), world.host().root());
        List<Issued> issued = study.issue(inCopy, "SCHEDULED", null);
        assertThat(issued).hasSize(1);
        Issued presentation = issued.getFirst();
        assertThat(presentation.json().toString()).contains(world.sound().toString());
        // the sound of the presentation plays for the copy's owner, as a variant
        assertThat(catalog.playback(world.copy().owner(), world.sound()).playable().objectKey()).isEqualTo(world.soundVariants().playbackKey());
        // the attempt is judged ready: readiness is a property of the lineage revision, not of who owns the asset
        assertThat(attempts.submit(world.copy().owner(), world.copy().deckId(), presentation.session(), StudyFixtures.attempt(presentation, textResponse("memory")))).isNotNull();
    }

    @Test
    void editingAMaterialOfACopyKeepsAnInheritedImageButAnUnheldAssetCannotBeAttached() {
        World world = world();
        UUID copyOwner = world.copy().owner();
        UUID copyDeck = world.copy().deckId();
        UUID own = study.readyAsset(copyOwner, "image/png");
        UUID foreign = study.readyAsset(author, "image/png");
        UUID strangers = study.readyAsset(stranger, "image/png");

        // keep the inherited image, add his own: both references; the inherited one names its REAL owner
        UUID second = MediaFixtures.saveImages(decks, items, copyOwner, copyDeck, world.material().member(), world.material().revision(), world.image(), own);
        assertThat(referenceOwner(second, world.image())).isEqualTo(author);
        assertThat(referenceOwner(second, own)).isEqualTo(copyOwner);
        // the author's other asset, which no head of his deck references, and a stranger's asset are refused
        assertThatThrownBy(() -> MediaFixtures.saveImages(decks, items, copyOwner, copyDeck, world.material().member(), second, world.image(), foreign))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> MediaFixtures.saveImages(decks, items, copyOwner, copyDeck, world.material().member(), second, world.image(), strangers))
                .isInstanceOf(ResourceNotFoundException.class);
        // dropping the inherited image removes it from the copy's heads: it cannot be attached again (fail closed)
        UUID third = MediaFixtures.saveImages(decks, items, copyOwner, copyDeck, world.material().member(), second, own);
        assertThatThrownBy(() -> MediaFixtures.saveImages(decks, items, copyOwner, copyDeck, world.material().member(), third, own, world.image()))
                .isInstanceOf(ResourceNotFoundException.class);
        // the source deck is untouched: the author's head still holds the image
        assertThat(catalog.playback(author, world.image()).original()).isNotNull();
        // an author cannot attach the copy owner's asset to his own deck either
        assertThatThrownBy(() -> MediaFixtures.saveImages(decks, items, author, world.deck(), world.material().member(), world.material().revision(), world.image(), own))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private UUID referenceOwner(UUID revision, UUID asset) {
        return jdbc.sql("SELECT asset_owner_id FROM app_learning.content_media_ref WHERE revision_id = :revision AND asset_id = :asset")
                .param("revision", revision).param("asset", asset).query(UUID.class).single();
    }

    @Test
    void aDraftOfACopyOwnerValidatesInheritedAssetsAndHoldsOnlyHisOwn() {
        World world = world();
        UUID copyOwner = world.copy().owner();
        UUID own = study.readyAsset(copyOwner, "image/png");
        UUID foreign = study.readyAsset(author, "image/png");
        UUID draft = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.sql("INSERT INTO app_learning.editing_draft(draft_id,owner_id,deck_id,reuse_scope_id,row_version,document,created_at,acknowledged_at,expires_at) "
                        + "VALUES (:draft,:owner,:deck,:scope,0,'{}'::jsonb,:now,:now,:expires)")
                .param("draft", draft).param("owner", copyOwner).param("deck", world.copy().deckId()).param("scope", world.copy().scope())
                .param("now", Timestamp.from(now)).param("expires", Timestamp.from(now.plus(30, ChronoUnit.DAYS))).update();
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        transaction.executeWithoutResult(status -> catalog.replaceDraft(copyOwner, draft, List.of(
                new MediaCatalog.Reference(UUID.randomUUID(), world.image()), new MediaCatalog.Reference(UUID.randomUUID(), own))));
        // the hold row names the draft's owner and his asset only; the inherited image is kept alive by the lineage references
        assertThat(jdbc.sql("SELECT asset_id FROM app_learning.draft_media_ref WHERE draft_id = :draft").param("draft", draft).query(UUID.class).list())
                .containsExactly(own);
        // an asset his heads do not hold is refused and the holds of the draft are not evicted by the failed call
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> catalog.replaceDraft(copyOwner, draft, List.of(
                new MediaCatalog.Reference(UUID.randomUUID(), foreign))))).isInstanceOf(ResourceNotFoundException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.draft_media_ref WHERE draft_id = :draft").param("draft", draft).query(Long.class).single()).isOne();
    }

    @Test
    void theAuthorizationStatementsLeadFromTheReferencesOfTheAssetAndNeverScanATable() {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        String indexes = jdbc.sql("SELECT string_agg(indexdef, E'\\n') FROM pg_indexes WHERE schemaname = 'app_learning' AND indexname IN ('deck_scope_owner', 'published_media_pkey')")
                .query(String.class).single();
        assertThat(indexes).contains("deck USING btree (reuse_scope_id, owner_id)").contains("(deck_id, asset_id)");
        // the REAL predicates (the same constants the catalog runs), explained on a database whose planner would rather scan if it could
        String copy = plan(transaction, "SELECT 1 FROM app_learning.media_asset a WHERE a.asset_id = :asset AND " + MediaCatalog.IN_HEADS_OF_ACTOR, "actor");
        String attach = plan(transaction, "SELECT a.owner_id FROM app_learning.media_asset a WHERE a.asset_id = :asset AND " + MediaCatalog.IN_HEADS_OF_DECK, "deck");
        String published = plan(transaction, "SELECT 1 FROM app_learning.media_asset a WHERE a.asset_id = :asset AND EXISTS "
                + "(SELECT 1 FROM app_learning.published_media pm WHERE pm.deck_id = :deck AND pm.asset_id = a.asset_id)", "deck");
        for (String statement : List.of(copy, attach)) {
            assertThat(statement).doesNotContain("Seq Scan").contains("content_media_ref_asset").contains("exercise_media_ref_asset").contains("deck_head_item_pkey");
            // led by the references of the asset: they are scanned before the heads are probed
            assertThat(statement.indexOf("content_media_ref_asset")).isLessThan(statement.indexOf("deck_head_item_pkey"));
        }
        // his decks are reached by the lineage index or, when the statistics prefer, by his own list: a few decks times the few references of one asset
        assertThat(copy).containsAnyOf("deck_scope_owner", "deck_owner_page");
        assertThat(published).contains("published_media_pkey").doesNotContain("Seq Scan");
    }

    private String plan(TransactionTemplate transaction, String statement, String extra) {
        return transaction.execute(status -> {
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            var query = jdbc.sql("EXPLAIN " + statement).param("asset", UUID.randomUUID()).param(extra, UUID.randomUUID());
            return String.join("\n", query.query(String.class).list());
        });
    }
}
