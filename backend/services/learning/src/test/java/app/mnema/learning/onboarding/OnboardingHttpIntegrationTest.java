package app.mnema.learning.onboarding;

import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.experiment.ExperimentAssignments;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.support.HttpIdentityFixture;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.StudyFixtures;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static app.mnema.learning.onboarding.OnboardingFixture.CONTRACT;
import static app.mnema.learning.onboarding.OnboardingFixture.JSON;
import static app.mnema.learning.onboarding.OnboardingFixture.copy;
import static app.mnema.learning.support.StudyFixtures.blocks;
import static app.mnema.learning.support.StudyFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The first-entry journey over the real servlet stack, the real security filters and a real PostgreSQL, with the examples of
 * {@code contracts/onboarding/journey.json} executed as they are written: eligibility, the exactly-once offer under concurrent first reads, the holdout cohort,
 * every transition of the table, idempotent and compare-and-set commands, the strict body, the ETag, and the owner's report.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "learning.onboarding.enabled=true", "learning.onboarding.eligible-from=2026-01-01T00:00:00Z",
        "learning.experiments.onboarding_v1.enabled=true", "learning.experiment-secret=onboarding-http-test-secret"})
class OnboardingHttpIntegrationTest extends PostgresIntegrationTest {
    private static final UUID CONSOLE = UUID.randomUUID();
    private static final Map<String, String> NONE = Map.of();

