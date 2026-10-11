package app.mnema.learning.support;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.item.ItemPublicationCommand;
import app.mnema.learning.catalog.item.ItemService;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.util.UUID;

/**
 * Media helpers of the Share/9 tests: native documents whose content is image nodes of given assets (every node is one media reference of the
 * revision), derived variants for a READY asset (blobs and variants as the media worker writes them, with fake object keys that only the presigner sees),
 * and the deletion of a material.
 */
public final class MediaFixtures {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MediaFixtures() { }

    /**
     * A document with a stable first paragraph and one image node per asset, in order (the same asset twice is two references). The paragraph keeps the
     * document valid with no media at all and its ids derive from the root, so a later save of the material can keep them.
     */
    public static ObjectNode imageDocument(UUID... assets) { return imageDocumentUnder(UUID.randomUUID(), assets); }

    public static ObjectNode imageDocumentUnder(UUID rootId, UUID... assets) {
        ObjectNode document = JSON.createObjectNode().put("formatVersion", 1);
        ObjectNode root = document.putObject("root").put("id", rootId.toString()).put("type", "doc").put("version", 1);
        root.putObject("attrs");
        var content = root.putArray("content");
        ObjectNode paragraph = content.addObject().put("id", derived(rootId, "p").toString()).put("type", "paragraph").put("version", 1);
        paragraph.putObject("attrs");
        ObjectNode text = paragraph.putArray("content").addObject().put("id", derived(rootId, "t").toString()).put("type", "text").put("version", 1);
        text.putObject("attrs").put("text", "Материал");
        text.putArray("content");
        for (UUID asset : assets) content.add(image(UUID.randomUUID(), asset));
        return document;
    }

    private static ObjectNode image(UUID node, UUID asset) {
        ObjectNode image = JSON.createObjectNode().put("id", node.toString()).put("type", "image").put("version", 1);
        image.putObject("attrs").put("assetId", asset.toString()).put("alt", "Схема").put("description", "Описание схемы.");
        image.putArray("content");
        return image;
    }

    /** A stable id derived from the root, shaped as a version 4 UUID (the entity-id policy accepts registered random versions only). */
    private static UUID derived(UUID root, String role) {
        UUID hashed = UUID.nameUUIDFromBytes((root + role).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new UUID((hashed.getMostSignificantBits() & ~0xF000L) | 0x4000L, hashed.getLeastSignificantBits());
    }

    /**
     * Saves a new revision of a material built with {@link #imageDocument}: its images become exactly {@code assets}, in order. Existing image nodes
     * are reused (the asset of the node changes), surplus ones are deleted and missing ones inserted, as the editor's structural edits do.
     */
    public static UUID saveImages(DeckService decks, ItemService items, UUID owner, UUID deck, UUID member, UUID expectedRevision, UUID... assets) {
        JsonNode head = decks.read(owner, deck);
        JsonNode current = items.read(owner, deck, member, null);
        ObjectNode document = (ObjectNode) current.path("document").deepCopy();
        UUID root = UUID.fromString(document.path("root").path("id").stringValue(null));
        var content = (tools.jackson.databind.node.ArrayNode) document.path("root").path("content");
        var images = new java.util.ArrayList<ObjectNode>();
        for (JsonNode child : content) if ("image".equals(child.path("type").stringValue(null))) images.add((ObjectNode) child);
        var edits = JSON.createArrayNode();
        for (int index = assets.length; index < images.size(); index++) {
            ObjectNode surplus = images.get(index);
            edits.addObject().put("type", "delete").put("nodeId", surplus.path("id").stringValue(null));
            for (int position = 0; position < content.size(); position++) {
                if (content.get(position) == surplus) { content.remove(position); break; }
            }
        }
        for (int index = 0; index < assets.length; index++) {
            if (index < images.size()) {
                ((ObjectNode) images.get(index).path("attrs")).put("assetId", assets[index].toString());
            } else {
                UUID node = UUID.randomUUID();
                edits.addObject().put("type", "insert").put("nodeId", node.toString()).put("parentId", root.toString()).put("childIndex", content.size());
                content.add(image(node, assets[index]));
            }
        }
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", head.path("revisionId").stringValue(null))
                .put("expectedItemRevisionId", expectedRevision.toString())
                .put("expectedOrdinal", items.read(owner, deck, member, null).path("ordinal").intValue());
        body.set("document", document);
        if (!edits.isEmpty()) body.set("edits", edits);
        JsonNode change = items.publish(owner, deck, Long.parseLong(head.path("rowVersion").stringValue(null)),
                ItemPublicationCommand.readSave(new ByteArrayInputStream(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)), member))
                .acknowledgement().path("changes").get(0);
        return UUID.fromString(change.path("itemRevisionId").stringValue(null));
    }

