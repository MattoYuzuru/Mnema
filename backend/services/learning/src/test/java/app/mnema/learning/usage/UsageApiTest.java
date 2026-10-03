package app.mnema.learning.usage;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.platform.api.ApiExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** The HTTP boundary of usage: the usage read, the estimate and the problem a refused admission answers with. */
class UsageApiTest extends UsageIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private UsageController usageController;
    @Autowired private EstimateController estimateController;
    @Autowired private EstimateService estimates;
    @Autowired private UsageState state;
    @Autowired private DeckService decks;

    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    private MockMvc mvc(UUID owner, Object... controllers) {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject(owner.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        return MockMvcBuilders.standaloneSetup(controllers).setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    private UUID deck(UUID owner) {
        return UUID.fromString(decks.create(owner, new DeckCommand(UUID.randomUUID(), "Deck", "Description"))
                .acknowledgement().path("deck").path("deckId").stringValue(null));
    }

    private MockHttpServletResponse estimate(UUID owner, UUID deck, String body) throws Exception {
        return estimate(owner, deck.toString(), body);
    }

    private MockHttpServletResponse estimate(UUID owner, String deck, String body) throws Exception {
        MockHttpServletRequestBuilder request = post("/decks/" + deck + "/generation-estimates")
                .contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc(owner, estimateController).perform(request).andReturn().getResponse();
    }

    private static JsonNode body(MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsString());
    }

    private static String spec(String settings) {
        return "{\"spec\":{\"kind\":\"MATERIALS\",\"sources\":[" + UsageSpecs.noteSource(1) + "],\"settings\":" + settings + "}}";
    }

    @Test
    void getUsageIsPrivateNoStoreAndScopedToTheTokenSubject() throws Exception {
        UUID plus = owner(Plan.PLUS);
        UUID free = owner(Plan.FREE);
        reserve(plus, 10);

        var paid = mvc(plus, usageController).perform(get("/usage")).andReturn().getResponse();
        assertThat(paid.getStatus()).isEqualTo(200);
        assertThat(paid.getHeader("Cache-Control")).isEqualTo("private, no-store");
        assertThat(paid.getContentType()).startsWith("application/json");
        assertThat(body(paid).path("plan").stringValue(null)).isEqualTo("PLUS");
        assertThat(body(paid).path("credits").path("reserved").intValue()).isEqualTo(10);

        var own = body(mvc(free, usageController).perform(get("/usage")).andReturn().getResponse());
        assertThat(own.path("plan").stringValue(null)).isEqualTo("FREE");
        assertThat(own.path("credits").path("reserved").intValue()).isZero();
        assertThat(own.path("entitlement").path("source").stringValue(null)).isEqualTo("CONFIG");
    }

    @Test
    void aTokenWithoutACanonicalSubjectIsAnInvalidRequest() throws Exception {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject("not-a-uuid").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        var response = MockMvcBuilders.standaloneSetup(usageController).setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build()
                .perform(get("/usage")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(body(response).path("code").stringValue(null)).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void anEstimateOfAnOwnDeckIsPrivateNoStoreAndReservesNothing() throws Exception {
        UUID owner = owner(Plan.PLUS);
        UUID deck = deck(owner);

        var response = estimate(owner, deck, spec("{\"effort\":\"MEDIUM\",\"media\":{\"audio\":{\"enabled\":true},\"imageSearch\":true}}"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
        JsonNode estimate = body(response);
        assertThat(estimate.path("credits").path("p95").intValue()).isEqualTo(21);
        assertThat(estimate.path("credits").path("p50").intValue()).isEqualTo(13);
        assertThat(estimate.path("canStart").booleanValue()).isTrue();
        assertThat(estimate.path("budget").isNull()).isTrue();
        assertThat(estimate.path("shortfallCredits").isNull()).isTrue();
        assertThat(countOf("usage_reservation", owner)).isZero();
        assertThat(countOf("usage_ledger_entry", owner)).isZero();
        assertThat(repository.balance(owner, "2026-10")).isEmpty();
    }

    @Test
    void aForeignOrAbsentDeckIsTheSameOpaqueNotFound() throws Exception {
        UUID owner = owner(Plan.PLUS);
        UUID stranger = owner(Plan.PLUS);
        UUID foreignDeck = deck(stranger);

        var foreign = estimate(owner, foreignDeck, spec("{}"));
        var absent = estimate(owner, UUID.randomUUID(), spec("{}"));

        assertThat(foreign.getStatus()).isEqualTo(404);
        assertThat(absent.getStatus()).isEqualTo(404);
        assertThat(body(foreign).path("code").stringValue(null)).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(body(foreign).path("detail")).isEqualTo(body(absent).path("detail"));
        assertThat(foreign.getHeader("Cache-Control")).isEqualTo("private, no-store");
        // Ownership comes before validation: a stranger learns nothing from a malformed body either.
        assertThat(estimate(owner, foreignDeck, "{").getStatus()).isEqualTo(404);
    }

    @Test
    void malformedRequestsAreInvalidRequestBeforeAnyPricing() throws Exception {
        UUID owner = owner(Plan.PLUS);
        UUID deck = deck(owner);
        String edit = "{\"sessionId\":\"5e550000-0000-4000-8000-000000000001\",\"artifactId\":\"a7a70000-0000-4000-8000-000000000001\","
                + "\"action\":\"REWRITE\"}";
        List<String> bodies = List.of("", "{", "[]", "null", "{}", "{\"spec\":{},\"edit\":" + edit + "}", "{\"spec\":null}",
                "{\"edit\":" + edit + ",\"extra\":1}", "{\"spec\":{\"kind\":\"MATERIALS\",\"kind\":\"MATERIALS\"}}",
                "{\"spec\":{\"kind\":\"MATERIALS\",\"prompt\":\"" + "x".repeat(70_000) + "\"}}",
                "{\"spec\":{\"kind\":\"MATERIALS\"}}", "{\"edit\":[]}",
                "{\"edit\":" + edit.replace("REWRITE", "DESCRIBE") + "}", "{\"edit\":" + edit.replace("\"action\":\"REWRITE\"", "") + "}",
                "{\"edit\":" + edit.replace("5e550000-0000-4000-8000-000000000001", "nope") + "}",
                "{\"edit\":" + edit.replace("5e550000", "5E550000") + "}",
                "{\"edit\":" + edit.replace("\"sessionId\"", "\"session\"") + "}",
                "{\"edit\":" + edit.replace("}", ",\"targetNodeCount\":0}") + "}",
                "{\"edit\":" + edit.replace("}", ",\"targetNodeCount\":51}") + "}",
                "{\"edit\":" + edit.replace("}", ",\"targetNodeCount\":\"2\"}") + "}",
                "{\"edit\":" + edit.replace("}", ",\"extra\":true}") + "}");
        for (String request : bodies) {
            var response = estimate(owner, deck, request);
            assertThat(response.getStatus()).as(request.length() > 200 ? "oversized" : request).isEqualTo(400);
            assertThat(body(response).path("code").stringValue(null)).isEqualTo("INVALID_REQUEST");
            assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
            assertThat(response.getContentAsString()).doesNotContain("MATERIALS", "xxxx");
        }
        assertThat(estimate(owner, "not-a-uuid", spec("{}")).getStatus()).isEqualTo(400);
        assertThat(estimate(owner, "00000000-0000-0000-0000-000000000000", spec("{}")).getStatus()).isEqualTo(400);
    }

    @Test
    void unsupportedKindsAndOversizedSpecsAreUnprocessableWithTheirMembers() throws Exception {
        UUID owner = owner(Plan.PLUS);
        UUID deck = deck(owner);

        // a revise spec is supported now (AI-16): without its target and instruction it is malformed, with them it is one edit turn
        var revise = estimate(owner, deck, "{\"spec\":{\"kind\":\"REVISE_ITEM\"}}");
        assertThat(revise.getStatus()).isEqualTo(400);
        assertThat(body(revise).path("code").stringValue(null)).isEqualTo("INVALID_REQUEST");
        var priced = estimate(owner, deck, "{\"spec\":{\"kind\":\"REVISE_ITEM\",\"instruction\":\"проще\",\"target\":"
                + "{\"memberKey\":\"44444444-4444-4444-8444-444444444444\",\"itemRevisionId\":\"55555555-5555-4555-8555-555555555555\"}}}");
        assertThat(priced.getStatus()).isEqualTo(200);
        assertThat(body(priced).path("breakdown").get(0).path("operation").stringValue(null)).isEqualTo("EDIT_SELECTION");
        assertThat(body(priced).path("credits").path("p95").intValue()).isEqualTo(4);
        var planned = estimate(owner, deck, "{\"spec\":{\"kind\":\"EXERCISES\",\"targets\":[{\"memberKey\":\"44444444-4444-4444-8444-444444444444\","
                + "\"itemRevisionId\":\"55555555-5555-4555-8555-555555555555\"}],\"settings\":{\"planFirst\":true}}}");
        assertThat(planned.getStatus()).isEqualTo(422);
        assertThat(body(planned).path("code").stringValue(null)).isEqualTo("SPEC_NOT_SUPPORTED");
        assertThat(body(planned).path("kind").stringValue(null)).isEqualTo("EXERCISES");

        String targets = String.join(",", java.util.stream.IntStream.rangeClosed(1, 21).mapToObj(i ->
                "{\"memberKey\":\"44444444-4444-4444-8444-4444444444%02d\",\"itemRevisionId\":\"55555555-5555-4555-8555-555555555555\"}"
                        .formatted(i)).toList());
        var tooMany = estimate(owner, deck, "{\"spec\":{\"kind\":\"EXERCISES\",\"targets\":[" + targets + "]}}");
        assertThat(tooMany.getStatus()).isEqualTo(422);
        JsonNode problem = body(tooMany);
        assertThat(problem.path("code").stringValue(null)).isEqualTo("RESOURCE_LIMIT_EXCEEDED");
        assertThat(problem.path("limit").stringValue(null)).isEqualTo("EXERCISE_TARGETS");
        assertThat(problem.path("limits").path("maxExerciseTargets").intValue()).isEqualTo(20);
        assertThat(problem.path("limits").path("maxExercisesPerTarget").intValue()).isEqualTo(10);
        assertThat(problem.path("limits").path("maxExercisesPerSession").intValue()).isEqualTo(60);
    }

    @Test
    void editTurnsAreOneSmallReservationWorthAndRemovingMediaIsFree() throws Exception {
        UUID owner = owner(Plan.PLUS);
        UUID deck = deck(owner);
        String ids = "{\"sessionId\":\"5e550000-0000-4000-8000-000000000001\",\"artifactId\":\"a7a70000-0000-4000-8000-000000000001\"";

        JsonNode rewrite = body(estimate(owner, deck, "{\"edit\":" + ids + ",\"action\":\"REWRITE\",\"targetNodeCount\":2}}"));
        assertThat(rewrite.path("credits").path("p95").intValue()).isEqualTo(4);
        assertThat(rewrite.path("credits").path("p50").intValue()).isEqualTo(3);
        assertThat(rewrite.path("breakdown").get(0).path("operation").stringValue(null)).isEqualTo("EDIT_SELECTION");

        JsonNode audio = body(estimate(owner, deck, "{\"edit\":" + ids + ",\"action\":\"AUDIO_REGENERATE\"}}"));
        assertThat(audio.path("credits").path("p95").intValue()).isEqualTo(10);
        assertThat(body(estimate(owner, deck, "{\"edit\":" + ids + ",\"action\":\"IMAGE_SEARCH\"}}")).path("credits").path("p95")
                .intValue()).isEqualTo(1);
        assertThat(body(estimate(owner, deck, "{\"edit\":" + ids + ",\"action\":\"FREE\"}}")).path("credits").path("p95")
                .intValue()).isEqualTo(4);

        JsonNode free = body(estimate(owner, deck, "{\"edit\":" + ids + ",\"action\":\"REMOVE_MEDIA\"}}"));
        assertThat(free.path("credits").path("p95").intValue()).isZero();
        assertThat(free.path("credits").path("p50").intValue()).isZero();
        assertThat(free.path("breakdown")).isEmpty();
        assertThat(free.path("canStart").booleanValue()).isTrue();

        var generated = estimate(owner, deck, "{\"edit\":" + ids + ",\"action\":\"IMAGE_GENERATE\"}}");
        assertThat(generated.getStatus()).isEqualTo(409);
        JsonNode problem = body(generated);
        assertThat(problem.path("code").stringValue(null)).isEqualTo("CAPABILITY_UNAVAILABLE");
        assertThat(problem.path("capability").stringValue(null)).isEqualTo("imageGeneration");
        assertThat(problem.path("reason").stringValue(null)).isEqualTo("PROVIDER_NOT_CONFIGURED");
    }

    @Test
    void aBudgetCapsWhatTheSessionHoldsAndAnEmptyBudgetBlocksNothingItCannotBuy() throws Exception {
        UUID plus = owner(Plan.PLUS);
        JsonNode half = body(estimate(plus, deck(plus), spec("{\"budgetPercent\":50}")));
        assertThat(half.path("budget").path("percent").intValue()).isEqualTo(50);
        assertThat(half.path("budget").path("capCredits").intValue()).isEqualTo(180);
        assertThat(half.path("canStart").booleanValue()).isTrue();

        UUID free = owner(Plan.FREE);
        JsonNode tiny = body(estimate(free, deck(free), spec("{\"budgetPercent\":10}")));
        // 10% of the 13 spendable credits is 1: the plan is trimmed to that hold, which fits.
        assertThat(tiny.path("budget").path("capCredits").intValue()).isOne();
        // Effort AUTO is priced and run as medium until the planner and auto-effort exist.
        assertThat(tiny.path("credits").path("p95").intValue()).isEqualTo(10);
        assertThat(tiny.path("canStart").booleanValue()).isTrue();
    }

    @Test
    void exerciseEstimatesUseTheProRataWeightAndTheBudgetShare() throws Exception {
        UUID owner = owner(Plan.PLUS);
        UUID deck = deck(owner);
        String target = "{\"memberKey\":\"44444444-4444-4444-8444-444444444444\",\"itemRevisionId\":\"55555555-5555-4555-8555-555555555555\"}";
        JsonNode three = body(estimate(owner, deck, "{\"spec\":{\"kind\":\"EXERCISES\",\"targets\":[" + target
                + "],\"settings\":{\"quantity\":{\"mode\":\"EXACT\",\"perTarget\":3}}}}"));
        assertThat(three.path("breakdown").get(0).path("operation").stringValue(null)).isEqualTo("EXERCISES_PER_MATERIAL");
        assertThat(three.path("breakdown").get(0).path("count").intValue()).isEqualTo(3);
        assertThat(three.path("credits").path("p95").intValue()).isEqualTo(5);
        // 0.6 x 4.8 = 2.88, rounded up once on the sum.
        assertThat(three.path("credits").path("p50").intValue()).isEqualTo(3);
    }

    @Test
    void aCapThatIsNotOfferedOrSpentBlocksTheEstimateBesideTheBar() {
        UUID free = owner(Plan.FREE);
        var now = now();
        var resolved = state.resolve(free, now);
        var interpretation = new GenerationSpecInterpreter.Interpretation(List.of(
                new GenerationSpecInterpreter.Line("PODCAST_3MIN", 1)), null, List.of());

        EstimateView view = estimates.price(resolved, state.credits(resolved), interpretation, now);

        assertThat(view.canStart()).isFalse();
        assertThat(view.blockingBuckets()).extracting(EstimateView.BlockView::bucket).containsExactly("CREDITS", "PODCASTS");
        assertThat(view.blockingBuckets().get(1).offered()).isFalse();
        assertThat(view.blockingBuckets().get(1).renewsAt()).isNull();
        assertThat(view.shortfallCredits()).isEqualTo(62);

        UUID plus = owner(Plan.PLUS);
        inTx(() -> {
            ledger.consume(plus, Bucket.PODCASTS, 2, "pods:" + plus, null);
            return null;
        });
        var plusState = state.resolve(plus, now);
        EstimateView capped = estimates.price(plusState, state.credits(plusState), interpretation, now);
        assertThat(capped.canStart()).isFalse();
        assertThat(capped.shortfallCredits()).isNull();
        assertThat(capped.blockingBuckets()).hasSize(1);
        assertThat(capped.blockingBuckets().getFirst().limit()).isEqualTo(2L);
        assertThat(capped.blockingBuckets().getFirst().offered()).isTrue();
    }

    @Test
    void twoParallelAdmissionsOnABalanceForOneAreOneOkAndOneUsageLimitReachedOverHttp() throws Exception {
        for (int round = 0; round < 8; round++) {
            UUID owner = owner(Plan.FREE);
            inTx(() -> ledger.release(owner, ledger.reserve(owner, ReservationScope.TURN, null, null, 1).reservationId()));
            AdmissionProbe probe = new AdmissionProbe(ledger, transactions);
            MockMvc mvc = MockMvcBuilders.standaloneSetup(probe).setControllerAdvice(new ApiExceptionHandler()).build();
            CountDownLatch go = new CountDownLatch(1);
            List<Future<MockHttpServletResponse>> pending = new ArrayList<>();
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                for (int i = 0; i < 2; i++) {
                    pending.add(pool.submit(() -> {
                        go.await();
                        return mvc.perform(get("/decks/" + UUID.randomUUID() + "/generation-sessions")
                                .header("X-Owner", owner.toString()).header("X-Credits", 10)).andReturn().getResponse();
                    }));
                }
                go.countDown();
                List<Integer> statuses = new ArrayList<>();
                MockHttpServletResponse refused = null;
                for (var future : pending) {
                    var response = future.get();
                    statuses.add(response.getStatus());
                    if (response.getStatus() == 409) refused = response;
                }
                assertThat(statuses).containsExactlyInAnyOrder(200, 409);
                JsonNode problem = body(refused);
                assertThat(problem.path("code").stringValue(null)).isEqualTo("USAGE_LIMIT_REACHED");
                assertThat(problem.path("bucket").stringValue(null)).isEqualTo("CREDITS");
                assertThat(problem.path("required").intValue()).isEqualTo(10);
                assertThat(problem.path("limit").intValue()).isEqualTo(13);
                assertThat(problem.path("used").intValue()).isEqualTo(10);
            }
            assertThat(balance(owner)).containsExactly(13, 0, 10);
        }
    }
}
