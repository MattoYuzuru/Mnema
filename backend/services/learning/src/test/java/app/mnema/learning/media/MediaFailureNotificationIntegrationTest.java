package app.mnema.learning.media;

import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The first notification producer: a terminal media failure notifies its owner in the very transaction that records
 * the failure, once per asset, and never for a failure that will be retried.
 */
@SpringBootTest
class MediaFailureNotificationIntegrationTest extends PostgresIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final byte[] FINGERPRINT = new byte[32];

    @Autowired private MediaProcessingRepository processing;
    @Autowired private MediaProcessingSettings settings;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcClient jdbc;

    @Test
    void aRejectedAssetNotifiesTheOwnerExactlyOnceWithOnlyIdentifiersAndEnums() {
        UUID owner = UUID.randomUUID();
        UUID asset = sealedAsset(owner, "image");
        var claim = processing.claim(asset);
        assertThat(processing.rejected(claim, "unsupported_image")).isTrue();

        List<Row> rows = notifications(owner);
        assertThat(rows).hasSize(1);
        Row row = rows.getFirst();
        assertThat(row.kind()).isEqualTo("MEDIA_PROCESSING_FAILED");
        assertThat(row.severity()).isEqualTo("ERROR");
        assertThat(row.route()).isEqualTo("NONE");
        assertThat(row.dedupeKey()).isEqualTo("media:" + asset + ":failed");
        assertThat(row.params().path("assetId").stringValue(null)).isEqualTo(asset.toString());
        assertThat(row.params().path("mediaKind").stringValue(null)).isEqualTo("IMAGE");
        assertThat(row.params().path("reason").stringValue(null)).isEqualTo("VERIFICATION_REJECTED");
        for (String unknown : List.of("deckId", "sessionId", "artifactId", "slotKey")) {
            assertThat(row.params().has(unknown)).as(unknown).isTrue();
            assertThat(row.params().path(unknown).isNull()).as(unknown).isTrue();
        }
        assertThat(row.params().size()).isEqualTo(7);
        assertThat(row.seq()).isEqualTo(1);
        // a stale worker's rejection is not a failure of the asset and notifies nobody
        assertThat(processing.rejected(claim, "unsupported_image")).isFalse();
        assertThat(notifications(owner)).hasSize(1);
    }

    @Test
    void exhaustedRetriesNotifyOnlyOnTheFinalAttempt() {
        UUID owner = UUID.randomUUID();
        UUID asset = sealedAsset(owner, "audio");
        var first = processing.claim(asset);
        assertThat(first.attempt()).isEqualTo(1);
        assertThat(processing.retryable(first, "processing_unavailable")).isTrue();
        assertThat(notifications(owner)).isEmpty();

        jdbc.sql("UPDATE app_learning.media_upload_session SET processing_next_attempt_at=NULL,"
                        + "processing_attempts=:attempts WHERE asset_id=:asset")
                .param("attempts", settings.maxAttempts - 1).param("asset", asset).update();
        var last = processing.claim(asset);
        assertThat(last.attempt()).isEqualTo(settings.maxAttempts);
        assertThat(processing.retryable(last, "processing_unavailable")).isTrue();

        assertThat(assetState(asset)).isEqualTo("FAILED_RETRYABLE");
        List<Row> rows = notifications(owner);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().params().path("mediaKind").stringValue(null)).isEqualTo("AUDIO");
        assertThat(rows.getFirst().params().path("reason").stringValue(null)).isEqualTo("PROCESSING_FAILED");

        // the owner retries the preserved bytes and it fails again: the first notification stands, no second one
        processing.retryPreserved(owner, asset, 0);
        jdbc.sql("UPDATE app_learning.media_upload_session SET processing_attempts=:attempts WHERE asset_id=:asset")
                .param("attempts", settings.maxAttempts - 1).param("asset", asset).update();
        assertThat(processing.retryable(processing.claim(asset), "processing_unavailable")).isTrue();
        assertThat(notifications(owner)).hasSize(1);
    }

    @Test
    void anInterruptedAssetThatRanOutOfAttemptsNotifiesWhenTheClaimGivesUp() {
        UUID owner = UUID.randomUUID();
        UUID asset = sealedAsset(owner, "video");
        jdbc.sql("UPDATE app_learning.media_upload_session SET processing_attempts=:attempts WHERE asset_id=:asset")
                .param("attempts", settings.maxAttempts).param("asset", asset).update();
        assertThat(processing.claim(asset)).isNull();
        assertThat(assetState(asset)).isEqualTo("FAILED_RETRYABLE");
        List<Row> rows = notifications(owner);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().params().path("mediaKind").stringValue(null)).isEqualTo("VIDEO");
        assertThat(rows.getFirst().params().path("reason").stringValue(null)).isEqualTo("PROCESSING_FAILED");
    }

    @Test
    void theNotificationAndTheStateChangeCommitOrRollBackTogether() {
        UUID owner = UUID.randomUUID();
        UUID asset = sealedAsset(owner, "image");
        var claim = processing.claim(asset);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(processing.rejected(claim, "unsupported_image")).isTrue();
            assertThat(notifications(owner)).hasSize(1);
            status.setRollbackOnly();
        });
        assertThat(assetState(asset)).isEqualTo("PROCESSING");
        assertThat(notifications(owner)).isEmpty();
    }

    @Test
    void aSuccessfulOrRetryableOutcomeNotifiesNobody() {
        UUID owner = UUID.randomUUID();
        UUID asset = sealedAsset(owner, "image");
        var claim = processing.claim(asset);
        assertThat(processing.retryable(claim, "processing_unavailable")).isTrue();
        assertThat(assetState(asset)).isEqualTo("PROCESSING");
        assertThat(notifications(owner)).isEmpty();
    }

    private UUID sealedAsset(UUID owner, String kind) {
        UUID asset = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_learning.media_asset(asset_id,owner_id,upload_intent_id,origin,state,created_at,"
                        + "updated_at) VALUES (:asset,:owner,:intent,'upload','VERIFYING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
                .param("asset", asset).param("owner", owner).param("intent", UUID.randomUUID()).update();
        UUID session = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_learning.media_upload_session(session_id,asset_id,owner_id,generation,kind,"
                        + "declared_mime,declared_length,request_fingerprint,method,staging_key,frozen_key,state,"
                        + "finalize_command_id,issued_until,expires_at,created_at,updated_at) VALUES (:session,:asset,"
                        + ":owner,0,:kind,'image/png',10,:fingerprint,'SINGLE',:staging,:frozen,'SEALED',:finalize,"
                        + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + interval '2 hours',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
                .param("session", session).param("asset", asset).param("owner", owner).param("kind", kind)
                .param("fingerprint", FINGERPRINT).param("staging", "staging/" + session)
                .param("frozen", "quarantine/" + session).param("finalize", UUID.randomUUID(), java.sql.Types.OTHER).update();
        return asset;
    }

    private String assetState(UUID asset) {
        return jdbc.sql("SELECT state FROM app_learning.media_asset WHERE asset_id=:asset")
                .param("asset", asset).query(String.class).single();
    }

    private List<Row> notifications(UUID owner) {
        return jdbc.sql("SELECT seq,kind,severity,route,dedupe_key,params::text AS params FROM app_learning.notification "
                        + "WHERE owner_id=:owner ORDER BY seq").param("owner", owner)
                .query((row, ignored) -> new Row(row.getLong("seq"), row.getString("kind"), row.getString("severity"),
                        row.getString("route"), row.getString("dedupe_key"), JSON.readTree(row.getString("params"))))
                .list();
    }

    private record Row(long seq, String kind, String severity, String route, String dedupeKey, JsonNode params) { }
}
