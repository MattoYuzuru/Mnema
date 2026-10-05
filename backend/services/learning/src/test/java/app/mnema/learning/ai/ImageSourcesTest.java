package app.mnema.learning.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The three adapters on recorded JSON answers served by a local server: license filter, HTML stripping, URLs, failures, secrets. */
class ImageSourcesTest {
    private static final String KEY = "pixabay-SECRET-KEY-1234";
    private static final String CLIENT_ID = "ov-client-id-ABC";
    private static final String CLIENT_SECRET = "ov-client-SECRET-XYZ";
    private static final String AGENT = "Mnema-Test/1.0 (+https://example.org)";
    private static final Duration BUDGET = Duration.ofSeconds(5);

    private final ChatHttp http = new ChatHttp(new AiProperties.Transport(Duration.ofSeconds(2), Duration.ofSeconds(2), 1 << 20, Duration.ofSeconds(2)));
    private ImageServer server;

    @BeforeEach
    void start() { server = new ImageServer(); }

    @AfterEach
    void stop() {
        server.close();
        http.close();
    }

    private AiProperties.Provider provider(String path, String key, String id, String secret) {
        return new AiProperties.Provider(true, server.origin() + path, key, "", "", "", AiProperties.EgressMode.DIRECT, id, secret);
    }

    private static String decode(String raw) { return URLDecoder.decode(raw, StandardCharsets.UTF_8); }

    private static List<ImageSearch.Candidate> ok(AiResult<List<ImageSearch.Candidate>> result) {
        assertThat(result).isInstanceOf(AiResult.Ok.class);
        return ((AiResult.Ok<List<ImageSearch.Candidate>>) result).value();
    }

    private static AiFailure failure(AiResult<List<ImageSearch.Candidate>> result) {
        assertThat(result).isInstanceOf(AiResult.Failed.class);
        return ((AiResult.Failed<List<ImageSearch.Candidate>>) result).failure();
    }

    // ----------------------------------------------------------------- Pixabay

    @Test
    void pixabayAsksForSafePhotosInTheLanguageAndKeepsOnlyHttpsResultsWithTheContentLicense() {
        server.json("/api/", ImageServer.fixture("pixabay.json"));
        var source = new PixabayImageSource(provider("/api/", KEY, "", ""), http, AGENT, Clock.systemUTC());

        List<ImageSearch.Candidate> found = ok(source.search("лиса зимой", "ru", 4, BUDGET));

        String query = decode(server.requests.getFirst().query());
        assertThat(query).contains("key=" + KEY, "q=лиса зимой", "lang=ru", "image_type=photo", "safesearch=true", "per_page=30", "page=1");
        assertThat(server.requests.getFirst().headers().get("User-agent")).containsExactly(AGENT);
        // id 2 has an http page and id 4 a download that is not https: both are dropped
        assertThat(found).extracting(ImageSearch.Candidate::sourceId).containsExactly("195893", "3");
        ImageSearch.Candidate fox = found.getFirst();
        assertThat(fox.source()).isEqualTo(ImageSearch.Source.PIXABAY);
        assertThat(fox.title()).isEqualTo("fox, red, snow");
        assertThat(fox.author()).isEqualTo("Ann");
        assertThat(fox.license()).isEqualTo("Pixabay Content License");
        assertThat(fox.licenseUrl()).isEqualTo("https://pixabay.com/service/license-summary/");
        assertThat(fox.sourcePageUrl()).isEqualTo("https://pixabay.com/photos/fox-195893/");
        assertThat(fox.shareAlike()).isFalse();
        assertThat(fox.width()).isEqualTo(640);
        assertThat(fox.height()).isEqualTo(427);
        assertThat(fox.downloadUrl()).isEqualTo("https://pixabay.com/get/g195893.jpg");
        // text is plain: tags and entities of a result are text, whitespace is collapsed
        assertThat(found.get(1).title()).isEqualTo("wolf, grey & white");
        assertThat(found.get(1).author()).isEqualTo("Clara M.");
        assertThat(fox.toString()).doesNotContain("https://").doesNotContain(KEY);
    }

