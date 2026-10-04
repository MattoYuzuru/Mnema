package app.mnema.learning.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pixabay (https://pixabay.com/api/docs/): {@code GET /api/?key&q(at most 100 characters)&lang&image_type=photo&safesearch=true&per_page&page}.
 * Per the API terms the answers are cached for 24 hours ({@link ImageSearchCache}) and images are never hot-linked: the 640 px
 * {@code webformatURL} is downloaded and stored as the owner's asset. The key is a query parameter by the API's design, so the URL is never
 * logged or put in an exception. License: Pixabay Content License (commercial use allowed).
 */
final class PixabayImageSource implements ImageSource {
    private static final Logger LOG = LoggerFactory.getLogger(PixabayImageSource.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ChatHttp http;
    private final URI base;
    private final String key;
    private final boolean enabled;
    private final String userAgent;
    private final Clock clock;

    PixabayImageSource(AiProperties.Provider provider, ChatHttp http, String userAgent, Clock clock) {
        this.http = http;
        this.base = URI.create(provider.baseUrl().isEmpty() ? "https://pixabay.com/api/" : provider.baseUrl());
        this.key = provider.apiKey();
        this.enabled = provider.enabled();
        this.userAgent = userAgent;
        this.clock = clock;
    }

    @Override public ImageSearch.Source source() { return ImageSearch.Source.PIXABAY; }

    @Override public String provider() { return "pixabay"; }

    @Override public boolean configured() { return enabled && !key.isEmpty() && http != null; }

    @Override public AiProperties.EgressMode egress() { return http == null ? AiProperties.EgressMode.DIRECT : http.egress(); }

    @Override public Set<String> imageHosts() { return Set.of("pixabay.com", "cdn.pixabay.com"); }

    @Override
    public AiResult<List<ImageSearch.Candidate>> search(String query, String lang, int maxResults, Duration budget) {
        String q = ImageText.bound(query, 100);
        int perPage = Math.max(3, Math.min(30, maxResults * 2));
        URI uri = URI.create(base + (base.toString().contains("?") ? "&" : "?") + "key=" + enc(key) + "&q=" + enc(q) + "&lang=" + enc(lang)
                + "&image_type=photo&safesearch=true&per_page=" + perPage + "&page=1");
        ChatHttp.Reply reply;
        try {
            reply = http.send(ImageSource.get(uri, userAgent), budget, null);
        } catch (ChatHttp.TransportException exception) {
            return AiResult.failed(switch (exception.kind()) {
                case TIMEOUT -> new AiFailure.Timeout();
                case TOO_LARGE -> new AiFailure.InvalidOutput("body_too_large");
                case IO -> new AiFailure.Transient("io_error");
            });
        }
        if (reply.status() / 100 != 2) return AiResult.failed(ImageSource.statusFailure(reply.status(), reply.retryAfter(), clock));
        JsonNode root;
        try {
            root = JSON.readTree(reply.body());
        } catch (JacksonException malformed) {
            return AiResult.failed(new AiFailure.InvalidOutput("malformed"));
        }
        if (!root.path("hits").isArray()) return AiResult.failed(new AiFailure.InvalidOutput("shape"));
        List<ImageSearch.Candidate> out = new ArrayList<>();
        for (JsonNode hit : root.path("hits")) {
            String page = ImageText.https(hit.path("pageURL").stringValue(null));
            String download = ImageText.https(hit.path("webformatURL").stringValue(null));
            if (page == null || download == null || !hit.path("id").isNumber()) continue;
            int width = hit.path("webformatWidth").asInt(hit.path("imageWidth").asInt(0));
            int height = hit.path("webformatHeight").asInt(hit.path("imageHeight").asInt(0));
            out.add(new ImageSearch.Candidate(ImageSearch.Source.PIXABAY, Long.toString(hit.path("id").asLong()),
                    ImageText.plain(hit.path("tags").stringValue(""), 300), ImageText.plain(hit.path("user").stringValue(""), 200),
                    ImageLicense.PIXABAY.name(), ImageLicense.PIXABAY.url(), page, false, Math.max(0, width), Math.max(0, height), download));
        }
        LOG.debug("image_source_answer source=pixabay hits={} kept={}", root.path("hits").size(), out.size());
        return AiResult.ok(out);
    }

    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
