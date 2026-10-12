package app.mnema.learning.community;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code learning.community.catalog.*}, the sizes of one slice of the catalogue jobs (the queue policies themselves are {@code learning.jobs.catalog.project.*} and
 * {@code learning.jobs.catalog.rebuild.*}). {@code slice-size} [500, 1-2000] is the number of exercises of the PUBLISHED manifest that {@link CatalogProjector} scans per
 * transaction; {@code rebuild-size} [200, 1-1000] the number of decks {@link CatalogRebuild} hands to the projector per transaction. Invalid values fail at startup.
 */
@Component
final class CatalogSettings {
    final int sliceSize;
    final int rebuildSize;

    CatalogSettings(@Value("${learning.community.catalog.slice-size:500}") int sliceSize,
                    @Value("${learning.community.catalog.rebuild-size:200}") int rebuildSize) {
        if (sliceSize < 1 || sliceSize > 2_000 || rebuildSize < 1 || rebuildSize > 1_000) throw new IllegalArgumentException("Invalid catalog settings");
        this.sliceSize = sliceSize;
        this.rebuildSize = rebuildSize;
    }
}
