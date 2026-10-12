package app.mnema.learning.community;

import app.mnema.learning.community.CatalogSeed.Deck;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Community/2 (#449), the search of {@code GET /api/public/community/decks?q=}: the fixtures of the contract in four languages, the typo, the ranking, the cursor and the limits. The rows are
 * written directly ({@link CatalogSeed}); the plans are in {@code CatalogScaleIntegrationTest}.
 */
class CatalogSearchHttpIntegrationTest extends CatalogHttpTest {
    @org.springframework.beans.factory.annotation.Autowired private app.mnema.learning.library.TopicDirectory directory;

    @Test
    void aQueryNamesTopicsThroughTheirSynonymsAndExtendsItselfWithThem() {
        var named = directory.named(app.mnema.learning.library.AliasNormalizer.normalize("Испанский"));
        assertThat(named.topics()).containsExactly("spanish");
        assertThat(named.texts()).contains("Испанский", "Spanish", "espanol");
        assertThat(directory.named("espanol").topics()).containsExactly("spanish");
        assertThat(directory.named(app.mnema.learning.library.AliasNormalizer.normalize("Español")).topics()).containsExactly("spanish");
        assertThat(directory.named("испанский язык").topics()).as("a phrase").containsExactly("spanish");
        assertThat(directory.named("выучить испанский быстро").topics()).as("a word inside a longer query").containsExactly("spanish");
        assertThat(directory.named("языки").topics()).containsExactly("languages");
        assertThat(directory.named("глаголы").topics()).isEmpty();
        assertThat(directory.named("").topics()).isEmpty();
        assertThat(directory.named("английский испанский немецкий французский итальянский").topics()).hasSize(app.mnema.learning.library.TopicDirectory.MAX_NAMED);
        assertThat(directory.named("английский испанский").texts()).doesNotContain("Португальский");
        assertThat(directory.find("spanish")).hasValueSatisfying(entry -> assertThat(entry.parentId()).isEqualTo("languages"));
        assertThat(directory.childrenOf("languages")).contains("spanish", "english").doesNotContain("languages");
        assertThat(directory.childrenOf("spanish")).isEmpty();
        assertThat(directory.entries().getFirst().id()).isEqualTo("languages");
    }

    /** The decks of {@code decks.fixtures} in the contract. */
    private List<Deck> fixtures() {
        return seed.insertAll(List.of(
                CatalogSeed.deck("Испанские глаголы").topic("spanish").content("ru").target("es").level("A1").tags("глаголы"),
                CatalogSeed.deck("Spanish verbs for travel").topic("spanish").content("en").target("es").level("A2"),
                CatalogSeed.deck("Español para principiantes").topic("spanish").content("es").target("es").level("A1"),
                CatalogSeed.deck("Английские фразовые глаголы").topic("english").content("ru").target("en").level("B1"),
                CatalogSeed.deck("日本語のひらがな").topic("japanese").content("ja").target("ja").level("A1"),
                CatalogSeed.deck("SQL для собеседований").topic("sql-data").content("ru")));
    }

    private JsonNode search(String text) {
        return ok(get(decks("q", text)));
    }

    @Test
    void theContractFixturesFindTheExpectedDecksInRussianEnglishSpanishAndJapanese() {
        fixtures();
        for (String text : List.of("испанский", "Spanish", "espanol", "Español", "ИСПАНСКИЙ", "  испанский  ")) {
            assertThat(titles(search(text))).as(text).containsExactlyInAnyOrder("Испанские глаголы", "Spanish verbs for travel", "Español para principiantes");
        }
        assertThat(titles(search("ひらがな"))).containsExactly("日本語のひらがな");
        assertThat(titles(search("sql"))).containsExactly("SQL для собеседований");
        assertThat(titles(search("SQL"))).containsExactly("SQL для собеседований");
        // a word of the Russian stem: «глаголы» finds both Russian decks by their titles
        assertThat(titles(search("глагол"))).containsExactlyInAnyOrder("Испанские глаголы", "Английские фразовые глаголы");
        // an unknown word finds nothing, and says so
        JsonNode nothing = search("кракозябра");
        assertThat(nothing).isEqualTo(CONTRACT.path("decks").path("nothing"));
    }

    @Test
    void aTypoStillFindsTheDeckAndPutsItFirst() {
        fixtures();
        JsonNode page = search("испнские глаголы");
        assertThat(titles(page)).first().isEqualTo("Испанские глаголы");
        assertThat(page.path("total").intValue()).isEqualTo(titles(page).size());
    }

    @Test
    void theAnswerHasTheShapeOfTheContractSearchExample() {
        fixtures();
        JsonNode page = search("испанский");
        assertThat(page.propertyNames()).containsExactly("decks", "total", "totalCapped", "nextCursor");
        assertThat(page.path("total").intValue()).isEqualTo(3);
        assertThat(page.path("totalCapped").booleanValue()).isFalse();
        assertThat(page.path("nextCursor").isNull()).isTrue();
        JsonNode card = page.path("decks").get(0);
        assertThat(card.propertyNames()).containsExactlyElementsOf(CONTRACT.path("card").propertyNames());
        // the example of the request in the contract is exactly this query
        assertThat(CONTRACT.path("decks").path("search").path("request").stringValue(null)).isEqualTo("GET /api/public/community/decks?q=%D0%B8%D1%81%D0%BF%D0%B0%D0%BD%D1%81%D0%BA%D0%B8%D0%B9&limit=24");
        assertThat(CONTRACT.path("decks").path("search").path("response").path("total").intValue()).isEqualTo(3);
        assertThat(get(CONTRACT.path("decks").path("search").path("request").stringValue(null).substring("GET ".length()).replace("/api", "")).statusCode()).isEqualTo(200);
    }

    @Test
    void aTopicNamedByTheQueryExtendsItAndATopLevelTopicIncludesItsChildren() {
        // «Языки» names the top-level topic: its children's decks are found without a word of their titles in the query
        seed.insertAll(List.of(CatalogSeed.deck("Заметки о чём угодно").topic("german"), CatalogSeed.deck("Другая колода про числа").topic("math")));
        assertThat(titles(search("языки"))).containsExactly("Заметки о чём угодно");
        assertThat(titles(search("german"))).containsExactly("Заметки о чём угодно");
        assertThat(titles(search("немецкий язык"))).containsExactly("Заметки о чём угодно");
        // a deck of another topic is never pulled in by the topic extension
        assertThat(titles(search("deutsch"))).containsExactly("Заметки о чём угодно");
    }

    @Test
    void theFiltersNarrowASearchAndComposeWithTheText() {
        fixtures();
        assertThat(titles(ok(get(decks("q", "испанский", "level", "A2"))))).containsExactly("Spanish verbs for travel");
        assertThat(titles(ok(get(decks("q", "испанский", "learn", "es", "level", "A1"))))).containsExactlyInAnyOrder("Испанские глаголы", "Español para principiantes");
        assertThat(titles(ok(get(decks("q", "испанский", "topic", "english"))))).isEmpty();
        assertThat(titles(ok(get(decks("q", "глаголы", "topic", "languages"))))).containsExactlyInAnyOrder("Испанские глаголы", "Английские фразовые глаголы");
    }

    @Test
    void theRelevancePrefersTheTitleAndPopularityAndLowersAnotherLanguage() {
        List<Deck> found = seed.insertAll(List.of(
                CatalogSeed.deck("Лисы").description("Первая").content("ru").code("1111111111"),
                CatalogSeed.deck("Лисы").description("Вторая").content("ru").code("2222222222"),
                CatalogSeed.deck("Лисы foxes").description("Третья").content("en").code("3333333333"),
                CatalogSeed.deck("Совсем другое").description("Здесь упоминаются лисы один раз").content("ru").code("4444444444")));
        // popularity percentile: the first and the third are popular, the deck with a description hit only is the most popular of all
        seed.generation(1, Instant.now(), List.of(
                new CatalogSeed.Rank(found.get(0).id(), 2, 2, 1, 0.9, 0, 0), new CatalogSeed.Rank(found.get(1).id(), 4, 4, 1, 0.1, 0, 0),
                new CatalogSeed.Rank(found.get(2).id(), 3, 3, 1, 0.9, 0, 0), new CatalogSeed.Rank(found.get(3).id(), 1, 1, 1, 1.0, 0, 0)));
        // same title: the more popular first; the English deck pays 15 % for its language but its popularity keeps it above the unpopular twin;
        // a description hit is below every title hit even for the most popular deck
        assertThat(codes(search("лисы"))).containsExactly("1111111111", "3333333333", "2222222222", "4444444444");
    }

    @Test
    void aSearchPagesWithoutDuplicatesOrLossesAndStopsAtTheCandidateCap() {
        List<CatalogSeed.Spec> specs = new ArrayList<>();
        for (int index = 0; index < 33; index++) specs.add(CatalogSeed.deck("Глаголы " + (index % 3 == 0 ? "очень " : "") + "часть " + index).description("Глагол номер " + index));
        List<Deck> created = seed.insertAll(specs);
        seed.rankInOrder(1, Instant.now(), created);
        JsonNode all = ok(get(decks("q", "глаголы", "limit", "48")));
        assertThat(codes(all)).hasSize(33);
        assertThat(all.path("total").intValue()).isEqualTo(33);
        assertThat(all.path("nextCursor").isNull()).isTrue();

        List<String> paged = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = ok(get(decks("q", "глаголы", "limit", "7") + (cursor == null ? "" : "&cursor=" + cursor)));
            paged.addAll(codes(page));
            assertThat(page.path("total").intValue()).as("every page carries the same total").isEqualTo(33);
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").stringValue(null);
            pages++;
        } while (cursor != null && pages < 20);
        assertThat(pages).isEqualTo(5);
        assertThat(paged).hasSize(33).doesNotHaveDuplicates().containsExactlyElementsOf(codes(all));
    }

    @Test
    void aSearchIsCappedAtTwoHundredCandidates() {
        List<CatalogSeed.Spec> specs = new ArrayList<>();
        for (int index = 0; index < 230; index++) specs.add(CatalogSeed.deck("Слова " + index).description("Слова на каждый день"));
        seed.insertAll(specs);
        JsonNode page = ok(get(decks("q", "слова", "limit", "48")));
        assertThat(page.path("total").intValue()).isEqualTo(200);
        assertThat(page.path("totalCapped").booleanValue()).isTrue();
        Set<String> seen = new HashSet<>(codes(page));
        String cursor = page.path("nextCursor").stringValue(null);
        while (cursor != null) {
            JsonNode next = ok(get(decks("q", "слова", "limit", "48", "cursor", cursor)));
            codes(next).forEach(code -> assertThat(seen.add(code)).as("no duplicate").isTrue());
            cursor = next.path("nextCursor").isNull() ? null : next.path("nextCursor").stringValue(null);
        }
        assertThat(seen).hasSize(200);
    }

    @Test
    void aSearchCursorIsBoundToItsQueryAndToAKeptGeneration() {
        List<CatalogSeed.Spec> specs = new ArrayList<>();
        for (int index = 0; index < 12; index++) specs.add(CatalogSeed.deck("Глаголы " + index));
        List<Deck> created = seed.insertAll(specs);
        seed.rankInOrder(1, Instant.now(), created);
        JsonNode first = ok(get(decks("q", "глаголы", "limit", "5")));
        String cursor = first.path("nextCursor").stringValue(null);
        assertThat(cursor).isNotNull();
        // another query, another filter, another list and garbage are 400
        problem(get(decks("q", "слова", "limit", "5", "cursor", cursor)), 400, "INVALID_REQUEST");
        problem(get(decks("q", "глаголы", "level", "A1", "limit", "5", "cursor", cursor)), 400, "INVALID_REQUEST");
        problem(get(decks("limit", "5", "cursor", cursor)), 400, "INVALID_REQUEST");
        problem(get(decks("q", "глаголы", "cursor", "not-a-cursor!")), 400, "INVALID_REQUEST");
        problem(get(decks("q", "глаголы", "cursor", cursor.substring(0, cursor.length() - 3))), 400, "INVALID_REQUEST");
        problem(get(decks("q", "глаголы", "cursor", "eyJrIjoicG9wdWxhciJ9")), 400, "INVALID_REQUEST");
        // the cursor holds a generation, the relevance in millionths and a public code: no score, popularity, rank or deck id
        JsonNode decoded = cursor(cursor);
        assertThat(decoded.propertyNames()).containsOnly("v", "k", "h", "g", "p", "c");
        assertThat(decoded.path("c").stringValue(null)).matches("[1-9A-HJ-NP-Za-km-z]{10}");
        assertThat(decoded.path("p").longValue()).isBetween(1L, 1_000_000L);
        for (Deck deck : created) assertThat(decoded.toString()).doesNotContain(deck.id().toString());
        // the same query is fine, and keeps working after the next generation arrives (the old one is kept for 24 hours)
        assertThat(ok(get(decks("q", "глаголы", "limit", "5", "cursor", cursor))).path("decks")).hasSize(5);
        seed.rankInOrder(2, Instant.now(), created.reversed());
        assertThat(ok(get(decks("q", "глаголы", "limit", "5", "cursor", cursor))).path("decks")).hasSize(5);
        // an expired generation is 412: the client restarts the list
        jdbc.sql("DELETE FROM app_learning.rank_generation WHERE generation = 1").update();
        problem(get(decks("q", "глаголы", "limit", "5", "cursor", cursor)), 412, "VERSION_CONFLICT");
        // a fresh list starts again
        assertThat(ok(get(decks("q", "глаголы", "limit", "5"))).path("decks")).hasSize(5);
    }

    @Test
    void stopWordsOperatorsAndPunctuationAreASearchNotAnError() {
        fixtures();
        for (String odd : List.of("для", "the", "на и в", "!!!", "-", "\"\"", "or", "\"испанские глаголы\"", "испанские -глаголы", "испанские or sql", "'; DROP TABLE x; --", "%", "_", "\\", "<script>", "a".repeat(99),
                "🙂", "ё", "Ёлка", "ﬁ", "٣٤٥", "@@", "(", ")", "испанские &", ":*", "1e999")) {
            HttpResponse<String> response = get(decks("q", odd));
            assertThat(response.statusCode()).as(odd + " " + response.body()).isEqualTo(200);
            assertThat(json(response).path("total").intValue()).isEqualTo(json(response).path("decks").size());
        }
        // an exclusion and an alternative are understood by the text search
        assertThat(titles(search("испанские or sql"))).contains("Испанские глаголы", "SQL для собеседований");
        assertThat(titles(search("\"испанские глаголы\""))).contains("Испанские глаголы");
    }

    @Test
    void theQueryIsValidatedBeforeAnythingIsRead() {
        fixtures();
        String hundred = "а".repeat(100);
        assertThat(get(decks("q", hundred)).statusCode()).isEqualTo(200);
        problem(get(decks("q", hundred + "б")), 400, "INVALID_REQUEST");
        // the length is in code points, not in UTF-16 units: 100 emoji fit, 101 do not
        assertThat(get(decks("q", "😀".repeat(100))).statusCode()).isEqualTo(200);
        problem(get(decks("q", "😀".repeat(101))), 400, "INVALID_REQUEST");
        // trimmed: 100 letters and blanks around them are fine; an empty (or blank) text is a browse
        assertThat(get(decks("q", "  " + hundred + "  ")).statusCode()).isEqualTo(200);
        assertThat(ok(get(decks("q", "   "))).path("decks")).isEmpty();
        // with a text the order is the relevance: a sort next to it is an error, even a valid one
        problem(get(decks("q", "испанский", "sort", "popular")), 400, "INVALID_REQUEST");
        problem(get(decks("q", "испанский", "sort", "nonsense")), 400, "INVALID_REQUEST");
        problem(get("/public/community/decks?q=a&q=b"), 400, "INVALID_REQUEST");
        problem(get(decks("q", "a\u0000b")), 400, "INVALID_REQUEST");
        problem(get(decks("q", "a\nb")), 400, "INVALID_REQUEST");
    }

    @Test
    void theSearchTextIsNeverInAnyLogLine() {
        fixtures();
        String secret = "очень-секретный-запрос-" + System.nanoTime();
        var root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        root.addAppender(appender);
        try {
            get(decks("q", secret));
            get(decks("q", secret, "sort", "popular"));
            get(decks("q", secret, "cursor", "broken"));
            get(decks("q", secret + "x".repeat(200)));
            get("/public/community/decks?q=" + java.net.URLEncoder.encode(secret, java.nio.charset.StandardCharsets.UTF_8) + "&q=again");
        } finally {
            root.detachAppender(appender);
        }
        for (var event : List.copyOf(appender.list)) {
            assertThat(event.getFormattedMessage()).as(event.getLoggerName()).doesNotContain(secret);
            assertThat(String.valueOf(event.getMDCPropertyMap())).doesNotContain(secret);
            if (event.getThrowableProxy() != null) assertThat(String.valueOf(event.getThrowableProxy().getMessage())).doesNotContain(secret);
        }
    }

    @Test
    void headIsAnsweredLikeGetWithoutABody() throws Exception {
        fixtures();
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/api" + decks("q", "испанский"))).method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> head = java.net.http.HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.body()).isEmpty();
        assertThat(head.headers().firstValue("cache-control")).contains("no-store");
    }
}
