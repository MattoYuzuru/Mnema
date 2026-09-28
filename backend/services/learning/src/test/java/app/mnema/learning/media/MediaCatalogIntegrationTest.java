package app.mnema.learning.media;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.item.ItemPublicationCommand;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class MediaCatalogIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MediaCatalog catalog;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void ownerAndRevisionReferencesAuthorizeSharedBlobWithoutMakingItsHashPublic() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID revision = publish(owner, deck);
        UUID member = jdbc.sql("SELECT member_key FROM app_learning.item_revision WHERE deck_id=:deck")
                .param("deck", deck).query(UUID.class).single();
        UUID asset = catalog.reserve(owner);
        UUID otherAsset = catalog.reserve(stranger);
        UUID blob = verifiedBlob();
        processing(asset);
        processing(otherAsset);
        assertThat(catalog.ready(asset, 1, blob)).isFalse();
        assertThat(catalog.ready(asset, 0, blob)).isTrue();
        assertThat(catalog.ready(otherAsset, 0, blob)).isTrue();
        assertThat(catalog.resolve(owner, asset, null).blobId()).isEqualTo(blob);
        assertThatThrownBy(() -> catalog.resolve(stranger, asset, null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> catalog.attachRevision(owner, deck, member, revision,
                List.of(new MediaCatalog.Reference(UUID.randomUUID(), otherAsset))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> catalog.attachRevision(owner, deck, member, UUID.randomUUID(),
                List.of(new MediaCatalog.Reference(UUID.randomUUID(), asset))))
                .isInstanceOf(ResourceNotFoundException.class);

        UUID node = UUID.randomUUID();
        catalog.attachRevision(owner, deck, member, revision, List.of(new MediaCatalog.Reference(node, asset)));
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.content_media_ref WHERE asset_id=:asset")
                .param("asset", asset).query(Long.class).single()).isOne();
        assertThatThrownBy(() -> catalog.attachRevision(stranger, deck, member, revision, List.of()))
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
        UUID first = catalog.reserve(owner);
        UUID second = catalog.reserve(owner);
        UUID foreign = catalog.reserve(UUID.randomUUID());
        UUID node = UUID.randomUUID();
        catalog.replaceDraft(owner, draft, List.of(new MediaCatalog.Reference(node, first)));
        assertThatThrownBy(() -> catalog.replaceDraft(owner, draft, List.of(
                new MediaCatalog.Reference(UUID.randomUUID(), second),
                new MediaCatalog.Reference(UUID.randomUUID(), foreign))))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(jdbc.sql("SELECT asset_id FROM app_learning.draft_media_ref WHERE draft_id=:draft")
                .param("draft", draft).query(UUID.class).single()).isEqualTo(first);
        catalog.replaceDraft(owner, draft, List.of(new MediaCatalog.Reference(node, second)));
        assertThat(jdbc.sql("SELECT asset_id FROM app_learning.draft_media_ref WHERE draft_id=:draft")
                .param("draft", draft).query(UUID.class).single()).isEqualTo(second);
        assertThatThrownBy(() -> catalog.replaceDraft(UUID.randomUUID(), draft, List.of()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void referenceInsertRollsBackWithOuterPublicationTransaction() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID revision = publish(owner, deck);
        UUID member = jdbc.sql("SELECT member_key FROM app_learning.item_revision WHERE deck_id=:deck")
                .param("deck", deck).query(UUID.class).single();
        UUID asset = catalog.reserve(owner);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            catalog.attachRevision(owner, deck, member, revision,
                    List.of(new MediaCatalog.Reference(UUID.randomUUID(), asset)));
            status.setRollbackOnly();
        });
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.content_media_ref WHERE asset_id=:asset")
                .param("asset", asset).query(Long.class).single()).isZero();
    }

    private UUID deck(UUID owner) {
        return UUID.fromString(decks.create(owner, new DeckCommand(UUID.randomUUID(), "Deck", "Description"))
                .acknowledgement().path("deck").path("deckId").textValue());
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
