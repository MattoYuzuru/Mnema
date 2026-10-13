package app.mnema.learning.community;

import app.mnema.learning.support.HttpIdentityFixture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationHttpIntegrationTest extends CatalogHttpTest {

    @Autowired private InterestService interests;
    @Autowired private InterestRebuild rebuild;
    @Autowired private InterestRepository interestRepository;
    @Autowired private app.mnema.learning.catalog.deck.DeckService deckService;
    @Autowired private app.mnema.learning.catalog.item.ItemService itemService;
    @Autowired private app.mnema.learning.catalog.exercise.ExerciseService exerciseService;
    @Autowired private app.mnema.learning.study.session.StudySessionService sessionService;
    @Autowired private app.mnema.learning.media.MediaCatalog mediaCatalog;
    @Autowired private app.mnema.learning.storage.ImmutableStorage storage;
    @Autowired private app.mnema.learning.library.PublicationService publication;
    @Autowired private app.mnema.learning.library.DeckModeration moderation;
    @Autowired private CatalogProjector projector;
    private UUID account;
    @DynamicPropertySource static void recommendations(DynamicPropertyRegistry properties) {
        properties.add("learning.community.events.enabled", () -> true);
        properties.add("learning.community.for-you.enabled", () -> true);
        properties.add("learning.community.forks.enabled", () -> true);
    }

    @BeforeEach void clearRecommendations() {
        jdbc.sql("DELETE FROM app_learning.catalog_event").update();
        jdbc.sql("DELETE FROM app_learning.deck_exposure_day").update();
        jdbc.sql("DELETE FROM app_learning.community_interest_profile").update();
        account = UUID.randomUUID();
    }

    private HttpResponse<String> send(String method, String path, JsonNode body, String version) {
        return HttpIdentityFixture.send(port, method, path, HttpIdentityFixture.reader(account), body == null ? null : body.toString(), version == null ? Map.of() : Map.of("If-Match", "\"" + version + "\""));
    }

    @Override protected JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        assertThat(response.headers().allValues("vary")).anySatisfy(value -> assertThat(value).containsIgnoringCase("authorization"));
        return json(response);
    }

    private JsonNode profile() {
        return ok(get("/community/interests", account));
    }

    private ObjectNode selection(String... topics) {
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString());
        var list = body.putArray("topicIds");
        for (String topic : topics) list.add(topic);
        return body;
    }

    private JsonNode choose(String... topics) {
        return ok(send("PUT", "/community/interests", selection(topics), profile().path("rowVersion").stringValue(null)));
    }

    private List<CatalogSeed.Deck> catalogue() {
        String[] topics = {
            "spanish", "english", "programming", "math", "biology", "anatomy", "business", "law", "german", "french"
        };
        List<CatalogSeed.Spec> specs = new ArrayList<>();
        for (String topic : topics) for (int i = 0; i < 2; i++) specs.add(CatalogSeed.deck(topic + " " + i).topic(topic).target(topic.equals("spanish") ? "es" : null));
        var decks = seed.insertAll(specs);
        seed.rankInOrder(901, Instant.now(), decks);
        return decks;
    }

    private ObjectNode event(String code) {
        return JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("kind", "DETAIL_VIEW").put("code", code).put("surface", "for-you").put("position", 1).put("rankVersion", "recs-v1");
    }

    @Test void authenticationAndPrivateCacheHeaders() {
        for (String path : List.of("/community/interests", "/community/recommendations")) {
            problem(get(path), 401, "AUTHENTICATION_REQUIRED");
            assertThat(HttpIdentityFixture.send(port, "GET", path, "bad-token", null, Map.of()).statusCode()).isEqualTo(401);
        }

        JsonNode profile = profile();
        assertThat(profile.path("rowVersion").stringValue()).isEqualTo("0");
        assertThat(profile.path("availableTopics").size()).isGreaterThan(40);
        assertThat(profile.path("selectedTopics")).isEmpty();
        assertThat(profile.path("inferredTopics")).isEmpty();
        assertThat(profile.toString()).doesNotContain("weight", "accountId", "score");
        assertThat(ok(get("/community/recommendations", account)).path("decks")).isEmpty();
        assertThat(HttpIdentityFixture.send(port, "PUT", "/community/interests", HttpIdentityFixture.token(account, "learning.read"), selection("spanish").toString(), Map.of("If-Match", "\"0\"")).statusCode()).isEqualTo(403);
    }

    @Test void selectionEmptySkipReceiptsCasAndStrictLeaves() {
        var selected = selection("spanish", "english");
        JsonNode response = ok(send("PUT", "/community/interests", selected, "0"));
        assertThat(response.path("rowVersion").stringValue()).isEqualTo("1");
        assertThat(response.path("selectedTopics").get(0).path("id").stringValue()).isEqualTo("spanish");
        assertThat(ok(send("PUT", "/community/interests", selected, "0"))).isEqualTo(response);
        problem(send("PUT", "/community/interests", selection("spanish"), "0"), 412, "VERSION_CONFLICT");
        selected.putArray("topicIds").add("english");
        problem(send("PUT", "/community/interests", selected, "1"), 409, "IDEMPOTENCY_CONFLICT");
        for (String topic : List.of("languages", "missing", "../secret")) problem(send("PUT", "/community/interests", selection(topic), "1"), 400, "INVALID_REQUEST");
        problem(send("PUT", "/community/interests", selection("spanish", "spanish"), "1"), 400, "INVALID_REQUEST");
        problem(send("PUT", "/community/interests", selection(), null), 428, "PRECONDITION_REQUIRED");
        JsonNode skip = ok(send("PUT", "/community/interests", selection(), "1"));
        assertThat(skip.path("onboardingComplete").booleanValue()).isTrue();
        assertThat(skip.path("selectedTopics")).isEmpty();
    }

    @Test void noClientAccountTextLifecycleOrWrongJsonTypesCanEnterTelemetry() {
        var deck = seed.insert(CatalogSeed.deck("Spain").topic("spanish"));
        ObjectNode valid = event(deck.code());
        for (String kind : List.of("FORK", "STUDY_START", "DROP")) {
            var invalid = valid.deepCopy().put("kind", kind);
            problem(send("POST", "/community/events", invalid, null), 400, "INVALID_REQUEST");
        }

        for (String field : List.of("accountId", "deckId", "query", "text")) problem(send("POST", "/community/events", valid.deepCopy().put(field, "secret"), null), 400, "INVALID_REQUEST");
        for (String field : List.of("kind", "code", "surface", "rankVersion", "commandId")) {
            var invalid = valid.deepCopy();
            invalid.set(field, JSON.createArrayNode());
            problem(send("POST", "/community/events", invalid, null), 400, "INVALID_REQUEST");
        }

        for (JsonNode position : List.of(JSON.readTree("\"1\""), JSON.getNodeFactory().numberNode(1.5), JSON.getNodeFactory().numberNode(0), JSON.getNodeFactory().numberNode(1001))) {
            var invalid = valid.deepCopy();
            invalid.set("position", position);
            problem(send("POST", "/community/events", invalid, null), 400, "INVALID_REQUEST");
        }

        for (String body : List.of("[]", "null", "{", "{\"commandId\":null}", valid.toString().replace("\"DETAIL_VIEW\"", "false")))
        assertThat(HttpIdentityFixture.send(port, "POST", "/community/events", HttpIdentityFixture.reader(account), body, Map.of()).statusCode()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.catalog_event").query(Long.class).single()).isZero();
    }

    @Test void eventAndExposureReceiptsAndPublicSourcePrivacy() {
        var deck = seed.insert(CatalogSeed.deck("Spain").topic("spanish"));
        var command = event(deck.code());
        assertThat(send("POST", "/community/events", command, null).statusCode()).isEqualTo(204);
        assertThat(send("POST", "/community/events", command, null).statusCode()).isEqualTo(204);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.catalog_event WHERE account_id=:a AND rank_version='recs-v1' AND topic_id='spanish' AND uuid_extract_version(event_id)=7").param("a", account).query(Long.class).single()).isEqualTo(1);
        var exposures = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("surface", "for-you").put("rankVersion", "recs-v1");
        exposures.putArray("items").addObject().put("code", deck.code()).put("position", 1);
        assertThat(send("POST", "/community/exposures", exposures, null).statusCode()).isEqualTo(204);
        assertThat(send("POST", "/community/exposures", exposures, null).statusCode()).isEqualTo(204);
        assertThat(jdbc.sql("SELECT sum(impressions) FROM app_learning.deck_exposure_day").query(Long.class).single()).isEqualTo(1);
        var own = seed.insert(CatalogSeed.deck("Own private text stays outside").owner(account).topic("anatomy"));
        problem(send("POST", "/community/events", event(own.code()), null), 404, "RESOURCE_NOT_FOUND");
        var unlisted = seed.insert(CatalogSeed.deck("Under threshold").items(2));
        problem(send("POST", "/community/events", event(unlisted.code()), null), 404, "RESOURCE_NOT_FOUND");
        problem(send("POST", "/community/events", event(CatalogSeed.code()), null), 404, "RESOURCE_NOT_FOUND");
        assertThat(jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_schema='app_learning' AND table_name IN ('catalog_event','deck_exposure_day')").query(String.class).list())
                .doesNotContain("query", "text", "title", "description");
        assertThat(jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_schema='app_learning' AND table_name='deck_exposure_day'").query(String.class).list()).doesNotContain("account_id", "position");
    }

    @Test void resetDeletesSignalsAndKeepsAnonymousExposureAndFutureEvents() {
        choose("spanish");
        var deck = seed.insert(CatalogSeed.deck("Spain").topic("spanish"));
        assertThat(send("POST", "/community/events", event(deck.code()), null).statusCode()).isEqualTo(204);
        var reset = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString());
        JsonNode response = ok(send("POST", "/community/interests:reset", reset, "1"));
        assertThat(response.path("rowVersion").stringValue()).isEqualTo("2");
        assertThat(response.path("onboardingComplete").booleanValue()).isTrue();
        assertThat(response.path("selectedTopics")).isEmpty();
        assertThat(response.path("inferredTopics")).isEmpty();
        assertThat(ok(send("POST", "/community/interests:reset", reset, "1"))).isEqualTo(response);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.catalog_event WHERE account_id=:a").param("a", account).query(Long.class).single()).isZero();
        assertThat(send("POST", "/community/events", event(deck.code()), null).statusCode()).isEqualTo(204);
        new TransactionTemplate(transactions).executeWithoutResult(status -> interests.changed(account));
        assertThat(profile().path("inferredTopics").get(0).path("id").stringValue()).isEqualTo("spanish");
    }

    @Test void rankingFrozenCursorFilteringAndChangedProfileGenerationAreBound() {
        var decks = catalogue();
        choose("spanish", "english", "programming", "math", "biology", "anatomy", "business", "law", "german", "french");
        JsonNode first = ok(get("/community/recommendations?limit=3", account));
        assertThat(first.path("total").intValue()).isEqualTo(12);
        assertThat(first.path("decks")).hasSize(3);
        assertThat(first.toString()).doesNotContain("score", "weight", "deckId", "publishedRevisionId");
        String cursor = first.path("nextCursor").stringValue();
        assertThat(cursor(cursor).toString()).doesNotContain("score", "weight", "rank", "account");
        String secondPath = "/community/recommendations?limit=3&cursor=" + cursor;
        JsonNode second = ok(get(secondPath, account));
        assertThat(codes(second)).doesNotContainAnyElementsOf(codes(first));
        var late = seed.insert(CatalogSeed.deck("late").topic("english").listedAt(Instant.now()));
        assertThat(ok(get(secondPath, account))).isEqualTo(second);
        assertThat(codes(ok(get("/community/recommendations", account)))).doesNotContain(late.code());
        problem(get(secondPath + "&topic=english", account), 400, "INVALID_REQUEST");
        problem(get(secondPath, UUID.randomUUID()), 400, "INVALID_REQUEST");
        choose("english");
        problem(get(secondPath, account), 412, "VERSION_CONFLICT");
        assertThat(decks).hasSize(20);
    }

    @Test void strictQueryUsesCatalogueSearchAndNeverPubliclyOffersForYou() {
        catalogue();
        choose("spanish", "english", "programming", "math", "biology", "anatomy", "business", "law", "german", "french");
        for (String query : List.of("limit=0", "sort=popular", "q=x&q=y", "unknown=1", "sort=for-you&sort=", "topic=missing", "media=audio,,image", "cursor=%40%40"))
        problem(get("/community/recommendations?" + query, account), 400, "INVALID_REQUEST");
        problem(get("/public/community/decks?sort=for-you", account), 400, "INVALID_REQUEST");
        assertThat(ok(get("/community/recommendations?sort=for-you", account)).path("decks")).hasSize(12);
        assertThat(ok(get("/community/recommendations?q=nevermatchesanything", account)).path("decks")).isEmpty();
        assertThat(ok(get("/community/recommendations?q=spanish", account)).path("decks")).isEmpty();
        // two matching sources are below the shelf threshold
    }

    @Test void enumGoalUsesActualParentsAndResetBoundaryExcludesOldAnswer() {
        jdbc.sql("INSERT INTO app_learning.learning_profile(owner_id,goal,answered_at) VALUES (:a,'LANGUAGE',statement_timestamp())").param("a", account).update();
        JsonNode response = profile();
        assertThat(response.path("inferredTopics")).hasSize(1);
        assertThat(response.path("inferredTopics").get(0).path("id").stringValue()).isEqualTo("languages");
        var reset = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString());
        ok(send("POST", "/community/interests:reset", reset, response.path("rowVersion").stringValue()));
        new TransactionTemplate(transactions).executeWithoutResult(status -> interests.changed(account));
        assertThat(profile().path("inferredTopics")).isEmpty();
        assertThat(interests.goalTopics("EXAMS")).containsExactly("exams", "school");
        assertThat(interests.goalTopics("WORK")).containsExactly("professional");
        assertThat(interests.goalTopics("SELF")).isEmpty();
    }

    @Test void expiredSnapshotCreatesFreshCursorAndDroppedMatchingPoolHidesShelf() {
        var decks = new ArrayList<>(catalogue());
        decks.add(seed.insert(CatalogSeed.deck("Spanish third").topic("spanish").target("es")));
        decks.add(seed.insert(CatalogSeed.deck("Spanish fourth").topic("spanish").target("es")));
        seed.rankInOrder(902, Instant.now(), decks);
        choose("spanish");
        JsonNode first = ok(get("/community/recommendations?limit=3", account));
        assertThat(first.path("total").intValue()).isEqualTo(12);
        // four candidates match, only two need be in first ten
        String old = first.path("nextCursor").stringValue();
        jdbc.sql("UPDATE app_learning.recommendation_snapshot SET created_at=statement_timestamp()-interval '25 hours' WHERE account_id=:a").param("a", account).update();
        problem(get("/community/recommendations?limit=3&cursor=" + old, account), 412, "VERSION_CONFLICT");
        JsonNode fresh = ok(get("/community/recommendations?limit=3", account));
        assertThat(fresh.path("nextCursor").stringValue()).isNotEqualTo(old);
        List<String> shown = codes(ok(get("/community/recommendations", account)));
        String removed = decks.stream().filter(d -> d.code().equals(decks.get(20).code()) || d.code().equals(decks.get(21).code())).map(CatalogSeed.Deck::code)
                .filter(c -> !shown.contains(c)).findFirst().orElse(decks.get(21).code());
        jdbc.sql("DELETE FROM app_learning.public_deck WHERE public_code=:c").param("c", removed).update();
        JsonNode hidden = ok(get("/community/recommendations", account));
        assertThat(hidden.path("total").intValue()).isZero();
        assertThat(hidden.path("decks")).isEmpty();
    }

    @Test void oldSelectionReplayAfterResetDoesNotReturnOrRestorePersonalHistory() {
        var selection = selection("spanish");
        ok(send("PUT", "/community/interests", selection, "0"));
        var reset = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString());
        JsonNode blank = ok(send("POST", "/community/interests:reset", reset, "1"));
        assertThat(ok(send("PUT", "/community/interests", selection, "0"))).isEqualTo(blank);
        assertThat(jdbc.sql("SELECT result::text FROM app_learning.command_receipt WHERE actor_id=:a AND command_scope='community.interests'").param("a", account).query(String.class).list())
                .allSatisfy(result -> assertThat(result).isEqualTo("{}"));
    }
    @Test void hidingAllPrimaryInterestCardsExplainsTheRemainingContributingInterest() {
        List<CatalogSeed.Deck> decks = new ArrayList<>();
        for (String topic : List.of("spanish", "english", "programming", "math", "biology", "anatomy", "business", "law")) {
            for (int index = 0; index < (topic.equals("spanish") || topic.equals("english") ? 4 : 2); index++) {
                decks.add(seed.insert(CatalogSeed.deck(topic + index).topic(topic)));
            }
        }
        seed.rankInOrder(904, Instant.now(), decks);
        choose("spanish", "english");
        // Model weights intentionally distinguish the main interest; selection weight equality is tested separately.
        jdbc.sql("UPDATE app_learning.user_topic_interest SET weight=0.7 WHERE account_id=:a AND topic_id='english'").param("a", account).update();
        JsonNode first = ok(get("/community/recommendations", account));
        assertThat(first.path("explanationTopic").path("id").stringValue()).isEqualTo("spanish");
        jdbc.sql("DELETE FROM app_learning.public_deck WHERE topic_id='spanish'").update();
        JsonNode after = ok(get("/community/recommendations", account));
        assertThat(after.path("total").intValue()).isGreaterThanOrEqualTo(4);
        assertThat(after.path("explanationTopic").path("id").stringValue()).isEqualTo("english");
    }

    @Test void effectiveCopyAndDropRefreshShelfWhenNormalizedInterestStaysTheSame() {
        List<CatalogSeed.Deck> decks = new ArrayList<>(catalogue());
        CatalogWorlds worlds = new CatalogWorlds(deckService, itemService, exerciseService, sessionService, mediaCatalog, storage, jdbc, transactions, publication, moderation);
        var source = worlds.deck("Highest ranked Spanish source", "Public source", 10);
        worlds.exercise(source.materials().getFirst(), app.mnema.learning.catalog.exercise.ExerciseType.FREE_RESPONSE);
        worlds.publish(source);
        worlds.project(projector, source.deck());
        String sourceCode = jdbc.sql("SELECT public_code FROM app_learning.public_deck WHERE deck_id=:d").param("d", source.deck()).query(String.class).single();
        decks.add(0, new CatalogSeed.Deck(source.deck(), sourceCode, source.owner(), true));
        decks.add(seed.insert(CatalogSeed.deck("Spanish third").topic("spanish").target("es")));
        decks.add(seed.insert(CatalogSeed.deck("Spanish fourth").topic("spanish").target("es")));
        seed.rankInOrder(905, Instant.now(), decks);
        choose("spanish");
        Map<String, Double> normalized = interestRepository.interests(account);
        assertThat(normalized).containsExactlyEntriesOf(Map.of("spanish", 1.0));
        long before = Long.parseLong(profile().path("rowVersion").stringValue());
        JsonNode firstPage = ok(get("/community/recommendations?limit=3", account));
        String oldCursor = firstPage.path("nextCursor").stringValue();
        JsonNode whole = ok(get("/community/recommendations", account));
        assertThat(whole.path("decks")).hasSize(12);
        assertThat(codes(whole)).contains(sourceCode);
        var copyCommand = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("sourceCode", sourceCode);
        var copied = send("POST", "/decks/forks", copyCommand, null);
        assertThat(copied.statusCode()).as(copied.body()).isEqualTo(201);
        String copyId = json(copied).path("deckId").stringValue();
        assertThat(interestRepository.interests(account)).isEqualTo(normalized);
        assertThat(Long.parseLong(profile().path("rowVersion").stringValue())).isEqualTo(before + 1);
        JsonNode fresh = ok(get("/community/recommendations", account));
        assertThat(fresh.path("decks")).hasSize(12);
        assertThat(codes(fresh)).doesNotContain(sourceCode);
        assertExplorationTail(fresh);
        problem(get("/community/recommendations?limit=3&cursor=" + oldCursor, account), 412, "VERSION_CONFLICT");
        assertThat(send("POST", "/decks/forks", copyCommand, null).headers().firstValue("Idempotency-Replayed")).contains("true");
        var existingCommand = copyCommand.deepCopy().put("commandId", UUID.randomUUID().toString());
        assertThat(send("POST", "/decks/forks", existingCommand, null).statusCode()).isEqualTo(200);
        assertThat(Long.parseLong(profile().path("rowVersion").stringValue())).isEqualTo(before + 1);
        String copyCursor = ok(get("/community/recommendations?limit=3", account)).path("nextCursor").stringValue();
        var copyHead = get("/decks/" + copyId, account);
        assertThat(copyHead.statusCode()).isEqualTo(200);
        String copyVersion = json(copyHead).path("rowVersion").stringValue();
        assertThat(send("DELETE", "/decks/" + copyId, null, copyVersion).statusCode()).isEqualTo(204);
        assertThat(interestRepository.interests(account)).isEqualTo(normalized);
        assertThat(Long.parseLong(profile().path("rowVersion").stringValue())).isEqualTo(before + 2);
        JsonNode restored = ok(get("/community/recommendations", account));
        assertThat(restored.path("decks")).hasSize(12);
        assertThat(codes(restored)).contains(sourceCode);
        assertExplorationTail(restored);
        problem(get("/community/recommendations?limit=3&cursor=" + copyCursor, account), 412, "VERSION_CONFLICT");
        assertThat(send("DELETE", "/decks/" + copyId, null, copyVersion).statusCode()).isEqualTo(404);
        assertThat(Long.parseLong(profile().path("rowVersion").stringValue())).isEqualTo(before + 2);
        new TransactionTemplate(transactions).executeWithoutResult(status -> interests.rebuild(account));
        assertThat(Long.parseLong(profile().path("rowVersion").stringValue())).isEqualTo(before + 2);
    }

    private void assertExplorationTail(JsonNode shelf) {
        List<String> top = List.of("spanish");
        for (int position = 10; position < 12; position++) {
            JsonNode card = shelf.path("decks").get(position);
            boolean newSource = jdbc.sql("SELECT first_listed_at>=statement_timestamp()-interval '30 days' FROM app_learning.public_deck WHERE public_code=:c")
                    .param("c", card.path("code").stringValue()).query(Boolean.class).single();
            assertThat(newSource || !top.contains(card.path("topic").path("id").stringValue())).isTrue();
        }
    }

}
