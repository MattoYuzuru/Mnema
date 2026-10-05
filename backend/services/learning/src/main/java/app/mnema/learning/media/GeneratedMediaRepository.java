package app.mnema.learning.media;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL of {@link GeneratedMediaStager#verified} and {@link GeneratedMediaStager#adopt}: a new asset that stands on blobs an earlier asset already had
 * verified (the speech cache, #297). The blobs are content-addressed and shared by design ({@code UNIQUE (sha256, byte_length)}), the GC keeps a blob
 * while any live asset or the speech cache reaches it, so adopting adds an asset and its variant rows and nothing else.
 */
@Repository
class GeneratedMediaRepository {
    private final JdbcClient jdbc;
    private final MediaCatalog catalog;

    GeneratedMediaRepository(JdbcClient jdbc, MediaCatalog catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    Optional<GeneratedMediaStager.VerifiedMedia> verified(UUID owner, UUID asset) {
        var found = jdbc.sql("SELECT source_blob_id,generation FROM app_learning.media_asset WHERE asset_id=:asset AND owner_id=:owner AND state='READY'")
                .param("asset", asset).param("owner", owner)
                .query((row, ignored) -> new Found((UUID) row.getObject("source_blob_id"), row.getLong("generation"))).optional();
        if (found.isEmpty()) return Optional.empty();
        List<GeneratedMediaStager.VerifiedMedia.Variant> variants = jdbc.sql("SELECT purpose,profile,blob_id,width,height,duration_ms "
                        + "FROM app_learning.media_variant WHERE asset_id=:asset AND asset_generation=:generation ORDER BY purpose,profile")
                .param("asset", asset).param("generation", found.get().generation())
                .query((row, ignored) -> new GeneratedMediaStager.VerifiedMedia.Variant(row.getString("purpose"), row.getString("profile"),
                        (UUID) row.getObject("blob_id"), (Integer) row.getObject("width"), (Integer) row.getObject("height"),
                        (Long) row.getObject("duration_ms"))).list();
        return Optional.of(new GeneratedMediaStager.VerifiedMedia(found.get().source(), variants));
    }

    /**
     * One transaction: every blob is locked FOR SHARE (a GC that is deleting one waits or has already marked it, in which case this returns false), an
     * object the GC began to reclaim refuses, one it only scanned is reset to TRACKED (as a manifest pin does), then the asset is created in PROCESSING,
     * its variants inserted and the asset made READY with the unattached hold, like the worker's publication.
     */
    @Transactional
    boolean adopt(UUID owner, UUID asset, GeneratedMediaStager.VerifiedMedia media) {
        var existing = jdbc.sql("SELECT owner_id,origin,state FROM app_learning.media_asset WHERE asset_id=:asset FOR UPDATE").param("asset", asset)
                .query((row, ignored) -> new Existing((UUID) row.getObject("owner_id"), row.getString("origin"), row.getString("state"))).optional();
        if (existing.isPresent()) {
            return existing.get().owner().equals(owner) && existing.get().origin().equals("generated") && existing.get().state().equals("READY");
        }
        List<UUID> ids = media.blobIds();
        List<String> keys = jdbc.sql("SELECT object_key FROM app_learning.media_blob WHERE blob_id IN (:ids) ORDER BY blob_id FOR SHARE")
                .param("ids", ids).query(String.class).list();
        if (keys.size() != ids.size()) return false;
        for (String key : keys) {
            String state = jdbc.sql("SELECT state FROM app_learning.media_gc_object WHERE object_key=:key FOR UPDATE").param("key", key)
                    .query(String.class).optional().orElse(null);
            if ("DELETING".equals(state) || "DELETED".equals(state)) return false;
            if ("FIRST".equals(state) || "SECOND".equals(state)) {
                jdbc.sql("UPDATE app_learning.media_gc_object SET state='TRACKED',first_scan_at=NULL,first_scan_epoch=NULL,second_scan_at=NULL,"
                                + "next_attempt_at=NULL,updated_at=GREATEST(CURRENT_TIMESTAMP,updated_at) WHERE object_key=:key").param("key", key).update();
            }
        }
        jdbc.sql("INSERT INTO app_learning.media_asset(asset_id,owner_id,upload_intent_id,origin,state,created_at,updated_at) "
                        + "VALUES (:asset,:owner,:asset,'generated','PROCESSING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
                .param("asset", asset).param("owner", owner).update();
        for (GeneratedMediaStager.VerifiedMedia.Variant variant : media.variants()) {
            jdbc.sql("INSERT INTO app_learning.media_variant(variant_id,asset_id,asset_generation,purpose,profile,blob_id,width,height,duration_ms,created_at) "
                            + "VALUES (:id,:asset,0,:purpose,:profile,:blob,:width,:height,:duration,CURRENT_TIMESTAMP)")
                    .param("id", UUID.randomUUID()).param("asset", asset).param("purpose", variant.purpose()).param("profile", variant.profile())
                    .param("blob", variant.blob()).param("width", variant.width()).param("height", variant.height())
                    .param("duration", variant.durationMs()).update();
        }
        if (!catalog.ready(asset, 0, media.sourceBlob())) throw new IllegalStateException("Adopted media asset did not become ready");
        return true;
    }

    private record Found(UUID source, long generation) { }

    private record Existing(UUID owner, String origin, String state) { }
}
