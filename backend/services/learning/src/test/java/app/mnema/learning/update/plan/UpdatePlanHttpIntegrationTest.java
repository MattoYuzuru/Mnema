package app.mnema.learning.update.plan;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.library.ManifestPages;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.storage.ImmutableStorage;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.StudyFixtures.Material;
import app.mnema.learning.update.plan.PlanWorlds.Authored;
import app.mnema.learning.update.plan.PlanWorlds.Copy;
import app.mnema.learning.update.plan.PlanWorlds.Source;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static app.mnema.learning.update.plan.PlanWorlds.json;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Updates/3 (#442) through the real application: the routes, their access matrix, the states a plan can be in, the pages and their cursor, the pins and the staleness, the two sides of
 * one unit, the proof that reading writes nothing, and the answers compared with the property sets of {@code contracts/decks/updates.json}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"learning.runtime.roles=worker", "learning.community.forks.slice-size=10", "learning.jobs.sweep-interval=PT0.5S",
                "learning.ai.provider=stub", "learning.features.speech-to-text.enabled=true", "learning.speech.sweep-interval=PT5M",
                "learning.generation.worker.sweep-interval=PT5M", "learning.ai.assess.sweep-interval=PT15S"})
class UpdatePlanHttpIntegrationTest extends PostgresIntegrationTest {
    private static final JsonNode CONTRACT = PlanWorlds.updatesContract();

