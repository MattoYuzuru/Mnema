package app.mnema.learning.update.plan;

import app.mnema.learning.catalog.content.NativeDocument;
import app.mnema.learning.catalog.content.NativeDocumentPreview;
import app.mnema.learning.catalog.content.NativeNodeIndex;
import app.mnema.learning.catalog.content.storage.NativeSnapshotDecoder;
import app.mnema.learning.catalog.content.storage.NativeStorageBatches;
import app.mnema.learning.platform.json.CanonicalJsonHasher;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.storage.StorageTypes.ObjectRef;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.HexFormat;
import java.util.UUID;

/**
 * The content side of the plan: material documents decoded from their content roots with the native reader, the stable-node index of a revision, the fingerprint of a unit's
 * learner-relevant content and the title of a document. Read-only. The caller has authorized the root (the plan names the revisions its three sides hold, never a request's id).
 */
@Component
final class PlanContent {
    private final NativeStorageBatches batches;
    private final CanonicalJsonHasher hasher = new CanonicalJsonHasher();

    PlanContent(ImmutableStorage storage) { this.batches = new NativeStorageBatches(storage); }

    private NativeDocument decode(UUID scope, UUID contentRoot) {
        var decoder = new NativeSnapshotDecoder(new ObjectRef(scope, contentRoot));
        while (!decoder.isComplete()) batches.readNext(decoder);
        return decoder.snapshot().document();
    }

    /** The native document stored under a content root (a validated snapshot; ids, types, attributes and structure). */
    JsonNode document(UUID scope, UUID contentRoot) { return decode(scope, contentRoot).toJson(); }

    NativeNodeIndex nodes(UUID scope, UUID contentRoot) {
        return NativeNodeIndex.load(batches, new ObjectRef(scope, contentRoot));
    }

    /** The first readable block of a document, as the material list shows it. */
    String title(UUID scope, UUID contentRoot) { return NativeDocumentPreview.title(decode(scope, contentRoot)); }

    /** SHA-256 of the canonical JSON: two values with the same fingerprint are the same content, whatever revision or key order carried them. */
    String fingerprint(JsonNode value) { return HexFormat.of().formatHex(hasher.hash(value).sha256()); }
}