    @Test
    void pixabayFailuresAreTypedAndNeverCarryTheKeyOrTheUrl() {
        var source = new PixabayImageSource(provider("/api/", KEY, "", ""), http, AGENT, Clock.systemUTC());
        server.on("/api/", exchange -> ImageServer.reply(exchange, 429, "text/plain", new byte[0]));
        assertThat(failure(source.search("fox", "en", 3, BUDGET))).isInstanceOf(AiFailure.RateLimited.class);
        server.on("/api/", exchange -> ImageServer.reply(exchange, 503, "text/plain", new byte[0]));
        assertThat(failure(source.search("fox", "en", 3, BUDGET))).isInstanceOf(AiFailure.Transient.class);
        server.on("/api/", exchange -> ImageServer.reply(exchange, 400, "text/plain", "[ERROR 400] Invalid key".getBytes()));
        AiFailure rejected = failure(source.search("fox", "en", 3, BUDGET));
        assertThat(rejected).isInstanceOf(AiFailure.Refusal.class);
        server.on("/api/", exchange -> ImageServer.reply(exchange, 403, "text/plain", new byte[0]));
        assertThat(failure(source.search("fox", "en", 3, BUDGET))).isInstanceOf(AiFailure.NotConfigured.class);
        server.json("/api/", "{not json");
        assertThat(failure(source.search("fox", "en", 3, BUDGET))).isInstanceOf(AiFailure.InvalidOutput.class);
        server.json("/api/", "{\"total\":0}");
        assertThat(failure(source.search("fox", "en", 3, BUDGET))).isInstanceOf(AiFailure.InvalidOutput.class);
        server.json("/api/", "{\"total\":0,\"totalHits\":0,\"hits\":[]}");
        assertThat(ok(source.search("fox", "en", 3, BUDGET))).isEmpty();
        assertThat(rejected.toString()).doesNotContain(KEY);
    }

    @Test
    void pixabayCutsTheQueryToHundredCharacters() {
        server.json("/api/", "{\"hits\":[]}");
        var source = new PixabayImageSource(provider("/api/", KEY, "", ""), http, AGENT, Clock.systemUTC());
        ok(source.search("я".repeat(250), "ru", 3, BUDGET));
        assertThat(decode(server.requests.getFirst().query())).contains("q=" + "я".repeat(100) + "&");
    }

    // --------------------------------------------------------------- Wikimedia

    @Test
    void wikimediaKeepsTheAllowedLicensesInSearchOrderAndStripsTheHtmlOfTheArtist() {
        server.json("/w/api.php", ImageServer.fixture("wikimedia.json"));
        var source = new WikimediaImageSource(provider("/w/api.php", "", "", ""), http, AGENT, Clock.systemUTC());

        List<ImageSearch.Candidate> found = ok(source.search("fox", "en", 5, BUDGET));

        String query = decode(server.requests.getFirst().query());
        assertThat(query).contains("action=query", "format=json", "generator=search", "gsrnamespace=6", "gsrsearch=filetype:bitmap fox",
                "prop=imageinfo", "iiprop=url|extmetadata|mime|size", "iiurlwidth=640");
        assertThat(server.requests.getFirst().headers().get("User-agent")).containsExactly(AGENT);
        // NC, GFDL-only, a file with Restrictions and an SVG are dropped
        assertThat(found).extracting(ImageSearch.Candidate::sourceId).containsExactly("11", "13", "16");
        ImageSearch.Candidate first = found.getFirst();
        assertThat(first.source()).isEqualTo(ImageSearch.Source.WIKIMEDIA);
        assertThat(first.title()).isEqualTo("Red fox 2");
        assertThat(first.author()).isEqualTo("Jörg Hempel & co");
        assertThat(first.license()).isEqualTo("CC BY-SA 4.0");
        assertThat(first.shareAlike()).isTrue();
        assertThat(first.licenseUrl()).isEqualTo("https://creativecommons.org/licenses/by-sa/4.0/");
        assertThat(first.sourcePageUrl()).isEqualTo("https://commons.wikimedia.org/wiki/File:Red_fox_2.jpg");
        assertThat(first.downloadUrl()).startsWith("https://upload.wikimedia.org/").endsWith("640px-Red_fox_2.jpg");
        assertThat(first.width()).isEqualTo(640);
        assertThat(found.get(1).license()).isEqualTo("Public domain");
        assertThat(found.get(1).licenseUrl()).isNull();
        assertThat(found.get(1).author()).isEmpty();
        assertThat(found.get(2).license()).isEqualTo("CC0");
        assertThat(found.get(2).shareAlike()).isFalse();
    }

    @Test
    void wikimediaAnswersForNothingFoundAndForARateLimitAreTyped() {
        var source = new WikimediaImageSource(provider("/w/api.php", "", "", ""), http, AGENT, Clock.systemUTC());
        server.json("/w/api.php", ImageServer.fixture("wikimedia-empty.json"));
        assertThat(ok(source.search("zzz", "en", 5, BUDGET))).isEmpty();
        server.json("/w/api.php", ImageServer.fixture("wikimedia-ratelimited.json"));
        assertThat(failure(source.search("zzz", "en", 5, BUDGET))).isInstanceOf(AiFailure.RateLimited.class);
        server.json("/w/api.php", "{\"error\":{\"code\":\"badvalue\"}}");
        assertThat(failure(source.search("zzz", "en", 5, BUDGET))).isInstanceOf(AiFailure.InvalidOutput.class);
        server.json("/w/api.php", "[]");
        assertThat(failure(source.search("zzz", "en", 5, BUDGET))).isInstanceOf(AiFailure.InvalidOutput.class);
    }

