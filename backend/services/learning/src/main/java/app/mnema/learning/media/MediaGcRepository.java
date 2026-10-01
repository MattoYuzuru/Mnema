package app.mnema.learning.media;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Durable two-scan ledger. All S3 I/O is performed by the caller outside these transactions. */
@Repository
class MediaGcRepository {
    private final JdbcClient jdbc;
    private final MediaGcSettings settings;

    MediaGcRepository(JdbcClient jdbc, MediaGcSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    /** The intent precedes a derived PUT, so a crash before catalog publication leaves a GC root. */
    @Transactional
    void recordDerivedIntent(MediaProcessingRepository.Claim claim, String key) {
        boolean current = jdbc.sql("SELECT 1 FROM app_learning.media_upload_session s "
                        + "JOIN app_learning.media_asset a ON a.asset_id=s.asset_id "
                        + "WHERE s.session_id=:session AND s.asset_id=:asset AND s.generation=:generation "
                        + "AND s.processing_token=:token AND s.lease_until>CURRENT_TIMESTAMP "
                        + "AND a.generation=s.generation AND a.state='PROCESSING' FOR SHARE OF s,a")
                .param("session", claim.sessionId()).param("asset", claim.assetId())
                .param("generation", claim.generation()).param("token", claim.token())
                .query(Integer.class).optional().isPresent();
        if (!current) throw new MediaStorageUnavailableException();
        jdbc.sql("INSERT INTO app_learning.media_gc_object(object_key,origin,asset_id,asset_generation,"
                        + "processing_token,created_at,state,updated_at) "
                        + "VALUES (:key,'derived',:asset,:generation,:token,CURRENT_TIMESTAMP,'TRACKED',CURRENT_TIMESTAMP) "
                        + "ON CONFLICT (object_key) DO NOTHING")
                .param("key", key).param("asset", claim.assetId())
                .param("generation", claim.generation()).param("token", claim.token()).update();
    }

    /** Discover older catalog rows and every frozen source, including rejected sources. */
    @Transactional
    int discover(int limit) {
        int catalog = jdbc.sql("INSERT INTO app_learning.media_gc_object(object_key,origin,created_at,state,updated_at) "
                        + "SELECT b.object_key,'catalog',CURRENT_TIMESTAMP,'TRACKED',CURRENT_TIMESTAMP "
                        + "FROM app_learning.media_blob b WHERE NOT EXISTS "
                        + "(SELECT 1 FROM app_learning.media_gc_object o WHERE o.object_key=b.object_key) "
                        + "ORDER BY b.verified_at,b.blob_id LIMIT :limit ON CONFLICT (object_key) DO NOTHING")
                .param("limit", limit).update();
        int sources = jdbc.sql("INSERT INTO app_learning.media_gc_object(object_key,origin,asset_id,"
                        + "asset_generation,created_at,state,updated_at) "
                        + "SELECT CASE WHEN s.method='SINGLE' THEN s.frozen_key ELSE s.staging_key END,"
                        + "'source',s.asset_id,s.generation,CURRENT_TIMESTAMP,'TRACKED',CURRENT_TIMESTAMP "
                        + "FROM app_learning.media_upload_session s WHERE s.state='SEALED' "
                        + "AND NOT EXISTS (SELECT 1 FROM app_learning.media_gc_object o WHERE o.object_key="
                        + "CASE WHEN s.method='SINGLE' THEN s.frozen_key ELSE s.staging_key END) "
                        + "ORDER BY s.updated_at,s.session_id LIMIT :limit ON CONFLICT (object_key) DO NOTHING")
                .param("limit", limit).update();
        return catalog + sources;
    }

    /** One bounded keyset page is one pass over all live roots for each selected key. */
    @Transactional
    int scan(int limit) {
        var cursor = jdbc.sql("SELECT last_key,epoch FROM app_learning.media_gc_scan_cursor "
                        + "WHERE singleton=TRUE FOR UPDATE")
                .query((row, ignored) -> new Cursor(row.getString("last_key"), row.getLong("epoch"))).single();
        List<ScanRow> rows = jdbc.sql("SELECT object_key,state,first_scan_epoch FROM app_learning.media_gc_object "
                        + "WHERE object_key>:last AND state IN ('TRACKED','FIRST','SECOND') "
                        + "ORDER BY object_key LIMIT :limit FOR UPDATE SKIP LOCKED")
                .param("last", cursor.lastKey()).param("limit", limit)
                .query((row, ignored) -> new ScanRow(row.getString("object_key"), row.getString("state"),
                        (Long) row.getObject("first_scan_epoch"))).list();
        if (rows.isEmpty()) {
            jdbc.sql("UPDATE app_learning.media_gc_scan_cursor SET last_key='',epoch=epoch+1,"
                            + "updated_at=CURRENT_TIMESTAMP WHERE singleton=TRUE").update();
            return 0;
        }
        for (var row : rows) {
            mark(row.key(), row.state(), row.firstEpoch(), cursor.epoch());
        }
        jdbc.sql("UPDATE app_learning.media_gc_scan_cursor SET last_key=:key,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE singleton=TRUE")
                .param("key", rows.getLast().key()).update();
        return rows.size();
    }

    /** A single candidate receives a complete root check; exposed for deterministic race tests. */
    @Transactional
    void scanKey(String key, long epoch) {
        var row = jdbc.sql("SELECT object_key,state,first_scan_epoch FROM app_learning.media_gc_object "
                        + "WHERE object_key=:key AND state IN ('TRACKED','FIRST','SECOND') FOR UPDATE")
                .param("key", key).query((result, ignored) -> new ScanRow(result.getString("object_key"),
                        result.getString("state"), (Long) result.getObject("first_scan_epoch")))
                .optional().orElse(null);
        if (row != null) mark(row.key(), row.state(), row.firstEpoch(), epoch);
    }

    private void mark(String key, String state, Long firstEpoch, long epoch) {
        if (held(key)) {
            if (!state.equals("TRACKED")) reset(key);
        } else if (state.equals("TRACKED")) {
            jdbc.sql("UPDATE app_learning.media_gc_object SET state='FIRST',"
                            + "first_scan_at=CURRENT_TIMESTAMP,first_scan_epoch=:epoch,"
                            + "updated_at=CURRENT_TIMESTAMP WHERE object_key=:key")
                    .param("epoch", epoch).param("key", key).update();
        } else if (state.equals("FIRST") && firstEpoch < epoch) {
            jdbc.sql("UPDATE app_learning.media_gc_object SET state='SECOND',"
                            + "second_scan_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP "
                            + "WHERE object_key=:key AND first_scan_at<=CURRENT_TIMESTAMP "
                            + "- (:gap * interval '1 second')")
                    .param("key", key).param("gap", settings.scanGap.toSeconds()).update();
        }
    }

    /** Claim one eligible key after locking its blob, then checking every hold again. */
    @Transactional
    Deletion claimDeletion() {
        String key = jdbc.sql("SELECT object_key FROM app_learning.media_gc_object WHERE "
                        + "(state='SECOND' AND first_scan_at<=CURRENT_TIMESTAMP "
                        + "- (:grace * interval '1 second')) OR "
                        + "(state='DELETING' AND lease_until<CURRENT_TIMESTAMP "
                        + "AND (next_attempt_at IS NULL OR next_attempt_at<=CURRENT_TIMESTAMP)) "
                        + "ORDER BY first_scan_at,object_key LIMIT 1")
                .param("grace", settings.grace.toSeconds()).query(String.class).optional().orElse(null);
        if (key == null) return null;
        return claimDeletion(key);
    }

    /** Check one known candidate with the same fence as the scheduled sweep. */
    @Transactional
    Deletion claimDeletion(String key) {
        lockBlob(key);
        ObjectState state = stateForUpdate(key);
        if (state == null || !(state.state().equals("SECOND") && state.graceElapsed()
                || state.state().equals("DELETING") && state.leaseExpired() && state.retryDue())) return null;
        if (held(key)) {
            if (state.state().equals("SECOND")) reset(key);
            else throw new IllegalStateException("A deleting media object acquired a new hold");
            return null;
        }
        UUID token = UUID.randomUUID();
        jdbc.sql("UPDATE app_learning.media_gc_object SET state='DELETING',delete_token=:token,"
                        + "lease_until=CURRENT_TIMESTAMP + (:lease * interval '1 second'),"
                        + "next_attempt_at=NULL,delete_attempts=delete_attempts+1,"
                        + "updated_at=CURRENT_TIMESTAMP WHERE object_key=:key")
                .param("token", token).param("lease", settings.deleteLease.toSeconds())
                .param("key", key).update();
        return new Deletion(key, token);
    }

    /** S3 DELETE succeeded; sever only dead references, then retain the ledger receipt. */
    @Transactional
    boolean completeDeletion(Deletion deletion) {
        UUID blob = lockBlob(deletion.key());
        ObjectState state = stateForUpdate(deletion.key());
        if (state == null || !state.state().equals("DELETING") || !deletion.token().equals(state.token()))
            return false;
        if (held(deletion.key())) throw new IllegalStateException("A deleting media object acquired a new hold");
        if (blob != null) {
            jdbc.sql("DELETE FROM app_learning.media_manifest_blob_ref r USING app_learning.media_manifest m "
                            + "WHERE r.manifest_id=m.manifest_id AND r.blob_id=:blob "
                            + "AND m.expires_at<=CURRENT_TIMESTAMP")
                    .param("blob", blob).update();
            jdbc.sql("DELETE FROM app_learning.media_variant v USING app_learning.media_asset a "
                            + "WHERE v.asset_id=a.asset_id AND v.blob_id=:blob AND a.state='DELETED'")
                    .param("blob", blob).update();
            jdbc.sql("UPDATE app_learning.media_asset SET source_blob_id=NULL,updated_at=CURRENT_TIMESTAMP "
                            + "WHERE source_blob_id=:blob AND state='DELETED'")
                    .param("blob", blob).update();
            jdbc.sql("DELETE FROM app_learning.media_blob WHERE blob_id=:blob")
                    .param("blob", blob).update();
        }
        jdbc.sql("UPDATE app_learning.media_gc_object SET state='DELETED',delete_token=NULL,lease_until=NULL,"
                        + "next_attempt_at=NULL,deleted_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE object_key=:key AND delete_token=:token")
                .param("key", deletion.key()).param("token", deletion.token()).update();
        return true;
    }

    /** Keep the delete fence in place on uncertain S3 failure; retry DELETE idempotently. */
    @Transactional
    void deferDeletion(Deletion deletion, String code) {
        jdbc.sql("UPDATE app_learning.media_gc_object SET lease_until=CURRENT_TIMESTAMP "
                        + "+ (:retry * interval '1 second'),next_attempt_at=CURRENT_TIMESTAMP "
                        + "+ (:retry * interval '1 second'),last_error_code=:code,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE object_key=:key AND delete_token=:token AND state='DELETING'")
                .param("retry", settings.retryDelay.toSeconds()).param("code", code)
                .param("key", deletion.key()).param("token", deletion.token()).update();
    }

    private UUID lockBlob(String key) {
        return jdbc.sql("SELECT blob_id FROM app_learning.media_blob WHERE object_key=:key FOR UPDATE")
                .param("key", key).query(UUID.class).optional().orElse(null);
    }

    private ObjectState stateForUpdate(String key) {
        return jdbc.sql("SELECT state,delete_token,lease_until<CURRENT_TIMESTAMP AS expired,"
                        + "(next_attempt_at IS NULL OR next_attempt_at<=CURRENT_TIMESTAMP) AS retry_due,"
                        + "first_scan_at<=CURRENT_TIMESTAMP - (:grace * interval '1 second') AS grace_elapsed "
                        + "FROM app_learning.media_gc_object WHERE object_key=:key FOR UPDATE")
                .param("key", key).param("grace", settings.grace.toSeconds())
                .query((row, ignored) -> new ObjectState(row.getString("state"),
                        (UUID) row.getObject("delete_token"), row.getBoolean("expired"),
                        row.getBoolean("grace_elapsed"), row.getBoolean("retry_due")))
                .optional().orElse(null);
    }

    private boolean held(String key) {
        UUID blob = jdbc.sql("SELECT blob_id FROM app_learning.media_blob WHERE object_key=:key")
                .param("key", key).query(UUID.class).optional().orElse(null);
        if (blob != null && blobHeld(blob)) return true;
        return jdbc.sql("SELECT 1 FROM app_learning.media_gc_object o "
                        + "JOIN app_learning.media_upload_session s ON s.asset_id=o.asset_id "
                        + "AND s.generation=o.asset_generation "
                        + "JOIN app_learning.media_asset a ON a.asset_id=s.asset_id "
                        + "WHERE o.object_key=:key AND ((o.origin='derived' "
                        + "AND s.processing_token=o.processing_token AND s.lease_until>CURRENT_TIMESTAMP) "
                        + "OR (o.origin='source' AND s.state='SEALED' AND a.generation=s.generation "
                        + "AND a.state IN ('VERIFYING','PROCESSING','FAILED_RETRYABLE'))) LIMIT 1")
                .param("key", key).query(Integer.class).optional().isPresent();
    }

    private boolean blobHeld(UUID blob) {
        return jdbc.sql("SELECT 1 FROM app_learning.media_asset a WHERE "
                        + "(a.source_blob_id=:blob OR EXISTS (SELECT 1 FROM app_learning.media_variant v "
                        + "WHERE v.asset_id=a.asset_id AND v.blob_id=:blob)) AND "
                        + "(a.state<>'DELETED' OR a.owner_hold_until>CURRENT_TIMESTAMP "
                        + "OR EXISTS (SELECT 1 FROM app_learning.content_media_ref r WHERE r.asset_id=a.asset_id) "
                        + "OR EXISTS (SELECT 1 FROM app_learning.exercise_media_ref r WHERE r.asset_id=a.asset_id) "
                        + "OR EXISTS (SELECT 1 FROM app_learning.draft_media_ref r "
                        + "JOIN app_learning.editing_draft d ON d.draft_id=r.draft_id "
                        + "WHERE r.asset_id=a.asset_id AND d.expires_at>CURRENT_TIMESTAMP)) "
                        + "UNION ALL SELECT 1 FROM app_learning.media_manifest_blob_ref r "
                        + "JOIN app_learning.media_manifest m ON m.manifest_id=r.manifest_id "
                        + "WHERE r.blob_id=:blob AND m.expires_at>CURRENT_TIMESTAMP LIMIT 1")
                .param("blob", blob).query(Integer.class).optional().isPresent();
    }

    private void reset(String key) {
        jdbc.sql("UPDATE app_learning.media_gc_object SET state='TRACKED',first_scan_at=NULL,"
                        + "first_scan_epoch=NULL,second_scan_at=NULL,next_attempt_at=NULL,"
                        + "updated_at=CURRENT_TIMESTAMP WHERE object_key=:key AND state IN ('FIRST','SECOND')")
                .param("key", key).update();
    }

    record Deletion(String key, UUID token) { }
    private record Cursor(String lastKey, long epoch) { }
    private record ScanRow(String key, String state, Long firstEpoch) { }
    private record ObjectState(String state, UUID token, boolean leaseExpired,
                               boolean graceElapsed, boolean retryDue) { }
}
