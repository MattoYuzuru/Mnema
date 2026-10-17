package app.mnema.learning.community;

import app.mnema.learning.community.CatalogSeed.Deck;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Community/2 (#449) and Community/7 (#454), facets and shelves: the examples of {@code contracts/community/catalog.json} executed as they are written, the rules of what a shelf is
 * (at least four decks, at most twelve, the owner's picks that are still listed, learners in thirty days, the newest entrants, the leaf topics with the most decks).
 */
class CatalogFacetsShelvesHttpIntegrationTest extends CatalogHttpTest {
    private static final Instant NOW = Instant.now();

    /** The 37 decks of the contract's facets example: 12 Spanish (9 of them A1, 3 B2), 8 English and 17 SQL decks; newest entrant last. */
    private List<Deck> contractCatalogue() {
        List<CatalogSeed.Spec> specs = new ArrayList<>();
        for (int index = 0; index < 12; index++) specs.add(CatalogSeed.deck("Испанский " + index).topic("spanish").target("es").level(index < 9 ? "A1" : "B2"));
        for (int index = 0; index < 8; index++) specs.add(CatalogSeed.deck("Английский " + index).topic("english").target("en"));
        for (int index = 0; index < 17; index++) specs.add(CatalogSeed.deck("SQL " + index).topic("sql-data"));
        for (int index = 0; index < specs.size(); index++) specs.get(index).listedAt(NOW.minus(Duration.ofHours(100 - index)));
        return seed.insertAll(specs);
    }

    @Test
    void theFacetsAnswerExactlyTheExampleOfTheContractAndTheEmptyOne() {
        assertThat(ok(get("/public/community/facets"))).isEqualTo(CONTRACT.path("facets").path("empty"));
        contractCatalogue();
        seed.insert(CatalogSeed.deck("Мало материалов").topic("spanish").target("es").level("A1").items(9));
        seed.insert(CatalogSeed.deck("Без описания").topic("spanish").target("es").level("A1").description(" "));
        seed.insert(CatalogSeed.deck("Без упражнений").topic("spanish").target("es").level("A1").exercises(0));
        service.invalidate();
        JsonNode facets = ok(get("/public/community/facets"));
        assertThat(facets).isEqualTo(CONTRACT.path("facets").path("response"));
    }

    @Test
    void theFacetsAreCachedPerInstanceAndCountOnlyListedDecks() {
        contractCatalogue();
        assertThat(ok(get("/public/community/facets")).path("listedCount").intValue()).isEqualTo(37);
        seed.insert(CatalogSeed.deck("Новая").topic("spanish").target("es"));
        assertThat(ok(get("/public/community/facets")).path("listedCount").intValue()).as("a cached answer of at most five minutes").isEqualTo(37);
        service.invalidate();
        JsonNode fresh = ok(get("/public/community/facets"));
        assertThat(fresh.path("listedCount").intValue()).isEqualTo(38);
        assertThat(fresh.path("topics").get(0).path("count").intValue()).as("languages: its own decks and its children's").isEqualTo(21);
        assertThat(fresh.path("topics").get(1).path("count").intValue()).isEqualTo(13);
        // an entry with a count of zero is absent, and a topic nobody has does not appear
        assertThat(fresh.path("topics").toString()).doesNotContain("physics");
        assertThat(fresh.path("levels")).hasSize(2);
    }

    @Test
    void theShelvesAnswerTheExampleOfTheContractInItsOrderWithAnEmptyCatalogue() {
        assertThat(ok(get("/public/community/shelves"))).isEqualTo(CONTRACT.path("shelves").path("empty"));
        // fewer listed decks than a shelf needs: nothing is a shelf yet
        seed.insertAll(List.of(CatalogSeed.deck("Одна").topic("spanish"), CatalogSeed.deck("Две").topic("spanish"), CatalogSeed.deck("Три").topic("spanish")));
        service.invalidate();
        JsonNode few = ok(get("/public/community/shelves"));
        assertThat(few.path("listedCount").intValue()).isEqualTo(3);
        assertThat(few.path("shelves")).isEmpty();
    }

    @Test
    void everyShelfFollowsItsRule() {
        List<Deck> decks = contractCatalogue();
        Deck below = seed.insert(CatalogSeed.deck("Ниже порога").topic("spanish").items(3));
        // generation: rank by position; the first six decks have learners in the last thirty days (6, 5, ... 1)
        List<CatalogSeed.Rank> ranks = new ArrayList<>();
        for (int index = 0; index < decks.size(); index++) {
            ranks.add(new CatalogSeed.Rank(decks.get(index).id(), index + 1, index + 1, 1.0 / (index + 1), 1.0 - index / 40.0, index < 6 ? 100 + index : 0, index < 6 ? 6 - index : 0));
        }
        ranks.add(new CatalogSeed.Rank(below.id(), 1, 1, 0, 0, 0, 0));
        seed.generation(1, NOW, ranks);
        // the owner's picks: five, one of which is below the threshold, in his order
        pick(List.of(decks.get(30), decks.get(2), below, decks.get(10), decks.get(20)));
        JsonNode shelves = ok(get("/public/community/shelves"));
        assertThat(shelves.path("listedCount").intValue()).isEqualTo(37);
        List<String> kinds = new ArrayList<>();
        shelves.path("shelves").forEach(shelf -> kinds.add(shelf.path("kind").stringValue(null) + (shelf.has("topic") ? ":" + shelf.path("topic").path("id").stringValue(null) : "")));
        assertThat(kinds).containsExactly("editorial", "learning", "new", "topic:sql-data", "topic:spanish", "topic:english");
        JsonNode editorial = shelves.path("shelves").get(0);
        assertThat(editorial.has("more")).isFalse();
        assertThat(codes(editorial)).containsExactly(decks.get(30).code(), decks.get(2).code(), decks.get(10).code(), decks.get(20).code());
        JsonNode learning = shelves.path("shelves").get(1);
        assertThat(codes(learning)).containsExactlyElementsOf(decks.subList(0, 6).stream().map(Deck::code).toList());
        assertThat(learning.path("more")).isEqualTo(CONTRACT.path("shelves").path("response").path("shelves").get(1).path("more"));
        JsonNode fresh = shelves.path("shelves").get(2);
        assertThat(codes(fresh)).hasSize(12).containsExactlyElementsOf(decks.reversed().subList(0, 12).stream().map(Deck::code).toList());
        assertThat(fresh.path("more")).isEqualTo(CONTRACT.path("shelves").path("response").path("shelves").get(2).path("more"));
        JsonNode sql = shelves.path("shelves").get(3);
        assertThat(sql.path("topic")).isEqualTo(tree("{\"id\":\"sql-data\",\"name\":\"SQL и данные\",\"parentId\":\"it\"}"));
        assertThat(codes(sql)).hasSize(12).containsExactlyElementsOf(decks.subList(20, 32).stream().map(Deck::code).toList());
        assertThat(sql.path("more")).isEqualTo(tree("{\"topic\":\"sql-data\",\"sort\":\"popular\"}"));
        JsonNode spanish = shelves.path("shelves").get(4);
        assertThat(spanish.path("topic")).isEqualTo(CONTRACT.path("shelves").path("response").path("shelves").get(3).path("topic"));
        assertThat(spanish.path("more")).isEqualTo(CONTRACT.path("shelves").path("response").path("shelves").get(3).path("more"));
        assertThat(codes(spanish)).hasSize(12).containsExactlyElementsOf(decks.subList(0, 12).stream().map(Deck::code).toList());
        assertThat(codes(shelves.path("shelves").get(5))).containsExactlyElementsOf(decks.subList(12, 20).stream().map(Deck::code).toList());
        // «<card>» stands for an object of the shape of `card`; the audience of the first learning deck is the generation's, displayed
        JsonNode card = learning.path("decks").get(0);
        assertThat(card.propertyNames()).containsExactlyElementsOf(CONTRACT.path("card").propertyNames());
        assertThat(card.path("addedCount").intValue()).isEqualTo(100);
        assertThat(card.path("learningNowCount").isNull()).as("six learners are not shown").isTrue();
        // an owner's pick that stops being listed falls out of the shelf; with three left the shelf is gone
        jdbc.sql("DELETE FROM app_learning.public_deck WHERE deck_id = :d").param("d", decks.get(2).id()).update();
        assertThat(kinds(ok(get("/public/community/shelves")))).doesNotContain("editorial");
    }

    @Test
    void atMostSixTopicShelvesAndNoShelfWithFewerThanFourDecks() {
        List<CatalogSeed.Spec> specs = new ArrayList<>();
        List<String> leaves = List.of("english", "spanish", "german", "french", "italian", "portuguese", "chinese", "japanese");
        for (int topic = 0; topic < leaves.size(); topic++) {
            // english 11 decks, spanish 10, ... japanese 4: eight topics qualify, six get a shelf
            for (int index = 0; index < 11 - topic; index++) specs.add(CatalogSeed.deck(leaves.get(topic) + index).topic(leaves.get(topic)));
        }
        for (int index = 0; index < 3; index++) specs.add(CatalogSeed.deck("Корея " + index).topic("korean"));
        List<Deck> decks = seed.insertAll(specs);
        seed.rankInOrder(1, NOW, decks);
        JsonNode shelves = ok(get("/public/community/shelves"));
        List<String> topics = new ArrayList<>();
        shelves.path("shelves").forEach(shelf -> { if (shelf.path("kind").stringValue(null).equals("topic")) topics.add(shelf.path("topic").path("id").stringValue(null)); });
        assertThat(topics).containsExactly("english", "spanish", "german", "french", "italian", "portuguese");
        assertThat(kinds(shelves)).doesNotContain("editorial", "learning");
    }

    private void pick(List<Deck> decks) {
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            for (int index = 0; index < decks.size(); index++) {
                jdbc.sql("INSERT INTO app_learning.editorial_pick(deck_id, position, added_at, added_by) VALUES (:d, :p, CURRENT_TIMESTAMP, :by)")
                        .param("d", decks.get(index).id()).param("p", index + 1).param("by", CONSOLE).update();
            }
        });
    }

    private static List<String> kinds(JsonNode shelves) {
        List<String> kinds = new ArrayList<>();
        shelves.path("shelves").forEach(shelf -> kinds.add(shelf.path("kind").stringValue(null)));
        return kinds;
    }

    private static JsonNode tree(String json) {
        return JSON.readTree(json);
    }
}
