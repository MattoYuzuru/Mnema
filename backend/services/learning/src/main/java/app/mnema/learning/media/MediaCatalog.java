package app.mnema.learning.media;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.idempotency.IdempotencyConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

/** Transactional identities, references and owner authorization; object transfer lives outside this boundary. */
@Service
public class MediaCatalog {
    private final JdbcClient jdbc;
    private final MediaSettings settings;

    public MediaCatalog(JdbcClient jdbc, MediaSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    /** Reserve a server-selected identity before any bytes are transferred. */
    @Transactional
    public UUID reserve(UUID owner, UUID uploadIntent, Origin origin) {
        UuidPolicy.requireEntityId(owner, "owner");
        UuidPolicy.requireEntityId(uploadIntent, "uploadIntentId");
        if (origin == null) throw new InvalidRequestException();
        UUID asset = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_learning.media_asset(asset_id,owner_id,upload_intent_id,origin,created_at,updated_at) "
                        + "VALUES (:asset,:owner,:intent,:origin,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) "
                        + "ON CONFLICT (owner_id,upload_intent_id) DO NOTHING")
                .param("asset", asset).param("owner", owner).param("intent", uploadIntent)
                .param("origin", origin.name().toLowerCase(java.util.Locale.ROOT)).update();
        Reservation reservation = jdbc.sql("SELECT asset_id,origin FROM app_learning.media_asset "
                        + "WHERE owner_id=:owner AND upload_intent_id=:intent")
                .param("owner", owner).param("intent", uploadIntent)
                .query((row, ignored) -> new Reservation((UUID) row.getObject("asset_id"), row.getString("origin")))
                .single();
        if (!reservation.origin().equals(origin.name().toLowerCase(java.util.Locale.ROOT))) {
            throw new IdempotencyConflictException();
        }
        return reservation.assetId();
    }

    /** A stale worker generation must never replace bytes from a newer upload attempt. */
    @Transactional
    public boolean ready(UUID asset, long generation, UUID verifiedBlob) {
        if (generation < 0) throw new InvalidRequestException();
        UuidPolicy.requireEntityId(asset, "assetId");
        UuidPolicy.requireEntityId(verifiedBlob, "blobId");
        return jdbc.sql("UPDATE app_learning.media_asset SET state='READY', source_blob_id=:blob, "
                        + "owner_hold_until=CURRENT_TIMESTAMP + (:holdSeconds * interval '1 second'), "
                        + "updated_at=CURRENT_TIMESTAMP "
                        + "WHERE asset_id=:asset AND generation=:generation "
                        + "AND state IN ('VERIFYING','PROCESSING')")
                .param("asset", asset).param("generation", generation).param("blob", verifiedBlob)
                .param("holdSeconds", settings.unattachedReadyHold().toSeconds()).update() == 1;
    }

    /** #237 must call this inside the transaction that inserts the item revision. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void attachRevision(UUID actor, UUID deck, UUID member, UUID revision, Collection<Reference> references) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(deck, "deckId");
        UuidPolicy.requireEntityId(member, "memberKey");
        UuidPolicy.requireEntityId(revision, "revisionId");
        if (!jdbc.sql("SELECT 1 FROM app_learning.item_revision WHERE deck_id=:deck AND member_key=:member "
                        + "AND revision_id=:revision AND owner_id=:owner")
                .param("deck", deck).param("member", member).param("revision", revision).param("owner", actor)
                .query(Integer.class).optional().isPresent()) throw new ResourceNotFoundException();
        validateReferences(actor, references);
        for (Reference reference : references) jdbc.sql("INSERT INTO app_learning.content_media_ref "
                        + "(deck_id,member_key,revision_id,node_id,owner_id,asset_id) "
                        + "VALUES (:deck,:member,:revision,:node,:owner,:asset)")
                .param("deck", deck).param("member", member).param("revision", revision)
                .param("node", reference.nodeId()).param("owner", actor).param("asset", reference.assetId()).update();
    }

    /** #238 must call this inside the transaction that saves the draft document. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceDraft(UUID actor, UUID draft, Collection<Reference> references) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(draft, "draftId");
        if (!jdbc.sql("SELECT 1 FROM app_learning.editing_draft WHERE draft_id=:draft "
                        + "AND owner_id=:owner AND expires_at>CURRENT_TIMESTAMP FOR UPDATE")
                .param("draft", draft).param("owner", actor).query(Integer.class).optional().isPresent()) {
            throw new ResourceNotFoundException();
        }
        // Validate first so a foreign asset cannot evict an authorized draft's current holds.
        validateReferences(actor, references);
        jdbc.sql("DELETE FROM app_learning.draft_media_ref WHERE draft_id=:draft AND owner_id=:owner")
                .param("draft", draft).param("owner", actor).update();
        for (Reference reference : references) jdbc.sql("INSERT INTO app_learning.draft_media_ref "
                        + "(draft_id,node_id,owner_id,asset_id) VALUES (:draft,:node,:owner,:asset)")
                .param("draft", draft).param("node", reference.nodeId())
                .param("owner", actor).param("asset", reference.assetId()).update();
    }

    /** Return a storage locator only after checking logical ownership and reachability. */
    @Transactional(readOnly = true)
    public BlobLocation resolve(UUID actor, UUID asset, UUID variant) {
        UuidPolicy.requireEntityId(actor, "actor");
        UuidPolicy.requireEntityId(asset, "assetId");
        if (variant != null) UuidPolicy.requireEntityId(variant, "variantId");
        String sql = variant == null
                ? "SELECT b.blob_id,b.object_key,b.byte_length,b.mime_type FROM app_learning.media_asset a "
                    + "JOIN app_learning.media_blob b ON b.blob_id=a.source_blob_id "
                    + "WHERE a.asset_id=:asset AND a.owner_id=:owner AND a.state='READY' "
                    + "AND (a.owner_hold_until>CURRENT_TIMESTAMP OR EXISTS "
                    + "(SELECT 1 FROM app_learning.content_media_ref r WHERE r.asset_id=a.asset_id) OR EXISTS "
                    + "(SELECT 1 FROM app_learning.draft_media_ref r JOIN app_learning.editing_draft d "
                    + "ON d.draft_id=r.draft_id WHERE r.asset_id=a.asset_id AND d.expires_at>CURRENT_TIMESTAMP))"
                : "SELECT b.blob_id,b.object_key,b.byte_length,b.mime_type FROM app_learning.media_asset a "
                    + "JOIN app_learning.media_variant v ON v.asset_id=a.asset_id "
                    + "AND v.asset_generation=a.generation AND v.variant_id=:variant "
                    + "JOIN app_learning.media_blob b ON b.blob_id=v.blob_id "
                    + "WHERE a.asset_id=:asset AND a.owner_id=:owner AND a.state='READY' "
                    + "AND (a.owner_hold_until>CURRENT_TIMESTAMP OR EXISTS "
                    + "(SELECT 1 FROM app_learning.content_media_ref r WHERE r.asset_id=a.asset_id) OR EXISTS "
                    + "(SELECT 1 FROM app_learning.draft_media_ref r JOIN app_learning.editing_draft d "
                    + "ON d.draft_id=r.draft_id WHERE r.asset_id=a.asset_id AND d.expires_at>CURRENT_TIMESTAMP))";
        var query = jdbc.sql(sql).param("asset", asset).param("owner", actor);
        if (variant != null) query = query.param("variant", variant);
        return query.query((row, ignored) -> new BlobLocation((UUID) row.getObject("blob_id"),
                row.getString("object_key"), row.getLong("byte_length"), row.getString("mime_type")))
                .optional().orElseThrow(ResourceNotFoundException::new);
    }

