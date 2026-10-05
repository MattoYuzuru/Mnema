package app.mnema.learning.ai;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * One web search provider: a single bounded provider request. Breakers, budget, permits, the journal and routing belong to
 * {@link RoutedWebSearch}; an adapter never throws for provider problems and never logs a query, a key or a provider message.
 */
interface WebSearchAdapter {
    /** The provider id of routes, the journal, metrics and breakers ({@code yandex}, {@code perplexity}, {@code stub}). */
    String provider();

    /** A key (and folder) are present, the kill switch is on and the transport exists (a proxied provider needs an active proxy). */
    boolean configured();

    AiProperties.EgressMode egress();

    /** How many queries one provider request answers: 1 for Yandex, up to 5 for Perplexity. */
    int maxQueries();

    /** The provider cost of one answered request in micro-US-dollars, from the configured price. */
    long requestCostMicros();

    /**
     * One request for {@code request.queries()} (at most {@link #maxQueries()}); the results carry {@code queryIndex} relative to that list.
     * Results with an unacceptable URL (not https, user info, too long) are dropped.
     */
    AiResult<List<WebSearch.Result>> search(WebSearch.Request request, Duration budget);

    /** The mapping of an HTTP status shared by the adapters (error bodies are never read: they could echo the query). */
    static AiFailure statusFailure(int status, String retryAfter, Clock clock) {
        return switch (status) {
            case 400, 422 -> new AiFailure.InvalidOutput("request");
            case 401, 403 -> new AiFailure.NotConfigured("http_" + status);
            case 429 -> {
                Duration wait = ImageSource.retryAfter(retryAfter, clock);
                yield new AiFailure.RateLimited(wait.isZero() ? Duration.ofSeconds(1) : wait);
            }
            case 504 -> new AiFailure.Timeout();
            default -> status >= 500 || status == 408 ? new AiFailure.Transient("http_" + status) : new AiFailure.Refusal("http_" + status);
        };
    }

    /** The failure of a transport problem. */
    static AiFailure transportFailure(ChatHttp.TransportException exception) {
        return switch (exception.kind()) {
            case TIMEOUT -> new AiFailure.Timeout();
            case TOO_LARGE -> new AiFailure.InvalidOutput("body_too_large");
            case IO -> new AiFailure.Transient("io_error");
        };
    }
}
