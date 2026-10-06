package app.mnema.learning.generation;

import app.mnema.learning.ai.ResearchSettings;
import app.mnema.learning.ai.WebSearch;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** The pure parts of the research step: which queries leave the server, and which results become numbered sources. */
class ResearchExecutorTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static List<String> queries(String json, int cap) {
        Optional<List<String>> parsed = ResearchExecutor.queries(JSON.readTree(json), cap);
        assertThat(parsed).isPresent();
        return parsed.get();
    }

    @Test
    void aQueryIsRedactedAndBoundedAndOneThatIsEmptyAfterwardsIsDroppedWithoutFailingTheRun() {
        assertThat(queries("{\"queries\":[\"x\\u0020\", \"" + "я".repeat(500) + "\", \"почта ivan@example.com postgres\", \"Postgres  planner\", \"postgres PLANNER\"]}", 5))
                .containsExactly("x", "почта [email] postgres", "Postgres planner");
        assertThat(queries("{\"queries\":[\"" + "я".repeat(500) + "\"]}", 3)).isEmpty();
        assertThat(queries("{\"queries\":[\"a\",\"b\",\"c\"]}", 2)).containsExactly("a", "b");
        assertThat(ResearchExecutor.queries(JSON.readTree("{\"queries\":[1]}"), 2)).isEmpty();
    }

    @Test
    void onlyResultsTheNativeLinkProfileAcceptsAreNumberedAfterNormalization() {
        ResearchExecutor executor = new ResearchExecutor(null, null, null, null, null, null, null, null, null, ResearchSettings.defaults(), null, null);
        List<WebSearch.Result> results = List.of(result("https://ru.wikipedia.org/wiki/Москва", 1), result("https://пример.рф/", 2),
                result("https://example.org/ok", 3), result("https://example.org/ok#again", 4), result("https://127.1/", 5), result("https://example.org:0/", 6),
                result("http://example.org/", 7));

        List<ResearchRepository.Source> numbered = executor.number(results);

        assertThat(numbered).extracting(ResearchRepository.Source::url).containsExactly("https://ru.wikipedia.org/wiki/%D0%9C%D0%BE%D1%81%D0%BA%D0%B2%D0%B0",
                "https://xn--e1afmkfd.xn--p1ai/", "https://example.org/ok");
        assertThat(numbered).extracting(ResearchRepository.Source::n).containsExactly(1, 2, 3);
        assertThat(numbered).allSatisfy(source -> assertThat(source.url()).hasSizeLessThanOrEqualTo(2_048).matches("[\\x21-\\x7e]+"));
    }

    private static WebSearch.Result result(String url, int rank) {
        return new WebSearch.Result(url, "t", "s", null, WebSearch.Provider.YANDEX, 0, rank);
    }
}
