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
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static app.mnema.learning.update.plan.PlanWorlds.json;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Updates/3 (#442), the two limits of a plan with the strictest settings: {@code compute-budget=PT0S} (the time for reading documents is spent before the first one, so everything that
 * needs a document is judged cautiously and the plan is still answered) and {@code max-concurrent=1} (the one place can be held by the test, and the next request is a 503 with
 * nothing computed).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"learning.runtime.roles=worker", "learning.community.forks.slice-size=10", "learning.jobs.sweep-interval=PT0.5S", "learning.updates.plan.compute-budget=PT0S",
                "learning.updates.plan.max-concurrent=1", "learning.ai.provider=stub", "learning.features.speech-to-text.enabled=true", "learning.speech.sweep-interval=PT5M",
                "learning.generation.worker.sweep-interval=PT5M", "learning.ai.assess.sweep-interval=PT15S"})
class UpdatePlanLimitsIntegrationTest extends PostgresIntegrationTest {
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
    @Autowired private UpdatePlanService service;
    @Autowired private UpdatePlanBulkhead bulkhead;
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

    @Test
    void withNoTimeLeftEveryComparisonThatNeedsADocumentIsJudgedCautiouslyAndThePlanIsStillAnswered() {
        Source source = worlds.source("LINK", 4, 0);
        List<Material> m = source.materials();
        Copy copy = worlds.copy(source);
        // two units both sides changed to the same content: SAFE with time, CONFLICT without
        for (int index = 0; index < 2; index++) {
            worlds.editText(source.author(), source.deck(), m.get(index).member(), "общий текст " + index);
            worlds.editText(copy.owner(), copy.deck(), m.get(index).member(), "общий текст " + index);
        }
        // an exercise on a non-head revision whose quoted node the copy's head still has: NEW with time, MATERIAL_CHANGED without
        Authored quoting = worlds.exercise(worlds.head(source.author(), source.deck(), m.get(2)), "Вопрос с цитатой", m.get(2).node(), "Цель цитаты");
        worlds.editText(source.author(), source.deck(), m.get(2).member(), "автор правит");
        worlds.editSecond(copy.owner(), copy.deck(), m.get(2).member(), "ученик правит");
        worlds.publish(source.author(), source.deck(), "LINK");

        long started = System.nanoTime();
        UpdatePlan plan = service.plan(copy.owner(), copy.deck());
        assertThat((System.nanoTime() - started) / 1_000_000).as("answered, not blocked").isLessThan(10_000);
        Map<UUID, PlanUnit> units = new java.util.HashMap<>();
        plan.units().forEach(unit -> units.put(unit.key(), unit));
        assertThat(units.get(m.get(0).member()).unitClass()).isEqualTo(UnitClass.CONFLICT);
        assertThat(units.get(m.get(0).member()).reason()).as("the reason is the one of the question: both changed").isEqualTo(Reason.BOTH_CHANGED);
        assertThat(units.get(m.get(1).member()).unitClass()).isEqualTo(UnitClass.CONFLICT);
        PlanUnit exercise = units.get(quoting.exerciseId());
        assertThat(exercise.unitClass()).isEqualTo(UnitClass.CONFLICT);
        assertThat(exercise.reason()).isEqualTo(Reason.MATERIAL_CHANGED);
        // the page is answered all the same
        assertThat(service.page(copy.owner(), copy.deck(), null, "100").path("units").size()).isEqualTo(plan.units().size());
    }

    @Test
    void theNextRequestIsRefusedAtOnceWithNothingComputedWhileTheOnlyPlaceIsTaken() {
        Source source = worlds.source("LINK", 3, 0);
        Copy copy = worlds.copy(source);
        worlds.editText(source.author(), source.deck(), source.materials().get(0).member(), "автор");
        worlds.publish(source.author(), source.deck(), "LINK");
        String path = "/decks/" + copy.deck() + "/update-plan";
        String version = json(worlds.get(path, copy.owner())).path("planVersion").stringValue(null);
        assertThat(bulkhead.tryAcquire()).isTrue();
        try {
            for (String request : new String[] {path, path + "?limit=1", path + "/units/MATERIAL/" + source.materials().get(0).member() + "?planVersion=" + version}) {
                HttpResponse<String> busy = worlds.get(request, copy.owner());
                assertThat(busy.statusCode()).as(request + " " + busy.body()).isEqualTo(503);
                assertThat(busy.headers().firstValue("retry-after")).hasValue("1");
                assertThat(busy.headers().firstValue("content-type")).hasValueSatisfying(value -> assertThat(value).startsWith("application/problem+json"));
                JsonNode body = json(busy);
                JsonNode expected = CONTRACT.path("problems").path("busy").path("body");
                for (String field : new String[] {"type", "title", "status", "detail", "code", "retryAfter"}) assertThat(body.path(field)).as(field).isEqualTo(expected.path(field));
            }
            // even a request that would be a 404 or a 400 is not looked at: nothing is computed
            assertThat(worlds.get("/decks/" + UUID.randomUUID() + "/update-plan", copy.owner()).statusCode()).isEqualTo(503);
        } finally {
            bulkhead.release();
        }
        assertThat(worlds.get(path, copy.owner()).statusCode()).as("the place is given back").isEqualTo(200);
        assertThat(worlds.get(path, copy.owner()).statusCode()).as("and again after a successful request").isEqualTo(200);
        // a failed request gives its place back too
        assertThat(worlds.get("/decks/" + UUID.randomUUID() + "/update-plan", copy.owner()).statusCode()).isEqualTo(404);
        assertThat(worlds.get(path, copy.owner()).statusCode()).isEqualTo(200);
    }
}
