package app.mnema.learning.catalog.content;

import app.mnema.learning.media.MediaCatalog;
import tools.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Extracts only supported v1 media nodes; opaque future payloads never gain storage authority. */
public final class NativeMediaReferences {
    private static final Set<String> MEDIA_TYPES = Set.of("image", "audio", "video");

    private NativeMediaReferences() { }

    public static List<MediaCatalog.Reference> from(NativeDocument document) {
        var references = new ArrayList<MediaCatalog.Reference>();
        var pending = new ArrayDeque<JsonNode>();
        pending.add(document.toJson().path("root"));
        while (!pending.isEmpty()) {
            JsonNode node = pending.removeLast();
            String type = node.path("type").stringValue(null);
            int version = node.path("version").intValue(0);
            if (!NativeNodeSchema.supports(type, version)) continue;
            if (MEDIA_TYPES.contains(type)) {
                references.add(new MediaCatalog.Reference(UUID.fromString(node.path("id").stringValue(null)),
                        UUID.fromString(node.path("attrs").path("assetId").stringValue(null))));
            }
            node.path("content").forEach(pending::add);
        }
        return List.copyOf(references);
    }
}
