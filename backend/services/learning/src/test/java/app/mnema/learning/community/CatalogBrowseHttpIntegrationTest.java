package app.mnema.learning.community;

import app.mnema.learning.community.CatalogSeed.Deck;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Community/2 (#449), browse: {@code GET /api/public/community/decks} without a text. The four sorts, every filter and their composition, the totals and their cap, the keyset
 * cursors (no duplicates or losses), the 400 table and the privacy of the cards.
 */
class CatalogBrowseHttpIntegrationTest extends CatalogHttpTest {
    private static final Instant NOW = Instant.now();

    private List<Deck> numbered(int count) {
        List<CatalogSeed.Spec> specs = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            specs.add(CatalogSeed.deck("Колода " + index).listedAt(NOW.minus(Duration.ofDays(100 - index))).updatedAt(NOW.minus(Duration.ofDays(200 - index * 3L))));
        }
        return seed.insertAll(specs);
    }

    private List<String> walk(String path, int limit) {
        List<String> all = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = ok(get(path + (path.contains("?") ? "&" : "?") + "limit=" + limit + (cursor == null ? "" : "&cursor=" + cursor)));
            all.addAll(codes(page));
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").stringValue(null);
            assertThat(++pages).isLessThan(100);
        } while (cursor != null);
        return all;
    }

    @Test
    void theBrowseExampleOfTheContractIsExecutedAsItIsWritten() {
        List<CatalogSeed.Spec> specs = new ArrayList<>();
        for (int index = 0; index < 30; index++) specs.add(CatalogSeed.deck("Языковая " + index).topic(index % 2 == 0 ? "spanish" : "english").media(CatalogMedia.AUDIO.bit()).listedAt(NOW.minusSeconds(index)));
        List<Deck> decks = seed.insertAll(specs);
        seed.rankInOrder(1, NOW, decks);
        JsonNode example = CONTRACT.path("decks").path("browse");
        String request = example.path("request").stringValue(null);
        assertThat(request).isEqualTo("GET /api/public/community/decks?topic=languages&media=audio&sort=popular&limit=24");
        String path = request.substring("GET /api".length());
        JsonNode page = ok(get(path));
        assertThat(page.propertyNames()).containsExactlyElementsOf(example.path("response").propertyNames());
        assertThat(page.path("decks")).hasSize(24);
        assertThat(page.path("total").intValue()).isEqualTo(30);
        assertThat(page.path("totalCapped").booleanValue()).isFalse();
        assertThat(page.path("nextCursor").stringValue(null)).isNotBlank();
        assertThat(ok(get(path + "&cursor=" + page.path("nextCursor").stringValue(null))).path("decks")).hasSize(6);
    }

    @Test
    void popularAndLearningFollowTheCurrentGenerationAndNewAndUpdatedFollowTheirTimes() {
        List<Deck> decks = numbered(10);
        // generation 1 ranks them by number; generation 2 (current) reverses them; learning order is by learning rank
        seed.rankInOrder(1, NOW.minus(Duration.ofHours(2)), decks);
        List<Deck> reversed = decks.reversed();
        seed.generation(2, NOW.minus(Duration.ofHours(1)), IntStream.range(0, 10).mapToObj(index ->
                new CatalogSeed.Rank(reversed.get(index).id(), index + 1, 10 - index, 1.0 / (index + 1), 0.5, 0, 0)).toList());
        List<String> popular = walk("/public/community/decks?sort=popular", 4);
        assertThat(popular).containsExactlyElementsOf(reversed.stream().map(Deck::code).toList());
        // the default sort is popular
        assertThat(walk("/public/community/decks", 4)).containsExactlyElementsOf(popular);
        assertThat(walk("/public/community/decks?sort=learning", 3)).containsExactlyElementsOf(decks.stream().map(Deck::code).toList());
        // «Новое»: the newest entrant first; «Обновлённые»: the most recent publication first
        assertThat(walk("/public/community/decks?sort=new", 3)).containsExactlyElementsOf(reversed.stream().map(Deck::code).toList());
        assertThat(walk("/public/community/decks?sort=updated", 4)).containsExactlyElementsOf(reversed.stream().map(Deck::code).toList());
    }

    @Test
    void aDeckTheGenerationDoesNotKnowYetIsInNewAndUpdatedOnlyAndNothingIsRankedBeforeTheFirstGeneration() {
        List<Deck> decks = numbered(5);
        assertThat(ok(get("/public/community/decks?sort=popular")).path("decks")).isEmpty();
        assertThat(ok(get("/public/community/decks?sort=learning")).path("total").intValue()).isZero();
        assertThat(codes(ok(get("/public/community/decks?sort=new")))).hasSize(5);
        seed.rankInOrder(1, NOW, decks.subList(0, 3));
        Deck late = seed.insert(CatalogSeed.deck("Опоздавшая").listedAt(NOW));
        assertThat(walk("/public/community/decks?sort=popular", 10)).containsExactlyElementsOf(decks.subList(0, 3).stream().map(Deck::code).toList()).doesNotContain(late.code());
        assertThat(walk("/public/community/decks?sort=new", 10)).contains(late.code()).hasSize(6);
        assertThat(walk("/public/community/decks?sort=updated", 10)).contains(late.code());
    }

    @Test
    void theFiltersSelectAndCompose() {
        Instant old = NOW.minus(Duration.ofDays(100));
        List<Deck> decks = seed.insertAll(List.of(
                CatalogSeed.deck("А").topic("spanish").target("es").level("A1").items(10).media(CatalogMedia.IMAGE.bit()).mechanics(CatalogMechanic.LISTENING.bit()),
                CatalogSeed.deck("Б").topic("spanish").target("es").level("B2").items(49).media(CatalogMedia.IMAGE.bit() | CatalogMedia.AUDIO.bit())
                        .mechanics(CatalogMechanic.LISTENING.bit() | CatalogMechanic.CHOICE.bit()).updatedAt(old),
                CatalogSeed.deck("В").topic("english").target("en").level("A1").items(50).media(CatalogMedia.VIDEO.bit()).mechanics(CatalogMechanic.WRITTEN.bit()),
                CatalogSeed.deck("Г").topic("sql-data").items(300).level("INTERMEDIATE").mechanics(CatalogMechanic.MATCHING.bit() | CatalogMechanic.ORDER.bit()).updatedAt(old),
                CatalogSeed.deck("Д").topic("python").items(301).media(CatalogMedia.IMAGE.bit() | CatalogMedia.AUDIO.bit() | CatalogMedia.VIDEO.bit()).mechanics(CatalogMechanic.ALL),
                CatalogSeed.deck("Е").items(5).level("A1"), // below the threshold: no filter ever shows it
                CatalogSeed.deck("Ж").topic("spanish").target("es").description("   ")));
        seed.rankInOrder(1, NOW, decks.stream().filter(Deck::listed).toList());
        assertThat(titles(ok(get(decks("topic", "spanish", "sort", "new")))).stream().sorted()).containsExactly("А", "Б");
        // a top-level topic includes its children; a leaf only itself
        assertThat(titles(ok(get(decks("topic", "languages", "sort", "new")))).stream().sorted()).containsExactly("А", "Б", "В");
        assertThat(titles(ok(get(decks("topic", "languages")))).stream().sorted()).containsExactly("А", "Б", "В");
        assertThat(titles(ok(get(decks("learn", "es"))))).containsExactlyInAnyOrder("А", "Б");
        assertThat(titles(ok(get(decks("level", "A1"))))).containsExactlyInAnyOrder("А", "В");
        assertThat(titles(ok(get(decks("level", "INTERMEDIATE"))))).containsExactly("Г");
        assertThat(titles(ok(get(decks("size", "evening"))))).containsExactlyInAnyOrder("А", "Б");
        assertThat(titles(ok(get(decks("size", "month"))))).containsExactlyInAnyOrder("В", "Г");
        assertThat(titles(ok(get(decks("size", "course"))))).containsExactly("Д");
        // a deck must have ALL listed values
        assertThat(titles(ok(get(decks("media", "image"))))).containsExactlyInAnyOrder("А", "Б", "Д");
        assertThat(titles(ok(get(decks("media", "image,audio"))))).containsExactlyInAnyOrder("Б", "Д");
        assertThat(titles(ok(get(decks("media", "video,image,audio"))))).containsExactly("Д");
        assertThat(titles(ok(get(decks("mechanics", "listening"))))).containsExactlyInAnyOrder("А", "Б", "Д");
        assertThat(titles(ok(get(decks("mechanics", "listening,choice"))))).containsExactlyInAnyOrder("Б", "Д");
        assertThat(titles(ok(get(decks("mechanics", "matching,order"))))).containsExactlyInAnyOrder("Г", "Д");
        assertThat(titles(ok(get(decks("mechanics", "written"))))).containsExactlyInAnyOrder("В", "Д");
        // «живая»: updated within thirty days
        assertThat(titles(ok(get(decks("fresh", "true"))))).containsExactlyInAnyOrder("А", "В", "Д");
        assertThat(titles(ok(get(decks("fresh", "false"))))).hasSize(5);
        // composition
        assertThat(titles(ok(get(decks("topic", "languages", "level", "A1", "media", "image", "fresh", "true"))))).containsExactly("А");
        assertThat(titles(ok(get(decks("learn", "es", "size", "evening", "mechanics", "listening", "media", "audio", "sort", "updated"))))).containsExactly("Б");
        assertThat(ok(get(decks("topic", "physics")))).isEqualTo(CONTRACT.path("decks").path("nothing"));
        // the cursor of a filtered list is bound to the filters
        String cursor = ok(get(decks("media", "image", "limit", "1"))).path("nextCursor").stringValue(null);
        problem(get(decks("media", "audio", "limit", "1", "cursor", cursor)), 400, "INVALID_REQUEST");
        assertThat(ok(get(decks("media", "image", "limit", "1", "cursor", cursor))).path("decks")).hasSize(1);
    }

    @Test
    void everyUnknownValueRepeatedParameterAndBadNumberIsRejected() {
        numbered(3);
        List<String> bad = List.of("topic=nope", "topic=Spanish", "learn=ES", "learn=e", "learn=espanol", "level=a1", "level=D9", "size=week", "media=sound", "media=image,", "media=image,image",
                "media=,image", "mechanics=speaking", "mechanics=listening;choice", "fresh=yes", "fresh=1", "sort=best", "sort=Popular", "limit=0", "limit=49", "limit=-1", "limit=1.5", "limit=abc",
                "limit=007", "limit=2147483648", "cursor=@@", "cursor=" + "A".repeat(301), "unknown=1", "limit=1&limit=2", "topic=spanish&topic=english", "sort=new&sort=new",
                "cursor=eyJ2IjoyfQ", "utm_source=a");
        for (String query : bad) problem(get("/public/community/decks?" + query), 400, "INVALID_REQUEST");
        // the largest page and the empty values (an empty parameter is an absent one)
        assertThat(get("/public/community/decks?limit=48").statusCode()).isEqualTo(200);
        assertThat(get("/public/community/decks?limit=1").statusCode()).isEqualTo(200);
        assertThat(get("/public/community/decks?q=&topic=&learn=&level=&size=&media=&mechanics=&fresh=&sort=&limit=&cursor=").statusCode()).isEqualTo(200);
        assertThat(get("/public/community/decks?topic=spanish&topic=").statusCode()).as("a repeated parameter is 400 even when one is empty").isEqualTo(400);
        // only GET and HEAD reach the routes: the others are refused by the chain
        for (String method : List.of("POST", "PUT", "DELETE", "PATCH")) {
            assertThat(app.mnema.learning.support.HttpIdentityFixture.send(port, method, "/public/community/decks", null, "{}", java.util.Map.of()).statusCode()).as(method).isIn(401, 403, 405);
        }
    }

    @Test
    void theTotalCountsUpToAThousandAndSaysWhenItStoppedCounting() {
        List<CatalogSeed.Spec> specs = new ArrayList<>();
        for (int index = 0; index < 1_010; index++) specs.add(CatalogSeed.deck("Много " + index).listedAt(NOW.minusSeconds(index)));
        List<Deck> decks = seed.insertAll(specs);
        seed.rankInOrder(1, NOW, decks);
        for (String sort : List.of("popular", "learning", "new", "updated")) {
            JsonNode page = ok(get("/public/community/decks?sort=" + sort + "&limit=48"));
            assertThat(page.path("total").intValue()).as(sort).isEqualTo(1_000);
            assertThat(page.path("totalCapped").booleanValue()).as(sort).isTrue();
            assertThat(page.path("decks")).hasSize(48);
        }
        JsonNode filtered = ok(get("/public/community/decks?level=A1&limit=48"));
        assertThat(filtered.path("total").intValue()).isZero();
        assertThat(filtered.path("totalCapped").booleanValue()).isFalse();
    }

    @Test
    void theKeysetPagesNeverRepeatOrLoseADeckAndTiesAreBrokenByTheCode() {
        // thirty decks that entered the catalogue in the same instant: the order inside the tie is the public code, descending
        Instant same = NOW.minus(Duration.ofDays(3));
        List<CatalogSeed.Spec> specs = new ArrayList<>();
        for (int index = 0; index < 30; index++) specs.add(CatalogSeed.deck("Одновременно " + index).listedAt(same).updatedAt(same));
        List<Deck> decks = seed.insertAll(specs);
        List<String> expected = decks.stream().map(Deck::code).sorted(java.util.Comparator.reverseOrder()).toList();
        for (int size : new int[] {1, 7, 29, 30, 48}) {
            assertThat(walk("/public/community/decks?sort=new", size)).as("new by %s", size).containsExactlyElementsOf(expected);
            assertThat(walk("/public/community/decks?sort=updated", size)).as("updated by %s", size).containsExactlyElementsOf(expected);
        }
        seed.rankInOrder(1, NOW, decks);
        for (int size : new int[] {1, 7, 29}) {
            assertThat(walk("/public/community/decks?sort=popular", size)).as("popular by %s", size).containsExactlyElementsOf(decks.stream().map(Deck::code).toList());
            assertThat(walk("/public/community/decks?sort=learning&topic=languages", size)).isEmpty();
        }
    }

    @Test
    void aRankCursorIsBoundToItsSortAndGenerationAndExpires() {
        List<Deck> decks = numbered(12);
        seed.rankInOrder(1, NOW.minus(Duration.ofHours(1)), decks);
        String cursor = ok(get("/public/community/decks?sort=popular&limit=5")).path("nextCursor").stringValue(null);
        problem(get("/public/community/decks?sort=learning&limit=5&cursor=" + cursor), 400, "INVALID_REQUEST");
        problem(get("/public/community/decks?sort=new&limit=5&cursor=" + cursor), 400, "INVALID_REQUEST");
        problem(get("/public/community/decks?sort=popular&topic=spanish&limit=5&cursor=" + cursor), 400, "INVALID_REQUEST");
        // a newer generation does not move a list that is being read
        seed.rankInOrder(2, NOW, decks.reversed());
        List<String> rest = codes(ok(get("/public/community/decks?sort=popular&limit=7&cursor=" + cursor)));
        assertThat(rest).containsExactlyElementsOf(decks.subList(5, 12).stream().map(Deck::code).toList());
        jdbc.sql("DELETE FROM app_learning.rank_generation WHERE generation = 1").update();
        problem(get("/public/community/decks?sort=popular&limit=5&cursor=" + cursor), 412, "VERSION_CONFLICT");
        // the list restarts on the current generation
        assertThat(codes(ok(get("/public/community/decks?sort=popular&limit=3")))).containsExactlyElementsOf(decks.reversed().subList(0, 3).stream().map(Deck::code).toList());
    }

    @Test
    void aCardNeverCarriesAnIdAScoreARankOrASmallAudience() {
        List<Deck> decks = numbered(4);
        seed.generation(1, NOW, List.of(
                new CatalogSeed.Rank(decks.get(0).id(), 1, 1, 0.8123456789, 0.9, 1234, 34), new CatalogSeed.Rank(decks.get(1).id(), 2, 2, 0.5, 0.5, 99, 9),
                new CatalogSeed.Rank(decks.get(2).id(), 3, 3, 0.4, 0.4, 9, 0), new CatalogSeed.Rank(decks.get(3).id(), 4, 4, 0.3, 0.3, 10, 10)));
        HttpResponseText response = new HttpResponseText(get("/public/community/decks?sort=popular&limit=2"));
        JsonNode page = response.json();
        JsonNode first = page.path("decks").get(0);
        assertThat(first.path("addedCount").intValue()).as("1234 is floored to two significant digits").isEqualTo(1200);
        assertThat(first.path("learningNowCount").intValue()).isEqualTo(34);
        assertThat(page.path("decks").get(1).path("addedCount").intValue()).isEqualTo(99);
        assertThat(page.path("decks").get(1).path("learningNowCount").isNull()).as("9 learners are not shown").isTrue();
        JsonNode small = ok(get("/public/community/decks?sort=new&limit=48")).path("decks");
        for (JsonNode card : small) {
            if (card.path("code").stringValue(null).equals(decks.get(2).code())) {
                assertThat(card.path("addedCount").isNull()).isTrue();
                assertThat(card.path("learningNowCount").isNull()).isTrue();
            }
            if (card.path("code").stringValue(null).equals(decks.get(3).code())) assertThat(card.path("addedCount").intValue()).isEqualTo(10);
        }
        // nothing that could tell a score, a rank, a deck id or a revision leaves the server, in the cards or in the cursor
        String body = response.text();
        for (String forbidden : List.of("score", "rank", "deckId", "deck_id", "revision", "0.8123", decks.get(0).id().toString())) assertThat(body).as(forbidden).doesNotContainIgnoringCase(forbidden);
        for (JsonNode card : page.path("decks")) assertThat(card.propertyNames()).containsExactlyElementsOf(CONTRACT.path("card").propertyNames());
        JsonNode decoded = cursor(page.path("nextCursor").stringValue(null));
        assertThat(decoded.propertyNames()).containsOnly("v", "k", "h", "g", "p");
        assertThat(decoded.toString()).doesNotContain("score").doesNotContain("pop_pct").doesNotContain(decks.get(1).id().toString());
        assertThat(decoded.path("p").longValue()).as("the cursor holds the integer position only").isEqualTo(2);
        // a time cursor holds a time and a public code
        JsonNode time = cursor(ok(get("/public/community/decks?sort=new&limit=1")).path("nextCursor").stringValue(null));
        assertThat(time.propertyNames()).containsOnly("v", "k", "h", "t", "c");
        // the deck's own description is truncated to 300 code points with an ellipsis
        seed.insert(CatalogSeed.deck("Длинная").description("слово ".repeat(100)).listedAt(NOW.plusSeconds(10)));
        String description = ok(get("/public/community/decks?sort=new&limit=1")).path("decks").get(0).path("description").stringValue(null);
        assertThat(description.codePointCount(0, description.length())).isLessThanOrEqualTo(300).isGreaterThan(290);
        assertThat(description).endsWith("…");
    }

    /** A response read once as text and as JSON. */
    private record HttpResponseText(java.net.http.HttpResponse<String> response) {
        String text() { return response.body(); }

        JsonNode json() { return CatalogHttpTest.json(response); }
    }
}
