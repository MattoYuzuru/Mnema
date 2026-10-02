package app.mnema.learning.generation.mbm;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The compiler output for one media directive; the source of the {@code generation_media_slot} row. The node already
 * references {@code assetId}; the asset is created later by the media step.
 *
 * @param spec {@code AUDIO}: {@code lang}, optional {@code voice}, {@code text}; {@code IMAGE}: {@code mode} and
 *             {@code query} (search) or {@code prompt} (generate); {@code VIDEO}: {@code prompt}
 */
public record MbmSlot(String slotKey, Kind kind, UUID nodeId, UUID assetId, Map<String, String> spec) {

    /** Media kind of a slot. */
    public enum Kind { AUDIO, IMAGE, VIDEO }

    public MbmSlot {
        Objects.requireNonNull(slotKey, "slotKey");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(assetId, "assetId");
        spec = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(spec));
    }
}
