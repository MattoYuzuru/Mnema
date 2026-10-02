package app.mnema.learning.media;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A database clock that steps backwards by a few milliseconds must not break
 * {@code updated_at >= created_at}. Every row below is created ahead of the database clock (an hour, so slow CI
 * cannot catch up), which is what a backwards step looks like to the next transaction, and then driven
 * through the real repositories. The CHECK constraints stay unchanged.
 */
@SpringBootTest
class MediaClockStepIntegrationTest extends PostgresIntegrationTest {
    private static final String AHEAD = "statement_timestamp() + interval '1 hour'";
    private static final byte[] FINGERPRINT = new byte[32];

    @Autowired private MediaUploadRepository uploads;
    @Autowired private MediaProcessingRepository processing;
    @Autowired private MediaGcRepository gc;
    @Autowired private MediaCatalog catalog;
    @Autowired private DeckService decks;
    @Autowired private JdbcClient jdbc;

    @Test
    void multipartUploadFinalizeSealProcessAndPublishSucceedWhenSessionAndAssetAreAhead() {
        UUID owner = UUID.randomUUID();
        UUID asset = aheadAsset(owner, "PENDING_UPLOAD");
        UUID session = aheadSession(asset, owner, "INITIATING", "MULTIPART", null);

        assertThat(uploads.claimInitiation(session)).isTrue();
        uploads.releaseInitiation(session);
        assertThat(uploads.claimInitiation(session)).isTrue();
        uploads.open(session, "storage-upload");
        uploads.issue(owner, asset, 0, Instant.now().plusSeconds(60));
        UUID command = UUID.randomUUID();
        assertThat(uploads.claim(owner, asset, 0, command).acquired()).isTrue();
        uploads.releaseIncomplete(session, command);
        assertThat(uploads.claim(owner, asset, 0, command).acquired()).isTrue();
        uploads.seal(session, command);
        assertThat(assetState(asset)).isEqualTo("VERIFYING");

        var claim = processing.claim(asset);
        assertThat(claim).isNotNull();
        assertThat(processing.heartbeat(claim)).isTrue();
        String sha = HexFormat.of().formatHex(sha256Of(session));
        String key = "verified/" + session;
        aheadGcObject(key, "FIRST");
        assertThat(processing.complete(claim, new MediaProcessingRepository.Blob(sha, 10, "image/png", key),
                List.of())).isTrue();
        assertThat(assetState(asset)).isEqualTo("READY");
        assertThat(gcState(key)).isEqualTo("TRACKED");
        assertOrdered("media_asset", "asset_id", asset);
        assertOrdered("media_upload_session", "session_id", session);
        assertOrdered("media_gc_object", "object_key", key);
    }

    @Test
    void singleCopyFailureRetryAndCancelSucceedWhenSessionAndAssetAreAhead() {
        UUID owner = UUID.randomUUID();
        UUID asset = aheadAsset(owner, "PENDING_UPLOAD");
        UUID session = aheadSession(asset, owner, "OPEN", "SINGLE", null);
        UUID command = UUID.randomUUID();
        assertThat(uploads.claim(owner, asset, 0, command).acquired()).isTrue();
        assertThat(uploads.claimCopy(session, command)).isTrue();
        uploads.rejectedCopyPrecondition(session, command);
        assertThat(uploads.claim(owner, asset, 0, command).acquired()).isTrue();
        assertThat(uploads.claimCopy(session, command)).isTrue();
        uploads.failUncertainCopy(session, command);
        assertThat(assetState(asset)).isEqualTo("FAILED_RETRYABLE");

        var retried = uploads.retry(owner, asset, UUID.randomUUID(), "image", "image/png", 10, FINGERPRINT);
        assertThat(retried.generation()).isOne();
        assertOrdered("media_asset", "asset_id", asset);
        assertOrdered("media_upload_session", "session_id", session);

        UUID cancelOwner = UUID.randomUUID();
        UUID cancelled = aheadAsset(cancelOwner, "PENDING_UPLOAD");
        UUID cancelledSession = aheadSession(cancelled, cancelOwner, "OPEN", "SINGLE", null);
        uploads.cancel(cancelOwner, cancelled, 0);
        uploads.cleaned(cancelledSession);
        assertThat(assetState(cancelled)).isEqualTo("FAILED_RETRYABLE");
        assertOrdered("media_asset", "asset_id", cancelled);
        assertOrdered("media_upload_session", "session_id", cancelledSession);
    }

