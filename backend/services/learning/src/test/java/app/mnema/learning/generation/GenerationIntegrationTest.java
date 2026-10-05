package app.mnema.learning.generation;

import app.mnema.learning.ai.OpaqueUserKey;
import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.catalog.exercise.ExerciseService;
import app.mnema.learning.catalog.item.ItemService;
import app.mnema.learning.media.MediaCatalog;
import app.mnema.learning.platform.api.ApiExceptionHandler;
import app.mnema.learning.study.session.StudySessionService;
import app.mnema.learning.usage.ReservationScope;
import app.mnema.learning.usage.UsageLedger;
import app.mnema.learning.support.PostgresIntegrationTest;
import app.mnema.learning.support.StudyFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The generation module on the real Spring context, PostgreSQL and the deterministic Stub (no network, no key), with the
 * worker running on fast timers. Subclasses share the context. Each test uses its own owner, so tests do not see each
 * other's sessions; steps of every owner are executed by the one running dispatcher.
 */
@SpringBootTest(properties = {
        "learning.runtime.roles=all",
        "learning.ai.provider=stub",
        "learning.features.ai-generation.enabled=true",
        "learning.features.text-to-speech.enabled=true",
        "learning.features.image-search.enabled=true",
        "learning.usage.entitlements.default-plan=PLUS",
        "learning.generation.worker.lease=PT2S",
        "learning.generation.worker.heartbeat=PT0.2S",
        "learning.generation.worker.sweep-interval=PT0.2S",
        "learning.generation.step.backoff-base=PT0.1S",
        "learning.generation.step.backoff-cap=PT1S",
        "learning.generation.stream.checkpoint-interval=PT0S",
        "learning.generation.context.outline-lines=5",
        "learning.generation.context.latest-materials=2",
        "learning.generation.context.top-k=2",
        // small pool: every distinct test context keeps its own pool and the shared test PostgreSQL has a connection cap
        "spring.datasource.hikari.maximum-pool-size=8"})
