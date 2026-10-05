package app.mnema.learning.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Perplexity Search API (https://docs.perplexity.ai/api-reference/search-post, the spike of 2026-10-05; not run against the live service: no key
 * exists yet): {@code POST {base}/search}, {@code Authorization: Bearer}, up to five queries in one request (one billing unit). It is a fallback,
 * off by default ({@code learning.ai.routes.search} lists it only when the owner turns it on) and reached through the egress proxy
 * ({@code learning.ai.providers.perplexity.egress=proxy}). {@code max_tokens_per_page} keeps the content of a page out of the answer: only the
 * pointer and a short snippet are used, and what the API returns beyond that is dropped.
 *
 * <p>With several queries the documentation describes one flat {@code results} list without saying which result answers which query (older versions
 * answered a list of lists): both shapes are read. A flat list cannot be told apart per query, so its results all belong to the first query of the
 * request.
 */
final class PerplexityWebSearch implements WebSearchAdapter {
    static final String PROVIDER = "perplexity";
    static final int MAX_QUERIES = 5;
    private static final Logger LOG = LoggerFactory.getLogger(PerplexityWebSearch.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_TOKENS_PER_PAGE = 400;

    private final ChatHttp http;
    private final URI endpoint;
    private final String key;
    private final boolean enabled;
    private final ResearchSettings settings;
    private final Clock clock;

    PerplexityWebSearch(AiProperties.Provider provider, ChatHttp http, ResearchSettings settings, Clock clock) {
        this.http = http;
        String base = provider.baseUrl().isEmpty() ? "https://api.perplexity.ai" : provider.baseUrl();
        this.endpoint = URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/search");
        this.key = provider.apiKey();
        this.enabled = provider.enabled();
        this.settings = settings;
        this.clock = clock;
    }

    @Override public String provider() { return PROVIDER; }

    @Override
    public boolean configured() { return enabled && !key.isEmpty() && http != null; }

    @Override public AiProperties.EgressMode egress() { return http == null ? AiProperties.EgressMode.DIRECT : http.egress(); }

    @Override public int maxQueries() { return MAX_QUERIES; }

    @Override
    public long requestCostMicros() { return settings.perplexityUsdPerRequest().multiply(BigDecimal.valueOf(1_000_000)).setScale(0, java.math.RoundingMode.CEILING).longValue(); }

    @Override
    public AiResult<List<WebSearch.Result>> search(WebSearch.Request request, Duration budget) {
        ObjectNode body = JSON.createObjectNode();
        if (request.queries().size() == 1) {
            body.put("query", request.queries().getFirst());
        } else {
            var queries = body.putArray("query");
            request.queries().forEach(queries::add);
        }
        body.put("max_results", request.maxResults()).put("search_type", "web").put("max_tokens_per_page", MAX_TOKENS_PER_PAGE);
        body.putArray("search_language_filter").add(request.lang());
        if (request.region() != null) body.put("country", request.region());
        HttpRequest.Builder post = HttpRequest.newBuilder(endpoint).header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        ChatHttp.Reply reply;
        try {
            reply = http.send(post, budget, null);
        } catch (ChatHttp.TransportException exception) {
            return AiResult.failed(WebSearchAdapter.transportFailure(exception));
        }
        if (reply.status() / 100 != 2) return AiResult.failed(WebSearchAdapter.statusFailure(reply.status(), reply.retryAfter(), clock));
        JsonNode root;
        try {
            root = JSON.readTree(reply.body());
        } catch (JacksonException malformed) {
            return AiResult.failed(new AiFailure.InvalidOutput("malformed"));
        }
        JsonNode results = root.path("results");
        if (!results.isArray()) return AiResult.failed(new AiFailure.InvalidOutput("shape"));
        List<WebSearch.Result> out = new ArrayList<>();
        boolean nested = !results.isEmpty() && results.get(0).isArray();
        if (nested) {
            for (int query = 0; query < results.size() && query < request.queries().size(); query++) {
                int kept = 0;
                for (JsonNode item : results.get(query)) if (add(out, item, query, kept + 1)) kept++;
            }
        } else {
            int kept = 0;
            for (JsonNode item : results) if (add(out, item, 0, kept + 1)) kept++;
        }
        LOG.debug("web_search_answer provider=perplexity queries={} kept={}", request.queries().size(), out.size());
        return AiResult.ok(out);
    }

    /** Adds the result when it is a usable pointer; the rank counts the usable ones. */
    private static boolean add(List<WebSearch.Result> out, JsonNode item, int query, int rank) {
        if (!item.isObject()) return false;
        String url = WebSearch.acceptable(item.path("url").stringValue(null));
        if (url == null) return false;
        String title = ImageText.plain(item.path("title").stringValue(""), WebSearch.MAX_TITLE);
        out.add(new WebSearch.Result(url, title.isEmpty() ? url : title, ImageText.plain(item.path("snippet").stringValue(""), WebSearch.MAX_SNIPPET),
                date(item.path("date").stringValue(null)), WebSearch.Provider.PERPLEXITY, query, rank));
        return true;
    }

    /** {@code YYYY-MM-DD} as sent, or null for anything else. */
    private static String date(String value) {
        if (value == null) return null;
        try {
            return LocalDate.parse(value.strip().substring(0, Math.min(10, value.strip().length()))).toString();
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }
}