    @Test
    void processingRejectionRetryAndPreservedRetrySucceedWhenSessionAndAssetAreAhead() {
        UUID owner = UUID.randomUUID();
        UUID rejected = aheadAsset(owner, "VERIFYING");
        UUID rejectedSession = aheadSession(rejected, owner, "SEALED", "SINGLE", UUID.randomUUID());
        var rejectedClaim = processing.claim(rejected);
        assertThat(rejectedClaim).isNotNull();
        assertThat(processing.rejected(rejectedClaim, "invalid_media")).isTrue();
        assertThat(assetState(rejected)).isEqualTo("REJECTED");

        UUID transientAsset = aheadAsset(owner, "VERIFYING");
        UUID transientSession = aheadSession(transientAsset, owner, "SEALED", "SINGLE", UUID.randomUUID());
        var transientClaim = processing.claim(transientAsset);
        assertThat(transientClaim).isNotNull();
        assertThat(processing.retryable(transientClaim, "worker_unavailable")).isTrue();

        UUID exhausted = aheadAsset(owner, "VERIFYING");
        UUID exhaustedSession = aheadSession(exhausted, owner, "SEALED", "SINGLE", UUID.randomUUID());
        jdbc.sql("UPDATE app_learning.media_upload_session SET processing_attempts=1000 WHERE session_id=:session")
                .param("session", exhaustedSession).update();
        assertThat(processing.claim(exhausted)).isNull();
        assertThat(assetState(exhausted)).isEqualTo("FAILED_RETRYABLE");
        processing.retryPreserved(owner, exhausted, 0);
        assertThat(assetState(exhausted)).isEqualTo("VERIFYING");

        for (UUID asset : List.of(rejected, transientAsset, exhausted)) assertOrdered("media_asset", "asset_id", asset);
        for (UUID session : List.of(rejectedSession, transientSession, exhaustedSession)) {
            assertOrdered("media_upload_session", "session_id", session);
        }
    }

    @Test
    void ownerHoldTombstoneSucceedsWhenAssetIsAhead() {
        UUID owner = UUID.randomUUID();
        UUID asset = UUID.randomUUID();
        UUID blob = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_learning.media_blob(blob_id,sha256,byte_length,mime_type,object_key,verified_at) "
                        + "VALUES (:blob,:hash,10,'image/png',:key,CURRENT_TIMESTAMP)")
                .param("blob", blob).param("hash", sha256Of(blob)).param("key", "verified/" + blob).update();
        jdbc.sql("INSERT INTO app_learning.media_asset(asset_id,owner_id,upload_intent_id,origin,state,source_blob_id,"
                        + "owner_hold_until,created_at,updated_at) VALUES (:asset,:owner,:intent,'upload','READY',:blob,"
                        + "CURRENT_TIMESTAMP - interval '1 day'," + AHEAD + "," + AHEAD + ")")
                .param("asset", asset).param("owner", owner).param("intent", UUID.randomUUID())
                .param("blob", blob).update();

