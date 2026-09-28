package app.mnema.learning.media;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.api.ResourceNotFoundException;
import app.mnema.learning.platform.id.UuidPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Immutable offline inventory. A manifest names verified bytes, never a signed URL or storage key. */
@Service
public class MediaManifestCatalog {
    private static final int MAX_REFERENCES = 50_000;
    private static final int MAX_ASSETS = 10_000;
    private static final int MAX_DOCUMENT_BYTES = 8 * 1024 * 1024;
    private static final Duration RETENTION = Duration.ofDays(90);
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    MediaManifestCatalog(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Snapshot current(UUID owner, UUID deck) {
        UuidPolicy.requireEntityId(owner, "ownerId");
        UuidPolicy.requireEntityId(deck, "deckId");
        UUID revision = jdbc.sql("SELECT head_revision_id FROM app_learning.deck "
                        + "WHERE deck_id=:deck AND owner_id=:owner FOR UPDATE")
                .param("deck", deck).param("owner", owner).query(UUID.class).optional()
                .orElseThrow(ResourceNotFoundException::new);
        List<Pin> pins = jdbc.sql("SELECT r.asset_id,'item' AS kind,r.member_key AS subject_id, "
                        + "r.revision_id,r.node_id FROM app_learning.content_media_ref r "
                        + "JOIN app_learning.deck_head_item h ON h.deck_id=r.deck_id "
                        + "AND h.member_key=r.member_key AND h.revision_id=r.revision_id "
                        + "WHERE r.deck_id=:deck AND r.owner_id=:owner UNION ALL "
                        + "SELECT r.asset_id,'exercise' AS kind,r.exercise_id AS subject_id, "
                        + "r.exercise_revision_id AS revision_id,NULL::uuid AS node_id "
                        + "FROM app_learning.exercise_media_ref r "
                        + "JOIN app_learning.deck_head_exercise h ON h.deck_id=r.deck_id "
                        + "AND h.exercise_id=r.exercise_id AND h.revision_id=r.exercise_revision_id "
                        + "WHERE r.deck_id=:deck AND r.owner_id=:owner "
                        + "ORDER BY asset_id,kind,subject_id,revision_id,node_id LIMIT :limit")
                .param("deck", deck).param("owner", owner).param("limit", MAX_REFERENCES + 1)
                .query((row, ignored) -> new Pin((UUID) row.getObject("asset_id"), row.getString("kind"),
                        (UUID) row.getObject("subject_id"), (UUID) row.getObject("revision_id"),
                        (UUID) row.getObject("node_id"))).list();
        if (pins.size() > MAX_REFERENCES) throw new InvalidRequestException();
        Map<UUID, List<Pin>> byAsset = new LinkedHashMap<>();
        pins.forEach(pin -> byAsset.computeIfAbsent(pin.assetId(), ignored -> new ArrayList<>()).add(pin));
        if (byAsset.size() > MAX_ASSETS) throw new InvalidRequestException();
        ObjectNode content = mapper.createObjectNode();
        content.put("schemaVersion", 1);
        content.put("deckId", deck.toString());
        content.put("deckRevisionId", revision.toString());
        ArrayNode assets = content.putArray("assets");
        Set<UUID> heldBlobs = new LinkedHashSet<>();
        for (var entry : byAsset.entrySet()) {
            UUID assetId = entry.getKey();
            Asset asset = jdbc.sql("SELECT state,generation,source_blob_id FROM app_learning.media_asset "
                            + "WHERE asset_id=:asset AND owner_id=:owner FOR SHARE")
                    .param("asset", assetId).param("owner", owner)
                    .query((row, ignored) -> new Asset(row.getString("state"), row.getLong("generation"),
                            (UUID) row.getObject("source_blob_id"))).optional()
                    .orElseThrow(ResourceNotFoundException::new);
            ObjectNode assetNode = assets.addObject();
            assetNode.put("assetId", assetId.toString());
            assetNode.put("state", asset.state());
            assetNode.put("generation", asset.generation());
            ArrayNode references = assetNode.putArray("references");
            for (Pin pin : entry.getValue()) {
                ObjectNode ref = references.addObject();
                ref.put("kind", pin.kind());
                ref.put("subjectId", pin.subjectId().toString());
                ref.put("revisionId", pin.revisionId().toString());
                if (pin.nodeId() != null) ref.put("nodeId", pin.nodeId().toString());
            }
            ArrayNode variants = assetNode.putArray("variants");
            if ("READY".equals(asset.state())) {
                List<Variant> currentVariants = jdbc.sql("SELECT v.variant_id,v.purpose,v.profile,v.blob_id, "
                                + "v.width,v.height,v.duration_ms FROM app_learning.media_variant v "
                                + "WHERE v.asset_id=:asset AND v.asset_generation=:generation "
                                + "ORDER BY v.purpose,v.profile,v.variant_id")
                        .param("asset", assetId).param("generation", asset.generation())
                        .query((row, ignored) -> new Variant((UUID) row.getObject("variant_id"),
                                row.getString("purpose"), row.getString("profile"),
                                (UUID) row.getObject("blob_id"), (Integer) row.getObject("width"),
                                (Integer) row.getObject("height"), (Long) row.getObject("duration_ms"))).list();
                // The source is retained for saving the original even if playback uses a derived variant.
                if (asset.sourceBlob() == null) throw new IllegalStateException("READY asset lacks source blob");
                appendBlob(variants.addObject(), null, "original", "original", asset.sourceBlob(),
                        null, null, null, heldBlobs);
                for (Variant variant : currentVariants) {
                    appendBlob(variants.addObject(), variant.id(), variant.purpose(), variant.profile(),
                            variant.blobId(), variant.width(), variant.height(), variant.durationMs(), heldBlobs);
                }
            }
        }
        byte[] contentBytes = json(content);
        byte[] digest = sha256(contentBytes);
        Snapshot latest = jdbc.sql("SELECT manifest_id,version,etag,document_json,expires_at,content_sha256 "
                        + "FROM app_learning.media_manifest WHERE deck_id=:deck AND owner_id=:owner "
                        + "ORDER BY version DESC LIMIT 1")
                .param("deck", deck).param("owner", owner).query(MediaManifestCatalog::snapshot).optional().orElse(null);
        if (latest != null && latest.expiresAt().isAfter(Instant.now())
                && MessageDigest.isEqual(digest, latest.contentSha256())) return latest;
        UUID id = UUID.randomUUID();
        long version = latest == null ? 1 : latest.version() + 1;
        String etag = "\"" + HexFormat.of().formatHex(digest) + "\"";
        ObjectNode document = mapper.createObjectNode();
        document.put("manifestId", id.toString());
        document.put("version", version);
        document.setAll(content);
        String body = new String(json(document), StandardCharsets.UTF_8);
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) throw new InvalidRequestException();
        Instant now = Instant.now();
        Instant expires = now.plus(RETENTION);
        jdbc.sql("INSERT INTO app_learning.media_manifest "
                        + "(manifest_id,deck_id,deck_revision_id,owner_id,version,content_sha256,etag,document_json,created_at,expires_at) "
                        + "VALUES (:id,:deck,:revision,:owner,:version,:digest,:etag,:document,:created,:expires)")
                .param("id", id).param("deck", deck).param("revision", revision).param("owner", owner)
                .param("version", version).param("digest", digest).param("etag", etag)
                .param("document", body).param("created", Timestamp.from(now))
                .param("expires", Timestamp.from(expires)).update();
        for (UUID blob : heldBlobs) jdbc.sql("INSERT INTO app_learning.media_manifest_blob_ref(manifest_id,blob_id) "
                        + "VALUES (:manifest,:blob)")
                .param("manifest", id).param("blob", blob).update();
        return new Snapshot(id, version, etag, body, expires, digest);
    }

