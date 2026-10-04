package app.mnema.learning.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Openverse (https://docs.openverse.org/api/): a client-credentials token ({@code POST {base}auth_tokens/token/} with the form fields
 * {@code grant_type=client_credentials}, {@code client_id}, {@code client_secret}; the answer has {@code access_token} and
 * {@code expires_in}) and {@code GET {base}images/?q&license_type=commercial&mature=false&page_size}. Results carry {@code license} (a code:
 * by, by-sa, cc0, pdm, ...), {@code license_version}, {@code license_url}, {@code foreign_landing_url} (the page at the source),
 * {@code creator}, {@code title}, {@code width}, {@code height}; the image is downloaded from the API's own thumbnail endpoint
 * {@code {base}images/{id}/thumb/} (served from the API host, so one allowlisted host and no third-party hotlink).
 *
 * <p>Not verified live: from the owner network the API host answered an anonymous request with a Cloudflare challenge, which is why the
 * source needs credentials and defaults to the egress proxy ({@code egress=proxy}); the field names above follow the published API schema.
 */
final class OpenverseImageSource implements ImageSource {
    private static final Logger LOG = LoggerFactory.getLogger(OpenverseImageSource.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ChatHttp http;
    private final URI base;
    private final String clientId;
    private final String clientSecret;
    private final boolean enabled;
    private final String userAgent;
    private final Clock clock;
    private String token;
    private Instant tokenUntil = Instant.EPOCH;

    OpenverseImageSource(AiProperties.Provider provider, ChatHttp http, String userAgent, Clock clock) {
        this.http = http;
        String root = provider.baseUrl().isEmpty() ? "https://api.openverse.org/v1/" : provider.baseUrl();
        this.base = URI.create(root.endsWith("/") ? root : root + "/");
        this.clientId = provider.clientId();
        this.clientSecret = provider.clientSecret();
        this.enabled = provider.enabled();
        this.userAgent = userAgent;
        this.clock = clock;
    }

    @Override public ImageSearch.Source source() { return ImageSearch.Source.OPENVERSE; }

    @Override public String provider() { return "openverse"; }

    @Override public boolean configured() { return enabled && !clientId.isEmpty() && !clientSecret.isEmpty() && http != null; }

    @Override public AiProperties.EgressMode egress() { return http == null ? AiProperties.EgressMode.DIRECT : http.egress(); }

    @Override public Set<String> imageHosts() { return Set.of(base.getHost().toLowerCase(java.util.Locale.ROOT)); }

    @Override
    public AiResult<List<ImageSearch.Candidate>> search(String query, String lang, int maxResults, Duration budget) {
        long started = System.nanoTime();
        AiResult<String> bearer = bearer(budget);
        if (bearer instanceof AiResult.Failed<String> failed) return AiResult.failed(failed.failure());
        Duration left = budget.minusNanos(System.nanoTime() - started);
        URI uri = base.resolve("images/?q=" + enc(ImageText.bound(query, 200)) + "&license_type=commercial&mature=false&page_size="
                + Math.max(5, Math.min(30, maxResults * 2)));
        ChatHttp.Reply reply;
        try {
            reply = http.send(ImageSource.get(uri, userAgent).header("Authorization", "Bearer " + ((AiResult.Ok<String>) bearer).value()), left, null);
        } catch (ChatHttp.TransportException exception) {
            return AiResult.failed(transport(exception));
        }
        if (reply.status() == 401) invalidate();
        if (reply.status() / 100 != 2) return AiResult.failed(ImageSource.statusFailure(reply.status(), reply.retryAfter(), clock));
        JsonNode root;
        try {
            root = JSON.readTree(reply.body());
        } catch (JacksonException malformed) {
            return AiResult.failed(new AiFailure.InvalidOutput("malformed"));
        }
        if (!root.path("results").isArray()) return AiResult.failed(new AiFailure.InvalidOutput("shape"));
        List<ImageSearch.Candidate> out = new ArrayList<>();
        for (JsonNode item : root.path("results")) {
            String id = item.path("id").stringValue("");
            var license = ImageLicense.fromCode(item.path("license").stringValue(null), item.path("license_version").stringValue(""),
                    item.path("license_url").stringValue(null));
            String page = ImageText.https(item.path("foreign_landing_url").stringValue(null));
            if (!id.matches("[A-Za-z0-9-]{1,64}") || license.isEmpty() || page == null) continue;
            out.add(new ImageSearch.Candidate(ImageSearch.Source.OPENVERSE, id, ImageText.plain(item.path("title").stringValue(""), 300),
                    ImageText.plain(item.path("creator").stringValue(""), 200), license.get().name(), license.get().url(), page,
                    license.get().shareAlike(), Math.max(0, item.path("width").asInt(0)), Math.max(0, item.path("height").asInt(0)),
                    base.resolve("images/" + id + "/thumb/").toString()));
        }
        LOG.debug("image_source_answer source=openverse results={} kept={}", root.path("results").size(), out.size());
        return AiResult.ok(out);
    }

    private synchronized void invalidate() {
        token = null;
        tokenUntil = Instant.EPOCH;
    }

    /** The cached bearer, or a new one (a minute of margin before it expires). The secret is only ever in the request body. */
    private AiResult<String> bearer(Duration budget) {
        synchronized (this) {
            if (token != null && clock.instant().isBefore(tokenUntil)) return AiResult.ok(token);
        }
        String form = "grant_type=client_credentials&client_id=" + enc(clientId) + "&client_secret=" + enc(clientSecret);
        ChatHttp.Reply reply;
        try {
            reply = http.send(HttpRequest.newBuilder(base.resolve("auth_tokens/token/"))
                    .header("User-Agent", userAgent).header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)), budget, null);
        } catch (ChatHttp.TransportException exception) {
            return AiResult.failed(transport(exception));
        }
        if (reply.status() / 100 != 2) {
            AiFailure failure = ImageSource.statusFailure(reply.status(), reply.retryAfter(), clock);
            // a rejected credential is a configuration problem whatever the status says
            return AiResult.failed(reply.status() == 400 || reply.status() == 401 ? new AiFailure.NotConfigured("token_rejected") : failure);
        }
        try {
            JsonNode root = JSON.readTree(reply.body());
            String access = root.path("access_token").stringValue("");
            long seconds = root.path("expires_in").asLong(0);
            if (access.isEmpty() || seconds <= 0) return AiResult.failed(new AiFailure.InvalidOutput("token_shape"));
            synchronized (this) {
                token = access;
                tokenUntil = clock.instant().plusSeconds(Math.max(1, seconds - 60));
            }
            return AiResult.ok(access);
        } catch (JacksonException malformed) {
            return AiResult.failed(new AiFailure.InvalidOutput("token_malformed"));
        }
    }

    private static AiFailure transport(ChatHttp.TransportException exception) {
        return switch (exception.kind()) {
            case TIMEOUT -> new AiFailure.Timeout();
            case TOO_LARGE -> new AiFailure.InvalidOutput("body_too_large");
            case IO -> new AiFailure.Transient("io_error");
        };
    }

    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
