package app.mnema.learning.community;

import app.mnema.learning.library.PublicMetaService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * {@code learning.community.catalog.*}: the switch and the knobs of the public catalogue routes (Community/2, /6 and /7; contract {@code contracts/community/catalog.json}). The
 * sizes of the projection jobs are {@link CatalogSettings}, the rank is {@link RankSettings}; this class is only what the HTTP side reads. Invalid values fail at startup.
 * <ul>
 *   <li>{@code enabled} [false]: every public route of the contract and the sitemap answer 404 as if absent. It is a child of {@code learning.community.public-routes.enabled}
 *       (which makes the admission answer 404 too); the owner's editorial routes work regardless.</li>
 *   <li>{@code similarity-threshold} [0.5, 0.1-1.0]: {@code pg_trgm.word_similarity_threshold} of the search statement. 0.5 keeps one typo in a word of seven letters
 *       («испнские») and the kana of a title, and leaves out the titles that share one word of two («Английские фразовые глаголы» for «испнские глаголы» scores 0.47).</li>
 *   <li>{@code facets-ttl} [PT5M, up to PT5M]: the per-instance cache of the facets (and of the topic shelves, which read them).</li>
 *   <li>{@code sitemap-ttl} [PT1H, PT1M-PT1H]: the per-instance cache of the sitemap. At most an hour on purpose: a deck that moderation hides leaves the sitemap only when the
 *       cache expires (plus whatever a crawler keeps), and a longer wait would be a longer window in which a hidden deck is still advertised.</li>
 *   <li>{@code max-concurrent-searches} [2, 1-64]: text searches an instance runs at once; the next one is refused with 503 {@code PUBLIC_READ_BUSY} before a connection is
 *       taken, so expensive searches cannot occupy every place of the public bulkhead.</li>
 *   <li>{@code query-timeout} [PT3S, PT1S-PT30S]: the timeout of the read transaction of one catalogue request.</li>
 * </ul>
 * The origin of the sitemap is the one of the link previews, {@code learning.community.public-origin} ({@code MNEMA_PUBLIC_BASE_URL}).
 */
@Component
final class CatalogRouteSettings {
    final boolean enabled;
    final double similarityThreshold;
    final Duration facetsTtl;
    final Duration sitemapTtl;
    final Duration queryTimeout;
    final int maxConcurrentSearches;
    final String origin;

    CatalogRouteSettings(@Value("${learning.community.catalog.enabled:false}") boolean enabled,
                         @Value("${learning.community.catalog.similarity-threshold:0.5}") double similarityThreshold,
                         @Value("${learning.community.catalog.facets-ttl:PT5M}") Duration facetsTtl,
                         @Value("${learning.community.catalog.sitemap-ttl:PT1H}") Duration sitemapTtl,
                         @Value("${learning.community.catalog.query-timeout:PT3S}") Duration queryTimeout,
                         @Value("${learning.community.catalog.max-concurrent-searches:2}") int maxConcurrentSearches,
                         @Value("${learning.community.public-origin:}") String origin) {
        if (!(similarityThreshold >= 0.1 && similarityThreshold <= 1.0) || outside(facetsTtl, Duration.ofSeconds(1), Duration.ofMinutes(5))
                || outside(sitemapTtl, Duration.ofMinutes(1), Duration.ofHours(1)) || maxConcurrentSearches < 1 || maxConcurrentSearches > 64 || outside(queryTimeout, Duration.ofSeconds(1), Duration.ofSeconds(30))) {
            throw new IllegalArgumentException("Invalid catalog route settings");
        }
        this.enabled = enabled;
        this.similarityThreshold = similarityThreshold;
        this.facetsTtl = facetsTtl;
        this.sitemapTtl = sitemapTtl;
        this.queryTimeout = queryTimeout;
        this.maxConcurrentSearches = maxConcurrentSearches;
        this.origin = PublicMetaService.origin(origin);
    }

    private static boolean outside(Duration value, Duration min, Duration max) {
        return value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0;
    }
}