    @Transactional(readOnly = true)
    public Snapshot read(UUID owner, UUID deck, UUID manifest) {
        UuidPolicy.requireEntityId(owner, "ownerId");
        UuidPolicy.requireEntityId(deck, "deckId");
        UuidPolicy.requireEntityId(manifest, "manifestId");
        return jdbc.sql("SELECT manifest_id,version,etag,document_json,expires_at,content_sha256 "
                        + "FROM app_learning.media_manifest WHERE manifest_id=:manifest "
                        + "AND deck_id=:deck AND owner_id=:owner AND expires_at>CURRENT_TIMESTAMP")
                .param("manifest", manifest).param("deck", deck).param("owner", owner)
                .query(MediaManifestCatalog::snapshot).optional().orElseThrow(ResourceNotFoundException::new);
    }

    private void appendBlob(ObjectNode node, UUID variantId, String purpose, String profile, UUID blobId,
                            Integer width, Integer height, Long durationMs, Set<UUID> heldBlobs) {
        Blob blob = jdbc.sql("SELECT blob_id,sha256,byte_length,mime_type FROM app_learning.media_blob "
                        + "WHERE blob_id=:blob FOR SHARE")
                .param("blob", blobId).query((row, ignored) -> new Blob((UUID) row.getObject("blob_id"),
                        (byte[]) row.getObject("sha256"), row.getLong("byte_length"),
                        row.getString("mime_type"))).optional().orElseThrow(ResourceNotFoundException::new);
        heldBlobs.add(blob.id());
        if (variantId != null) node.put("variantId", variantId.toString());
        node.put("purpose", purpose);
        node.put("profile", profile);
        node.put("blobId", blob.id().toString());
        node.put("sha256", HexFormat.of().formatHex(blob.sha256()));
        node.put("byteLength", blob.length());
        node.put("mimeType", blob.mime());
        if (width != null) node.put("width", width);
        if (height != null) node.put("height", height);
        if (durationMs != null) node.put("durationMs", durationMs);
    }

    private byte[] json(ObjectNode node) {
        try { return mapper.writeValueAsBytes(node); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Manifest serialization failed", failure); }
    }

    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    private static Snapshot snapshot(ResultSet row, int ignored) throws SQLException {
        return new Snapshot((UUID) row.getObject("manifest_id"), row.getLong("version"),
                row.getString("etag"), row.getString("document_json"),
                row.getTimestamp("expires_at").toInstant(), (byte[]) row.getObject("content_sha256"));
    }

    public record Snapshot(UUID id, long version, String etag, String body, Instant expiresAt,
                           byte[] contentSha256) { }
    private record Pin(UUID assetId, String kind, UUID subjectId, UUID revisionId, UUID nodeId) { }
    private record Asset(String state, long generation, UUID sourceBlob) { }
    private record Variant(UUID id, String purpose, String profile, UUID blobId,
                           Integer width, Integer height, Long durationMs) { }
    private record Blob(UUID id, byte[] sha256, long length, String mime) { }
}
