package app.mnema.learning.usage;

import app.mnema.learning.catalog.deck.DeckCommand;
import app.mnema.learning.catalog.deck.DeckService;
import app.mnema.learning.platform.api.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Executes {@code contracts/usage/usage.json}: the response and problem examples are reproduced from real database
 * state through the real services and compared field for field with the contract's own fixtures.
 */
class UsageContractTest extends UsageIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode USAGE = ContractExamples.read("contracts/usage/usage.json");
    private static final Instant MONTH = Instant.parse("2026-09-30T21:00:00Z");
    private static final Instant DAY = Instant.parse("2026-10-01T21:00:00Z");

    @Autowired private DeckService decks;
    @Autowired private EstimateService estimates;
    @Autowired private UsageState state;
    @Autowired private RateCard rateCard;

    private UUID deck(UUID owner) {
        return UUID.fromString(decks.create(owner, new DeckCommand(UUID.randomUUID(), "Deck", "Description"))
                .acknowledgement().path("deck").path("deckId").stringValue(null));
    }

    private static JsonNode json(Object view) {
        return JSON.readTree(JSON.writeValueAsString(view));
    }

    private void counter(UUID owner, Bucket bucket, Window window, Instant start, long used) {
        jdbc.sql("INSERT INTO app_learning.usage_counter(owner_id,bucket,window_kind,window_start,used) "
                + "VALUES (:o,:b,:w,:s,:u)").param("o", owner).param("b", bucket.name()).param("w", window.name())
                .param("s", Timestamp.from(start)).param("u", used).update();
    }

    /** A PLUS account in the state of the contract's {@code usageResponse}: 42 used, 10 held, 11 debited today. */
    private UUID plusLikeTheExample() {
        UUID owner = owner(Plan.PLUS);
        reserve(owner, 10);
        jdbc.sql("UPDATE app_learning.usage_balance SET used=42 WHERE owner_id=:o").param("o", owner).update();
        jdbc.sql("INSERT INTO app_learning.usage_ledger_entry(entry_id,owner_id,kind,credits,operation,rate_card_version,"
                + "period_id,idempotency_key,created_at) VALUES (:id,:o,'DEBIT',-11,'MATERIAL_MEDIUM','rc-v1','2026-10',:k,:t)")
                .param("id", UUID.randomUUID()).param("o", owner).param("k", "seed:" + owner)
                .param("t", Timestamp.from(Instant.parse("2026-10-02T08:00:00Z"))).update();
        counter(owner, Bucket.STT, Window.MONTH, MONTH, 692);
        counter(owner, Bucket.STT, Window.DAY, DAY, 200);
        counter(owner, Bucket.ASSESSMENT, Window.MONTH, MONTH, 41);
        counter(owner, Bucket.ASSESSMENT, Window.DAY, DAY, 6);
        counter(owner, Bucket.HIGH_FACTCHECK, Window.MONTH, MONTH, 1);
        counter(owner, Bucket.SMART_PLAN, Window.MONTH, MONTH, 1);
        return owner;
    }

    @Test
    void getUsageForAPaidPlanIsTheContractsUsageResponse() {
        UUID owner = plusLikeTheExample();
        assertThat(json(usage.read(owner))).isEqualTo(USAGE.path("usageResponse"));
    }

    @Test
    void getUsageForFreeIsTheContractsFreeResponseWithItsWeeklyUnlock() {
        UUID owner = owner(Plan.FREE);
        settle(owner, reserve(owner, 8), "debit:free:" + owner, 8);
        counter(owner, Bucket.STT, Window.MONTH, MONTH, 3071);
        counter(owner, Bucket.STT, Window.DAY, DAY, 150);
        counter(owner, Bucket.ASSESSMENT, Window.MONTH, MONTH, 10);
        counter(owner, Bucket.ASSESSMENT, Window.DAY, DAY, 1);
        assertThat(json(usage.read(owner))).isEqualTo(USAGE.path("usageResponseFree"));
    }

    @Test
    void speechToTextWithoutAMonthlyLimitIsTheContractsMaxFairUse() {
        UUID owner = owner(Plan.MAX);
        counter(owner, Bucket.STT, Window.MONTH, MONTH, 24_590);
        counter(owner, Bucket.STT, Window.DAY, DAY, 2_100);
        assertThat(json(usage.read(owner).fairUse().stt())).isEqualTo(USAGE.path("usageResponseMaxFairUse").path("stt"));
    }

    @Test
    void aFreshAccountSeesTheWholeSchedule() {
        JsonNode view = json(usage.read(owner(Plan.FREE)));
        assertThat(view.path("credits").path("unlocked").intValue()).isEqualTo(13);
        assertThat(view.path("credits").path("percentUsed").intValue()).isZero();
        assertThat(view.path("weeklyUnlock").path("unlockedPortions").intValue()).isOne();
        assertThat(view.path("dailyBurst").isNull()).isTrue();
        clock.set("2026-10-25T09:00:00Z");
        JsonNode late = json(usage.read(owner(Plan.FREE)));
        assertThat(late.path("credits").path("unlocked").intValue()).isEqualTo(50);
        assertThat(late.path("weeklyUnlock").path("unlockedPortions").intValue()).isEqualTo(4);
        assertThat(late.path("weeklyUnlock").path("nextUnlockAt").isNull()).isTrue();
        assertThat(late.path("weeklyUnlock").path("nextPortionCredits").isNull()).isTrue();
    }

    @Test
    void theContractsMaterialsExampleIsPricedAsInTheEstimateResponse() {
        UUID owner = plusLikeTheExample();
        JsonNode body = JSON.createObjectNode().set("spec", ContractExamples.generation("specMaterials"));
        assertThat(json(estimates.estimate(owner, deck(owner), body))).isEqualTo(USAGE.path("estimateResponse"));
    }

    @Test
    void aShortfallNamesTheBlockingBucketTheShortfallAndTheWarningsOfTheScan() {
        UUID owner = owner(Plan.PLUS);
        reserve(owner, 1);
        jdbc.sql("UPDATE app_learning.usage_balance SET used=348, reserved=0 WHERE owner_id=:o").param("o", owner).update();
        var withWarning = new GenerationSpecInterpreter() {
            @Override
            public Interpretation interpret(UUID o, UUID d, JsonNode spec, int remaining) {
                Interpretation base = new StandardSpecInterpreter(new GenerationLimits(20, 20, 20, 10, 60))
                        .interpret(o, d, spec, remaining);
                JsonNode warning = USAGE.path("estimateResponseShortfall").path("warnings").get(0);
                return new Interpretation(base.lines(), base.budgetPercent(), List.of(new Warning(
                        warning.path("code").stringValue(null),
                        java.util.Map.of("type", "NOTE", "noteId", warning.path("sourceRef").path("noteId").stringValue(null)),
                        List.of(new Range(120, 138)))));
            }
        };
        var service = new EstimateService(repository, state, rateCard, withWarning, clock);
        String sources = UsageSpecs.noteSource(1) + "," + UsageSpecs.noteSource(2);
        JsonNode body = JSON.readTree("{\"spec\":{\"kind\":\"MATERIALS\",\"sources\":[" + sources
                + "],\"settings\":{\"effort\":\"DETAILED\"}}}");

        assertThat(json(service.estimate(owner, deck(owner), body))).isEqualTo(USAGE.path("estimateResponseShortfall"));
    }

    @Test
    void aFreeAccountThatCannotAffordADetailedMaterialSeesTheWeeklyBlock() {
        UUID owner = owner(Plan.FREE);
        settle(owner, reserve(owner, 8), "debit:fb:" + owner, 8);
        JsonNode body = JSON.readTree("{\"spec\":{\"kind\":\"MATERIALS\",\"sources\":[" + UsageSpecs.noteSource(1)
                + "],\"settings\":{\"effort\":\"DETAILED\"}}}");
        assertThat(json(estimates.estimate(owner, deck(owner), body))).isEqualTo(USAGE.path("estimateResponseFreeBlocked"));
    }

    private JsonNode problem(String path, UUID owner, int credits) throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdmissionProbe(ledger, transactions))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        var result = mvc.perform(get(path).header("X-Owner", owner.toString()).header("X-Credits", credits))
                .andExpect(status().isConflict()).andReturn().getResponse();
        assertThat(result.getContentType()).startsWith("application/problem+json");
        assertThat(result.getHeader("Cache-Control")).isEqualTo("private, no-store");
        ObjectNode body = (ObjectNode) JSON.readTree(result.getContentAsString());
        assertThat(body.path("instance").stringValue(null)).isEqualTo(path);
        return body;
    }

    private static JsonNode expectedProblem(String name) {
        ObjectNode example = (ObjectNode) USAGE.path("errors").path("USAGE_LIMIT_REACHED").path(name).deepCopy();
        example.remove("instance");
        return example;
    }

    @Test
    void usageLimitReachedIsTheContractsProblemForEachOfItsThreeExamples() throws Exception {
        String path = "/decks/11111111-1111-4111-8111-111111111111/generation-sessions";
        UUID plus = owner(Plan.PLUS);
        reserve(plus, 1);
        jdbc.sql("UPDATE app_learning.usage_balance SET used=348, reserved=0 WHERE owner_id=:o").param("o", plus).update();
        ObjectNode paid = (ObjectNode) problem(path, plus, 44);
        paid.remove("instance");
        assertThat(paid).isEqualTo(expectedProblem("example"));

        UUID free = owner(Plan.FREE);
        settle(free, reserve(free, 8), "debit:pb:" + free, 8);
        ObjectNode week = (ObjectNode) problem(path, free, 22);
        week.remove("instance");
        assertThat(week).isEqualTo(expectedProblem("exampleFreeWeek"));

        ObjectNode notOffered = (ObjectNode) problem("/decks/11111111-1111-4111-8111-111111111111/podcasts", owner(Plan.FREE), 1);
        notOffered.remove("instance");
        assertThat(notOffered).isEqualTo(expectedProblem("exampleNotOffered"));
    }

    @Test
    void theContractsBucketAndWindowVocabularyIsTheEnums() {
        assertThat(USAGE.path("errors").path("buckets").valueStream().map(JsonNode::stringValue).toList())
                .containsExactlyElementsOf(java.util.Arrays.stream(Bucket.values()).map(Enum::name).toList());
        assertThat(USAGE.path("errors").path("windows").valueStream().map(JsonNode::stringValue).toList())
                .containsExactlyElementsOf(java.util.Arrays.stream(Window.values()).map(Enum::name).toList());
        assertThat(USAGE.path("ledger").path("kinds").propertyNames())
                .containsExactlyInAnyOrder("GRANT", "DEBIT", "REFUND", "ADJUSTMENT", "EXPIRE");
        assertThat(USAGE.path("reservation").path("states").propertyNames())
                .containsExactlyInAnyOrder(java.util.Arrays.stream(ReservationState.values()).map(Enum::name).toArray(String[]::new));
        assertThat(USAGE.path("reservation").path("scopes").propertyNames())
                .containsExactlyInAnyOrder(java.util.Arrays.stream(ReservationScope.values()).map(Enum::name).toArray(String[]::new));
    }

    @Test
    void theContractsLedgerExamplesAreAcceptedByTheTable() {
        UUID owner = owner(Plan.PLUS);
        Reservation held = reserve(owner, 10);
        settle(owner, held, "debit:contract:1", 10);
        JsonNode debit = USAGE.path("ledger").path("examples").get(1);
        String row = jdbc.sql("SELECT kind||':'||credits||':'||cost_micros||':'||operation||':'||rate_card_version||':'||period_id "
                + "FROM app_learning.usage_ledger_entry WHERE idempotency_key='debit:contract:1'").query(String.class).single();
        assertThat(row).isEqualTo(debit.path("kind").stringValue(null) + ":" + debit.path("credits").intValue() + ":"
                + debit.path("costMicros").intValue() + ":" + debit.path("operation").stringValue(null) + ":"
                + debit.path("rateCardVersion").stringValue(null) + ":" + debit.path("periodId").stringValue(null));
    }
}