    @LocalServerPort private int port;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    @Autowired private ImmutableStorage storage;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ManifestPages manifests;
    @Autowired private app.mnema.learning.library.DeckModeration moderation;
    @Autowired private app.mnema.learning.library.DeckPublications publications;
    private PlanWorlds worlds;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.community.forks.enabled", () -> true);
        registry.add("learning.community.public-routes.enabled", () -> true);
        registry.add("learning.community.public-routes.guest-per-minute", () -> 100_000);
        registry.add("learning.community.public-routes.account-per-minute", () -> 100_000);
    }

    @BeforeEach
    void worlds() {
        worlds = new PlanWorlds(port, decks, items, exercises, sessions, media, storage, jdbc, transactions, manifests);
    }

    private static Set<String> names(JsonNode node) {
        Set<String> result = new TreeSet<>();
        node.propertyNames().forEach(result::add);
        return result;
    }

    private static void problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("content-type")).hasValueSatisfying(value -> assertThat(value).startsWith("application/problem+json"));
        assertThat(json(response).path("code").stringValue(null)).isEqualTo(code);
    }

    private JsonNode plan(Copy copy, String query) {
        HttpResponse<String> response = worlds.get("/decks/" + copy.deck() + "/update-plan" + query, copy.owner());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control")).hasValue("private, no-store");
        return json(response);
    }

    private HttpResponse<String> unit(Copy copy, String kind, UUID key, String version) {
        return worlds.get("/decks/" + copy.deck() + "/update-plan/units/" + kind + "/" + key + (version == null ? "" : "?planVersion=" + version), copy.owner());
    }

    /** A source with {@code changed} materials edited by the author after the copy was made, and the copy. */
    private record Scene(Source source, Copy copy, List<UUID> edited) { }

    private Scene scene(int materials, int exerciseCount, int changed) {
        Source source = worlds.source("LINK", materials, exerciseCount);
        Copy copy = worlds.copy(source);
        List<UUID> edited = new ArrayList<>();
        for (int index = 0; index < changed; index++) {
            UUID member = source.materials().get(index).member();
            worlds.editText(source.author(), source.deck(), member, "автор " + index);
            edited.add(member);
        }
        worlds.publish(source.author(), source.deck(), "LINK");
        return new Scene(source, copy, edited);
    }

    // ---- shapes, pages and pins ----

    @Test
    void thePlanIsPagedFromOneDeterministicOrderAndEveryAnswerHasTheShapeOfTheContract() {
        Source source = worlds.source("LINK", 14, 3);
        Copy copy = worlds.copy(source);
        UUID author = source.author();
        UUID deck = source.deck();
        List<Material> m = source.materials();
        for (int index = 4; index < 12; index++) worlds.editText(author, deck, m.get(index).member(), "автор " + index);
        worlds.deleteMaterial(author, deck, m.get(12).member());
        worlds.revise(author, deck, m.get(0), source.exercises().get(0), "Автор: вопрос", "Автор: цель");
        Material added = worlds.study.addMaterial(author, deck, "новое слово", "перевод");
        worlds.exercise(added, "Что значит новое?", null, "Новая цель");
        worlds.retitle(author, deck, "Второе издание", "Описание");
        assertThat(worlds.publishRaw(author, deck, "LINK", "Добавили слово и поправили вопрос").statusCode()).isEqualTo(200);

        JsonNode first = plan(copy, "?limit=7");
        JsonNode example = CONTRACT.path("plan").path("body");
        assertThat(names(first)).isEqualTo(names(example));
        assertThat(names(first.path("summary"))).isEqualTo(names(example.path("summary")));
        assertThat(names(first.path("units").get(0))).isEqualTo(names(example.path("units").get(0)));
        assertThat(first.path("state").stringValue(null)).isEqualTo("READY");
        assertThat(first.path("releaseNote").stringValue(null)).isEqualTo("Добавили слово и поправили вопрос");
        assertThat(first.path("planVersion").stringValue(null)).matches(CONTRACT.path("constants").path("planVersionPattern").stringValue(null));
        assertThat(first.path("units")).hasSize(7);
        assertThat(first.path("nextCursor").isString()).isTrue();

        // every page repeats the pins and the summary of the WHOLE plan; the concatenation is the plan of the service, in order
        List<String> ids = new ArrayList<>();
        JsonNode page = first;
        int pages = 1;
        while (true) {
            assertThat(page.path("planVersion")).isEqualTo(first.path("planVersion"));
            assertThat(page.path("summary")).isEqualTo(first.path("summary"));
            assertThat(page.path("baseRevisionId")).isEqualTo(first.path("baseRevisionId"));
            page.path("units").forEach(unit -> ids.add(unit.path("unitId").stringValue(null)));
            if (page.path("nextCursor").isNull()) break;
            page = plan(copy, "?limit=7&cursor=" + page.path("nextCursor").stringValue(null));
            pages++;
        }
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(pages).isEqualTo((ids.size() + 6) / 7);
        JsonNode all = plan(copy, "?limit=100");
        List<String> allIds = new ArrayList<>();
        all.path("units").forEach(unit -> allIds.add(unit.path("unitId").stringValue(null)));
        assertThat(ids).isEqualTo(allIds);
        assertThat(all.path("nextCursor").isNull()).isTrue();
        // the summary counts the whole plan, whatever the page
        int safe = 0;
        int created = 0;
        int removed = 0;
        for (JsonNode unit : all.path("units")) {
            switch (unit.path("class").stringValue(null)) {
                case "SAFE" -> safe++;
                case "NEW" -> created++;
                case "DELETED" -> removed++;
                default -> { }
            }
        }
        assertThat(first.path("summary").path("fixes").intValue()).isEqualTo(safe);
        assertThat(first.path("summary").path("newMaterials").intValue() + first.path("summary").path("newExercises").intValue()).isEqualTo(created);
        assertThat(first.path("summary").path("deletedByAuthor").intValue()).isEqualTo(removed);
        // class order: new, safe, conflict, deleted, asked again
        List<String> classes = new ArrayList<>();
        all.path("units").forEach(unit -> classes.add(unit.path("class").stringValue(null)));
        List<String> order = CONTRACT.path("constants").path("classes").valueStream().map(node -> node.stringValue(null)).toList();
        assertThat(classes.stream().map(order::indexOf).toList()).isSorted();
        // the title, the first line of the revision shown
        for (JsonNode unit : all.path("units")) {
            assertThat(unit.path("title").isString() || unit.path("kind").stringValue(null).equals("ORDER")).isTrue();
            if (unit.path("title").isString()) assertThat(unit.path("title").stringValue(null).codePointCount(0, unit.path("title").stringValue(null).length())).isLessThanOrEqualTo(160);
        }
        JsonNode material = all.path("units").valueStream().filter(unit -> unit.path("unitId").stringValue("").equals("MATERIAL:" + m.get(4).member())).findFirst().orElseThrow();
        assertThat(material.path("title").stringValue(null)).isEqualTo("автор 4");
        assertThat(material.path("class").stringValue(null)).isEqualTo("SAFE");
        assertThat(material.path("defaultChoice").stringValue(null)).isEqualTo("ACCEPT");
        JsonNode metadata = all.path("units").valueStream().filter(unit -> unit.path("kind").stringValue("").equals("METADATA")).findFirst().orElseThrow();
        assertThat(metadata.path("title").stringValue(null)).isEqualTo("Второе издание");
        JsonNode exercise = all.path("units").valueStream().filter(unit -> unit.path("kind").stringValue("").equals("EXERCISE") && unit.path("class").stringValue("").equals("NEW"))
                .findFirst().orElseThrow();
        assertThat(exercise.path("title").stringValue(null)).isEqualTo("Что значит новое?");
    }

    @Test
    void aCopyThatIsUpToDateHasAnEmptyPlanAndTheSameShape() {
        Source source = worlds.source("LINK", 3, 1);
        Copy copy = worlds.copy(source);
        JsonNode plan = plan(copy, "");
        assertThat(names(plan)).isEqualTo(names(CONTRACT.path("upToDate").path("body")));
        assertThat(plan.path("state").stringValue(null)).isEqualTo("UP_TO_DATE");
        assertThat(plan.path("units")).isEmpty();
        assertThat(plan.path("baseRevisionId")).isEqualTo(plan.path("upstreamRevisionId"));
        assertThat(plan.path("forkHeadRevisionId").stringValue(null)).isEqualTo(worlds.headRevisionOf(copy.deck()).toString());
        assertThat(plan.path("summary")).isEqualTo(CONTRACT.path("upToDate").path("body").path("summary"));
        assertThat(plan.path("releaseNote").isNull()).isTrue();
        // a unit the learner changed alone is not on offer either
        worlds.editText(copy.owner(), copy.deck(), source.materials().get(0).member(), "мой текст");
        JsonNode mine = plan(copy, "");
        assertThat(mine.path("state").stringValue(null)).isEqualTo("UP_TO_DATE");
        assertThat(mine.path("planVersion")).as("the copy's head is a pin of the plan").isNotEqualTo(plan.path("planVersion"));
    }

    @Test
    void theCursorBelongsToThePlanItWasCutFromAndAnyChangeOfTheCopyOrTheSourceMakesItStale() {
        Scene scene = scene(8, 0, 6);
        JsonNode first = plan(scene.copy(), "?limit=2");
        String cursor = first.path("nextCursor").stringValue(null);
        assertThat(plan(scene.copy(), "?limit=2&cursor=" + cursor).path("units")).hasSize(2);

        // the learner edits his copy: the head moved, the old cursor is stale, the new plan has another version and starts again
        worlds.editText(scene.copy().owner(), scene.copy().deck(), scene.source().materials().get(7).member(), "мой");
        problem(worlds.get("/decks/" + scene.copy().deck() + "/update-plan?limit=2&cursor=" + cursor, scene.copy().owner()), 409, "UPDATE_PLAN_STALE");
        JsonNode second = plan(scene.copy(), "?limit=2");
        assertThat(second.path("planVersion")).isNotEqualTo(first.path("planVersion"));
        assertThat(second.path("forkHeadRevisionId")).isNotEqualTo(first.path("forkHeadRevisionId"));
        assertThat(second.path("upstreamRevisionId")).isEqualTo(first.path("upstreamRevisionId"));
        String cursor2 = second.path("nextCursor").stringValue(null);
        assertThat(plan(scene.copy(), "?limit=2&cursor=" + cursor2).path("units")).hasSize(2);

        // the author publishes again: the source's pin moved
        worlds.editText(scene.source().author(), scene.source().deck(), scene.source().materials().get(7).member(), "автор снова");
        worlds.publish(scene.source().author(), scene.source().deck(), "LINK");
        problem(worlds.get("/decks/" + scene.copy().deck() + "/update-plan?limit=2&cursor=" + cursor2, scene.copy().owner()), 409, "UPDATE_PLAN_STALE");
        JsonNode third = plan(scene.copy(), "?limit=2");
        assertThat(third.path("upstreamRevisionId")).isNotEqualTo(second.path("upstreamRevisionId"));
        assertThat(third.path("planVersion")).isNotEqualTo(second.path("planVersion"));
        String cursor3 = third.path("nextCursor").stringValue(null);

        // a decision is a pin too
        worlds.decide(scene.copy().deck(), "MATERIAL", scene.source().materials().get(0).member(), "KEEP_MINE", worlds.itemHead(scene.source().deck(), scene.source().materials().get(0).member()),
                false, null, false);
        problem(worlds.get("/decks/" + scene.copy().deck() + "/update-plan?limit=2&cursor=" + cursor3, scene.copy().owner()), 409, "UPDATE_PLAN_STALE");

        // the same state twice is the same plan version, on any page size
        assertThat(plan(scene.copy(), "?limit=3").path("planVersion")).isEqualTo(plan(scene.copy(), "?limit=100").path("planVersion"));
    }

    @Test
    void malformedQueriesAndCursorsAreRefusedWithoutDisclosure() {
        Scene scene = scene(5, 0, 4);
        String base = "/decks/" + scene.copy().deck() + "/update-plan";
        UUID owner = scene.copy().owner();
        for (String query : new String[] {"?limit=0", "?limit=101", "?limit=-1", "?limit=abc", "?limit=007", "?limit=1&limit=2", "?cursor=", "?cursor=%25%25", "?cursor=" + "A".repeat(300),
                "?cursor=Zm9v", "?limit=1&cursor=a&cursor=b"}) {
            problem(worlds.get(base + query, owner), 400, "INVALID_REQUEST");
        }
        // a cursor of another copy is malformed for this one
        Scene other = scene(5, 0, 4);
        String foreign = plan(other.copy(), "?limit=1").path("nextCursor").stringValue(null);
        problem(worlds.get(base + "?cursor=" + foreign, owner), 400, "INVALID_REQUEST");
        // the same malformed cursor against a foreign deck is also a 400, not a hint about the deck
        problem(worlds.get(base.replace(scene.copy().deck().toString(), UUID.randomUUID().toString()) + "?cursor=Zm9v", owner), 400, "INVALID_REQUEST");
        problem(worlds.get("/decks/not-a-uuid/update-plan", owner), 400, "INVALID_REQUEST");
    }

    // ---- access and the states of a plan ----

    @Test
    void onlyTheOwnerOfACopyReadsItsPlanAndNothingElseIsDisclosed() {
        Scene scene = scene(4, 1, 2);
        String path = "/decks/" + scene.copy().deck() + "/update-plan";
        UUID stranger = UUID.randomUUID();
        // the author, a stranger, an unknown deck, an ordinary deck of the copier's own and the source itself are one and the same 404
        problem(worlds.get(path, scene.source().author()), 404, "RESOURCE_NOT_FOUND");
        problem(worlds.get(path, stranger), 404, "RESOURCE_NOT_FOUND");
        problem(worlds.get("/decks/" + UUID.randomUUID() + "/update-plan", scene.copy().owner()), 404, "RESOURCE_NOT_FOUND");
        UUID ordinary = worlds.shared.deck(scene.copy().owner(), "Моя колода");
        problem(worlds.get("/decks/" + ordinary + "/update-plan", scene.copy().owner()), 404, "RESOURCE_NOT_FOUND");
        problem(worlds.get("/decks/" + scene.source().deck() + "/update-plan", scene.copy().owner()), 404, "RESOURCE_NOT_FOUND");
        problem(worlds.get("/decks/" + scene.source().deck() + "/update-plan", scene.source().author()), 404, "RESOURCE_NOT_FOUND");
        // the unit route too
        UUID member = scene.source().materials().get(0).member();
        String version = plan(scene.copy(), "").path("planVersion").stringValue(null);
        problem(worlds.get(path + "/units/MATERIAL/" + member + "?planVersion=" + version, stranger), 404, "RESOURCE_NOT_FOUND");
        problem(worlds.get(path + "/units/MATERIAL/" + member + "?planVersion=" + version, scene.source().author()), 404, "RESOURCE_NOT_FOUND");
        // authentication and scope: no token is 401, a token without learning.read is 403
        problem(worlds.send("GET", path, null, null, null), 401, "AUTHENTICATION_REQUIRED");
        HttpResponse<String> writeOnly = HttpIdentityFixture.send(port, "GET", path, HttpIdentityFixture.token(scene.copy().owner(), "learning.write"), null, Map.of());
        problem(writeOnly, 403, "ACCESS_DENIED");
        // a deleted copy is gone
        assertThat(worlds.send("DELETE", "/decks/" + scene.copy().deck(), scene.copy().owner(), null,
                "\"" + decks.read(scene.copy().owner(), scene.copy().deck()).path("rowVersion").stringValue(null) + "\"").statusCode()).isEqualTo(204);
        problem(worlds.get(path, scene.copy().owner()), 404, "RESOURCE_NOT_FOUND");
    }

    private void closed(Copy copy, String state) {
        JsonNode plan = plan(copy, "");
        assertThat(plan.path("state").stringValue(null)).isEqualTo(state);
        assertThat(names(plan)).isEqualTo(names(CONTRACT.path("closed").path("body")));
        assertThat(plan.path("units")).isEmpty();
        assertThat(plan.path("nextCursor").isNull()).isTrue();
        assertThat(plan.toString()).as("nothing about the source").doesNotContain("planVersion", "upstream", "summary");
        // and no unit of it is readable
        problem(unit(copy, "METADATA", copy.deck(), "sha256:" + "0".repeat(64)), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void aCopyThatIsPreparingDetachedOrWhoseSourceIsGoneHasNoPlanAndNothingIsSaidOfTheSource() {
        // preparing: the net of the database is lifted for the test the way the fixtures lift it
        Scene preparing = scene(3, 0, 2);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("UPDATE app_learning.deck_fork SET materialized_at = NULL WHERE deck_id = :d").param("d", preparing.copy().deck()).update();
        });
        closed(preparing.copy(), "PREPARING");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.sql("SET LOCAL session_replication_role = replica").update();
            jdbc.sql("UPDATE app_learning.deck_fork SET materialized_at = clock_timestamp() WHERE deck_id = :d").param("d", preparing.copy().deck()).update();
        });
        assertThat(plan(preparing.copy(), "").path("state").stringValue(null)).isEqualTo("READY");

        // detached: the owner's decision
        assertThat(worlds.send("POST", "/decks/" + preparing.copy().deck() + "/fork:detach", preparing.copy().owner(), null, null).statusCode()).isEqualTo(200);
        closed(preparing.copy(), "DETACHED");

        // the source went private
        Scene priv = scene(3, 0, 2);
        worlds.level(priv.source().author(), priv.source().deck(), "PRIVATE");
        closed(priv.copy(), "SOURCE_UNAVAILABLE");

        // its code was rotated away by lowering the level: the copy's stored code stops working, and a new public code does not bring it back
        Source public_ = worlds.source("PUBLIC", 3, 0);
        Copy publicCopy = worlds.copy(public_);
        worlds.editText(public_.author(), public_.deck(), public_.materials().get(0).member(), "автор");
        worlds.publish(public_.author(), public_.deck(), "PUBLIC");
        assertThat(plan(publicCopy, "").path("state").stringValue(null)).isEqualTo("READY");
        worlds.level(public_.author(), public_.deck(), "LINK");
        closed(publicCopy, "SOURCE_UNAVAILABLE");

        // hidden by moderation
        Scene hidden = scene(3, 0, 2);
        Boolean didHide = new TransactionTemplate(transactions).execute(status -> moderation.hide(hidden.source().deck(), java.time.Instant.now()));
        assertThat(didHide).isTrue();
        closed(hidden.copy(), "SOURCE_UNAVAILABLE");

        // deleted
        Scene deleted = scene(3, 0, 2);
        assertThat(worlds.send("DELETE", "/decks/" + deleted.source().deck(), deleted.source().author(), null,
                "\"" + decks.read(deleted.source().author(), deleted.source().deck()).path("rowVersion").stringValue(null) + "\"").statusCode()).isEqualTo(204);
        closed(deleted.copy(), "SOURCE_UNAVAILABLE");
    }

    // ---- reading writes nothing ----

    private String everything() {
        List<String> tables = jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'app_learning' AND table_type = 'BASE TABLE' ORDER BY table_name")
                .query(String.class).list();
        StringBuilder snapshot = new StringBuilder();
        for (String table : tables) {
            // every row's text, hashed: an insert, an update and a delete all change it
            snapshot.append(table).append('=').append(jdbc.sql("SELECT count(*) || ':' || COALESCE(md5(string_agg(md5(t::text), ',' ORDER BY md5(t::text))), '') FROM app_learning." + table + " t")
                    .query(String.class).single()).append('\n');
        }
        return snapshot.toString();
    }

    @Test
    void readingAPlanAndItsUnitsWritesNothingAnywhere() {
        Scene scene = scene(6, 2, 4);
        Source source = scene.source();
        worlds.deleteMaterial(source.author(), source.deck(), source.materials().get(5).member());
        worlds.revise(source.author(), source.deck(), source.materials().get(0), source.exercises().get(0), "Автор: вопрос", "Автор: цель");
        worlds.retitle(source.author(), source.deck(), "Новое издание", "Описание");
        worlds.reorder(source.author(), source.deck(), source.materials().get(4).member(), 0);
        worlds.publish(source.author(), source.deck(), "LINK");
        JsonNode warm = plan(scene.copy(), "?limit=100");
        String version = warm.path("planVersion").stringValue(null);
        assertThat(warm.path("units").size()).isGreaterThan(6);

        String before = everything();
        plan(scene.copy(), "?limit=100");
        plan(scene.copy(), "?limit=3");
        for (JsonNode unit : warm.path("units")) {
            HttpResponse<String> response = unit(scene.copy(), unit.path("kind").stringValue(null), UUID.fromString(unit.path("key").stringValue(null)), version);
            assertThat(response.statusCode()).as(unit.path("unitId").stringValue(null) + " " + response.body()).isEqualTo(200);
        }
        assertThat(everything()).as("no table changed").isEqualTo(before);
    }

    // ---- the two sides of one unit ----

    @Test
    void theTwoSidesOfAUnitAreTheCopiesAndTheAuthorsAndOnlyOfAUnitOfThePlan() {
        Source source = worlds.source("LINK", 8, 3);
        Copy copy = worlds.copy(source);
        UUID author = source.author();
        UUID deck = source.deck();
        List<Material> m = source.materials();
        List<Authored> e = source.exercises();
        worlds.editText(author, deck, m.get(0).member(), "автор нуль");
        worlds.editText(author, deck, m.get(1).member(), "автор один");
        worlds.editText(copy.owner(), copy.deck(), m.get(1).member(), "ученик один");
        worlds.deleteMaterial(author, deck, m.get(7).member());
        Authored revised = worlds.revise(author, deck, m.get(2), e.get(2), "Автор: вопрос два", "Автор: цель два");
        worlds.deleteExercise(author, deck, e.get(1).exerciseId());
        Material added = worlds.study.addMaterial(author, deck, "новое", "слово");
        Authored brandNew = worlds.exercise(added, "Новый вопрос", added.node(), "Новая цель");
        worlds.retitle(author, deck, "Заголовок автора", "Описание автора");
        worlds.reorder(author, deck, m.get(6).member(), 0);
        worlds.publish(author, deck, "LINK");
        JsonNode plan = plan(copy, "?limit=100");
        String version = plan.path("planVersion").stringValue(null);

        // a material: both documents of the editor, and the node whose text differs
        HttpResponse<String> response = unit(copy, "MATERIAL", m.get(1).member(), version);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("cache-control")).hasValue("private, no-store");
        JsonNode side = json(response);
        assertThat(names(side)).isEqualTo(names(CONTRACT.path("unit").path("material").path("body")));
        assertThat(names(side.path("mine"))).isEqualTo(names(CONTRACT.path("unit").path("material").path("body").path("mine")));
        assertThat(side.path("class").stringValue(null)).isEqualTo("CONFLICT");
        assertThat(side.path("planVersion").stringValue(null)).isEqualTo(version);
        assertThat(side.path("mine").path("itemRevisionId").stringValue(null)).isEqualTo(worlds.itemHead(copy.deck(), m.get(1).member()).toString());
        assertThat(side.path("author").path("itemRevisionId").stringValue(null)).isEqualTo(worlds.itemHead(deck, m.get(1).member()).toString());
        String leafOfFirstParagraph = side.path("mine").path("document").path("root").path("content").get(0).path("content").get(0).path("id").stringValue(null);
        assertThat(side.path("mine").path("document").path("root").path("content").get(0).path("content").get(0).path("attrs").path("text").stringValue(null)).isEqualTo("ученик один");
        assertThat(side.path("author").path("document").path("root").path("content").get(0).path("content").get(0).path("attrs").path("text").stringValue(null)).isEqualTo("автор один");
        assertThat(side.path("changedNodeIds")).extracting(JsonNode::stringValue).containsExactly(leafOfFirstParagraph);

        // a removed material: only the copy has it; a new one: only the author has it
        JsonNode removed = json(unit(copy, "MATERIAL", m.get(7).member(), version));
        assertThat(removed.path("author").isNull()).isTrue();
        assertThat(removed.path("mine").path("document").isObject()).isTrue();
        assertThat(removed.path("changedNodeIds")).isEmpty();
        JsonNode created = json(unit(copy, "MATERIAL", added.member(), version));
        assertThat(created.path("mine").isNull()).isTrue();
        assertThat(created.path("author").path("document").isObject()).isTrue();

        // an exercise: the revision as the exercise read returns it, answer key included
        JsonNode exercise = json(unit(copy, "EXERCISE", e.get(2).exerciseId(), version));
        assertThat(names(exercise)).isEqualTo(names(CONTRACT.path("unit").path("exercise").path("body")));
        assertThat(names(exercise.path("author"))).isEqualTo(names(CONTRACT.path("unit").path("exercise").path("body").path("author")));
        assertThat(exercise.path("author").path("exerciseRevisionId").stringValue(null)).isEqualTo(revised.revisionId().toString());
        assertThat(exercise.path("mine").path("exerciseRevisionId").stringValue(null)).isEqualTo(e.get(2).revisionId().toString());
        assertThat(exercise.path("author").path("content").path("prompt").get(0).path("text").stringValue(null)).isEqualTo("Автор: вопрос два");
        assertThat(exercise.path("mine").path("content").path("prompt").get(0).path("text").stringValue(null)).isEqualTo("Что значит 2?");
        assertThat(exercise.path("author").path("answerKey").isObject()).isTrue();
        assertThat(exercise.path("author").path("objective").path("title").stringValue(null)).isEqualTo("Автор: цель два");
        assertThat(exercise.path("mine").path("objective").path("title").stringValue(null)).isEqualTo("Цель 2");
        assertThat(exercise.path("author").path("subject").path("memberKey").stringValue(null)).isEqualTo(m.get(2).member().toString());
        JsonNode gone = json(unit(copy, "EXERCISE", e.get(1).exerciseId(), version));
        assertThat(gone.path("class").stringValue(null)).isEqualTo("DELETED");
        assertThat(gone.path("author").isNull()).isTrue();
        assertThat(gone.path("mine").path("answerKey").isObject()).isTrue();
        assertThat(json(unit(copy, "EXERCISE", brandNew.exerciseId(), version)).path("mine").isNull()).isTrue();

        // the objective, the deck's text and the order
        JsonNode objective = json(unit(copy, "OBJECTIVE", revised.objectiveId(), version));
        assertThat(names(objective.path("author"))).isEqualTo(names(CONTRACT.path("unit").path("objective").path("side")));
        assertThat(objective.path("author").path("title").stringValue(null)).isEqualTo("Автор: цель два");
        assertThat(objective.path("mine").path("title").stringValue(null)).isEqualTo("Цель 2");
        JsonNode metadata = json(unit(copy, "METADATA", copy.deck(), version));
        assertThat(names(metadata.path("author"))).isEqualTo(names(CONTRACT.path("unit").path("metadata").path("side")));
        assertThat(metadata.path("author").path("title").stringValue(null)).isEqualTo("Заголовок автора");
        assertThat(metadata.path("mine").path("title").stringValue(null)).isEqualTo(decks.read(copy.owner(), copy.deck()).path("metadata").path("title").stringValue(null));
        JsonNode order = json(unit(copy, "ORDER", copy.deck(), version));
        assertThat(names(order.path("author"))).isEqualTo(names(CONTRACT.path("unit").path("order").path("side")));
        JsonNode movedByAuthor = order.path("author").path("moved");
        assertThat(movedByAuthor).hasSize(1);
        assertThat(movedByAuthor.get(0).path("memberKey").stringValue(null)).isEqualTo(m.get(6).member().toString());
        assertThat(movedByAuthor.get(0).path("from").intValue()).isEqualTo(6);
        assertThat(movedByAuthor.get(0).path("to").intValue()).isZero();
        assertThat(movedByAuthor.get(0).path("title").stringValue(null)).isEqualTo("слово 6");
        assertThat(order.path("author").path("truncated").booleanValue(true)).isFalse();
        assertThat(order.path("mine").path("moved")).isEmpty();

        // never a way to read a revision of the lineage: a unit that is not in the plan is the opaque 404 whatever it is
        problem(unit(copy, "MATERIAL", m.get(3).member(), version), 404, "RESOURCE_NOT_FOUND");
        problem(unit(copy, "EXERCISE", m.get(1).member(), version), 404, "RESOURCE_NOT_FOUND");
        problem(unit(copy, "MATERIAL", e.get(2).exerciseId(), version), 404, "RESOURCE_NOT_FOUND");
        problem(unit(copy, "MATERIAL", UUID.randomUUID(), version), 404, "RESOURCE_NOT_FOUND");
        problem(unit(copy, "METADATA", UUID.randomUUID(), version), 404, "RESOURCE_NOT_FOUND");
        problem(unit(copy, "ORDER", m.get(1).member(), version), 404, "RESOURCE_NOT_FOUND");
        Source foreign = worlds.source("LINK", 2, 0);
        problem(unit(copy, "MATERIAL", foreign.materials().get(0).member(), version), 404, "RESOURCE_NOT_FOUND");
        // kind, key and version are validated; a version that is not the plan's is stale
        problem(unit(copy, "material", m.get(1).member(), version), 400, "INVALID_REQUEST");
        problem(unit(copy, "NOPE", m.get(1).member(), version), 400, "INVALID_REQUEST");
        problem(worlds.get("/decks/" + copy.deck() + "/update-plan/units/MATERIAL/not-a-uuid?planVersion=" + version, copy.owner()), 400, "INVALID_REQUEST");
        problem(unit(copy, "MATERIAL", m.get(1).member(), null), 400, "INVALID_REQUEST");
        problem(unit(copy, "MATERIAL", m.get(1).member(), "sha256:xyz"), 400, "INVALID_REQUEST");
        problem(unit(copy, "MATERIAL", m.get(1).member(), "sha256:" + "0".repeat(64)), 409, "UPDATE_PLAN_STALE");
        problem(worlds.get("/decks/" + copy.deck() + "/update-plan/units/MATERIAL/" + m.get(1).member() + "?planVersion=" + version + "&planVersion=" + version, copy.owner()), 400, "INVALID_REQUEST");
        // and after the learner edits the unit the version is stale even though the unit is still in the plan
        worlds.editText(copy.owner(), copy.deck(), m.get(0).member(), "ученик нуль");
        problem(unit(copy, "MATERIAL", m.get(1).member(), version), 409, "UPDATE_PLAN_STALE");
    }

    @Test
    void theProblemBodiesAreTheOnesOfTheContract() {
        Scene scene = scene(3, 0, 2);
        String path = "/decks/" + scene.copy().deck() + "/update-plan";
        for (String name : new String[] {"notFound", "invalidRequest", "stale"}) {
            JsonNode expected = CONTRACT.path("problems").path(name).path("body");
            HttpResponse<String> response = switch (name) {
                case "notFound" -> worlds.get("/decks/" + UUID.randomUUID() + "/update-plan", scene.copy().owner());
                case "invalidRequest" -> worlds.get(path + "?limit=0", scene.copy().owner());
                default -> unit(scene.copy(), "MATERIAL", scene.edited().get(0), "sha256:" + "0".repeat(64));
            };
            assertThat(response.statusCode()).isEqualTo(CONTRACT.path("problems").path(name).path("status").intValue());
            JsonNode actual = json(response);
            assertThat(names(actual)).isEqualTo(names(expected));
            for (String field : new String[] {"type", "title", "status", "detail", "code"}) assertThat(actual.path(field)).as(name + "." + field).isEqualTo(expected.path(field));
        }
    }

    @Test
    void anInvitationSourceWhoseGrantWasRevokedIsUnavailableAndTheCopierIsNotToldMore() {
        Source source = worlds.source("INVITE", 3, 0);
        UUID reader = UUID.randomUUID();
        publications.grant(source.author(), source.deck(), reader, app.mnema.learning.library.GrantRole.VIEWER);
        UUID deck = worlds.forked(reader, source.code());
        worlds.awaitReady(reader, deck);
        Copy copy = new Copy(reader, deck);
        worlds.editText(source.author(), source.deck(), source.materials().get(0).member(), "автор");
        worlds.publish(source.author(), source.deck(), "INVITE");
        assertThat(plan(copy, "").path("state").stringValue(null)).as("a grantee reads the plan").isEqualTo("READY");

        publications.revoke(source.author(), source.deck(), reader);
        closed(copy, "SOURCE_UNAVAILABLE");
        // and a granted reader again is a reader again
        publications.grant(source.author(), source.deck(), reader, app.mnema.learning.library.GrantRole.VIEWER);
        assertThat(plan(copy, "").path("state").stringValue(null)).isEqualTo("READY");
    }
}