    /** Tombstone only assets whose owner hold elapsed and which no content or draft still reaches. */
    @Transactional
    public int expireUnattached(int limit) {
        if (limit < 1 || limit > 1_000) throw new InvalidRequestException();
        List<UUID> candidates = jdbc.sql("SELECT a.asset_id FROM app_learning.media_asset a "
                        + "WHERE a.state='READY' AND a.owner_hold_until<CURRENT_TIMESTAMP "
                        + "AND NOT EXISTS (SELECT 1 FROM app_learning.content_media_ref r WHERE r.asset_id=a.asset_id) "
                        + "AND NOT EXISTS (SELECT 1 FROM app_learning.draft_media_ref r "
                        + "JOIN app_learning.editing_draft d ON d.draft_id=r.draft_id "
                        + "WHERE r.asset_id=a.asset_id AND d.expires_at>CURRENT_TIMESTAMP) "
                        + "ORDER BY a.owner_hold_until,a.asset_id LIMIT :limit FOR UPDATE OF a SKIP LOCKED")
                .param("limit", limit).query(UUID.class).list();
        for (UUID candidate : candidates) {
            jdbc.sql("UPDATE app_learning.media_asset SET state='DELETED',updated_at=CURRENT_TIMESTAMP "
                            + "WHERE asset_id=:asset AND state='READY'")
                    .param("asset", candidate).update();
        }
        return candidates.size();
    }

    private void validateReferences(UUID actor, Collection<Reference> references) {
        if (references == null || references.size() > 1_000) throw new InvalidRequestException();
        var nodes = new HashSet<UUID>();
        var assets = new TreeSet<UUID>();
        for (Reference reference : references) {
            if (reference == null || !nodes.add(reference.nodeId())) throw new InvalidRequestException();
            assets.add(reference.assetId());
        }
        // Stable lock ordering avoids deadlocks when two documents reuse the same assets in a different node order.
        for (UUID asset : assets) {
            if (!jdbc.sql("SELECT 1 FROM app_learning.media_asset a WHERE a.asset_id=:asset "
                            + "AND a.owner_id=:owner AND a.state NOT IN ('DELETED','REJECTED') AND "
                            + "(a.state<>'READY' OR a.owner_hold_until>CURRENT_TIMESTAMP "
                            + "OR EXISTS (SELECT 1 FROM app_learning.content_media_ref r WHERE r.asset_id=a.asset_id) "
                            + "OR EXISTS (SELECT 1 FROM app_learning.draft_media_ref r "
                            + "JOIN app_learning.editing_draft d ON d.draft_id=r.draft_id "
                            + "WHERE r.asset_id=a.asset_id AND d.expires_at>CURRENT_TIMESTAMP)) "
                            + "FOR UPDATE OF a")
                    .param("asset", asset).param("owner", actor)
                    .query(Integer.class).optional().isPresent()) throw new ResourceNotFoundException();
        }
    }

    public record Reference(UUID nodeId, UUID assetId) {
        public Reference {
            UuidPolicy.requireEntityId(nodeId, "nodeId");
            UuidPolicy.requireEntityId(assetId, "assetId");
        }
    }

    public record BlobLocation(UUID blobId, String objectKey, long byteLength, String mimeType) { }

    private record Reservation(UUID assetId, String origin) { }

    public enum Origin { UPLOAD, RECORDING, IMPORT }
}
