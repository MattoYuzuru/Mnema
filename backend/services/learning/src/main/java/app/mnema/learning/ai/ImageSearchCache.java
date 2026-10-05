package app.mnema.learning.ai;

import java.util.List;
import java.util.Optional;

/**
 * The 24-hour cache of source answers (Pixabay requires it; every source uses it). Keyed by {@code SHA-256(source|query|lang|page)}, holds
 * only the licensed candidates of one source, no account data. A failing cache never fails a search: it is a miss.
 */
interface ImageSearchCache {
    /** The cached licensed candidates of a source for this query, when fresher than the TTL. */
    Optional<List<ImageSearch.Candidate>> get(String source, String query, String lang, int page);

    void put(String source, String query, String lang, int page, List<ImageSearch.Candidate> candidates);

    /** No cache at all. */
    ImageSearchCache NONE = new ImageSearchCache() {
        @Override public Optional<List<ImageSearch.Candidate>> get(String source, String query, String lang, int page) { return Optional.empty(); }

        @Override public void put(String source, String query, String lang, int page, List<ImageSearch.Candidate> candidates) { }
    };
}