    // --------------------------------------------------------------- Openverse

    @Test
    void openverseExchangesTheCredentialsOnceAndKeepsTheAllowedLicenses() {
        server.json("/v1/auth_tokens/token/", ImageServer.fixture("openverse-token.json"));
        server.json("/v1/images/", ImageServer.fixture("openverse.json"));
        var source = new OpenverseImageSource(provider("/v1/", "", CLIENT_ID, CLIENT_SECRET), http, AGENT, Clock.systemUTC());

        List<ImageSearch.Candidate> found = ok(source.search("fox", "en", 5, BUDGET));
        ok(source.search("fox again", "en", 5, BUDGET));

        List<ImageServer.Recorded> tokens = server.requests.stream().filter(request -> request.path().endsWith("/token/")).toList();
        assertThat(tokens).hasSize(1);
        assertThat(tokens.getFirst().method()).isEqualTo("POST");
        assertThat(tokens.getFirst().body()).contains("grant_type=client_credentials", "client_id=" + CLIENT_ID, "client_secret=" + CLIENT_SECRET);
        ImageServer.Recorded search = server.requests.stream().filter(request -> request.path().equals("/v1/images/")).findFirst().orElseThrow();
        assertThat(decode(search.query())).contains("q=fox", "license_type=commercial", "mature=false", "page_size=30");
        assertThat(search.headers().get("Authorization")).containsExactly("Bearer tok-1");
        assertThat(search.query()).doesNotContain(CLIENT_SECRET);
        // NC and the result without an https page are dropped; the license code becomes a short name
        assertThat(found).extracting(ImageSearch.Candidate::license).containsExactly("CC BY-SA 4.0", "CC0 1.0", "Public domain");
        assertThat(found.getFirst().shareAlike()).isTrue();
        assertThat(found.getFirst().author()).isEqualTo("Ann Lee");
        assertThat(found.getFirst().sourcePageUrl()).isEqualTo("https://www.flickr.com/photos/1/2");
        assertThat(found.getFirst().downloadUrl()).isEqualTo(server.origin() + "/v1/images/4b61fb6f-0f1c-4d39-a24b-1c5e3d1b8a01/thumb/");
        assertThat(found.get(1).author()).isEmpty();
        assertThat(source.imageHosts()).containsExactly("localhost");
    }

    @Test
    void openverseRejectedCredentialsAreNotConfiguredAndAStaleTokenIsReplaced() {
        var source = new OpenverseImageSource(provider("/v1/", "", CLIENT_ID, CLIENT_SECRET), http, AGENT, Clock.systemUTC());
        server.on("/v1/auth_tokens/token/", exchange -> ImageServer.reply(exchange, 401, "application/json", "{}".getBytes()));
        AiFailure rejected = failure(source.search("fox", "en", 5, BUDGET));
        assertThat(rejected).isInstanceOf(AiFailure.NotConfigured.class);
        assertThat(rejected.toString()).doesNotContain(CLIENT_SECRET).doesNotContain(CLIENT_ID);

        server.json("/v1/auth_tokens/token/", ImageServer.fixture("openverse-token.json"));
        server.on("/v1/images/", exchange -> ImageServer.reply(exchange, 401, "application/json", "{}".getBytes()));
        assertThat(failure(source.search("fox", "en", 5, BUDGET))).isInstanceOf(AiFailure.NotConfigured.class);
        server.json("/v1/images/", ImageServer.fixture("openverse.json"));
        assertThat(ok(source.search("fox", "en", 5, BUDGET))).hasSize(3);
        // the 401 dropped the cached token: a second one was requested
        assertThat(server.requests.stream().filter(request -> request.path().endsWith("/token/")).count()).isEqualTo(3);
        server.json("/v1/auth_tokens/token/", "{\"access_token\":\"\",\"expires_in\":10}");
        var fresh = new OpenverseImageSource(provider("/v1/", "", CLIENT_ID, CLIENT_SECRET), http, AGENT, Clock.systemUTC());
        assertThat(failure(fresh.search("fox", "en", 5, BUDGET))).isInstanceOf(AiFailure.InvalidOutput.class);
    }

    // ------------------------------------------------------------- configured

