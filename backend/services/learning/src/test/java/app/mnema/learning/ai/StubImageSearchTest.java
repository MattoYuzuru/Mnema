package app.mnema.learning.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StubImageSearchTest {
    private final StubImageSearch stub = new StubImageSearch();

    private static ImageSearch.Request request(String query, int max, Set<String> exclude) {
        return new ImageSearch.Request(query, "en", max, exclude, null, 1);
    }

    private List<ImageSearch.Candidate> found(String query) {
        AiResult<List<ImageSearch.Candidate>> result = stub.search(request(query, 10, Set.of()));
        assertThat(result).isInstanceOf(AiResult.Ok.class);
        return ((AiResult.Ok<List<ImageSearch.Candidate>>) result).value();
    }

    @Test
    void aQueryGetsFourToSixStableCandidatesWithTheCc0LicenseAndAnExamplePage() {
        List<ImageSearch.Candidate> first = found("лиса зимой");

        assertThat(first).hasSizeBetween(4, 6);
        assertThat(found("лиса зимой")).isEqualTo(first);
        assertThat(first).allSatisfy(candidate -> {
            assertThat(candidate.source()).isEqualTo(ImageSearch.Source.STUB);
            assertThat(candidate.license()).isEqualTo("CC0 1.0");
            assertThat(candidate.sourcePageUrl()).startsWith("https://example.org/stub/");
            assertThat(candidate.licenseUrl()).startsWith("https://");
        });
        assertThat(first.stream().map(ImageSearch.Candidate::key).distinct()).hasSameSizeAs(first);
        assertThat(found("другой запрос").getFirst().key()).isNotEqualTo(first.getFirst().key());
    }

    @Test
    void knownKeysAreExcludedAndTheLimitHolds() {
        List<ImageSearch.Candidate> all = found("fox");
        var rest = ((AiResult.Ok<List<ImageSearch.Candidate>>) stub.search(request("fox", 10, Set.of(all.getFirst().key())))).value();
        assertThat(rest).hasSize(all.size() - 1).doesNotContain(all.getFirst());
        var two = ((AiResult.Ok<List<ImageSearch.Candidate>>) stub.search(request("fox", 2, Set.of()))).value();
        assertThat(two).hasSize(2);
    }

    @Test
    void fetchDrawsADistinctValidPngPerCandidate() {
        List<ImageSearch.Candidate> all = found("fox");
        var first = ((AiResult.Ok<ImageSearch.Image>) stub.fetch(all.get(0))).value();
        var second = ((AiResult.Ok<ImageSearch.Image>) stub.fetch(all.get(1))).value();

        assertThat(first.mimeType()).isEqualTo("image/png");
        assertThat(SafeImageFetcher.magicMatches("image/png", first.bytes())).isTrue();
        assertThat(first.bytes()).isNotEqualTo(second.bytes());
        assertThat(((AiResult.Ok<ImageSearch.Image>) stub.fetch(all.get(0))).value().bytes()).isEqualTo(first.bytes());
        // the PNG decodes (the JDK's own reader) at the size the candidate says
        try {
            var decoded = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(first.bytes()));
            assertThat(decoded.getWidth()).isEqualTo(all.get(0).width());
            assertThat(decoded.getHeight()).isEqualTo(all.get(0).height());
        } catch (java.io.IOException unreadable) {
            throw new AssertionError(unreadable);
        }
        var foreign = stub.fetch(new ImageSearch.Candidate(ImageSearch.Source.PIXABAY, "1", "", "", "x", null, "https://example.org", false, 1, 1, "https://x/y.jpg"));
        assertThat(foreign).isInstanceOf(AiResult.Failed.class);
    }

    @Test
    void theMarkersSimulateNothingLicensedAndEverySourceDown() {
        assertThat(found("лиса " + StubImageSearch.NONE)).isEmpty();
        var down = stub.search(request("лиса " + StubImageSearch.DOWN, 5, Set.of()));
        assertThat(((AiResult.Failed<List<ImageSearch.Candidate>>) down).failure()).isInstanceOf(AiFailure.Transient.class);
    }
}
