package app.mnema.learning.ai;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in smoke test of the real image sources; skipped unless {@code MNEMA_AI_LIVE=true}:
 * <pre>
 * MNEMA_AI_LIVE=true ./gradlew :services:learning:cleanTest :services:learning:test --tests '*ImageSearchLive*'
 * </pre>
 * Wikimedia Commons needs no key, so it always runs: one search and one safe download of the first result (the real fetcher: https, the host
 * allowlist, the address check, the size cap, the magic bytes). Pixabay and Openverse run only with {@code MNEMA_AI_PIXABAY_API_KEY} and
 * {@code MNEMA_AI_OPENVERSE_CLIENT_ID} / {@code MNEMA_AI_OPENVERSE_CLIENT_SECRET} in the environment (Openverse also needs a network that is not behind
 * its challenge, or the egress proxy); otherwise they are skipped. It asserts shapes only and never prints a key, a token or a URL.
 */
@EnabledIfEnvironmentVariable(named = "MNEMA_AI_LIVE", matches = "true")
class ImageSearchLiveTest {
    private static final String AGENT = ImageSearchSettings.defaults().userAgent();
    private static final AiProperties.Transport TRANSPORT = new AiProperties.Transport(Duration.ofSeconds(5), Duration.ofSeconds(30), 4 << 20, Duration.ofSeconds(30));

    private static ImageSearch.Image roundTrip(ImageSource source, String query, String lang, ChatHttp http) {
        AiResult<List<ImageSearch.Candidate>> result = source.search(query, lang, 5, Duration.ofSeconds(20));
        assertThat(result).as(source.provider() + " search").isInstanceOf(AiResult.Ok.class);
        List<ImageSearch.Candidate> found = ((AiResult.Ok<List<ImageSearch.Candidate>>) result).value();
        assertThat(found).as(source.provider() + " results").isNotEmpty();
        assertThat(found).allSatisfy(candidate -> {
            assertThat(candidate.license()).isNotBlank();
            assertThat(candidate.sourcePageUrl()).startsWith("https://");
            assertThat(candidate.author().length()).isLessThanOrEqualTo(200);
        });
        AiResult<ImageSearch.Image> image = new SafeImageFetcher(true, ImageAddressPolicy.Resolver.SYSTEM, AGENT)
                .fetch(http.client(), false, source.imageHosts(), found.getFirst().downloadUrl(), Duration.ofSeconds(30));
        assertThat(image).as(source.provider() + " download").isInstanceOf(AiResult.Ok.class);
        ImageSearch.Image value = ((AiResult.Ok<ImageSearch.Image>) image).value();
        assertThat(value.bytes().length).isBetween(1, SafeImageFetcher.MAX_BYTES);
        assertThat(SafeImageFetcher.magicMatches(value.mimeType(), value.bytes())).isTrue();
        return value;
    }

    @Test
    void wikimediaCommonsSearchesAndDownloadsWithoutAKey() {
        try (ChatHttp http = new ChatHttp(TRANSPORT)) {
            var provider = new AiProperties.Provider(true, "https://commons.wikimedia.org/w/api.php", "", "", "", "");
            roundTrip(new WikimediaImageSource(provider, http, AGENT, Clock.systemUTC()), "red fox", "en", http);
        }
    }

    @Test
    void pixabaySearchesAndDownloadsWithAKey() {
        String key = System.getenv("MNEMA_AI_PIXABAY_API_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank(), "MNEMA_AI_PIXABAY_API_KEY is not set");
        try (ChatHttp http = new ChatHttp(TRANSPORT)) {
            var provider = new AiProperties.Provider(true, "https://pixabay.com/api/", key, "", "", "");
            roundTrip(new PixabayImageSource(provider, http, AGENT, Clock.systemUTC()), "лиса", "ru", http);
        }
    }

    @Test
    void openverseSearchesAndDownloadsWithClientCredentials() {
        String id = System.getenv("MNEMA_AI_OPENVERSE_CLIENT_ID");
        String secret = System.getenv("MNEMA_AI_OPENVERSE_CLIENT_SECRET");
        Assumptions.assumeTrue(id != null && !id.isBlank() && secret != null && !secret.isBlank(), "MNEMA_AI_OPENVERSE_CLIENT_ID/_SECRET are not set");
        try (ChatHttp http = new ChatHttp(TRANSPORT)) {
            var provider = new AiProperties.Provider(true, "https://api.openverse.org/v1/", "", "", "", "", AiProperties.EgressMode.DIRECT, id, secret);
            roundTrip(new OpenverseImageSource(provider, http, AGENT, Clock.systemUTC()), "red fox", "en", http);
        }
    }
}