    @Test
    void aSourceIsConfiguredOnlyWithItsCredentialsAndItsSwitchAndATransport() {
        Clock clock = Clock.systemUTC();
        assertThat(new PixabayImageSource(provider("/api/", KEY, "", ""), http, AGENT, clock).configured()).isTrue();
        assertThat(new PixabayImageSource(provider("/api/", "", "", ""), http, AGENT, clock).configured()).isFalse();
        assertThat(new PixabayImageSource(provider("/api/", KEY, "", ""), null, AGENT, clock).configured()).isFalse();
        assertThat(new OpenverseImageSource(provider("/v1/", "", CLIENT_ID, CLIENT_SECRET), http, AGENT, clock).configured()).isTrue();
        assertThat(new OpenverseImageSource(provider("/v1/", "", CLIENT_ID, ""), http, AGENT, clock).configured()).isFalse();
        assertThat(new OpenverseImageSource(provider("/v1/", "", CLIENT_ID, CLIENT_SECRET), null, AGENT, clock).configured()).isFalse();
        assertThat(new WikimediaImageSource(provider("/w/api.php", "", "", ""), http, AGENT, clock).configured()).isTrue();
        var off = new AiProperties.Provider(false, server.origin() + "/w/api.php", "", "", "", "", AiProperties.EgressMode.DIRECT, "", "");
        assertThat(new WikimediaImageSource(off, http, AGENT, clock).configured()).isFalse();
        assertThat(provider("/v1/", "", CLIENT_ID, CLIENT_SECRET).toString()).doesNotContain(CLIENT_ID).doesNotContain(CLIENT_SECRET);
    }

    @Test
    void licensesOfEveryFamilyAreClassified() {
        assertThat(ImageLicense.fromShortName("CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/")).isPresent();
        assertThat(ImageLicense.fromShortName("CC BY-SA 3.0 de", null).orElseThrow().shareAlike()).isTrue();
        assertThat(ImageLicense.fromShortName("cc-by-sa-2.5", null).orElseThrow().shareAlike()).isTrue();
        assertThat(ImageLicense.fromShortName("CC0", null)).isPresent();
        assertThat(ImageLicense.fromShortName("Public domain", null)).isPresent();
        assertThat(ImageLicense.fromShortName("PD-old-100", null)).isPresent();
        for (String refused : new String[] {"CC BY-NC 4.0", "CC BY-ND 2.0", "CC BY-NC-SA 3.0", "CC BY-SA-NC 3.0", "GFDL 1.2", "Fair use", "Copyrighted free use",
                "", "Attribution", "CC BY-NC"}) {
            assertThat(ImageLicense.fromShortName(refused, null)).as(refused).isEmpty();
        }
        assertThat(ImageLicense.fromCode("by-nd", "2.0", null)).isEmpty();
        assertThat(ImageLicense.fromCode("by-nc-sa", "2.0", null)).isEmpty();
        assertThat(ImageLicense.fromCode(null, null, null)).isEmpty();
        assertThat(ImageLicense.fromCode("by", "2.0", "http://not-https").orElseThrow().url()).isNull();
    }

    @Test
    void textHygieneStripsMarkupBoundsLengthAndKeepsOnlyHttpsUrls() {
        assertThat(ImageText.plain("<a href=\"x\">Jörg</a> &amp; <script>x</script>co&nbsp;&#169;&#x41;", 50)).isEqualTo("Jörg & x co ©A");
        assertThat(ImageText.plain("a  b\n\tc", 50)).isEqualTo("a b c");
        assertThat(ImageText.plain("x".repeat(500), 200)).hasSize(200);
        assertThat(ImageText.plain(null, 10)).isEmpty();
    }

    @Test
    void textHygieneReplacesInvisibleAndDirectionalControlsWithASpace() {
        // bidi override, zero-width space, line separator, a lone surrogate by entity and a private-use character
        assertThat(ImageText.plain("a\u202Eb\u200Bc\u2028d&#xD800;e\uE000f", 50)).isEqualTo("a b c d e f");
        assertThat(ImageText.plain("\u202E\u200B", 50)).isEmpty();
        assertThat(ImageText.plain("x\u2029y", 50)).isEqualTo("x y");
    }

    @Test
    void urlsStayStrict() {
        assertThat(ImageText.https("https://example.org/a")).isEqualTo("https://example.org/a");
        assertThat(ImageText.https("http://example.org/a")).isNull();
        assertThat(ImageText.https("https://user:pw@example.org/a")).isNull();
        assertThat(ImageText.https("//example.org/a")).isNull();
        assertThat(ImageText.https("https://example.org/" + "a".repeat(2_100))).isNull();
    }
}
