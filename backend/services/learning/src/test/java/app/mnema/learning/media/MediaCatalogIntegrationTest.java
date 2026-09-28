package app.mnema.learning.media;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.item.ItemPublicationCommand;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class MediaCatalogIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MediaCatalog catalog;
    @Autowired private MediaManifestCatalog manifests;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void offlineManifestPinsVerifiedBytesAndVersionsAssetStateWithoutLeakingStorageKeys() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID itemRevision = publish(owner, deck);
        UUID member = jdbc.sql("SELECT member_key FROM app_learning.item_revision WHERE deck_id=:deck")
                .param("deck", deck).query(UUID.class).single();
        UUID asset = reserve(owner);
        attach(owner, deck, member, itemRevision,
                List.of(new MediaCatalog.Reference(UUID.randomUUID(), asset)));

        var pending = manifests.current(owner, deck);
        assertThat(pending.version()).isEqualTo(1);
        assertThat(pending.body()).contains("PENDING_UPLOAD", asset.toString(), itemRevision.toString());
        assertThat(pending.body()).doesNotContain("object_key", "verified/");
        assertThat(manifests.current(owner, deck).id()).isEqualTo(pending.id());
        assertThatThrownBy(() -> manifests.current(UUID.randomUUID(), deck))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> manifests.read(UUID.randomUUID(), deck, pending.id()))
                .isInstanceOf(ResourceNotFoundException.class);

        UUID blob = verifiedBlob();
        processing(asset);
        UUID variant = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_learning.media_variant(variant_id,asset_id,asset_generation,purpose,profile,blob_id,"
                        + "created_at) VALUES (:variant,:asset,0,'playback','image_webp_2048_v1',:blob,CURRENT_TIMESTAMP)")
                .param("variant", variant).param("asset", asset).param("blob", blob).update();
        assertThat(catalog.ready(asset, 0, blob)).isTrue();
        var ready = manifests.current(owner, deck);
        assertThat(ready.version()).isEqualTo(2);
        assertThat(ready.id()).isNotEqualTo(pending.id());
        assertThat(ready.body()).contains("READY", "sha256", variant.toString(), blob.toString());
        assertThat(ready.body()).doesNotContain("verified/", "objectKey", "https://");
        assertThat(manifests.read(owner, deck, pending.id()).body()).isEqualTo(pending.body());
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.media_manifest_blob_ref WHERE manifest_id=:manifest")
                .param("manifest", ready.id()).query(Long.class).single()).isOne();
        long deckVersion = Long.parseLong(decks.read(owner, deck).path("rowVersion").textValue());
        decks.save(owner, deck, deckVersion, new DeckCommand(UUID.randomUUID(), "Renamed", "Description"));
        var revised = manifests.current(owner, deck);
        assertThat(revised.version()).isEqualTo(3);
        assertThat(revised.etag()).isNotEqualTo(ready.etag());
        assertThat(manifests.read(owner, deck, ready.id()).body()).isEqualTo(ready.body());
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.media_manifest SET version=4 "
                        + "WHERE manifest_id=:manifest").param("manifest", ready.id()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ownerAndRevisionReferencesAuthorizeSharedBlobWithoutMakingItsHashPublic() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID revision = publish(owner, deck);
        UUID member = jdbc.sql("SELECT member_key FROM app_learning.item_revision WHERE deck_id=:deck")
                .param("deck", deck).query(UUID.class).single();
        UUID asset = reserve(owner);
        UUID otherAsset = reserve(stranger);
        UUID blob = verifiedBlob();
        processing(asset);
        processing(otherAsset);
        assertThat(catalog.ready(asset, 1, blob)).isFalse();
        assertThat(catalog.ready(asset, 0, blob)).isTrue();
        assertThat(catalog.ready(otherAsset, 0, blob)).isTrue();
        assertThat(catalog.resolve(owner, asset, null).blobId()).isEqualTo(blob);
        assertThatThrownBy(() -> catalog.resolve(stranger, asset, null))
                .isInstanceOf(ResourceNotFoundException.class);
        UUID variant = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_learning.media_variant(variant_id,asset_id,asset_generation,purpose,profile,blob_id,"
                        + "created_at) VALUES (:variant,:asset,0,'thumbnail','image_320_webp',:blob,CURRENT_TIMESTAMP)")
                .param("variant", variant).param("asset", asset).param("blob", blob).update();
        UUID largerVariant = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_learning.media_variant(variant_id,asset_id,asset_generation,purpose,profile,blob_id,"
                        + "created_at) VALUES (:variant,:asset,0,'thumbnail','image_1024_webp',:blob,CURRENT_TIMESTAMP)")
                .param("variant", largerVariant).param("asset", asset).param("blob", blob).update();
        assertThat(catalog.resolve(owner, asset, variant).blobId()).isEqualTo(blob);
        assertThat(catalog.resolve(owner, asset, largerVariant).blobId()).isEqualTo(blob);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO app_learning.media_variant "
                        + "(variant_id,asset_id,asset_generation,purpose,profile,blob_id,created_at) "
                        + "VALUES (:variant,:asset,1,'thumbnail','image_2048_webp',:blob,CURRENT_TIMESTAMP)")
                .param("variant", UUID.randomUUID()).param("asset", asset).param("blob", blob).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> catalog.resolve(stranger, asset, variant))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.media_variant SET purpose='poster' "
                        + "WHERE variant_id=:variant").param("variant", variant).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> attach(owner, deck, member, revision,
                List.of(new MediaCatalog.Reference(UUID.randomUUID(), otherAsset))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> attach(owner, deck, member, UUID.randomUUID(),
                List.of(new MediaCatalog.Reference(UUID.randomUUID(), asset))))
                .isInstanceOf(ResourceNotFoundException.class);

        UUID node = UUID.randomUUID();
        attach(owner, deck, member, revision, List.of(new MediaCatalog.Reference(node, asset)));
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.content_media_ref WHERE asset_id=:asset")
                .param("asset", asset).query(Long.class).single()).isOne();
        assertThatThrownBy(() -> attach(stranger, deck, member, revision, List.of()))
                .isInstanceOf(ResourceNotFoundException.class);

        jdbc.sql("UPDATE app_learning.media_asset SET owner_hold_until=CURRENT_TIMESTAMP-interval '1 day' "
                        + "WHERE asset_id IN (:first,:second)")
                .param("first", asset).param("second", otherAsset).update();
        assertThat(catalog.expireUnattached(10)).isEqualTo(1);
        assertThat(catalog.resolve(owner, asset, null).blobId()).isEqualTo(blob);
        assertThatThrownBy(() -> catalog.resolve(stranger, otherAsset, null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.media_blob WHERE blob_id=:blob")
                .param("blob", blob).query(Long.class).single()).isOne();
    }

    @Test
    void playbackProjectionRequiresTheOwnerAndReachableReadyVariants() {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID asset = reserve(owner);
        assertThat(catalog.playback(owner, asset).state()).isEqualTo("PENDING_UPLOAD");
        assertThatThrownBy(() -> catalog.playback(stranger, asset))
                .isInstanceOf(ResourceNotFoundException.class);

        UUID source = verifiedBlob();
        UUID variant = UUID.randomUUID();
        processing(asset);
        jdbc.sql("INSERT INTO app_learning.media_variant(variant_id,asset_id,asset_generation,purpose,profile,blob_id,"
                        + "created_at) VALUES (:variant,:asset,0,'playback','image_webp_2048_v1',:blob,CURRENT_TIMESTAMP)")
                .param("variant", variant).param("asset", asset).param("blob", source).update();
        assertThat(catalog.ready(asset, 0, source)).isTrue();
        var view = catalog.playback(owner, asset);
        assertThat(view.state()).isEqualTo("READY");
        assertThat(view.playable().blobId()).isEqualTo(source);
        assertThat(view.original().blobId()).isEqualTo(source);
        assertThatThrownBy(() -> catalog.playback(stranger, asset))
                .isInstanceOf(ResourceNotFoundException.class);
        jdbc.sql("UPDATE app_learning.media_asset SET owner_hold_until=CURRENT_TIMESTAMP-interval '1 day' "
                        + "WHERE asset_id=:asset").param("asset", asset).update();
        assertThatThrownBy(() -> catalog.playback(owner, asset))
                .isInstanceOf(ResourceNotFoundException.class);
        jdbc.sql("UPDATE app_learning.media_asset SET state='DELETED',updated_at=CURRENT_TIMESTAMP "
                        + "WHERE asset_id=:asset").param("asset", asset).update();
    }

    @Test
    void draftReplacementAndFailedBatchKeepReferencesAtomic() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID draft = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.sql("INSERT INTO app_learning.editing_draft(draft_id,owner_id,deck_id,row_version,document,"
                        + "created_at,acknowledged_at,expires_at) "
                        + "VALUES (:draft,:owner,:deck,0,'{}'::jsonb,:now,:now,:expires)")
                .param("draft", draft).param("owner", owner).param("deck", deck)
                .param("now", Timestamp.from(now))
                .param("expires", Timestamp.from(now.plus(30, ChronoUnit.DAYS))).update();
        UUID first = reserve(owner);
        UUID second = reserve(owner);
        UUID foreign = reserve(UUID.randomUUID());
        UUID node = UUID.randomUUID();
        replaceDraft(owner, draft, List.of(new MediaCatalog.Reference(node, first)));
        assertThatThrownBy(() -> replaceDraft(owner, draft, List.of(
                new MediaCatalog.Reference(UUID.randomUUID(), second),
                new MediaCatalog.Reference(UUID.randomUUID(), foreign))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(jdbc.sql("SELECT asset_id FROM app_learning.draft_media_ref WHERE draft_id=:draft")
                .param("draft", draft).query(UUID.class).single()).isEqualTo(first);
        replaceDraft(owner, draft, List.of(new MediaCatalog.Reference(node, second)));
        assertThat(jdbc.sql("SELECT asset_id FROM app_learning.draft_media_ref WHERE draft_id=:draft")
                .param("draft", draft).query(UUID.class).single()).isEqualTo(second);
        assertThatThrownBy(() -> replaceDraft(UUID.randomUUID(), draft, List.of()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void referenceInsertRollsBackWithOuterPublicationTransaction() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID revision = publish(owner, deck);
        UUID member = jdbc.sql("SELECT member_key FROM app_learning.item_revision WHERE deck_id=:deck")
                .param("deck", deck).query(UUID.class).single();
        UUID asset = reserve(owner);
        assertThatThrownBy(() -> catalog.attachRevision(owner, deck, member, revision, List.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            catalog.attachRevision(owner, deck, member, revision,
                    List.of(new MediaCatalog.Reference(UUID.randomUUID(), asset)));
            status.setRollbackOnly();
        });
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.content_media_ref WHERE asset_id=:asset")
                .param("asset", asset).query(Long.class).single()).isZero();
    }

    @Test
    void databaseGuardsKeepLogicalOwnerAndVerifiedBytesStable() {
        UUID owner = UUID.randomUUID();
        UUID intent = UUID.randomUUID();
        UUID asset = catalog.reserve(owner, intent, MediaCatalog.Origin.UPLOAD);
        assertThat(catalog.reserve(owner, intent, MediaCatalog.Origin.UPLOAD)).isEqualTo(asset);
        assertThatThrownBy(() -> catalog.reserve(owner, intent, MediaCatalog.Origin.RECORDING))
                .isInstanceOf(IdempotencyConflictException.class);
        UUID blob = verifiedBlob();
        processing(asset);
        assertThat(catalog.ready(asset, 0, blob)).isTrue();
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.media_asset SET owner_id=:other "
                        + "WHERE asset_id=:asset").param("other", UUID.randomUUID())
                .param("asset", asset).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.media_asset SET generation=1,state='PENDING_UPLOAD' "
                        + "WHERE asset_id=:asset").param("asset", asset).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE app_learning.media_blob SET object_key='changed' "
                        + "WHERE blob_id=:blob").param("blob", blob).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentIdenticalIntentReservesOnlyOneAsset() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID intent = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                start.await();
                return catalog.reserve(owner, intent, MediaCatalog.Origin.UPLOAD);
            });
            var second = executor.submit(() -> {
                start.await();
                return catalog.reserve(owner, intent, MediaCatalog.Origin.UPLOAD);
            });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.media_asset "
                        + "WHERE owner_id=:owner AND upload_intent_id=:intent")
                .param("owner", owner).param("intent", intent).query(Long.class).single()).isOne();
    }

    @Test
    void expiredDraftDoesNotRetainOrAuthorizeReadyAsset() {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID draft = UUID.randomUUID();
        UUID asset = reserve(owner);
        UUID blob = verifiedBlob();
        processing(asset);
        assertThat(catalog.ready(asset, 0, blob)).isTrue();
        Instant acknowledged = Instant.now().minus(31, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        jdbc.sql("INSERT INTO app_learning.editing_draft(draft_id,owner_id,deck_id,row_version,document,"
                        + "created_at,acknowledged_at,expires_at) "
                        + "VALUES (:draft,:owner,:deck,0,'{}'::jsonb,:time,:time,:expires)")
                .param("draft", draft).param("owner", owner).param("deck", deck)
                .param("time", Timestamp.from(acknowledged))
                .param("expires", Timestamp.from(acknowledged.plus(30, ChronoUnit.DAYS))).update();
        jdbc.sql("INSERT INTO app_learning.draft_media_ref(draft_id,node_id,owner_id,asset_id) "
                        + "VALUES (:draft,:node,:owner,:asset)")
                .param("draft", draft).param("node", UUID.randomUUID())
                .param("owner", owner).param("asset", asset).update();
        jdbc.sql("UPDATE app_learning.media_asset SET owner_hold_until=CURRENT_TIMESTAMP-interval '1 day' "
                        + "WHERE asset_id=:asset").param("asset", asset).update();
        assertThatThrownBy(() -> catalog.resolve(owner, asset, null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> replaceDraft(owner, draft, List.of()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(catalog.expireUnattached(10)).isEqualTo(1);
    }

    @Test
    void reverseReferenceOrdersUseTheSameAssetLockOrder() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID revision = publish(owner, deck);
        UUID member = jdbc.sql("SELECT member_key FROM app_learning.item_revision WHERE deck_id=:deck")
                .param("deck", deck).query(UUID.class).single();
        UUID first = reserve(owner);
        UUID second = reserve(owner);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var left = executor.submit(() -> {
                start.await();
                attach(owner, deck, member, revision, List.of(
                        new MediaCatalog.Reference(UUID.randomUUID(), first),
                        new MediaCatalog.Reference(UUID.randomUUID(), second)));
                return true;
            });
            var right = executor.submit(() -> {
                start.await();
                attach(owner, deck, member, revision, List.of(
                        new MediaCatalog.Reference(UUID.randomUUID(), second),
                        new MediaCatalog.Reference(UUID.randomUUID(), first)));
                return true;
            });
            start.countDown();
            assertThat(left.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(right.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.content_media_ref WHERE deck_id=:deck")
                .param("deck", deck).query(Long.class).single()).isEqualTo(4);
    }

    private UUID deck(UUID owner) {
        return UUID.fromString(decks.create(owner, new DeckCommand(UUID.randomUUID(), "Deck", "Description"))
                .acknowledgement().path("deck").path("deckId").textValue());
    }

    private void attach(UUID owner, UUID deck, UUID member, UUID revision,
                        List<MediaCatalog.Reference> references) {
        new TransactionTemplate(transactions).executeWithoutResult(ignored ->
                catalog.attachRevision(owner, deck, member, revision, references));
    }

    private void replaceDraft(UUID owner, UUID draft, List<MediaCatalog.Reference> references) {
        new TransactionTemplate(transactions).executeWithoutResult(ignored ->
                catalog.replaceDraft(owner, draft, references));
    }

    private UUID reserve(UUID owner) {
        return catalog.reserve(owner, UUID.randomUUID(), MediaCatalog.Origin.UPLOAD);
    }

    private UUID publish(UUID owner, UUID deck) throws Exception {
        JsonNode before = decks.read(owner, deck);
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("contracts/content/native-v1/valid/mixed.json"))) root = root.getParent();
        JsonNode document = JSON.readTree(Files.readString(root.resolve("contracts/content/native-v1/valid/mixed.json")));
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", before.path("revisionId").textValue());
        body.set("document", document);
        var result = items.publish(owner, deck, 0,
                ItemPublicationCommand.readCreate(new ByteArrayInputStream(JSON.writeValueAsBytes(body))));
        return UUID.fromString(result.acknowledgement().path("changes").get(0).path("itemRevisionId").textValue());
    }

    private UUID verifiedBlob() {
        UUID blob = UUID.randomUUID();
        byte[] hash = new byte[32];
        java.nio.ByteBuffer.wrap(hash).putLong(blob.getMostSignificantBits()).putLong(blob.getLeastSignificantBits());
        jdbc.sql("INSERT INTO app_learning.media_blob(blob_id,sha256,byte_length,mime_type,object_key,verified_at) "
                        + "VALUES (:blob,:hash,32,'image/png',:key,CURRENT_TIMESTAMP)")
                .param("blob", blob).param("hash", hash).param("key", "verified/" + blob).update();
        return blob;
    }

    private void processing(UUID asset) {
        jdbc.sql("UPDATE app_learning.media_asset SET state='PROCESSING',updated_at=CURRENT_TIMESTAMP "
                        + "WHERE asset_id=:asset")
                .param("asset", asset).update();
    }
}