    /** The id of the root node of the material's current document (what a replacing save must keep). */
    public static UUID rootOf(ItemService items, UUID owner, UUID deck, UUID member) {
        return UUID.fromString(items.read(owner, deck, member, null).path("document").path("root").path("id").stringValue(null));
    }

    /** The object keys of the variants of an asset (what the presigner will sign). */
    public record Variants(UUID playback, String playbackKey, String playbackMime, UUID poster, String posterKey, String originalKey) { }

    /**
     * Writes a playback variant of {@code playbackMime} and a thumbnail (WebP) for a READY asset of generation 0, the way the media worker's result is
     * stored. The original's blob is the one the asset already has.
     */
    public static Variants variants(JdbcClient jdbc, UUID asset, String playbackMime) { return variants(jdbc, asset, playbackMime, true); }

    /** As above; {@code withPoster = false} is an audio asset, which has a playback variant and nothing else. */
    public static Variants variants(JdbcClient jdbc, UUID asset, String playbackMime, boolean withPoster) {
        UUID playback = variant(jdbc, asset, "playback", "playback_v1", playbackMime);
        UUID thumbnail = withPoster ? variant(jdbc, asset, "thumbnail", "thumbnail_v1", "image/webp") : null;
        return new Variants(playback, key(jdbc, playback), playbackMime, thumbnail, thumbnail == null ? null : key(jdbc, thumbnail),
                jdbc.sql("SELECT b.object_key FROM app_learning.media_asset a JOIN app_learning.media_blob b ON b.blob_id = a.source_blob_id WHERE a.asset_id = :asset")
                        .param("asset", asset).query(String.class).single());
    }

    private static UUID variant(JdbcClient jdbc, UUID asset, String purpose, String profile, String mime) {
        UUID blob = UUID.randomUUID();
        byte[] hash = new byte[32];
        java.nio.ByteBuffer.wrap(hash).putLong(blob.getMostSignificantBits()).putLong(blob.getLeastSignificantBits());
        jdbc.sql("INSERT INTO app_learning.media_blob(blob_id,sha256,byte_length,mime_type,object_key,verified_at) VALUES (:blob,:hash,64,:mime,:key,CURRENT_TIMESTAMP)")
                .param("blob", blob).param("hash", hash).param("mime", mime).param("key", "variant/" + blob).update();
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_learning.media_variant(variant_id,asset_id,asset_generation,purpose,profile,blob_id,created_at) "
                        + "VALUES (:id,:asset,0,:purpose,:profile,:blob,CURRENT_TIMESTAMP)")
                .param("id", id).param("asset", asset).param("purpose", purpose).param("profile", profile).param("blob", blob).update();
        return id;
    }

    private static String key(JdbcClient jdbc, UUID variant) {
        return jdbc.sql("SELECT b.object_key FROM app_learning.media_variant v JOIN app_learning.media_blob b ON b.blob_id = v.blob_id WHERE v.variant_id = :variant")
                .param("variant", variant).query(String.class).single();
    }

    /** Deletes a material the owner has at {@code revision}. */
    public static void deleteMaterial(DeckService decks, ItemService items, UUID owner, UUID deck, UUID member, UUID revision) {
        JsonNode head = decks.read(owner, deck);
        int ordinal = items.read(owner, deck, member, null).path("ordinal").intValue();
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedDeckRevisionId", head.path("revisionId").stringValue(null));
        body.putArray("changes").addObject().put("operation", "delete").put("memberKey", member.toString())
                .put("expectedItemRevisionId", revision.toString()).put("expectedOrdinal", ordinal);
        items.publish(owner, deck, Long.parseLong(head.path("rowVersion").stringValue(null)),
                ItemPublicationCommand.readBulk(new ByteArrayInputStream(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }
}
