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
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Wikimedia Commons through the MediaWiki Action API (no key): {@code action=query&generator=search&gsrnamespace=6&gsrsearch=filetype:bitmap <q>}
 * with {@code prop=imageinfo&iiprop=url|extmetadata|mime|size&iiurlwidth=640}. API etiquette
 * (https://www.mediawiki.org/wiki/API:Etiquette): a meaningful {@code User-Agent} with a contact URL, serial requests (one search per
 * call) and no retry storm ({@code ratelimited} is a rate-limit failure). {@code Artist} and {@code ImageDescription} are HTML and are
 * stripped to text. A file with any {@code Restrictions} (trademark, personality rights) is dropped. The 640 px {@code thumburl} is
 * downloaded, never hot-linked.
 */
final class WikimediaImageSource implements ImageSource {
    private static final Logger LOG = LoggerFactory.getLogger(WikimediaImageSource.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> MIME = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");
    private final ChatHttp http;
    private final URI base;
    private final boolean enabled;
    private final String userAgent;
    private final Clock clock;

    WikimediaImageSource(AiProperties.Provider provider, ChatHttp http, String userAgent, Clock clock) {
        this.http = http;
        this.base = URI.create(provider.baseUrl().isEmpty() ? "https://commons.wikimedia.org/w/api.php" : provider.baseUrl());
        this.enabled = provider.enabled();
        this.userAgent = userAgent;
        this.clock = clock;
    }

    @Override public ImageSearch.Source source() { return ImageSearch.Source.WIKIMEDIA; }

    @Override public String provider() { return "wikimedia"; }

    @Override public boolean configured() { return enabled && http != null; }

    @Override public AiProperties.EgressMode egress() { return http == null ? AiProperties.EgressMode.DIRECT : http.egress(); }

    @Override public Set<String> imageHosts() { return Set.of("upload.wikimedia.org", "thumb.wikimedia.org"); }

    @Override
    public AiResult<List<ImageSearch.Candidate>> search(String query, String lang, int maxResults, Duration budget) {
        String search = "filetype:bitmap " + ImageText.bound(query, 200);
        URI uri = URI.create(base + (base.toString().contains("?") ? "&" : "?") + "action=query&format=json&generator=search"
                + "&gsrnamespace=6&gsrsearch=" + enc(search) + "&gsrlimit=" + PAGE_SIZE
                + "&prop=imageinfo&iiprop=" + enc("url|extmetadata|mime|size") + "&iiurlwidth=640&iiextmetadatafilter="
                + enc("LicenseShortName|License|Artist|LicenseUrl|AttributionRequired|Restrictions|ImageDescription"));
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
        if (root.has("error")) {
            return AiResult.failed("ratelimited".equals(root.path("error").path("code").stringValue(""))
                    ? new AiFailure.RateLimited(Duration.ofSeconds(5)) : new AiFailure.InvalidOutput("api_error"));
        }
        // query.pages is an object keyed by page id (formatversion=2 would make it an array: both are read); absent when nothing matched
        JsonNode pages = root.path("query").path("pages");
        List<JsonNode> ordered = new ArrayList<>();
        if (pages.isArray()) pages.forEach(ordered::add);
        else if (pages.isObject()) pages.forEach(ordered::add);
        else if (!root.path("query").isMissingNode() || !root.path("batchcomplete").isMissingNode()) return AiResult.ok(List.of());
        else return AiResult.failed(new AiFailure.InvalidOutput("shape"));
        ordered.sort(Comparator.comparingInt(page -> page.path("index").asInt(Integer.MAX_VALUE)));
        List<ImageSearch.Candidate> out = new ArrayList<>();
        for (JsonNode page : ordered) {
            if (out.size() >= PAGE_SIZE) break;
            JsonNode info = page.path("imageinfo").path(0);
            JsonNode meta = info.path("extmetadata");
            if (info.isMissingNode() || !MIME.contains(info.path("mime").stringValue(""))) continue;
            if (!ImageText.plain(meta.path("Restrictions").path("value").stringValue(""), 200).isEmpty()) continue;
            var license = ImageLicense.fromShortName(meta.path("LicenseShortName").path("value").stringValue(null),
                    meta.path("LicenseUrl").path("value").stringValue(null));
            String pageUrl = ImageText.https(info.path("descriptionurl").stringValue(null));
            String download = ImageText.https(info.path("thumburl").stringValue(info.path("url").stringValue(null)));
            if (license.isEmpty() || pageUrl == null || download == null) continue;
            String title = page.path("title").stringValue("");
            if (title.startsWith("File:")) title = title.substring(5);
            int dot = title.lastIndexOf('.');
            if (dot > 0) title = title.substring(0, dot);
            int width = info.path("thumbwidth").asInt(info.path("width").asInt(0));
            int height = info.path("thumbheight").asInt(info.path("height").asInt(0));
            out.add(new ImageSearch.Candidate(ImageSearch.Source.WIKIMEDIA, Long.toString(page.path("pageid").asLong()),
                    ImageText.plain(title.replace('_', ' '), 300), ImageText.plain(meta.path("Artist").path("value").stringValue(""), 200),
                    license.get().name(), license.get().url(), pageUrl, license.get().shareAlike(), Math.max(0, width), Math.max(0, height), download));
        }
        LOG.debug("image_source_answer source=wikimedia pages={} kept={}", ordered.size(), out.size());
        return AiResult.ok(out);
    }

    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
