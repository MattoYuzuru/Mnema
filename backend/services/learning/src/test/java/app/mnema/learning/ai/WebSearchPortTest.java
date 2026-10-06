package app.mnema.learning.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The port's own rules: how a query is bounded, which URLs are link targets, what makes two URLs the same source, the caps and the Stub. */
class WebSearchPortTest {
    @Test
    void aQueryIsCollapsedAndBoundedToFortyWordsAndFourHundredCharacters() {
        var request = new WebSearch.Request(List.of("  planner \n  of   postgres ", "слово ".repeat(60), "x".repeat(300) + " " + "y".repeat(300)), "RU", 5, "ru", null, 0);
        assertThat(request.queries().get(0)).isEqualTo("planner of postgres");
        assertThat(request.queries().get(1).split(" ")).hasSize(40);
        // the 400-character bound cuts between words
        assertThat(request.queries().get(2)).isEqualTo("x".repeat(300));
        // a first word that alone is over 400 characters leaves nothing: such a query is refused, not cut in the middle of a word
        assertThatThrownBy(() -> new WebSearch.Request(List.of("x".repeat(500)), "en", 5, null, null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(request.lang()).isEqualTo("ru");
        assertThat(request.region()).isEqualTo("RU");
        assertThat(request.attempt()).isEqualTo(1);
    }

    @Test
    void theRequestIsStrict() {
        assertThatThrownBy(() -> new WebSearch.Request(List.of(), "en", 5, null, null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebSearch.Request(null, "en", 5, null, null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebSearch.Request(List.of(" "), "en", 5, null, null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebSearch.Request(java.util.Collections.nCopies(16, "q"), "en", 5, null, null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebSearch.Request(List.of("q"), "en", 0, null, null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebSearch.Request(List.of("q"), "en", 21, null, null, 1)).isInstanceOf(IllegalArgumentException.class);
        // a language that is not a two-letter code is English, a region that is not a country is none
        var odd = new WebSearch.Request(List.of("q"), "zh-Hans", 5, "Russia", UUID.randomUUID(), 3);
        assertThat(odd.lang()).isEqualTo("en");
        assertThat(odd.region()).isNull();
    }

    @Test
    void onlyAbsoluteHttpsUrlsWithoutUserInfoAreLinkTargets() {
        assertThat(WebSearch.acceptable("https://example.org/a?b=c#frag")).isEqualTo("https://example.org/a?b=c");
        assertThat(WebSearch.acceptable("HTTPS://Example.org/Path")).isEqualTo("https://example.org/Path");
        for (String bad : new String[] {null, "", "http://example.org/", "ftp://example.org/", "javascript:alert(1)", "//example.org/", "https:///path",
                "https://user@example.org/", "https://example.org/a b", "https://example.org/<x>", "https://example.org/\"", "mailto:a@b.c",
                "https://example.org/" + "a".repeat(WebSearch.MAX_URL)}) {
            assertThat(WebSearch.acceptable(bad)).as(String.valueOf(bad)).isNull();
        }
    }

    @Test
    void twoUrlsOfOneSourceShareAKey() {
        String canonical = WebSearch.key("https://example.org/page");
        assertThat(WebSearch.key("https://www.example.org/page/")).isEqualTo(canonical);
        assertThat(WebSearch.key("https://EXAMPLE.org:443/page?utm_source=x&fbclid=1&gclid=2&yclid=3")).isEqualTo(canonical);
        assertThat(WebSearch.key("https://example.org/page?id=1&utm_medium=m")).isEqualTo("https://example.org/page?id=1");
        assertThat(WebSearch.key("https://example.org/page?id=1")).isNotEqualTo(WebSearch.key("https://example.org/page?id=2"));
        assertThat(WebSearch.key("https://example.org:8443/page")).isNotEqualTo(canonical);
        assertThat(WebSearch.key("https://example.org")).isEqualTo(WebSearch.key("https://example.org/"));
        assertThat(WebSearch.key("https://example.org/a")).isNotEqualTo(WebSearch.key("https://example.org/b"));
    }

    @Test
    void theRequestCapsByEffortAreTwoSixAndThreeBoundedByTheGlobalCap() {
        assertThat(ResearchSettings.cap("SHORT", 15)).isZero();
        assertThat(ResearchSettings.cap("MEDIUM", 15)).isEqualTo(2);
        assertThat(ResearchSettings.cap("DETAILED", 15)).isEqualTo(6);
        assertThat(ResearchSettings.cap("AUTO", 15)).isEqualTo(3);
        assertThat(ResearchSettings.cap(null, 15)).isZero();
        assertThat(ResearchSettings.cap("DETAILED", 4)).isEqualTo(4);
        assertThat(ResearchSettings.cap("DETAILED", 0)).isZero();
        assertThat(ResearchSettings.cap("DETAILED", -3)).isZero();
        assertThat(ResearchSettings.defaults().cap("DETAILED")).isEqualTo(6);
    }

    @Test
    void theSettingsRefuseWhatCannotWork() {
        var defaults = ResearchSettings.defaults();
        assertThatThrownBy(() -> new ResearchSettings(51, 30, 5, defaults.callTimeout(), defaults.deadline(), "", "225", defaults.yandexRubPerRequest(),
                defaults.perplexityUsdPerRequest())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResearchSettings(15, 0, 5, defaults.callTimeout(), defaults.deadline(), "", "225", defaults.yandexRubPerRequest(),
                defaults.perplexityUsdPerRequest())).isInstanceOf(IllegalArgumentException.class);
        // The HTTP envelope and native client accept at most 30 sources, even if the storage envelope has more room.
        assertThatThrownBy(() -> new ResearchSettings(15, 31, 5, defaults.callTimeout(), defaults.deadline(), "", "225", defaults.yandexRubPerRequest(),
                defaults.perplexityUsdPerRequest())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResearchSettings(15, 30, 5, Duration.ZERO, defaults.deadline(), "", "225", defaults.yandexRubPerRequest(),
                defaults.perplexityUsdPerRequest())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResearchSettings(15, 30, 5, defaults.callTimeout(), Duration.ofSeconds(1), "", "225", defaults.yandexRubPerRequest(),
                defaults.perplexityUsdPerRequest())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResearchSettings(15, 30, 5, defaults.callTimeout(), defaults.deadline(), "not a folder!", "225",
                defaults.yandexRubPerRequest(), defaults.perplexityUsdPerRequest())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResearchSettings(15, 30, 5, defaults.callTimeout(), defaults.deadline(), "", "Moscow", defaults.yandexRubPerRequest(),
                defaults.perplexityUsdPerRequest())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResearchSettings(15, 30, 5, defaults.callTimeout(), defaults.deadline(), "", "225", java.math.BigDecimal.valueOf(-1),
                defaults.perplexityUsdPerRequest())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theStubAnswersThreeResultsPerQueryOnTheExampleHostAndItsMarkersFailOrEmptyIt() {
        var stub = new StubWebSearch();
        var request = new WebSearch.Request(List.of("первый запрос", "второй запрос"), "ru", 5, null, null, 1);
        var answer = ((AiResult.Ok<WebSearch.Answer>) stub.search(request)).value();

        assertThat(answer.requests()).isEqualTo(2);
        assertThat(answer.costMicros()).isZero();
        assertThat(answer.results()).hasSize(6);
        assertThat(answer.results()).extracting(WebSearch.Result::url).containsExactly("https://example.org/stub/research/1", "https://example.org/stub/research/2",
                "https://example.org/stub/research/3", "https://example.org/stub/research/4", "https://example.org/stub/research/5",
                "https://example.org/stub/research/6");
        assertThat(answer.results()).allSatisfy(result -> {
            assertThat(WebSearch.acceptable(result.url())).isEqualTo(result.url());
            assertThat(result.title()).startsWith("Источник ");
            assertThat(result.provider()).isEqualTo(WebSearch.Provider.STUB);
        });
        assertThat(answer.results()).extracting(WebSearch.Result::queryIndex).containsExactly(0, 0, 0, 1, 1, 1);
        assertThat(stub.search(request)).isEqualTo(stub.search(request));
        // fewer results than three when fewer are asked
        var two = ((AiResult.Ok<WebSearch.Answer>) stub.search(new WebSearch.Request(List.of("q"), "ru", 2, null, null, 1))).value();
        assertThat(two.results()).hasSize(2);

        var down = stub.search(new WebSearch.Request(List.of("q", "q " + StubWebSearch.DOWN), "ru", 5, null, null, 1));
        assertThat(down).isInstanceOf(AiResult.Failed.class);
        var empty = ((AiResult.Ok<WebSearch.Answer>) stub.search(new WebSearch.Request(List.of("q " + StubWebSearch.EMPTY), "ru", 5, null, null, 1))).value();
        assertThat(empty.results()).isEmpty();
        // an empty search is still a request that was made
        assertThat(empty.requests()).isEqualTo(1);
        assertThat(stub.configured()).isTrue();
    }

    @Test
    void anInternationalizedOrNonAsciiUrlIsNormalizedToAsciiNotDropped() {
        assertThat(WebSearch.acceptable("https://ru.wikipedia.org/wiki/Москва")).isEqualTo("https://ru.wikipedia.org/wiki/%D0%9C%D0%BE%D1%81%D0%BA%D0%B2%D0%B0");
        assertThat(WebSearch.acceptable("https://президент.рф/новости?q=привет&a=1#x")).isEqualTo("https://xn--d1abbgf6aiiy.xn--p1ai/%D0%BD%D0%BE%D0%B2%D0%BE%D1%81%D1%82%D0%B8?q=%D0%BF%D1%80%D0%B8%D0%B2%D0%B5%D1%82&a=1");
        // existing escapes stay, characters a URI may not carry are encoded
        assertThat(WebSearch.acceptable("https://example.org/a%20b|c")).isEqualTo("https://example.org/a%20b%7Cc");
        assertThat(WebSearch.acceptable(" https://example.org/x \n")).isEqualTo("https://example.org/x");
        // a host that cannot be an ASCII name, and a URL that stays over the bound after encoding, are dropped
        assertThat(WebSearch.acceptable("https://" + "я".repeat(70) + ".рф/")).isNull();
        assertThat(WebSearch.acceptable("https://example.org/" + "я".repeat(700))).isNull();
        assertThat(WebSearch.acceptable("https://example.org/" + "a".repeat(WebSearch.MAX_URL - 20))).isNotNull();
        assertThat(WebSearch.acceptable("https://example.org/" + "a".repeat(WebSearch.MAX_URL))).isNull();
    }

    @Test
    void everyOutgoingQueryIsRedactedAndOneThatIsEmptyAfterwardsIsDropped() {
        var request = new WebSearch.Request(List.of("write to ivan@example.com about postgres", "card 4111 1111 1111 1111 limits", "plain", "x".repeat(500)), "en", 5, null, null, 1);
        assertThat(request.queries()).containsExactly("write to [email] about postgres", "card [card] limits", "plain");
        assertThat(WebSearch.Request.clean("x".repeat(500))).isEmpty();
        assertThatThrownBy(() -> new WebSearch.Request(List.of("x".repeat(500), " "), "en", 5, null, null, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