        assertThat(catalog.expireUnattached(1000)).isGreaterThanOrEqualTo(1);
        assertThat(assetState(asset)).isEqualTo("DELETED");
        assertOrdered("media_asset", "asset_id", asset);
    }

    @Test
    void garbageCollectionMarkClaimDeferAndCompleteSucceedWhenObjectIsAhead() {
        String key = "gc/" + UUID.randomUUID();
        aheadGcObject(key, "TRACKED");
        gc.scanKey(key, 0);
        assertThat(gcState(key)).isEqualTo("FIRST");
        jdbc.sql("UPDATE app_learning.media_gc_object SET first_scan_at=CURRENT_TIMESTAMP - interval '2 days' "
                + "WHERE object_key=:key").param("key", key).update();
        gc.scanKey(key, 1);
        assertThat(gcState(key)).isEqualTo("SECOND");

        var deletion = gc.claimDeletion(key);
        assertThat(deletion).isNotNull();
        gc.deferDeletion(deletion, "storage_unavailable");
        assertThat(gcState(key)).isEqualTo("DELETING");
        assertThat(gc.completeDeletion(deletion)).isTrue();
        assertThat(gcState(key)).isEqualTo("DELETED");
        assertOrdered("media_gc_object", "object_key", key);
    }

    @Test
    void manifestPinResetsAnAheadGcObjectThroughTheDatabaseTrigger() {
        UUID owner = UUID.randomUUID();
        UUID deck = UUID.fromString(decks.create(owner, new DeckCommand(UUID.randomUUID(), "Deck", "Description"))
                .acknowledgement().path("deck").path("deckId").stringValue(null));
        UUID revision = jdbc.sql("SELECT revision_id FROM app_learning.deck_revision WHERE deck_id=:deck "
                + "ORDER BY sequence DESC LIMIT 1").param("deck", deck).query(UUID.class).single();
        UUID blob = UUID.randomUUID();
        String key = "verified/" + blob;
        jdbc.sql("INSERT INTO app_learning.media_blob(blob_id,sha256,byte_length,mime_type,object_key,verified_at) "
                        + "VALUES (:blob,:hash,10,'image/png',:key,CURRENT_TIMESTAMP)")
                .param("blob", blob).param("hash", sha256Of(blob)).param("key", key).update();
        aheadGcObject(key, "FIRST");
        UUID manifest = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_learning.media_manifest(manifest_id,deck_id,deck_revision_id,owner_id,version,"
                        + "content_sha256,etag,document_json,created_at,expires_at) VALUES (:id,:deck,:revision,:owner,1,"
                        + ":sha,:etag,'{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + interval '1 day')")
                .param("id", manifest).param("deck", deck).param("revision", revision).param("owner", owner)
                .param("sha", FINGERPRINT).param("etag", "x".repeat(66)).update();

        jdbc.sql("INSERT INTO app_learning.media_manifest_blob_ref(manifest_id,blob_id) VALUES (:manifest,:blob)")
                .param("manifest", manifest).param("blob", blob).update();

        assertThat(gcState(key)).isEqualTo("TRACKED");
        assertOrdered("media_gc_object", "object_key", key);
    }

    private UUID aheadAsset(UUID owner, String state) {
        UUID asset = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_learning.media_asset(asset_id,owner_id,upload_intent_id,origin,state,created_at,"
                        + "updated_at) VALUES (:asset,:owner,:intent,'upload',:state," + AHEAD + "," + AHEAD + ")")
                .param("asset", asset).param("owner", owner).param("intent", UUID.randomUUID())
                .param("state", state).update();
        return asset;
    }

    private UUID aheadSession(UUID asset, UUID owner, String state, String method, UUID finalizeCommand) {
        UUID session = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_learning.media_upload_session(session_id,asset_id,owner_id,generation,kind,"
                        + "declared_mime,declared_length,request_fingerprint,method,staging_key,frozen_key,state,"
                        + "finalize_command_id,issued_until,expires_at,created_at,updated_at) VALUES (:session,:asset,"
                        + ":owner,0,'image','image/png',10,:fingerprint,:method,:staging,:frozen,:state,:finalize,"
                        + AHEAD + "," + AHEAD + " + interval '1 hour'," + AHEAD + "," + AHEAD + ")")
                .param("session", session).param("asset", asset).param("owner", owner)
                .param("fingerprint", FINGERPRINT).param("method", method).param("staging", "staging/" + session)
                .param("frozen", "quarantine/" + session).param("state", state)
                .param("finalize", finalizeCommand, java.sql.Types.OTHER).update();
        return session;
    }

    private void aheadGcObject(String key, String state) {
        jdbc.sql("INSERT INTO app_learning.media_gc_object(object_key,origin,state,first_scan_at,first_scan_epoch,"
                        + "created_at,updated_at) VALUES (:key,'catalog',:state,"
                        + "CASE WHEN :state = 'TRACKED' THEN NULL ELSE CURRENT_TIMESTAMP END,"
                        + "CASE WHEN :state = 'TRACKED' THEN NULL ELSE 0 END," + AHEAD + "," + AHEAD + ")")
                .param("key", key).param("state", state).update();
    }

    private String assetState(UUID asset) {
        return jdbc.sql("SELECT state FROM app_learning.media_asset WHERE asset_id=:asset")
                .param("asset", asset).query(String.class).single();
    }

    private String gcState(String key) {
        return jdbc.sql("SELECT state FROM app_learning.media_gc_object WHERE object_key=:key")
                .param("key", key).query(String.class).single();
    }

    /** The CHECK already rejects a violation; the assertion also proves the row really was ahead and kept so. */
    private void assertOrdered(String table, String idColumn, Object id) {
        assertThat(jdbc.sql("SELECT updated_at >= created_at AND created_at > statement_timestamp() "
                        + "FROM app_learning." + table + " WHERE " + idColumn + "=:id")
                .param("id", id).query(Boolean.class).single()).isTrue();
    }

    private static byte[] sha256Of(UUID seed) {
        byte[] hash = new byte[32];
        java.nio.ByteBuffer.wrap(hash).putLong(seed.getMostSignificantBits()).putLong(seed.getLeastSignificantBits());
        return hash;
    }
}