    @LocalServerPort private int port;
    @Autowired private JdbcClient jdbc;
    @Autowired private ExperimentAssignments assignments;
    @Autowired private MeterRegistry meters;
    @Autowired private DeckService decks;
    @Autowired private ItemService items;
    @Autowired private ExerciseService exercises;
    @Autowired private StudySessionService sessions;
    @Autowired private MediaCatalog media;
    private UUID templateSession;
    private List<String> sessionColumns;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        HttpIdentityFixture.register(registry);
        registry.add("learning.admin.owner-id", CONSOLE::toString);
    }

    // ---- helpers ----

    private HttpResponse<String> get(UUID account, Map<String, String> headers) {
        return HttpIdentityFixture.send(port, "GET", "/onboarding", HttpIdentityFixture.reader(account), null, headers);
    }

    private HttpResponse<String> get(UUID account) {
        return get(account, NONE);
    }

    private HttpResponse<String> journey(UUID account, JsonNode body) {
        return HttpIdentityFixture.send(port, "PUT", "/onboarding/journey", HttpIdentityFixture.reader(account), body.toString(), NONE);
    }

    private HttpResponse<String> preferences(UUID account, JsonNode body) {
        return HttpIdentityFixture.send(port, "PUT", "/onboarding/preferences", HttpIdentityFixture.reader(account), body.toString(), NONE);
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private static ObjectNode command(String rowVersion, String action, String stepKey, String branch) {
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString());
        if (rowVersion != null) body.put("rowVersion", rowVersion);
        if (action != null) body.put("action", action);
        if (stepKey != null) body.put("stepKey", stepKey);
        if (branch != null) body.put("branch", branch);
        return body;
    }

    private static ObjectNode preference(String rowVersion) {
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString());
        if (rowVersion != null) body.put("rowVersion", rowVersion);
        return body;
    }

    /** A brand-new account: Identity marks it new, so it is eligible. */
    private static UUID newAccount() {
        UUID account = UUID.randomUUID();
        HttpIdentityFixture.signals(account, false, true);
        return account;
    }

    private UUID newAccountIn(String variant) {
        for (int attempt = 0; attempt < 10_000; attempt++) {
            UUID account = newAccount();
            if (assignments.assigned(account, "onboarding_v1").orElseThrow().equals(variant)) return account;
        }
        throw new AssertionError("no account in " + variant);
    }

    private long rows(UUID account) {
        return jdbc.sql("SELECT count(*) FROM app_learning.onboarding_journey WHERE account_id=:a").param("a", account).query(Long.class).single();
    }

    private String column(UUID account, String column) {
        return jdbc.sql("SELECT " + column + "::text FROM app_learning.onboarding_journey WHERE account_id=:a").param("a", account)
                .query((row, number) -> row.getString(1)).list().getFirst();
    }

    /** A row in the given state at version {@code version}, as an earlier session of the account would have left it. */
    private void seed(UUID account, String state, String cohort, String branch, String stepKey, long version) {
        jdbc.sql("""
                INSERT INTO app_learning.onboarding_journey(account_id, tour_version, state, cohort, branch, step_key, offered_at, finished_at, row_version, updated_at)
                VALUES (:a, 1, :state, :cohort, :branch, :step, statement_timestamp(),
                        CASE WHEN :state IN ('SKIPPED','COMPLETED') THEN statement_timestamp() END, :version, statement_timestamp())
                """).param("a", account).param("state", state).param("cohort", cohort, Types.VARCHAR).param("branch", branch, Types.VARCHAR)
                .param("step", stepKey, Types.VARCHAR).param("version", version).update();
    }

    private double exposures() {
        return meters.find("mnema_experiment_events_total").tag("key", "onboarding_v1").tag("event", "EXPOSURE").counters().stream()
                .mapToDouble(Counter::count).sum();
    }

    private double steps(String action, String step) {
        Counter counter = meters.find("mnema_onboarding_journey_total").tag("action", action).tag("step", step).counter();
        return counter == null ? 0 : counter.count();
    }

    /** The contract view with the server's {@code offeredAt} (the fixture has a fixed one). */
    private static JsonNode contractView(String name, JsonNode actual) {
        ObjectNode expected = copy(CONTRACT.path("views").path(name));
        if (expected.path("journey").isObject() && !expected.path("journey").path("offeredAt").isNull()) {
            ((ObjectNode) expected.path("journey")).set("offeredAt", actual.path("journey").path("offeredAt"));
        }
        return expected;
    }

    private static void assertProblem(HttpResponse<String> response, String contractName, String path) {
        JsonNode expected = CONTRACT.path("problems").path(contractName);
        JsonNode actual = json(response);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected.path("status").intValue());
        for (String member : new String[] {"type", "title", "detail", "code"}) assertThat(actual.path(member)).as(member).isEqualTo(expected.path(member));
        assertThat(actual.path("instance").stringValue()).isEqualTo(path);
        assertThat(expected.path("instance").stringValue()).isEqualTo(path);
    }

    private static void assertCode(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json(response).path("code").stringValue()).isEqualTo(code);
    }

    // ---- eligibility and the first read ----

    @Test
    void theFirstReadOfANewAccountOffersTheTourOnceAndTheViewIsTheContractView() {
        UUID account = newAccountIn("tour");
        double before = exposures();

        HttpResponse<String> first = get(account);

        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(first.headers().firstValue("Cache-Control")).contains("private, no-store");
        JsonNode view = json(first);
        assertThat(view).isEqualTo(contractView("offered", view));
        assertThat(Instant.parse(view.path("journey").path("offeredAt").stringValue())).isBetween(Instant.now().minus(Duration.ofMinutes(2)), Instant.now().plusSeconds(5));
        assertThat(rows(account)).isOne();
        assertThat(column(account, "cohort")).isEqualTo("TOUR");
        assertThat(exposures() - before).isEqualTo(1.0);

        HttpResponse<String> again = get(account);
        assertThat(json(again)).isEqualTo(view);
        assertThat(rows(account)).isOne();
        assertThat(exposures() - before).isEqualTo(1.0);
    }

    @Test
    void concurrentFirstReadsCreateExactlyOneRowAndCountOneExposure() throws Exception {
        UUID account = newAccount();
        double before = exposures();
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> answers = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < threads; index++) {
                answers.add(pool.submit(() -> {
                    start.await();
                    return get(account);
                }));
            }
            start.countDown();
            Instant offeredAt = null;
            for (Future<HttpResponse<String>> answer : answers) {
                HttpResponse<String> response = answer.get();
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                assertThat(json(response).path("journey").path("state").stringValue()).isEqualTo("OFFERED");
                Instant seen = Instant.parse(json(response).path("journey").path("offeredAt").stringValue());
                if (offeredAt == null) offeredAt = seen;
                assertThat(seen).isEqualTo(offeredAt);
            }
        } catch (ExecutionException failure) {
            throw new AssertionError(failure);
        }
        assertThat(rows(account)).isOne();
        assertThat(exposures() - before).isEqualTo(1.0);
    }

    @Test
    void anAccountThatIsNotNewOrHasADeckOrHasNoClaimGetsNotOfferedAndNoRow() {
        UUID old = UUID.randomUUID();
        HttpIdentityFixture.signals(old, true, false);
        UUID silent = UUID.randomUUID();
        HttpIdentityFixture.withoutSignals(silent);
        UUID owner = newAccount();
        new StudyFixtures(decks, items, exercises, sessions, media, jdbc).material(owner);

        for (UUID account : new UUID[] {old, silent, owner}) {
            JsonNode view = json(get(account));
            assertThat(view).isEqualTo(CONTRACT.path("views").path("notOffered"));
            assertThat(rows(account)).isZero();
        }
    }

    @Test
    void aNewAccountWhoseOnlyDeckWasDeletedIsStillOffered() {
        UUID account = newAccount();
        StudyFixtures.Material material = new StudyFixtures(decks, items, exercises, sessions, media, jdbc).material(account);
        assertThat(json(get(account)).path("journey").path("state").stringValue()).isEqualTo("NOT_OFFERED");

        decks.delete(account, material.deck(), Long.parseLong(decks.read(account, material.deck()).path("rowVersion").stringValue()));

        assertThat(json(get(account)).path("journey").path("state").stringValue()).isEqualTo("OFFERED");
        assertThat(rows(account)).isOne();
    }

    @Test
    void anAccountInTheHoldoutIsRecordedAsControlAndIsNotAutoOffered() {
        UUID held = newAccountIn("control");

        JsonNode view = json(get(held));

        assertThat(view).isEqualTo(contractView("holdout", view));
        assertThat(column(held, "cohort")).isEqualTo("CONTROL");
        // the holdout can still take the tour from the profile
        JsonNode started = json(journey(held, command("0", "REPLAY", "choose", null)));
        assertThat(started.path("journey").path("state").stringValue()).isEqualTo("IN_PROGRESS");
        assertThat(started.path("journey").path("replayCount").intValue()).isOne();
        assertThat(started.path("journey").path("eligible").booleanValue()).isTrue();
        assertThat(started.path("journey").path("autoOffer").booleanValue()).isFalse();
    }

    @Test
    void authenticationAndScopesAreTheBlanketRulesOfTheChain() {
        UUID account = newAccount();
        assertThat(HttpIdentityFixture.send(port, "GET", "/onboarding", null, null, NONE).statusCode()).isEqualTo(401);
        String readOnly = HttpIdentityFixture.token(account, "learning.read");
        assertThat(HttpIdentityFixture.send(port, "GET", "/onboarding", readOnly, null, NONE).statusCode()).isEqualTo(200);
        assertThat(HttpIdentityFixture.send(port, "PUT", "/onboarding/journey", readOnly, command("0", "REPLAY", "choose", null).toString(), NONE).statusCode())
                .isEqualTo(403);
        assertThat(HttpIdentityFixture.send(port, "PUT", "/onboarding/journey", null, command("0", "REPLAY", "choose", null).toString(), NONE).statusCode())
                .isEqualTo(401);
    }

    // ---- ETag ----

    @Test
    void theViewHasAStrongEtagThatChangesWithItAndAMatchingIfNoneMatchIs304() {
        UUID account = newAccount();
        HttpResponse<String> first = get(account);
        String tag = first.headers().firstValue("ETag").orElseThrow();
        assertThat(tag).matches("\"[0-9a-f]{32}\"");

        HttpResponse<String> unchanged = get(account, Map.of("If-None-Match", tag));
        assertThat(unchanged.statusCode()).isEqualTo(304);
        assertThat(unchanged.body()).isEmpty();
        assertThat(unchanged.headers().firstValue("ETag")).contains(tag);
        assertThat(unchanged.headers().firstValue("Cache-Control")).contains("private, no-store");
        assertThat(get(account, Map.of("If-None-Match", "W/" + tag)).statusCode()).isEqualTo(304);
        assertThat(get(account, Map.of("If-None-Match", "\"other\", " + tag)).statusCode()).isEqualTo(304);
        assertThat(get(account, Map.of("If-None-Match", "*")).statusCode()).isEqualTo(304);
        assertThat(get(account, Map.of("If-None-Match", "\"other\"")).statusCode()).isEqualTo(200);

        HttpResponse<String> changed = journey(account, command("0", "START", "welcome", null));
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
        String next = changed.headers().firstValue("ETag").orElseThrow();
        assertThat(next).isNotEqualTo(tag);
        assertThat(get(account, Map.of("If-None-Match", tag)).statusCode()).isEqualTo(200);
        HttpResponse<String> read = get(account, Map.of("If-None-Match", next));
        assertThat(read.statusCode()).isEqualTo(304);
    }

    // ---- the transition table ----

    @Test
    void everyActionFromEveryStateFollowsTheFixtureTable() {
        JsonNode table = CONTRACT.path("transitions");
        for (JsonNode action : CONTRACT.path("constants").path("actions")) {
            JsonNode rule = table.path(action.stringValue());
            for (JsonNode state : CONTRACT.path("constants").path("states")) {
                UUID account = UUID.randomUUID();
                HttpIdentityFixture.signals(account, false, false);
                boolean noRow = state.stringValue().equals("NOT_OFFERED");
                if (!noRow) seed(account, state.stringValue(), "TOUR", null, "welcome", 3);
                String step = rule.path("stepKey").stringValue().equals("required") ? "choose" : null;
                String label = action.stringValue() + " from " + state.stringValue();

                HttpResponse<String> response = journey(account, command(noRow ? "0" : "3", action.stringValue(), step, null));

                String target = rule.path("from").path(state.stringValue()).stringValue(null);
                if (target == null) {
                    assertCode(response, 409, "ONBOARDING_TRANSITION");
                    assertProblem(response, "transition", "/api/onboarding/journey");
                    assertThat(rows(account)).as(label).isEqualTo(noRow ? 0 : 1);
                } else {
                    assertThat(response.statusCode()).as(label + ": " + response.body()).isEqualTo(200);
                    JsonNode journey = json(response).path("journey");
                    assertThat(journey.path("state").stringValue()).as(label).isEqualTo(target);
                    assertThat(journey.path("rowVersion").stringValue()).as(label).isEqualTo(noRow ? "0" : "4");
                    assertThat(journey.path("finishedAt").isNull()).as(label).isEqualTo(!List.of("SKIPPED", "COMPLETED").contains(target));
                }
            }
        }
    }

    @Test
    void aWholeTourWalksThroughTheStatesWithTheirEffects() {
        UUID account = newAccountIn("tour");
        JsonNode offered = json(get(account)).path("journey");
        double started = steps("START", "survey.goal");

        JsonNode view = json(journey(account, command("0", "START", "survey.goal", null))).path("journey");
        assertThat(view.path("state").stringValue()).isEqualTo("IN_PROGRESS");
        assertThat(view.path("stepKey").stringValue()).isEqualTo("survey.goal");
        assertThat(view.path("rowVersion").stringValue()).isEqualTo("1");
        assertThat(view.path("offeredAt")).isEqualTo(offered.path("offeredAt"));
        assertThat(view.path("autoOffer").booleanValue()).isFalse();
        assertThat(steps("START", "survey.goal") - started).isEqualTo(1.0);

        view = json(journey(account, command("1", "STEP", "choose", null))).path("journey");
        assertThat(view.path("branch").isNull()).isTrue();
        view = json(journey(account, command("2", "STEP", "own.details", "OWN"))).path("journey");
        assertThat(view.path("branch").stringValue()).isEqualTo("OWN");
        assertThat(view.path("stepKey").stringValue()).isEqualTo("own.details");
        view = json(journey(account, command("3", "PAUSE", null, null))).path("journey");
        assertThat(view.path("state").stringValue()).isEqualTo("PAUSED");
        assertThat(view.path("stepKey").stringValue()).isEqualTo("own.details");
        view = json(journey(account, command("4", "RESUME", "own.material", null))).path("journey");
        assertThat(view.path("state").stringValue()).isEqualTo("IN_PROGRESS");
        assertThat(view.path("stepKey").stringValue()).isEqualTo("own.material");
        assertThat(view.path("branch").stringValue()).isEqualTo("OWN");
        view = json(journey(account, command("5", "STEP", "finale.done", null))).path("journey");
        assertThat(view.path("branch").stringValue()).isEqualTo("OWN");

        view = json(journey(account, command("6", "COMPLETE", null, null))).path("journey");
        assertThat(view.path("state").stringValue()).isEqualTo("COMPLETED");
        assertThat(view.path("finishedAt").isString()).isTrue();
        view = json(journey(account, command("7", "UNDO_FINISH", null, null))).path("journey");
        assertThat(view.path("state").stringValue()).isEqualTo("PAUSED");
        assertThat(view.path("finishedAt").isNull()).isTrue();
        assertThat(view.path("stepKey").stringValue()).isEqualTo("finale.done");

        view = json(journey(account, command("8", "COMPLETE", null, null))).path("journey");
        view = json(journey(account, command("9", "REPLAY", "welcome", null))).path("journey");
        assertThat(view.path("state").stringValue()).isEqualTo("IN_PROGRESS");
        assertThat(view.path("stepKey").stringValue()).isEqualTo("welcome");
        assertThat(view.path("branch").isNull()).isTrue();
        assertThat(view.path("finishedAt").isNull()).isTrue();
        assertThat(view.path("replayCount").intValue()).isOne();
        assertThat(view.path("tourVersion").intValue()).isEqualTo(1);
        assertThat(view.path("rowVersion").stringValue()).isEqualTo("10");
        assertThat(view.path("offeredAt")).isEqualTo(offered.path("offeredAt"));
        assertThat(column(account, "cohort")).isEqualTo("TOUR");
    }

    @Test
    void skippingAndUndoingTheSkipOffersTheTourAgain() {
        UUID account = newAccountIn("tour");
        get(account);

        JsonNode skipped = json(journey(account, command("0", "SKIP", null, null))).path("journey");
        assertThat(skipped.path("state").stringValue()).isEqualTo("SKIPPED");
        assertThat(skipped.path("finishedAt").isString()).isTrue();
        assertThat(skipped.path("autoOffer").booleanValue()).isFalse();
        // a skipped account is not offered again by a read
        assertThat(json(get(account)).path("journey").path("state").stringValue()).isEqualTo("SKIPPED");

        JsonNode undone = json(journey(account, command("1", "UNDO_FINISH", null, null))).path("journey");
        assertThat(undone.path("state").stringValue()).isEqualTo("OFFERED");
        assertThat(undone.path("finishedAt").isNull()).isTrue();
        assertThat(undone.path("autoOffer").booleanValue()).isTrue();
    }

    @Test
    void replayFromNotOfferedCreatesARowOutsideTheCohort() {
        UUID account = UUID.randomUUID();
        HttpIdentityFixture.signals(account, true, false);

        JsonNode view = json(journey(account, copy(CONTRACT.path("commands").path("replay")).put("commandId", UUID.randomUUID().toString())));

        JsonNode journey = view.path("journey");
        assertThat(journey.path("state").stringValue()).isEqualTo("IN_PROGRESS");
        assertThat(journey.path("eligible").booleanValue()).isFalse();
        assertThat(journey.path("autoOffer").booleanValue()).isFalse();
        assertThat(journey.path("replayCount").intValue()).isOne();
        assertThat(journey.path("rowVersion").stringValue()).isEqualTo("0");
        assertThat(journey.path("stepKey").stringValue()).isEqualTo("choose");
        assertThat(journey.path("offeredAt").isString()).isTrue();
        assertThat(column(account, "cohort")).isNull();
        assertThat(json(get(account)).path("journey").path("eligible").booleanValue()).isFalse();
    }

    @Test
    void aBranchStepNeedsItsBranchFromTheCommandOrTheRow() {
        UUID account = newAccount();
        seed(account, "IN_PROGRESS", "TOUR", null, "choose", 1);

        assertCode(journey(account, command("1", "STEP", "community.add", null)), 400, "INVALID_REQUEST");
        JsonNode chosen = json(journey(account, command("1", "STEP", "community.search", "COMMUNITY"))).path("journey");
        assertThat(chosen.path("branch").stringValue()).isEqualTo("COMMUNITY");
        assertCode(journey(account, command("2", "STEP", "own.material", null)), 400, "INVALID_REQUEST");
        assertThat(journey(account, command("2", "STEP", "community.add", null)).statusCode()).isEqualTo(200);
        assertThat(journey(account, command("3", "STEP", "finale.ai", null)).statusCode()).isEqualTo(200);
        assertThat(column(account, "branch")).isEqualTo("COMMUNITY");
        assertThat(column(account, "row_version")).isEqualTo("4");
        // a replay clears the branch, so it cannot land on a branch step
        UUID paused = newAccount();
        seed(paused, "PAUSED", "TOUR", "COMMUNITY", "community.add", 1);
        assertCode(journey(paused, command("1", "REPLAY", "sample.hub", null)), 400, "INVALID_REQUEST");
        assertThat(column(paused, "row_version")).isEqualTo("1");
    }

    // ---- idempotency, compare-and-set and the strict body ----

    @Test
    void aRepeatedCommandReturnsTheStoredAnswerAndIsCountedOnce() {
        UUID account = newAccount();
        get(account);
        ObjectNode start = command("0", "START", "survey.interests", null);
        double before = steps("START", "survey.interests");

        HttpResponse<String> first = journey(account, start);
        HttpResponse<String> repeat = journey(account, start);

        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(first.headers().firstValue("Idempotency-Replayed")).isEmpty();
        assertThat(repeat.statusCode()).isEqualTo(200);
        assertThat(repeat.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(json(repeat)).isEqualTo(json(first));
        assertThat(repeat.headers().firstValue("ETag")).isEqualTo(first.headers().firstValue("ETag"));
        assertThat(column(account, "row_version")).isEqualTo("1");
        assertThat(steps("START", "survey.interests") - before).isEqualTo(1.0);

        ObjectNode other = start.deepCopy().put("stepKey", "survey.days");
        assertCode(journey(account, other), 409, "IDEMPOTENCY_CONFLICT");
        assertThat(column(account, "row_version")).isEqualTo("1");
    }

    @Test
    void aStaleVersionIs412AndAMissingOneIs428AndNeitherChangesTheRow() {
        UUID account = newAccount();
        get(account);
        journey(account, command("0", "START", "welcome", null));

        assertCode(journey(account, command("0", "STEP", "choose", null)), 412, "VERSION_CONFLICT");
        assertCode(journey(account, command("2", "STEP", "choose", null)), 412, "VERSION_CONFLICT");
        assertCode(journey(account, command(null, "STEP", "choose", null)), 428, "PRECONDITION_REQUIRED");
        assertCode(preferences(account, preference("0").put("weeklyTarget", 3)), 412, "VERSION_CONFLICT");
        assertCode(preferences(account, preference(null).put("weeklyTarget", 3)), 428, "PRECONDITION_REQUIRED");
        assertThat(column(account, "row_version")).isEqualTo("1");

        // no row: any version but "0" is stale
        UUID nobody = UUID.randomUUID();
        HttpIdentityFixture.signals(nobody, false, false);
        assertCode(journey(nobody, command("1", "REPLAY", "choose", null)), 412, "VERSION_CONFLICT");
        assertThat(rows(nobody)).isZero();
    }

    @Test
    void ofTwoCommandsOnTheSameVersionExactlyOneWins() throws Exception {
        UUID account = newAccount();
        get(account);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> answers = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String action : new String[] {"START", "SKIP", "START", "SKIP"}) {
                ObjectNode body = command("0", action, action.equals("START") ? "welcome" : null, null);
                answers.add(pool.submit(() -> {
                    start.await();
                    return journey(account, body);
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<HttpResponse<String>> answer : answers) statuses.add(answer.get().statusCode());
            assertThat(statuses).containsExactlyInAnyOrder(200, 412, 412, 412);
        }
        assertThat(column(account, "row_version")).isEqualTo("1");
    }

    @Test
    void everyInvalidExampleIsA400OnBothEndpointsWhateverTheRowIs() {
        UUID account = newAccount();
        seed(account, "IN_PROGRESS", "TOUR", null, "choose", 1);
        CONTRACT.path("invalid").properties().forEach(entry -> {
            assertCode(journey(account, entry.getValue()), 400, "INVALID_REQUEST");
            assertCode(preferences(account, entry.getValue()), 400, "INVALID_REQUEST");
        });
        for (String body : new String[] {"", "{", "[]", "null", "{\"commandId\":\"x\"}"}) {
            assertThat(HttpIdentityFixture.send(port, "PUT", "/onboarding/journey", HttpIdentityFixture.reader(account), body, NONE).statusCode()).as(body).isEqualTo(400);
            assertThat(HttpIdentityFixture.send(port, "PUT", "/onboarding/preferences", HttpIdentityFixture.reader(account), body, NONE).statusCode()).as(body).isEqualTo(400);
        }
        assertThat(column(account, "row_version")).isEqualTo("1");
    }

    // ---- preferences ----

    @Test
    void preferencesNeedARowAndStoreOrClearEachAnswer() {
        UUID account = newAccount();
        assertCode(preferences(account, preference("0").put("weeklyTarget", 3)), 409, "ONBOARDING_TRANSITION");
        get(account);

        JsonNode both = json(preferences(account, copy(CONTRACT.path("commands").path("preferences")).put("commandId", UUID.randomUUID().toString())
                .put("rowVersion", "0"))).path("journey");
        assertThat(both.path("experience").stringValue()).isEqualTo("FAMILIAR");
        assertThat(both.path("weeklyTarget").intValue()).isEqualTo(5);
        assertThat(both.path("rowVersion").stringValue()).isEqualTo("1");
        assertThat(both.path("state").stringValue()).isEqualTo("OFFERED");

        JsonNode clearedTarget = json(preferences(account, preference("1").putNull("weeklyTarget"))).path("journey");
        assertThat(clearedTarget.path("weeklyTarget").isNull()).isTrue();
        assertThat(clearedTarget.path("experience").stringValue()).isEqualTo("FAMILIAR");
        JsonNode changed = json(preferences(account, preference("2").put("experience", "CONFIDENT"))).path("journey");
        assertThat(changed.path("experience").stringValue()).isEqualTo("CONFIDENT");
        assertThat(changed.path("rowVersion").stringValue()).isEqualTo("3");

        for (int target : new int[] {0, 8, -1}) {
            assertCode(preferences(account, preference("3").put("weeklyTarget", target)), 400, "INVALID_REQUEST");
        }
        assertCode(preferences(account, preference("3")), 400, "INVALID_REQUEST");
        assertCode(preferences(account, preference("3").put("experience", "EXPERT")), 400, "INVALID_REQUEST");
        assertThat(column(account, "row_version")).isEqualTo("3");
    }

    // ---- the owner's report ----

    private HttpResponse<String> report(String bearer, String query) {
        return HttpIdentityFixture.send(port, "GET", "/admin/onboarding/experiment" + query, bearer, null, NONE);
    }

    private HttpResponse<String> report(String query) {
        return report(HttpIdentityFixture.adminToken(CONSOLE), query);
    }

    private UUID member(String cohort, String offeredAt) {
        UUID account = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO app_learning.onboarding_journey(account_id, tour_version, state, cohort, offered_at, replay_count, updated_at)
                VALUES (:a, 1, 'OFFERED', :cohort, :at, :replays, :at)
                """).param("a", account).param("cohort", cohort, Types.VARCHAR).param("at", Timestamp.from(Instant.parse(offeredAt)))
                .param("replays", cohort == null ? 1 : 0).update();
        return account;
    }

    /** A copy of a completed template session for {@code account}; {@code completedAt} null makes it an unfinished ACTIVE session. */
    private void session(UUID account, String mode, String localDate, String completedAt) {
        if (templateSession == null) {
            StudyFixtures fixtures = new StudyFixtures(decks, items, exercises, sessions, media, jdbc);
            StudyFixtures.Material material = fixtures.material();
            fixtures.publish(material, fixtures.freeResponse(material, blocks(text("Question")), blocks(), "memory"));
            templateSession = UUID.fromString(fixtures.session(material, "SCHEDULED", null).path("sessionId").stringValue());
            jdbc.sql("UPDATE app_learning.study_session SET status='COMPLETE',completed_at=statement_timestamp(),batch_size=0,row_version=row_version+1 WHERE session_id=:s")
                    .param("s", templateSession).update();
            sessionColumns = jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_schema='app_learning' AND table_name='study_session' ORDER BY ordinal_position")
                    .query(String.class).list();
        }
        String columns = String.join(",", sessionColumns);
        String selected = sessionColumns.stream().map(column -> switch (column) {
            case "account_id" -> ":account";
            case "session_id" -> ":id";
            case "command_id" -> ":command";
            case "local_study_date" -> ":date";
            case "mode" -> ":mode";
            case "practice_order" -> "CASE WHEN :mode = 'PRACTICE' THEN 'SEEDED' END";
            case "status" -> completedAt == null ? "'ACTIVE'" : "'COMPLETE'";
            case "completed_at" -> ":completed";
            default -> column;
        }).collect(Collectors.joining(","));
        jdbc.sql("INSERT INTO app_learning.study_session(" + columns + ") SELECT " + selected + " FROM app_learning.study_session WHERE session_id = :source")
                .param("account", account).param("id", UUID.randomUUID()).param("command", UUID.randomUUID()).param("date", LocalDate.parse(localDate))
                .param("mode", mode).param("completed", completedAt == null ? null : Timestamp.from(Instant.parse(completedAt)), Types.TIMESTAMP)
                .param("source", templateSession).update();
    }

    @Test
    void theReportCountsActivationAndReturnPerVariantAndIsTheContractShape() {
        // The contract's example: 12 control accounts (4 activated, 3 returned) and 71 tour accounts (30 activated, 22 returned).
        String offered = "2025-03-10T09:00:00Z";
        seedGroup("CONTROL", 12, 4, 3, offered);
        seedGroup("TOUR", 71, 30, 22, offered);

        HttpResponse<String> response = report("?from=2025-03-01&to=2025-03-31");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("private, no-store");
        ObjectNode expected = copy(CONTRACT.path("report"));
        expected.put("from", "2025-03-01").put("to", "2025-03-31");
        assertThat(json(response)).isEqualTo(expected);
        assertThat(response.body()).doesNotContain("accountId", "account_id");
    }

    private void seedGroup(String cohort, int accounts, int activated, int returned, String offeredAt) {
        Instant base = Instant.parse(offeredAt);
        for (int index = 0; index < accounts; index++) {
            UUID account = member(cohort, offeredAt);
            if (index >= activated) continue;
            session(account, "SCHEDULED", LocalDate.ofInstant(base.plus(Duration.ofDays(1)), java.time.ZoneOffset.UTC).toString(), base.plus(Duration.ofDays(1)).toString());
            if (index < returned) {
                session(account, "PRACTICE", LocalDate.ofInstant(base.plus(Duration.ofDays(2)), java.time.ZoneOffset.UTC).toString(), base.plus(Duration.ofDays(2)).toString());
            }
        }
    }

    @Test
    void theReportDefinitionsAreExactAtTheirBoundaries() {
        // window [2025-06-01, 2025-06-10); the offer of every account below is 2025-06-02T10:00:00Z unless stated
        String offered = "2025-06-02T10:00:00Z";
        Instant base = Instant.parse(offered);
        UUID t1 = member("TOUR", offered);
        session(t1, "SCHEDULED", "2025-06-08", base.plus(Duration.ofDays(7)).minusSeconds(1).toString());
        UUID t2 = member("TOUR", offered);
        session(t2, "SCHEDULED", "2025-06-09", base.plus(Duration.ofDays(7)).toString());
        UUID t3 = member("TOUR", offered);
        session(t3, "SCHEDULED", "2025-06-02", base.minusSeconds(3600).toString());
        session(t3, "SCHEDULED", "2025-06-03", base.plus(Duration.ofDays(1)).toString());
        UUID t4 = member("TOUR", offered);
        session(t4, "SCHEDULED", "2025-06-03", base.plus(Duration.ofDays(1)).toString());
        session(t4, "PRACTICE", "2025-06-16", base.plus(Duration.ofDays(14)).minusSeconds(60).toString());
        UUID t5 = member("TOUR", offered);
        session(t5, "SCHEDULED", "2025-06-03", base.plus(Duration.ofDays(1)).toString());
        session(t5, "SCHEDULED", "2025-06-16", base.plus(Duration.ofDays(14)).toString());
        UUID t6 = member("TOUR", offered);
        session(t6, "SCHEDULED", "2025-06-03", null);
        session(t6, "PRACTICE", "2025-06-04", null);
        member("TOUR", "2025-06-01T00:00:00Z");
        UUID c1 = member("CONTROL", offered);
        session(c1, "PRACTICE", "2025-06-03", base.plus(Duration.ofDays(1)).toString());
        session(c1, "PRACTICE", "2025-06-04", base.plus(Duration.ofDays(2)).toString());
        UUID c2 = member("CONTROL", offered);
        session(c2, "SCHEDULED", "2025-06-03", base.plus(Duration.ofDays(1)).toString());
        session(c2, "SCHEDULED", "2025-06-03", base.plus(Duration.ofDays(1)).plusSeconds(3600).toString());
        member("CONTROL", offered);
        // outside the experiment or the window: a replay row (no cohort), and offers at the exclusive end and just before the start
        UUID replay = member(null, offered);
        session(replay, "SCHEDULED", "2025-06-03", base.plus(Duration.ofDays(1)).toString());
        UUID atEnd = member("TOUR", "2025-06-10T00:00:00Z");
        session(atEnd, "SCHEDULED", "2025-06-11", "2025-06-11T01:00:00Z");
        UUID before = member("TOUR", "2025-05-31T23:59:59Z");
        session(before, "SCHEDULED", "2025-06-01", "2025-06-01T01:00:00Z");

        JsonNode report = json(report("?from=2025-06-01&to=2025-06-10"));

        assertThat(report.path("experiment").stringValue()).isEqualTo("onboarding_v1");
        assertThat(report.path("minimumGroup").intValue()).isEqualTo(100);
        assertThat(report.path("smallSample").booleanValue()).isTrue();
        assertThat(report.path("groups")).hasSize(2);
        assertThat(report.path("groups").get(0)).isEqualTo(JSON.readTree("{\"variant\":\"control\",\"accounts\":3,\"activated\":1,\"returned\":1}"));
        assertThat(report.path("groups").get(1)).isEqualTo(JSON.readTree("{\"variant\":\"tour\",\"accounts\":7,\"activated\":4,\"returned\":1}"));
    }

    @Test
    void theReportIsNotASmallSampleOnlyWhenBothGroupsReachTheMinimum() {
        jdbc.sql("""
                INSERT INTO app_learning.onboarding_journey(account_id, tour_version, state, cohort, offered_at, updated_at)
                SELECT gen_random_uuid(), 1, 'OFFERED', CASE WHEN n <= 99 THEN 'CONTROL' ELSE 'TOUR' END, timestamptz '2025-04-10T09:00:00Z', timestamptz '2025-04-10T09:00:00Z'
                  FROM generate_series(1, 199) AS n
                """).update();
        // 99 control and 100 tour accounts
        JsonNode report = json(report("?from=2025-04-01&to=2025-04-30"));
        assertThat(report.path("groups").get(0).path("accounts").intValue()).isEqualTo(99);
        assertThat(report.path("groups").get(1).path("accounts").intValue()).isEqualTo(100);
        assertThat(report.path("smallSample").booleanValue()).isTrue();

        member("CONTROL", "2025-04-11T09:00:00Z");
        assertThat(json(report("?from=2025-04-01&to=2025-04-30")).path("smallSample").booleanValue()).isFalse();
        // an empty window has both groups, zero, and is a small sample
        JsonNode empty = json(report("?from=2024-01-01&to=2024-01-08"));
        assertThat(empty.path("groups").get(0).path("accounts").intValue()).isZero();
        assertThat(empty.path("groups").get(1).path("accounts").intValue()).isZero();
        assertThat(empty.path("smallSample").booleanValue()).isTrue();
    }

    @Test
    void theReportIsForTheConsoleOwnerOnTheAdminClientAndHasTheConsoleWindow() {
        String ok = "?from=2025-01-01&to=2025-01-08";
        assertThat(report(ok).statusCode()).isEqualTo(200);
        assertThat(report(null, ok).statusCode()).isEqualTo(401);
        // the owner's ordinary learner token, another account's admin token
        assertThat(report(HttpIdentityFixture.reader(CONSOLE), ok).statusCode()).isEqualTo(403);
        assertThat(report(HttpIdentityFixture.adminToken(UUID.randomUUID()), ok).statusCode()).isEqualTo(403);
        assertThat(report(HttpIdentityFixture.token(CONSOLE, "learning.write", "mnema-admin-web"), ok).statusCode()).isEqualTo(403);

        LocalDate tomorrow = LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1);
        assertThat(report("?from=" + tomorrow.minusDays(1) + "&to=" + tomorrow).statusCode()).isEqualTo(200);
        assertThat(report("?from=" + tomorrow.minusDays(90) + "&to=" + tomorrow).statusCode()).isEqualTo(200);
        for (String query : new String[] {"", "?from=2025-01-01", "?to=2025-01-08", "?from=2025-01-08&to=2025-01-08", "?from=2025-01-09&to=2025-01-08",
                "?from=2025-01-01&to=2025-04-02", "?from=" + tomorrow.minusDays(1) + "&to=" + tomorrow.plusDays(1), "?from=2025-1-1&to=2025-01-08",
                "?from=2025-01-01T00:00:00Z&to=2025-01-08T00:00:00Z", "?from=2025-01-01&to=2025-01-08&x=1", "?from=2025-01-01&from=2025-01-02&to=2025-01-08"}) {
            HttpResponse<String> response = report(query);
            assertThat(response.statusCode()).as(query).isEqualTo(400);
            assertThat(json(response).path("code").stringValue()).as(query).isEqualTo("INVALID_REQUEST");
        }
    }
}
