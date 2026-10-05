package app.mnema.learning.media;

import app.mnema.learning.notification.NotificationKind;
import app.mnema.learning.notification.NotificationPublisher;
import app.mnema.learning.notification.NotificationRoute;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Durable claim, fencing, and one-transaction publication of verified media. */
@Repository
class MediaProcessingRepository {
    /** Stable reasons of MEDIA_PROCESSING_FAILED (contracts/notifications). */
    private static final String VERIFICATION_REJECTED = "VERIFICATION_REJECTED";
    private static final String PROCESSING_FAILED = "PROCESSING_FAILED";

    private final JdbcClient jdbc;
    private final MediaCatalog catalog;
    private final MediaProcessingSettings settings;
    private final NotificationPublisher notifications;

    MediaProcessingRepository(JdbcClient jdbc, MediaCatalog catalog, MediaProcessingSettings settings,
                              NotificationPublisher notifications) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.settings = settings;
        this.notifications = notifications;
    }

    @Transactional
    Claim claim() { return claim(null); }

    @Transactional
    Claim claim(UUID onlyAsset) {
        var statement = jdbc.sql("SELECT s.session_id,s.asset_id,s.generation,s.kind,s.declared_length,"
                        + "s.processing_attempts FROM app_learning.media_upload_session s "
                        + "JOIN app_learning.media_asset a ON a.asset_id=s.asset_id "
                        + "AND a.owner_id=s.owner_id AND a.generation=s.generation "
                        + "WHERE s.state='SEALED' AND a.state IN ('VERIFYING','PROCESSING') "
                        + (onlyAsset == null ? "" : "AND s.asset_id=:onlyAsset ")
                        + "AND (s.lease_until IS NULL OR s.lease_until<CURRENT_TIMESTAMP) "
                        + "AND (s.processing_next_attempt_at IS NULL OR s.processing_next_attempt_at<=CURRENT_TIMESTAMP) "
                        + "ORDER BY CASE s.kind WHEN 'image' THEN 0 WHEN 'audio' THEN 1 ELSE 2 END,"
                        + "s.updated_at,s.session_id LIMIT 1 FOR UPDATE OF s,a SKIP LOCKED");
        if (onlyAsset != null) statement = statement.param("onlyAsset", onlyAsset);
        var candidate = statement.query((row, ignored) -> new Candidate((UUID) row.getObject("session_id"),
                        (UUID) row.getObject("asset_id"), row.getLong("generation"),
                        row.getString("kind"), row.getLong("declared_length"),
                        row.getInt("processing_attempts")))
                .optional().orElse(null);
        if (candidate == null) return null;
        if (candidate.attempts() >= settings.maxAttempts) {
            jdbc.sql("UPDATE app_learning.media_asset SET state='FAILED_RETRYABLE',updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) "
                            + "WHERE asset_id=:asset AND generation=:generation AND state IN ('VERIFYING','PROCESSING')")
                    .param("asset", candidate.assetId()).param("generation", candidate.generation()).update();
            jdbc.sql("UPDATE app_learning.media_upload_session SET processing_token=NULL,lease_until=NULL,"
                            + "processing_next_attempt_at=NULL,processing_error_code='processing_interrupted',"
                            + "updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) WHERE session_id=:session")
                    .param("session", candidate.sessionId()).update();
            notifyFailed(candidate.assetId(), candidate.kind(), PROCESSING_FAILED);
            return null;
        }
        UUID token = UUID.randomUUID();
        jdbc.sql("UPDATE app_learning.media_upload_session SET processing_token=:token,"
                        + "processing_attempts=processing_attempts+1,"
                        + "processing_next_attempt_at=NULL,processing_error_code=NULL,"
                        + "lease_until=CURRENT_TIMESTAMP + (:lease * interval '1 second'),"
                        + "updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) WHERE session_id=:session")
                .param("token", token).param("lease", settings.lease.toSeconds())
                .param("session", candidate.sessionId()).update();
        jdbc.sql("UPDATE app_learning.media_asset SET state='PROCESSING',updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) "
                        + "WHERE asset_id=:asset AND generation=:generation AND state IN ('VERIFYING','PROCESSING')")
                .param("asset", candidate.assetId()).param("generation", candidate.generation()).update();
        return new Claim(candidate.sessionId(), candidate.assetId(), candidate.generation(),
                candidate.kind(), candidate.length(), token, candidate.attempts() + 1);
    }

    @Transactional
    boolean heartbeat(Claim claim) {
        return jdbc.sql("UPDATE app_learning.media_upload_session s SET "
                        + "lease_until=CURRENT_TIMESTAMP + (:lease * interval '1 second'),"
                        + "updated_at=GREATEST(CURRENT_TIMESTAMP,s.updated_at) FROM app_learning.media_asset a "
                        + "WHERE s.session_id=:session AND s.processing_token=:token "
                        + "AND s.lease_until>CURRENT_TIMESTAMP AND s.state='SEALED' "
                        + "AND a.asset_id=s.asset_id AND a.generation=s.generation "
                        + "AND a.state='PROCESSING'")
                .param("lease", settings.lease.toSeconds()).param("session", claim.sessionId())
                .param("token", claim.token()).update() == 1;
    }

    @Transactional
    boolean complete(Claim claim, Blob source, List<Variant> variants) {
        if (!current(claim)) return false;
        UUID sourceBlob = blob(source);
        for (Variant variant : variants) {
            UUID variantBlob = blob(variant.blob());
            jdbc.sql("INSERT INTO app_learning.media_variant(variant_id,asset_id,asset_generation,"
                            + "purpose,profile,blob_id,width,height,duration_ms,created_at) VALUES "
                            + "(:id,:asset,:generation,:purpose,:profile,:blob,:width,:height,:duration,CURRENT_TIMESTAMP)")
                    .param("id", UUID.randomUUID()).param("asset", claim.assetId())
                    .param("generation", claim.generation()).param("purpose", variant.purpose())
                    .param("profile", variant.profile()).param("blob", variantBlob)
                    .param("width", variant.width()).param("height", variant.height())
                    .param("duration", variant.durationMs()).update();
        }
        if (!catalog.ready(claim.assetId(), claim.generation(), sourceBlob)) {
            throw new IllegalStateException("Media asset changed during publication");
        }
        jdbc.sql("UPDATE app_learning.media_upload_session SET processing_token=NULL,lease_until=NULL,"
                        + "processing_next_attempt_at=NULL,processing_error_code=NULL,updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) "
                        + "WHERE session_id=:session AND processing_token=:token")
                .param("session", claim.sessionId()).param("token", claim.token()).update();
        return true;
    }

    @Transactional
    boolean rejected(Claim claim, String code) {
        if (!current(claim)) return false;
        jdbc.sql("UPDATE app_learning.media_asset SET state='REJECTED',updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) "
                        + "WHERE asset_id=:asset AND generation=:generation AND state='PROCESSING'")
                .param("asset", claim.assetId()).param("generation", claim.generation()).update();
        release(claim, code, null);
        notifyFailed(claim.assetId(), claim.kind(), VERIFICATION_REJECTED);
        return true;
    }

    @Transactional
    boolean retryable(Claim claim, String code) {
        if (!current(claim)) return false;
        if (claim.attempt() >= settings.maxAttempts) {
            jdbc.sql("UPDATE app_learning.media_asset SET state='FAILED_RETRYABLE',updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) "
                            + "WHERE asset_id=:asset AND generation=:generation AND state='PROCESSING'")
                    .param("asset", claim.assetId()).param("generation", claim.generation()).update();
            release(claim, code, null);
            notifyFailed(claim.assetId(), claim.kind(), PROCESSING_FAILED);
        } else {
            release(claim, code, settings.delay(claim.attempt()));
        }
        return true;
    }

    /** Whether the asset is one the server itself staged (origin {@code generated}); only those may carry a WAV source. */
    @Transactional(readOnly = true)
    boolean generated(UUID asset) {
        return jdbc.sql("SELECT origin FROM app_learning.media_asset WHERE asset_id=:asset").param("asset", asset).query(String.class).optional()
                .filter("generated"::equals).isPresent();
    }

    /** Owner retries the preserved sealed bytes after transient failures; no new upload is needed. */
    @Transactional
    void retryPreserved(UUID owner, UUID asset, long generation) {
        var session = jdbc.sql("SELECT s.session_id FROM app_learning.media_upload_session s "
                        + "JOIN app_learning.media_asset a ON a.asset_id=s.asset_id "
                        + "AND a.owner_id=s.owner_id AND a.generation=s.generation "
                        + "WHERE a.asset_id=:asset AND a.owner_id=:owner AND a.generation=:generation "
                        + "AND a.state='FAILED_RETRYABLE' AND s.state='SEALED' FOR UPDATE OF s,a")
                .param("owner", owner).param("asset", asset).param("generation", generation)
                .query(UUID.class).optional().orElseThrow(ResourceNotFoundException::new);
        jdbc.sql("UPDATE app_learning.media_upload_session SET processing_attempts=0,"
                        + "processing_token=NULL,lease_until=NULL,processing_next_attempt_at=NULL,"
                        + "processing_error_code=NULL,updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) WHERE session_id=:session")
                .param("session", session).update();
        jdbc.sql("UPDATE app_learning.media_asset SET state='VERIFYING',updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) "
                        + "WHERE asset_id=:asset AND generation=:generation")
                .param("asset", asset).param("generation", generation).update();
    }

    /**
     * Tells the owner that an asset reached a terminal failure, in the transaction of the state change that made it
     * terminal (so the notification exists exactly when the failure is committed). The pipeline does not know a deck or
     * a generation session, hence those params are null and the route NONE; a repeat for the same asset is a no-op.
     */
    private void notifyFailed(UUID assetId, String kind, String reason) {
        UUID owner = jdbc.sql("SELECT owner_id FROM app_learning.media_asset WHERE asset_id=:asset")
                .param("asset", assetId).query(UUID.class).single();
        var params = new HashMap<String, Object>();
        params.put("assetId", assetId);
        params.put("mediaKind", kind.toUpperCase(Locale.ROOT));
        params.put("deckId", null);
        params.put("sessionId", null);
        params.put("artifactId", null);
        params.put("slotKey", null);
        params.put("reason", reason);
        notifications.publish(owner, NotificationKind.MEDIA_PROCESSING_FAILED, "media:" + assetId + ":failed", params,
                NotificationRoute.NONE);
    }

    private boolean current(Claim claim) {
        return jdbc.sql("SELECT 1 FROM app_learning.media_upload_session s "
                        + "JOIN app_learning.media_asset a ON a.asset_id=s.asset_id "
                        + "AND a.owner_id=s.owner_id AND a.generation=s.generation "
                        + "WHERE s.session_id=:session AND s.processing_token=:token "
                        + "AND s.lease_until>CURRENT_TIMESTAMP AND s.state='SEALED' "
                        + "AND a.asset_id=:asset AND a.generation=:generation AND a.state='PROCESSING' "
                        + "FOR UPDATE OF s,a")
                .param("session", claim.sessionId()).param("token", claim.token())
                .param("asset", claim.assetId()).param("generation", claim.generation())
                .query(Integer.class).optional().isPresent();
    }

    private void release(Claim claim, String code, Duration delay) {
        jdbc.sql("UPDATE app_learning.media_upload_session SET processing_token=NULL,lease_until=NULL,"
                        + "processing_next_attempt_at=CASE WHEN CAST(:delay AS bigint) IS NULL THEN NULL "
                        + "ELSE CURRENT_TIMESTAMP + (CAST(:delay AS bigint) * interval '1 second') END,"
                        + "processing_error_code=:code,updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) "
                        + "WHERE session_id=:session AND processing_token=:token")
                .param("delay", delay == null ? null : delay.toSeconds())
                .param("code", code).param("session", claim.sessionId())
                .param("token", claim.token()).update();
    }

    private UUID blob(Blob value) {
        byte[] digest = HexFormat.of().parseHex(value.sha256());
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_learning.media_blob(blob_id,sha256,byte_length,mime_type,object_key,verified_at) "
                        + "VALUES (:id,:sha,:length,:mime,:key,CURRENT_TIMESTAMP) "
                        + "ON CONFLICT (sha256,byte_length) DO NOTHING")
                .param("id", id).param("sha", digest).param("length", value.byteLength())
                .param("mime", value.mimeType()).param("key", value.objectKey()).update();
        var existing = jdbc.sql("SELECT blob_id,object_key FROM app_learning.media_blob "
                        + "WHERE sha256=:sha AND byte_length=:length")
                .param("sha", digest).param("length", value.byteLength())
                .query((row, ignored) -> new ExistingBlob((UUID) row.getObject("blob_id"),
                        row.getString("object_key"))).single();
        jdbc.sql("SELECT blob_id FROM app_learning.media_blob WHERE blob_id=:id FOR UPDATE")
                .param("id", existing.id()).query(UUID.class).single();
        var state = jdbc.sql("SELECT state FROM app_learning.media_gc_object WHERE object_key=:key FOR UPDATE")
                .param("key", existing.objectKey()).query(String.class).optional().orElse(null);
        if ("DELETING".equals(state) || "DELETED".equals(state)) throw new MediaStorageUnavailableException();
        if ("FIRST".equals(state) || "SECOND".equals(state)) {
            jdbc.sql("UPDATE app_learning.media_gc_object SET state='TRACKED',first_scan_at=NULL,"
                            + "first_scan_epoch=NULL,second_scan_at=NULL,next_attempt_at=NULL,"
                            + "updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) WHERE object_key=:key")
                    .param("key", existing.objectKey()).update();
        }
        return existing.id();
    }

    record Claim(UUID sessionId, UUID assetId, long generation, String kind, long declaredLength,
                 UUID token, int attempt) { }
    record Blob(String sha256, long byteLength, String mimeType, String objectKey) { }
    record Variant(String purpose, String profile, Blob blob, Integer width, Integer height,
                   Long durationMs) { }
    private record Candidate(UUID sessionId, UUID assetId, long generation, String kind,
                             long length, int attempts) { }
    private record ExistingBlob(UUID id, String objectKey) { }
}
