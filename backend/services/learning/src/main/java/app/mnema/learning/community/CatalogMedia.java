package app.mnema.learning.community;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The «внутри» facet of the catalogue: which media the MATERIALS of a published deck show. The bit of {@code public_deck.media_mask} and the wire word of the filter
 * ({@code image}, {@code audio}, {@code video}) are defined here once. The kind of an asset is the top-level type of the MIME type of its verified source blob
 * ({@code image/png} is an image), the same rule that rejects a declared kind the bytes do not match.
 */
enum CatalogMedia {
    IMAGE("image", 1),
    AUDIO("audio", 2),
    VIDEO("video", 4);

    /** The mask with every bit set; the column's CHECK is {@code BETWEEN 0 AND 7}. */
    static final int ALL = 7;

    private final String wire;
    private final int bit;

    CatalogMedia(String wire, int bit) {
        this.wire = wire;
        this.bit = bit;
    }

    String wire() { return wire; }

    int bit() { return bit; }

    static Optional<CatalogMedia> fromWire(String value) {
        for (CatalogMedia media : values()) if (media.wire.equals(value)) return Optional.of(media);
        return Optional.empty();
    }

    /** The mask of the top-level MIME types ({@code image}, {@code audio}, ...) of a deck's assets; other types (a document, a font) set nothing. */
    static int maskOf(Set<String> topLevelTypes) {
        int mask = 0;
        for (String type : topLevelTypes) {
            String lower = type.toLowerCase(Locale.ROOT);
            for (CatalogMedia media : values()) if (media.wire.equals(lower)) mask |= media.bit;
        }
        return mask;
    }

    static Set<CatalogMedia> fromMask(int mask) {
        Set<CatalogMedia> found = EnumSet.noneOf(CatalogMedia.class);
        for (CatalogMedia media : values()) if ((mask & media.bit) != 0) found.add(media);
        return found;
    }
}