// MiddayUsageClock: a daily-burst park must outlast the test at any wall-clock time (no Moscow midnight inside a run)
@Import({GenerationTestConfiguration.class, app.mnema.learning.usage.MiddayUsageClock.class})
abstract class GenerationIntegrationTest extends PostgresIntegrationTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired protected JdbcClient jdbc;
    @Autowired protected GenerationController controller;
    @Autowired protected DeckService decks;
    @Autowired protected ItemService items;
    @Autowired protected ExerciseService exercises;
    @Autowired protected StudySessionService studySessions;
    @Autowired protected MediaCatalog media;
    @Autowired protected GenerationTestConfiguration.Scripted provider;
    @Autowired private ProviderKeys keys;

    @Autowired protected StepRepository steps;
    @Autowired protected UsageLedger ledger;
    @Autowired protected PlatformTransactionManager transactions;

    protected StudyFixtures fixtures;

    @BeforeEach
    void resetDoubles() {
        provider.reset();
        fixtures = new StudyFixtures(decks, items, exercises, studySessions, media, jdbc);
    }

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------- fixtures

    protected UUID deck(UUID owner) {
        return UUID.fromString(decks.create(owner, new DeckCommand(UUID.randomUUID(), "Движение", "Глаголы движения"))
                .acknowledgement().path("deck").path("deckId").stringValue(null));
    }

    /** A capture note of the deck; returns its id (its row version is 0). */
    protected UUID note(UUID owner, UUID deck, String text) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_learning.capture_note(note_id,owner_id,deck_id,row_version,source,note_text,created_at,updated_at) "
                        + "VALUES (:id,:owner,:deck,0,'test',:text,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
                .param("id", id).param("owner", owner).param("deck", deck).param("text", text).update();
        return id;
    }

    protected static ObjectNode noteSource(UUID note, long version) {
        return JSON.createObjectNode().put("role", "SOURCE").put("type", "NOTE").put("noteId", note.toString())
                .put("noteRowVersion", Long.toString(version));
    }

    /** A MATERIALS spec with a prompt and the given sources, at medium effort and no media. */
    protected static ObjectNode spec(String prompt, ObjectNode... sources) {
        ObjectNode spec = JSON.createObjectNode().put("kind", "MATERIALS").put("outputLanguage", "ru");
        if (prompt != null) spec.put("prompt", prompt);
        if (sources.length > 0) {
            ArrayNode list = spec.putArray("sources");
            for (ObjectNode source : sources) list.add(source);
        }
        spec.putObject("settings").put("effort", "MEDIUM");
        return spec;
    }

    protected static ObjectNode body(UUID commandId, ObjectNode spec) {
        ObjectNode body = JSON.createObjectNode().put("commandId", commandId.toString());
        body.set("spec", spec);
        return body;
    }

    // -------------------------------------------------------------------- HTTP

    protected MockMvc mvc(UUID owner) {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject(owner.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    protected MockHttpServletResponse send(UUID owner, MockHttpServletRequestBuilder request) throws Exception {
        return mvc(owner).perform(request).andReturn().getResponse();
    }

    protected MockHttpServletResponse create(UUID owner, UUID deck, ObjectNode body) throws Exception {
        return send(owner, post("/decks/" + deck + "/generation-sessions").contentType("application/json").content(body.toString()));
    }

    protected MockHttpServletResponse create(UUID owner, UUID deck, ObjectNode spec, UUID commandId) throws Exception {
        return create(owner, deck, body(commandId, spec));
    }

    /** Creates a session and returns its id, failing the test if the answer is not 201. */
    protected UUID start(UUID owner, UUID deck, ObjectNode spec) throws Exception {
        MockHttpServletResponse response = create(owner, deck, spec, UUID.randomUUID());
        if (response.getStatus() != 201) {
            throw new AssertionError("createSession answered " + response.getStatus() + ": " + response.getContentAsString());
        }
        return UUID.fromString(json(response).path("sessionId").stringValue(null));
    }

    protected MockHttpServletResponse getSession(UUID owner, UUID deck, UUID session) throws Exception {
        return send(owner, get("/decks/" + deck + "/generation-sessions/" + session));
    }

    protected MockHttpServletResponse events(UUID owner, UUID deck, UUID session, String query) throws Exception {
        return send(owner, get("/decks/" + deck + "/generation-sessions/" + session + "/events" + query));
    }

    protected MockHttpServletResponse cancel(UUID owner, UUID deck, UUID session, UUID commandId) throws Exception {
        return send(owner, post("/decks/" + deck + "/generation-sessions/" + session + "/cancellation")
                .contentType("application/json").content("{\"commandId\":\"" + commandId + "\"}"));
    }

    protected static JsonNode json(MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsString());
    }

    // ---------------------------------------------------------------- provider

    /**
     * The provider calls made for {@code owner}. A test asserts on these, never on {@code provider.calls}: steps of other
     * tests' owners may still be running on the shared dispatcher and call the same provider double.
     */
    protected List<GenerationTestConfiguration.Call> calls(UUID owner) {
        OpaqueUserKey key = keys.opaque(owner);
        return provider.calls.stream().filter(call -> call.userKey().equals(key)).toList();
    }

    /** The streamed deltas of {@code owner}'s provider calls (see {@link #calls}). */
    protected List<GenerationTestConfiguration.DeltaObservation> deltas(UUID owner) {
        OpaqueUserKey key = keys.opaque(owner);
        return provider.deltas.stream().filter(delta -> delta.userKey().equals(key)).toList();
    }

    // ---------------------------------------------------------------- database

    protected String sessionState(UUID session) {
        return jdbc.sql("SELECT state FROM app_learning.generation_session WHERE session_id=:id").param("id", session)
                .query(String.class).single();
    }

    protected List<String> artifactStates(UUID session) {
        return jdbc.sql("SELECT state FROM app_learning.generation_artifact WHERE session_id=:id ORDER BY ordinal")
                .param("id", session).query(String.class).list();
    }

    protected List<String> artifactErrors(UUID session) {
        return jdbc.sql("SELECT COALESCE(error_code,'-') FROM app_learning.generation_artifact WHERE session_id=:id ORDER BY ordinal")
                .param("id", session).query(String.class).list();
    }

    protected int debits(UUID owner) {
        return jdbc.sql("SELECT COALESCE(-sum(credits),0)::integer FROM app_learning.usage_ledger_entry WHERE owner_id=:owner AND kind='DEBIT'")
                .param("owner", owner).query(Integer.class).single();
    }

    protected String reservationState(UUID session) {
        return jdbc.sql("SELECT r.state FROM app_learning.usage_reservation r JOIN app_learning.generation_session s "
                + "ON s.reservation_id=r.reservation_id WHERE s.session_id=:id").param("id", session).query(String.class).single();
    }

    protected List<String> notificationKinds(UUID owner) {
        return jdbc.sql("SELECT kind FROM app_learning.notification WHERE owner_id=:owner ORDER BY seq").param("owner", owner)
                .query(String.class).list();
    }

    protected UUID stepOf(UUID session) {
        return jdbc.sql("SELECT step_id FROM app_learning.generation_step WHERE session_id=:id AND kind='TEXT_DRAFT'").param("id", session)
                .query(UUID.class).single();
    }

    /**
     * A session whose only step the daily burst parks (PLUS may debit 35% of 360 = 126 credits a day, and 125 are spent
     * first), so no worker touches it and a test can change the world or play a crash before the step runs. The park ends at
     * the next usage day start, which {@link app.mnema.learning.usage.MiddayUsageClock} keeps at least twelve hours away.
     */
    protected UUID parkedSession(UUID owner, UUID deck, ObjectNode spec) throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var reservation = ledger.reserve(owner, ReservationScope.SESSION, UUID.randomUUID(), null, 125);
            ledger.settle(owner, new UsageLedger.Debit(reservation.reservationId(), "debit:burst:" + owner, "MATERIAL_DETAILED", 125,
                    null, null));
        });
        UUID session = start(owner, deck, spec);
        await("the step to be parked", Duration.ofSeconds(10), () -> steps.step(stepOf(session)).orElseThrow()
                .nextAttemptAt().isAfter(Instant.now().plus(Duration.ofMinutes(10))));
        return session;
    }

    // ----------------------------------------------------------------- waiting

    /** Polls until {@code condition} holds; fails with {@code what} after {@code timeout}. */
    protected static void await(String what, Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    protected void awaitState(UUID session, String state) throws InterruptedException {
        await("session " + session + " to be " + state, Duration.ofSeconds(20), () -> sessionState(session).equals(state));
    }
}
