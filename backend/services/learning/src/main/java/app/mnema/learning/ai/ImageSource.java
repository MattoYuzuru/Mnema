package app.mnema.learning.ai;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

/**
 * One stock image source: a single bounded search call that returns only candidates with an allowed license ({@link ImageLicense}) and an
 * https source page. Breakers, budget, the call journal and the cache belong to {@link RoutedImageSearch}; an adapter never throws for
 * provider problems and never logs a key, a token or a URL.
 */
interface ImageSource {
    /**
     * Results asked from a source per search, whatever the caller needs: the 24-hour cache key is {@code source|query|lang|page}, so a
     * page must always have the same size or a later "find similar" turn would be served a smaller cached page. The caller slices.
     * Within every source limit (Pixabay per_page up to 200, Wikimedia gsrlimit up to 50, Openverse page_size up to 500).
     */
    int PAGE_SIZE = 30;

    ImageSearch.Source source();

    /** The provider id of the journal, metrics and breakers (lower case). */
    String provider();

    /** Credentials (or none needed) and the kill switch say the source may be called. */
    boolean configured();

    AiProperties.EgressMode egress();

    /** The exact host names whose images this source's candidates may be downloaded from. */
    Set<String> imageHosts();

    AiResult<List<ImageSearch.Candidate>> search(String query, String lang, int maxResults, Duration budget);

    /** Shared mapping of an HTTP status to a failure, as the text adapters do it. */
    static AiFailure statusFailure(int status, String retryAfter, Clock clock) {
        if (status == 429) return new AiFailure.RateLimited(retryAfter(retryAfter, clock));
        if (status >= 500 || status == 408) return new AiFailure.Transient("http_" + status);
        if (status == 401 || status == 403) return new AiFailure.NotConfigured("http_" + status);
        return new AiFailure.Refusal("http_" + status);
    }

    static Duration retryAfter(String value, Clock clock) {
        if (value == null || value.isBlank()) return Duration.ZERO;
        try {
            return Duration.ofSeconds(Math.min(Long.parseLong(value.strip()), 3_600));
        } catch (NumberFormatException notSeconds) {
            try {
                Duration wait = Duration.between(clock.instant(), ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                return wait.isNegative() ? Duration.ZERO : wait.compareTo(Duration.ofHours(1)) > 0 ? Duration.ofHours(1) : wait;
            } catch (DateTimeParseException notDate) {
                return Duration.ZERO;
            }
        }
    }

    /** A GET with the polite headers every source gets (a meaningful User-Agent, JSON). */
    static HttpRequest.Builder get(java.net.URI uri, String userAgent) {
        return HttpRequest.newBuilder(uri).GET().header("User-Agent", userAgent).header("Accept", "application/json");
    }
}
