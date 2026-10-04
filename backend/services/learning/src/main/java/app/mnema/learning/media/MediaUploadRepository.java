package app.mnema.learning.media;

import app.mnema.learning.platform.api.ResourceLimitExceededException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Repository
class MediaUploadRepository {
    private final JdbcClient jdbc;
    private final MediaCatalog catalog;
    private final MediaUploadSettings settings;

    MediaUploadRepository(JdbcClient jdbc, MediaCatalog catalog, MediaUploadSettings settings) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.settings = settings;
    }

    @Transactional
    Session reserve(UUID owner, UUID intent, MediaCatalog.Origin origin, String kind, String mime,
                    long length, byte[] fingerprint) {
        ownerLock(owner);
        UUID asset = catalog.reserve(owner, intent, origin);
        Asset current = asset(owner, asset, true);
        Session existing = byAssetGeneration(asset, 0);
        if (existing != null) {
            if (!Arrays.equals(existing.fingerprint(), fingerprint)) throw new IdempotencyConflictException();
            return existing;
        }
        if (current.generation() != 0 || !current.state().equals("PENDING_UPLOAD")) {
            throw new MediaUploadConflictException();
        }
        quota(owner, length);
        return insert(owner, asset, 0, kind, mime, length, fingerprint, null);
    }

    /**
     * The asset a server staged itself ({@code origin='generated'}) under an identity it chose, and its one upload session; a repeat with the same
     * bytes returns the same session. The transfer then follows the ordinary path (single PUT, finalize, seal, verification), but the owner's upload
     * quota is not charged: the owner did not start it.
     */
    @Transactional
    Session reserveGenerated(UUID owner, UUID asset, String kind, String mime, long length, byte[] fingerprint) {
        ownerLock(owner);
        jdbc.sql("INSERT INTO app_learning.media_asset(asset_id,owner_id,upload_intent_id,origin,created_at,updated_at) "
                        + "VALUES (:asset,:owner,:asset,'generated',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) ON CONFLICT (asset_id) DO NOTHING")
                .param("asset", asset).param("owner", owner).update();
        Asset current = asset(owner, asset, true);
        String origin = jdbc.sql("SELECT origin FROM app_learning.media_asset WHERE asset_id=:asset").param("asset", asset)
                .query(String.class).single();
        if (!origin.equals("generated")) throw new MediaUploadConflictException();
        Session existing = byAssetGeneration(asset, 0);
        if (existing != null) {
            if (!Arrays.equals(existing.fingerprint(), fingerprint)) throw new IdempotencyConflictException();
            return existing;
        }
        if (current.generation() != 0 || !current.state().equals("PENDING_UPLOAD")) throw new MediaUploadConflictException();
        return insert(owner, asset, 0, kind, mime, length, fingerprint, null);
    }

    /** The state of an asset of {@code owner}, or empty when there is none (or it is another owner's). */
    @Transactional(readOnly = true)
    java.util.Optional<String> assetState(UUID owner, UUID asset) {
        return jdbc.sql("SELECT state FROM app_learning.media_asset WHERE asset_id=:asset AND owner_id=:owner")
                .param("asset", asset).param("owner", owner).query(String.class).optional();
    }

    @Transactional
    Session retry(UUID owner, UUID asset, UUID command, String kind, String mime, long length, byte[] fingerprint) {
        ownerLock(owner);
        Asset current = asset(owner, asset, true);
        Session replay = byRetryCommand(asset, command);
        if (replay != null) {
            if (!Arrays.equals(replay.fingerprint(), fingerprint)) throw new IdempotencyConflictException();
            return replay;
        }
        if (!current.state().equals("FAILED_RETRYABLE") && !current.state().equals("REJECTED")) {
            throw new MediaUploadConflictException();
        }
        quota(owner, length);
        int updated = jdbc.sql("UPDATE app_learning.media_asset SET generation=generation+1,state='PENDING_UPLOAD',"
                        + "source_blob_id=NULL,owner_hold_until=NULL,updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) "
                        + "WHERE asset_id=:asset AND owner_id=:owner AND generation=:generation")
                .param("asset", asset).param("owner", owner).param("generation", current.generation()).update();
        if (updated != 1) throw new MediaUploadConflictException();
        return insert(owner, asset, current.generation() + 1, kind, mime, length, fingerprint, command);
    }

    @Transactional(readOnly = true)
    Session own(UUID owner, UUID asset) {
        Asset current = asset(owner, asset, false);
        Session session = byAssetGeneration(asset, current.generation());
        if (session == null) throw new ResourceNotFoundException();
        return session;
    }

    @Transactional(readOnly = true)
    AssetStatus assetStatus(UUID owner, UUID asset) {
        Asset current = asset(owner, asset, false);
        return new AssetStatus(current.generation(), current.state());
    }

    @Transactional(readOnly = true)
    SealedSource sealedSource(UUID asset, long generation) {
        return jdbc.sql("SELECT s.session_id,s.owner_id,s.kind,s.declared_mime,s.declared_length,"
                        + "s.method,s.staging_key,s.frozen_key FROM app_learning.media_upload_session s "
                        + "JOIN app_learning.media_asset a ON a.asset_id=s.asset_id "
                        + "AND a.owner_id=s.owner_id AND a.generation=s.generation "
                        + "WHERE s.asset_id=:asset AND s.generation=:generation AND s.state='SEALED' "
                        + "AND a.state IN ('VERIFYING','PROCESSING','READY')")
                .param("asset", asset).param("generation", generation)
                .query((row, ignored) -> new SealedSource((UUID) row.getObject("session_id"),
                        (UUID) row.getObject("owner_id"), row.getString("kind"), row.getString("declared_mime"),
                        row.getLong("declared_length"), row.getString("method").equals("SINGLE")
                                ? row.getString("frozen_key") : row.getString("staging_key")))
                .optional().orElseThrow(ResourceNotFoundException::new);
    }

    @Transactional
    boolean claimInitiation(UUID sessionId) {
        return jdbc.sql("UPDATE app_learning.media_upload_session SET lease_until=CURRENT_TIMESTAMP + interval '5 minutes',"
                        + "updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) WHERE session_id=:session AND state='INITIATING' "
                        + "AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP)")
                .param("session", sessionId).update() == 1;
    }

    @Transactional
    void releaseInitiation(UUID sessionId) {
        jdbc.sql("UPDATE app_learning.media_upload_session SET lease_until=NULL,updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) "
                        + "WHERE session_id=:session AND state='INITIATING'")
                .param("session", sessionId).update();
    }

    @Transactional
    Session open(UUID sessionId, String storageUploadId) {
        int updated = jdbc.sql("UPDATE app_learning.media_upload_session SET state='OPEN',storage_upload_id=:storage,"
                        + "lease_until=NULL,updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) WHERE session_id=:session AND state='INITIATING'")
                .param("storage", storageUploadId).param("session", sessionId).update();
        if (updated != 1) throw new MediaUploadConflictException();
        return byId(sessionId);
    }

    @Transactional
    Session issue(UUID owner, UUID asset, long generation, Instant signedExpiry) {
        Session session = own(owner, asset);
        if (session.generation() != generation || !session.state().equals("OPEN")
                || !session.expiresAt().isAfter(Instant.now())) throw new MediaUploadConflictException();
        int updated = jdbc.sql("UPDATE app_learning.media_upload_session SET "
                        + "issued_until=GREATEST(issued_until,:signedExpiry),"
                        + "updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) WHERE session_id=:session AND state='OPEN' "
                        + "AND expires_at>CURRENT_TIMESTAMP")
                .param("signedExpiry", java.sql.Timestamp.from(signedExpiry.plusSeconds(1)))
                .param("session", session.sessionId()).update();
        if (updated != 1) throw new MediaUploadConflictException();
        return byId(session.sessionId());
    }

    @Transactional
    Claim claim(UUID owner, UUID asset, long generation, UUID command) {
        Session session = ownForUpdate(owner, asset);
        if (session.generation() != generation) throw new MediaUploadConflictException();
        if (session.finalizeCommandId() != null && !session.finalizeCommandId().equals(command)) {
            throw new IdempotencyConflictException();
        }
        if (session.state().equals("SEALED")) return new Claim(session, false);
        if (!session.state().equals("OPEN") && !session.state().equals("FINALIZING")) {
            throw new MediaUploadConflictException();
        }
        if (session.state().equals("OPEN") && !session.expiresAt().isAfter(Instant.now())) {
            throw new MediaUploadConflictException();
        }
        if (session.state().equals("FINALIZING") && session.leaseUntil() != null
                && session.leaseUntil().isAfter(Instant.now())) return new Claim(session, false);
        jdbc.sql("UPDATE app_learning.media_upload_session SET state='FINALIZING',finalize_command_id=:command,"
                        + "lease_until=CURRENT_TIMESTAMP + (:lease * interval '1 second'),updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) "
                        + "WHERE session_id=:session")
                .param("command", command).param("session", session.sessionId())
                .param("lease", settings.finalizeLease.toSeconds()).update();
        return new Claim(byId(session.sessionId()), true);
    }

    @Transactional
    void releaseIncomplete(UUID sessionId, UUID command) {
        jdbc.sql("UPDATE app_learning.media_upload_session SET state='OPEN',lease_until=NULL,"
                        + "updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) WHERE session_id=:session AND state='FINALIZING' "
                        + "AND finalize_command_id=:command")
                .param("session", sessionId).param("command", command).update();
    }

    @Transactional
    boolean claimCopy(UUID sessionId, UUID command) {
        return jdbc.sql("UPDATE app_learning.media_upload_session SET copy_started_at=CURRENT_TIMESTAMP,"
                        + "updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) WHERE session_id=:session AND state='FINALIZING' "
                        + "AND finalize_command_id=:command AND copy_started_at IS NULL")
                .param("session", sessionId).param("command", command).update() == 1;
    }

    @Transactional
    void rejectedCopyPrecondition(UUID sessionId, UUID command) {
        jdbc.sql("UPDATE app_learning.media_upload_session SET copy_started_at=NULL,state='OPEN',lease_until=NULL,"
                        + "updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) WHERE session_id=:session AND state='FINALIZING' "
                        + "AND finalize_command_id=:command")
                .param("session", sessionId).param("command", command).update();
    }

    @Transactional
    void failUncertainCopy(UUID sessionId, UUID command) {
        Session session = byIdForUpdate(sessionId);
        if (!session.state().equals("FINALIZING") || !command.equals(session.finalizeCommandId())) return;
        jdbc.sql("UPDATE app_learning.media_upload_session SET state='FAILED',lease_until=NULL,"
                        + "updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) WHERE session_id=:session")
                .param("session", sessionId).update();
        jdbc.sql("UPDATE app_learning.media_asset SET state='FAILED_RETRYABLE',updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) "
                        + "WHERE asset_id=:asset AND owner_id=:owner AND generation=:generation "
                        + "AND state='PENDING_UPLOAD'")
                .param("asset", session.assetId()).param("owner", session.ownerId())
                .param("generation", session.generation()).update();
    }

    @Transactional
    void seal(UUID sessionId, UUID command) {
        Session session = byIdForUpdate(sessionId);
        if (session.state().equals("SEALED") && command.equals(session.finalizeCommandId())) return;
        if (!session.state().equals("FINALIZING") || !command.equals(session.finalizeCommandId())) {
            throw new MediaUploadConflictException();
        }
        int updated = jdbc.sql("UPDATE app_learning.media_asset SET state='VERIFYING',updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) "
                        + "WHERE asset_id=:asset AND owner_id=:owner AND generation=:generation "
                        + "AND state='PENDING_UPLOAD'")
                .param("asset", session.assetId()).param("owner", session.ownerId())
                .param("generation", session.generation()).update();
        if (updated != 1) throw new MediaUploadConflictException();
        jdbc.sql("UPDATE app_learning.media_upload_session SET state='SEALED',lease_until=NULL,"
                        + "updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) WHERE session_id=:session")
                .param("session", sessionId).update();
    }

    @Transactional
    Session cancel(UUID owner, UUID asset, long generation) {
        Session session = ownForUpdate(owner, asset);
        if (session.generation() != generation || session.state().equals("SEALED")) {
            throw new MediaUploadConflictException();
        }
        if (!session.state().equals("ABORTING") && !session.state().equals("ABORTED")) {
            jdbc.sql("UPDATE app_learning.media_upload_session SET state='ABORTING',lease_until=NULL,"
                            + "updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) WHERE session_id=:session")
                    .param("session", session.sessionId()).update();
            jdbc.sql("UPDATE app_learning.media_asset SET state='FAILED_RETRYABLE',updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) "
                            + "WHERE asset_id=:asset AND owner_id=:owner AND generation=:generation "
                            + "AND state='PENDING_UPLOAD'")
                    .param("asset", asset).param("owner", owner).param("generation", generation).update();
        }
        return byId(session.sessionId());
    }

    @Transactional
    List<Session> cleanupCandidates(int limit) {
        jdbc.sql("UPDATE app_learning.media_upload_session SET state='EXPIRED',updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) "
                        + "WHERE state='FINALIZING' AND expires_at<CURRENT_TIMESTAMP "
                        + "AND lease_until<CURRENT_TIMESTAMP")
                .update();
        jdbc.sql("UPDATE app_learning.media_upload_session SET state='EXPIRED',updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) "
                        + "WHERE state IN ('INITIATING','OPEN') AND expires_at<CURRENT_TIMESTAMP")
                .update();
        jdbc.sql("UPDATE app_learning.media_asset a SET state='FAILED_RETRYABLE',updated_at=GREATEST(CURRENT_TIMESTAMP,a.updated_at) "
                        + "FROM app_learning.media_upload_session s WHERE s.asset_id=a.asset_id "
                        + "AND s.generation=a.generation AND s.state='EXPIRED' AND a.state='PENDING_UPLOAD'")
                .update();
        return jdbc.sql("SELECT * FROM app_learning.media_upload_session WHERE cleanup_done_at IS NULL "
                        + "AND issued_until + (:grace * interval '1 second')<CURRENT_TIMESTAMP "
                        + "AND (state IN ('ABORTING','EXPIRED','FAILED') "
                        + "OR (state='SEALED' AND method='SINGLE')) ORDER BY updated_at LIMIT :limit")
                .param("limit", limit).param("grace", settings.cleanupGrace.toSeconds())
                .query(MediaUploadRepository::map).list();
    }

    @Transactional
    void cleaned(UUID sessionId) {
        jdbc.sql("UPDATE app_learning.media_upload_session SET cleanup_done_at=CURRENT_TIMESTAMP,"
                        + "state=CASE WHEN state='ABORTING' THEN 'ABORTED' ELSE state END,"
                        + "updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) WHERE session_id=:session AND cleanup_done_at IS NULL")
                .param("session", sessionId).update();
    }

    @Transactional(readOnly = true)
    List<Session> stalledFinalizations(int limit) {
        return jdbc.sql("SELECT * FROM app_learning.media_upload_session WHERE state='FINALIZING' "
                        + "AND lease_until<CURRENT_TIMESTAMP ORDER BY updated_at LIMIT :limit")
                .param("limit", limit).query(MediaUploadRepository::map).list();
    }

    private Session insert(UUID owner, UUID asset, long generation, String kind, String mime,
                           long length, byte[] fingerprint, UUID retryCommand) {
        UUID session = UUID.randomUUID();
        String prefix = "media/staging/" + owner + "/" + asset + "/" + generation + "/" + session;
        boolean multi = settings.multipart(length);
        jdbc.sql("INSERT INTO app_learning.media_upload_session(session_id,asset_id,owner_id,generation,kind,"
                        + "declared_mime,declared_length,request_fingerprint,retry_command_id,method,staging_key,frozen_key,state,"
                        + "issued_until,expires_at,created_at,updated_at) VALUES (:session,:asset,:owner,:generation,"
                        + ":kind,:mime,:length,:fingerprint,:retry,:method,:staging,:frozen,:state,CURRENT_TIMESTAMP,"
                        + "CURRENT_TIMESTAMP + (:ttl * interval '1 second'),CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
                .param("session", session).param("asset", asset).param("owner", owner)
                .param("generation", generation).param("kind", kind).param("mime", mime)
                .param("length", length).param("fingerprint", fingerprint)
                .param("retry", retryCommand)
                .param("method", multi ? "MULTIPART" : "SINGLE")
                .param("staging", prefix).param("frozen", "media/quarantine/" + session)
                .param("state", multi ? "INITIATING" : "OPEN")
                .param("ttl", settings.sessionTtl.toSeconds()).update();
        return byId(session);
    }

    private void quota(UUID owner, long additional) {
        var usage = jdbc.sql("SELECT count(*) AS active,coalesce(sum(declared_length),0) AS bytes "
                        + "FROM app_learning.media_upload_session WHERE owner_id=:owner AND cleanup_done_at IS NULL "
                        + "AND state NOT IN ('SEALED','ABORTED')")
                .param("owner", owner).query((row, ignored) -> new Usage(row.getLong("active"), row.getLong("bytes"))).single();
        if (usage.active() >= settings.maxActiveUploads || additional > settings.maxReservedBytes - usage.bytes()) {
            throw new ResourceLimitExceededException();
        }
    }

    private void ownerLock(UUID owner) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:owner, 783))")
                .param("owner", owner.toString()).query((row, ignored) -> 0).single();
    }

    private Asset asset(UUID owner, UUID asset, boolean lock) {
        String sql = "SELECT generation,state FROM app_learning.media_asset WHERE owner_id=:owner AND asset_id=:asset"
                + (lock ? " FOR UPDATE" : "");
        return jdbc.sql(sql).param("owner", owner).param("asset", asset)
                .query((row, ignored) -> new Asset(row.getLong("generation"), row.getString("state")))
                .optional().orElseThrow(ResourceNotFoundException::new);
    }

    private Session byAssetGeneration(UUID asset, long generation) {
        return jdbc.sql("SELECT * FROM app_learning.media_upload_session WHERE asset_id=:asset AND generation=:generation")
                .param("asset", asset).param("generation", generation)
                .query(MediaUploadRepository::map).optional().orElse(null);
    }

    private Session byRetryCommand(UUID asset, UUID command) {
        return jdbc.sql("SELECT * FROM app_learning.media_upload_session WHERE asset_id=:asset "
                        + "AND retry_command_id=:command")
                .param("asset", asset).param("command", command)
                .query(MediaUploadRepository::map).optional().orElse(null);
    }

    private Session ownForUpdate(UUID owner, UUID asset) {
        Asset current = asset(owner, asset, true);
        return jdbc.sql("SELECT * FROM app_learning.media_upload_session WHERE asset_id=:asset "
                        + "AND owner_id=:owner AND generation=:generation FOR UPDATE")
                .param("asset", asset).param("owner", owner).param("generation", current.generation())
                .query(MediaUploadRepository::map).optional().orElseThrow(ResourceNotFoundException::new);
    }

    private Session byId(UUID id) {
        return jdbc.sql("SELECT * FROM app_learning.media_upload_session WHERE session_id=:id")
                .param("id", id).query(MediaUploadRepository::map).single();
    }

    private Session byIdForUpdate(UUID id) {
        return jdbc.sql("SELECT * FROM app_learning.media_upload_session WHERE session_id=:id FOR UPDATE")
                .param("id", id).query(MediaUploadRepository::map).single();
    }

    private static Session map(ResultSet row, int ignored) throws SQLException {
        var lease = row.getTimestamp("lease_until");
        var copyStarted = row.getTimestamp("copy_started_at");
        return new Session((UUID) row.getObject("session_id"), (UUID) row.getObject("asset_id"),
                (UUID) row.getObject("owner_id"), row.getLong("generation"), row.getString("kind"),
                row.getString("declared_mime"), row.getLong("declared_length"), row.getBytes("request_fingerprint"),
                (UUID) row.getObject("retry_command_id"),
                row.getString("method"), row.getString("staging_key"), row.getString("frozen_key"),
                row.getString("storage_upload_id"), row.getString("state"),
                (UUID) row.getObject("finalize_command_id"), copyStarted == null ? null : copyStarted.toInstant(),
                row.getTimestamp("issued_until").toInstant(),
                row.getTimestamp("expires_at").toInstant(), lease == null ? null : lease.toInstant());
    }

    record Session(UUID sessionId, UUID assetId, UUID ownerId, long generation, String kind, String mime,
                   long length, byte[] fingerprint, UUID retryCommandId, String method, String stagingKey, String frozenKey,
                   String storageUploadId, String state, UUID finalizeCommandId, Instant copyStartedAt, Instant issuedUntil,
                   Instant expiresAt, Instant leaseUntil) {
        String sealedKey() { return method.equals("SINGLE") ? frozenKey : stagingKey; }
    }
    record Claim(Session session, boolean acquired) { }
    record SealedSource(UUID sessionId, UUID ownerId, String kind, String declaredMime,
                        long declaredLength, String objectKey) { }
    record AssetStatus(long generation, String state) { }
    private record Asset(long generation, String state) { }
    private record Usage(long active, long bytes) { }
}
