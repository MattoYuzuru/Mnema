package app.mnema.learning.generation;

import app.mnema.learning.ai.AiRoute;
import app.mnema.learning.support.StudyFixtures;
import app.mnema.learning.usage.Bucket;
import app.mnema.learning.usage.ReservationScope;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Acceptance of #295 (AI-14) on the Stub, PostgreSQL and the real catalog: a plan-first session is PLANNING with no artifact, the PLAN step makes the
 * plan (repaired once, failed and cancelled when it stays invalid), the plan is debited on its own and counted against the smart-plan cap, the session
 * waits in PLAN_READY, and the approval of the plan the owner edited creates exactly the planned artifacts through the ordinary admission code, with the
 * batch hold re-sized to the plan, in one idempotent command.
 */
class GenerationPlanIntegrationTest extends GenerationReviewSupport {
    @org.springframework.beans.factory.annotation.Autowired private SessionRetention retention;

    private static final String FIRST = "Планировщик выбирает план выполнения";
    private static final String SECOND = "Статистика таблицы обновляется командой ANALYZE";

    // ------------------------------------------------------------------------ helpers

    private StudyFixtures.Material material(UUID owner, UUID deck, String marker) {
        return fixtures.addMaterial(owner, deck, FIRST + " " + marker, SECOND);
    }

    private ObjectNode exercisesSpec(String quantity, List<String> mechanics, StudyFixtures.Material... targets) throws Exception {
        ObjectNode spec = JSON.createObjectNode().put("kind", "EXERCISES").put("outputLanguage", "ru");
        ArrayNode list = spec.putArray("targets");
        for (StudyFixtures.Material target : targets) {
            list.addObject().put("memberKey", target.member().toString()).put("itemRevisionId", target.itemRevision().toString());
        }
        ObjectNode settings = spec.putObject("settings");
        if (mechanics == null) settings.put("mechanics", "AUTO");
        else mechanics.forEach(settings.putArray("mechanics")::add);
        settings.put("priority", "BALANCED").put("planFirst", true);
        settings.set("quantity", JSON.readTree(quantity));
        return spec;
    }

    private ObjectNode materialsSpec(String prompt, ObjectNode... sources) {
        ObjectNode spec = spec(prompt, sources);
        ((ObjectNode) spec.path("settings")).put("planFirst", true);
        return spec;
    }

    private JsonNode detail(UUID owner, UUID deck, UUID session) throws Exception {
        return json(getSession(owner, deck, session));
    }

    private MockHttpServletResponse approvePlan(UUID owner, UUID deck, UUID session, UUID command, String version, ObjectNode plan) throws Exception {
        ObjectNode body = JSON.createObjectNode().put("commandId", command.toString()).put("expectedSessionVersion", version);
        body.set("plan", plan);
        return send(owner, post(base(deck, session) + "/plan-approval").contentType("application/json").content(body.toString()));
    }

    /** The plan as the owner would send it back: the items of the shown plan, in the wire shape of the request. */
    private static ObjectNode planOf(JsonNode shown, List<Integer> keep, java.util.function.Consumer<ObjectNode> edit) {
        ObjectNode plan = JSON.createObjectNode();
        ArrayNode items = plan.putArray("items");
        for (int index : keep) {
            JsonNode item = shown.path("items").get(index);
            ObjectNode row = items.addObject();
            if (shown.path("kind").stringValue("").equals("EXERCISES")) {
                row.put("memberKey", item.path("memberKey").stringValue(null));
                row.set("mechanics", item.path("mechanics").deepCopy());
                row.put("count", item.path("count").intValue());
            } else {
                row.set("source", item.path("source").deepCopy());
                row.put("title", item.path("title").stringValue(null)).put("effort", item.path("effort").stringValue(null));
            }
            edit.accept(row);
        }
        return plan;
    }

    private UUID planned(UUID owner, UUID deck, ObjectNode spec) throws Exception {
        UUID session = start(owner, deck, spec);
        awaitState(session, "PLAN_READY");
        return session;
    }

    private List<String> stepKinds(UUID session) {
        return jdbc.sql("SELECT kind FROM app_learning.generation_step WHERE session_id=:id ORDER BY created_at").param("id", session)
                .query(String.class).list();
    }

    private List<GenerationTestConfiguration.Call> planCalls(String marker) {
        return provider.callsOf(marker).stream().filter(call -> call.prompt().contains("<task kind=\"plan\">")).toList();
    }

    private int smartPlans(UUID owner) {
        return jdbc.sql("SELECT COALESCE(sum(units),0)::integer FROM app_learning.usage_ledger_entry WHERE owner_id=:owner AND bucket='SMART_PLAN'")
                .param("owner", owner).query(Integer.class).single();
    }

    private List<String> eventTypes(UUID owner, UUID deck, UUID session) throws Exception {
        List<String> types = new ArrayList<>();
        for (JsonNode event : json(events(owner, deck, session, "?after=0&limit=100")).path("events")) {
            JsonNode payload = event.path("payload");
            types.add(event.path("type").stringValue(null) + (event.path("type").stringValue("").equals("SESSION_STATE")
                    ? ":" + payload.path("state").stringValue(null) : ""));
        }
        return types;
    }

    // ----------------------------------------------------------------------------- exercises

    @Test
    void anExercisesPlanIsMadeDebitedApartEditedAndLaunchedAsExactlyThePlannedExercises() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material first = material(owner, deck, "[[t:plan-one]]");
        StudyFixtures.Material second = material(owner, deck, "[[t:plan-two]]");
        StudyFixtures.Material third = material(owner, deck, "[[t:plan-three]]");
        ObjectNode spec = exercisesSpec("{\"mode\":\"EXACT\",\"perTarget\":3}", List.of("CLOZE", "CHOICE", "ORDER"), first, second, third);

        MockHttpServletResponse created = create(owner, deck, spec, UUID.randomUUID());
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        JsonNode body = json(created);
        UUID session = UUID.fromString(body.path("sessionId").stringValue(null));
        // PLANNING: no artifact, a plan hold and the batch hold exactly as without the plan (9 exercises: 15 credits), one PLAN step
        assertThat(body.path("state").stringValue(null)).isEqualTo("PLANNING");
        assertThat(body.path("artifacts")).isEmpty();
        assertThat(body.path("plan").isNull()).isTrue();
        assertThat(body.path("usage").path("reservedCredits").intValue()).isEqualTo(15 + 20);

        awaitState(session, "PLAN_READY");
        JsonNode shown = detail(owner, deck, session);
        JsonNode plan = shown.path("plan");
        assertThat(shown.path("artifacts")).isEmpty();
        assertThat(plan.path("kind").stringValue(null)).isEqualTo("EXERCISES");
        assertThat(plan.path("approved").booleanValue()).isFalse();
        assertThat(plan.path("items")).hasSize(3);
        // one item per target, the allowed mechanics round-robin, the count the spec asked for
        assertThat(plan.path("items").get(0).path("memberKey").stringValue(null)).isEqualTo(first.member().toString());
        assertThat(plan.path("items").get(0).path("mechanics").toString()).isEqualTo("[\"CLOZE\",\"CHOICE\"]");
        assertThat(plan.path("items").get(1).path("mechanics").toString()).isEqualTo("[\"CHOICE\",\"ORDER\"]");
        assertThat(plan.path("items").get(0).path("count").intValue()).isEqualTo(3);
        assertThat(plan.path("items").get(0).path("title").stringValue(null)).contains("Планировщик выбирает план выполнения");
        assertThat(plan.path("items").get(0).path("why").stringValue(null)).isNotBlank();
        assertThat(plan.path("targets")).hasSize(3);
        assertThat(plan.path("totals").path("artifacts").intValue()).isEqualTo(9);
        // the plan fits the hold: the cost is visible before launching, the plan was debited apart (20 credits, one smart plan counted)
        assertThat(plan.path("cost").path("planCredits").intValue()).isEqualTo(20);
        assertThat(plan.path("cost").path("batchCredits").intValue()).isEqualTo(15);
        assertThat(plan.path("cost").path("holdCredits").intValue()).isEqualTo(15);
        assertThat(plan.path("cost").path("barCredits").intValue()).isEqualTo(360);
        assertThat(plan.path("rates").path("exercisesPerFive").intValue()).isEqualTo(8);
        assertThat(plan.path("allowedMechanics").toString()).isEqualTo("[\"CLOZE\",\"CHOICE\",\"ORDER\"]");
        assertThat(plan.path("limits").path("maxExercisesPerTarget").intValue()).isEqualTo(10);
        assertThat(debits(owner)).isEqualTo(20);
        assertThat(smartPlans(owner)).isEqualTo(1);
        assertThat(shown.path("usage").path("spentCredits").intValue()).isEqualTo(20);
        assertThat(shown.path("usage").path("reservedCredits").intValue()).isEqualTo(15);
        assertThat(stepKinds(session)).containsExactly("PLAN");
        assertThat(notificationKinds(owner)).containsExactly("GENERATION_PLAN_READY");
        assertThat(eventTypes(owner, deck, session)).containsSubsequence("SESSION_STATE:PLANNING", "USAGE_UPDATED", "SESSION_STATE:PLAN_READY");
        // the planner's call: thinking route, no transaction, the budget and the handles in the prompt, no material text
        List<GenerationTestConfiguration.Call> calls = planCalls("[[t:plan-one]]");
        assertThat(calls).hasSize(1);
        assertThat(calls.getFirst().route()).isEqualTo(AiRoute.PLAN);
        assertThat(calls.getFirst().transactionAtCall()).isFalse();
        assertThat(calls.getFirst().connectionsAtCall()).isZero();
        assertThat(calls.getFirst().prompt()).contains("<data_policy>").contains("m1 · ").contains("m3 · ").contains("<budget>не больше 15 кредитов")
                .doesNotContain(SECOND);

        // the owner removes the middle material and changes the count and the mechanics of the last one
        ObjectNode edited = planOf(plan, List.of(0, 2), row -> { });
        ((ObjectNode) edited.path("items").get(1)).put("count", 2).putArray("mechanics").add("CHOICE");
        UUID command = UUID.randomUUID();
        MockHttpServletResponse approved = approvePlan(owner, deck, session, command, shown.path("rowVersion").stringValue(null), edited);
        assertThat(approved.getStatus()).as(approved.getContentAsString()).isEqualTo(200);
        assertThat(approved.getHeader("ETag")).isNotNull();
        JsonNode running = json(approved);
        assertThat(running.path("state").stringValue(null)).isEqualTo("RUNNING");
        // exactly the planned artifacts: 3 + 2 exercises, none for the removed material
        assertThat(running.path("artifacts")).hasSize(5);
        assertThat(running.path("plan").path("approved").booleanValue()).isTrue();
        assertThat(running.path("plan").path("totals").path("artifacts").intValue()).isEqualTo(5);
        assertThat(running.path("plan").path("cost").path("batchCredits").intValue()).isEqualTo(8);
        // the batch hold was re-sized to the plan (5 exercises: 8 credits); the plan's debit is part of what the session spent
        assertThat(running.path("usage").path("spentCredits").intValue()).isEqualTo(20);
        assertThat(running.path("usage").path("reservedCredits").intValue()).isEqualTo(8);
        List<String> memberOfArtifact = jdbc.sql("SELECT source_refs->0->>'memberKey' FROM app_learning.generation_artifact WHERE session_id=:id ORDER BY ordinal")
                .param("id", session).query(String.class).list();
        assertThat(memberOfArtifact).containsExactly(first.member().toString(), first.member().toString(), first.member().toString(),
                third.member().toString(), third.member().toString());

        // an exact repeat is the stored answer, a changed body with the same command is a conflict
        MockHttpServletResponse replay = approvePlan(owner, deck, session, command, shown.path("rowVersion").stringValue(null), edited);
        assertThat(replay.getStatus()).isEqualTo(200);
        assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(replay.getHeader("ETag")).isNull();
        problem(approvePlan(owner, deck, session, command, shown.path("rowVersion").stringValue(null), planOf(plan, List.of(0), row -> { })), 409,
                "IDEMPOTENCY_CONFLICT");

        awaitState(session, "REVIEW");
        List<Proposal> proposals = proposals(owner, deck, session);
        assertThat(proposals).hasSize(5);
        assertThat(artifactStates(session)).containsOnly("PROPOSED");
        // the mechanics the owner chose bind the step: the last material's exercises are all CHOICE
        for (int index = 3; index < 5; index++) {
            JsonNode artifact = json(send(owner, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    base(deck, session) + "/artifacts/" + proposals.get(index).artifact())));
            assertThat(artifact.path("display").path("mechanic").stringValue(null)).isEqualTo("CHOICE");
        }
        // 20 for the plan and 8 for five exercises, each debit its own entry; every hold ended
        assertThat(debits(owner)).isEqualTo(28);
        assertThat(smartPlans(owner)).isEqualTo(1);
        assertThat(reservationsOf(owner)).doesNotContain("ACTIVE");
        assertThat(json(getSession(owner, deck, session)).path("usage").path("spentCredits").intValue()).isEqualTo(28);
    }

    @Test
    void theOwnersPlanIsValidatedStrictlyAndNothingIsClampedOrCreatedOnARefusal() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material first = material(owner, deck, "[[t:strict-one]]");
        StudyFixtures.Material second = material(owner, deck, "[[t:strict-two]]");
        UUID session = planned(owner, deck, exercisesSpec("{\"mode\":\"EXACT\",\"perTarget\":2}", List.of("CLOZE", "CHOICE"), first, second));
        JsonNode shown = detail(owner, deck, session);
        String version = shown.path("rowVersion").stringValue(null);
        JsonNode plan = shown.path("plan");

        // shape and identifiers: 400
        problem(approvePlan(owner, deck, session, UUID.randomUUID(), version, planOf(plan, List.of(), row -> { })), 400, "INVALID_REQUEST");
        problem(approvePlan(owner, deck, session, UUID.randomUUID(), version, planOf(plan, List.of(0), row -> row.put("memberKey", UUID.randomUUID().toString()))),
                400, "INVALID_REQUEST");
        problem(approvePlan(owner, deck, session, UUID.randomUUID(), version, planOf(plan, List.of(0, 0), row -> { })), 400, "INVALID_REQUEST");
        problem(approvePlan(owner, deck, session, UUID.randomUUID(), version, planOf(plan, List.of(0), row -> row.putArray("mechanics").add("MATCH"))),
                400, "INVALID_REQUEST");
        problem(approvePlan(owner, deck, session, UUID.randomUUID(), version, planOf(plan, List.of(0), row -> row.put("count", 0))), 400, "INVALID_REQUEST");
        problem(approvePlan(owner, deck, session, UUID.randomUUID(), version, planOf(plan, List.of(0), row -> row.put("surplus", 1))), 400, "INVALID_REQUEST");
        // limits: 422, never clamped
        MockHttpServletResponse perTarget = approvePlan(owner, deck, session, UUID.randomUUID(), version, planOf(plan, List.of(0), row -> row.put("count", 11)));
        problem(perTarget, 422, "RESOURCE_LIMIT_EXCEEDED");
        assertThat(json(perTarget).path("limit").stringValue(null)).isEqualTo("EXERCISES_PER_TARGET");
        assertThat(json(perTarget).path("limits").path("maxExercisesPerTarget").intValue()).isEqualTo(10);
        // a stale version is 412 before anything else
        problem(approvePlan(owner, deck, session, UUID.randomUUID(), "0", planOf(plan, List.of(0), row -> { })), 412, "VERSION_CONFLICT");
        // an unknown session is the opaque 404
        problem(approvePlan(owner, deck, UUID.randomUUID(), UUID.randomUUID(), version, planOf(plan, List.of(0), row -> { })), 404, "RESOURCE_NOT_FOUND");
        assertThat(artifactStates(session)).isEmpty();
        assertThat(sessionState(session)).isEqualTo("PLAN_READY");

        // only a PLAN_READY session is launched: once launched (and quiet again), the same plan is an illegal state
        MockHttpServletResponse launched = approvePlan(owner, deck, session, UUID.randomUUID(), version, planOf(plan, List.of(0), row -> { }));
        assertThat(launched.getStatus()).isEqualTo(200);
        awaitState(session, "REVIEW");
        MockHttpServletResponse again = approvePlan(owner, deck, session, UUID.randomUUID(),
                detail(owner, deck, session).path("rowVersion").stringValue(null), planOf(plan, List.of(0), row -> { }));
        problem(again, 409, "GENERATION_STATE_CONFLICT");
        assertThat(json(again).path("reason").stringValue(null)).isEqualTo("ILLEGAL_STATE");
    }

    @Test
    void aPlanDearerThanTheHoldExtendsItAndWhatTheBalanceCannotPayIsAUsageLimit() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        StudyFixtures.Material first = material(owner, deck, "[[t:extend-one]]");
        StudyFixtures.Material second = material(owner, deck, "[[t:extend-two]]");
        UUID session = planned(owner, deck, exercisesSpec("{\"mode\":\"EXACT\",\"perTarget\":1}", null, first, second));
        JsonNode shown = detail(owner, deck, session);
        assertThat(shown.path("plan").path("cost").path("holdCredits").intValue()).isEqualTo(4);

        // 8 exercises cost 13 credits, above the hold of 4: the hold is extended to the plan
        ObjectNode bigger = planOf(shown.path("plan"), List.of(0, 1), row -> row.put("count", 4));
        // but the balance is nearly spent by another hold: 360 - 20 (plan) - 4 (this hold) - 330 leaves 6, and 6 + 4 does not pay 13
        UUID[] other = new UUID[1];
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                other[0] = ledger.reserve(owner, ReservationScope.SESSION, UUID.randomUUID(), null, 330).reservationId());
        MockHttpServletResponse refused = approvePlan(owner, deck, session, UUID.randomUUID(), shown.path("rowVersion").stringValue(null), bigger);
        problem(refused, 409, "USAGE_LIMIT_REACHED");
        // nothing changed: still PLAN_READY with its first hold, no artifact
        assertThat(sessionState(session)).isEqualTo("PLAN_READY");
        assertThat(artifactStates(session)).isEmpty();
        assertThat(reservationState(session)).isEqualTo("ACTIVE");
        assertThat(detail(owner, deck, session).path("rowVersion").stringValue(null)).isEqualTo(shown.path("rowVersion").stringValue(null));

        // the credits come back: the same plan is launched and its hold is the plan's cost
        new TransactionTemplate(transactions).executeWithoutResult(status -> ledger.release(owner, other[0]));
        MockHttpServletResponse extended = approvePlan(owner, deck, session, UUID.randomUUID(), shown.path("rowVersion").stringValue(null), bigger);
        assertThat(extended.getStatus()).as(extended.getContentAsString()).isEqualTo(200);
        assertThat(json(extended).path("artifacts")).hasSize(8);
        assertThat(json(extended).path("usage").path("reservedCredits").intValue()).isEqualTo(13);
    }

    // ----------------------------------------------------------------------------- materials

    @Test
    void aMaterialsPlanFollowsTheNotesAndTheApprovedItemsAreWrittenAtTheirEffortOnTheirTopic() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID noteOne = note(owner, deck, "Шахматы: дебют, миттельшпиль и эндшпиль в одном конспекте");
        UUID noteTwo = note(owner, deck, "Глаголы движения в японском языке");
        UUID session = planned(owner, deck, materialsSpec(null, noteSource(noteOne, 0), noteSource(noteTwo, 0)));

        JsonNode shown = detail(owner, deck, session);
        JsonNode plan = shown.path("plan");
        assertThat(plan.path("kind").stringValue(null)).isEqualTo("MATERIALS");
        assertThat(plan.path("items")).hasSize(2);
        assertThat(plan.path("items").get(0).path("source").stringValue(null)).isEqualTo(noteOne.toString());
        assertThat(plan.path("items").get(0).path("title").stringValue(null)).startsWith("Шахматы");
        assertThat(plan.path("items").get(0).path("effort").stringValue(null)).isEqualTo("MEDIUM");
        assertThat(plan.path("items").get(0).path("creditsByEffort").path("SHORT").intValue()).isEqualTo(4);
        assertThat(plan.path("items").get(0).path("creditsByEffort").path("DETAILED").intValue()).isEqualTo(22);
        assertThat(plan.path("sources")).hasSize(2);
        assertThat(plan.path("sources").get(1).path("label").stringValue(null)).startsWith("Глаголы движения");
        assertThat(plan.path("cost").path("batchCredits").intValue()).isEqualTo(20);
        assertThat(plan.path("cost").path("planCredits").intValue()).isEqualTo(20);
        assertThat(shown.path("artifacts")).isEmpty();
        assertThat(planCalls("Шахматы")).hasSize(1);
        assertThat(planCalls("Шахматы").getFirst().prompt()).contains("<note id=\"n1\">").contains("<note id=\"n2\">").contains("Вид: MATERIALS");

        // the owner drops the second item, renames the first and makes it short
        ObjectNode edited = planOf(plan, List.of(0), row -> row.put("title", "Шахматные дебюты").put("effort", "SHORT"));
        MockHttpServletResponse approved = approvePlan(owner, deck, session, UUID.randomUUID(), shown.path("rowVersion").stringValue(null), edited);
        assertThat(approved.getStatus()).as(approved.getContentAsString()).isEqualTo(200);
        assertThat(json(approved).path("artifacts")).hasSize(1);
        assertThat(json(approved).path("usage").path("reservedCredits").intValue()).isEqualTo(4);
        String refs = jdbc.sql("SELECT source_refs::text FROM app_learning.generation_artifact WHERE session_id=:id").param("id", session)
                .query(String.class).single();
        assertThat(refs).contains(noteOne.toString()).doesNotContain(noteTwo.toString());

        awaitState(session, "REVIEW");
        // the draft was written on the topic the plan names, at the short effort (150 words), and charged at it
        List<GenerationTestConfiguration.Call> drafts = provider.callsOf("Шахматные дебюты").stream()
                .filter(call -> call.prompt().contains("<task kind=\"material\">")).toList();
        assertThat(drafts).hasSize(1);
        assertThat(drafts.getFirst().prompt()).contains("Тема материала: Шахматные дебюты");
        assertThat(debits(owner)).isEqualTo(20 + 4);
    }

    @Test
    void aPlanOverTheBudgetIsTrimmedFromTheEndAndSaysSo() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID noteOne = note(owner, deck, "Первая тема");
        UUID noteTwo = note(owner, deck, "Вторая тема");
        ObjectNode spec = materialsSpec(null, noteSource(noteOne, 0), noteSource(noteTwo, 0));
        // 3% of 360 credits buys one medium material: the hold is 10 and the plan of two does not fit it
        ((ObjectNode) spec.path("settings")).put("budgetPercent", 3);
        UUID session = planned(owner, deck, spec);
        JsonNode plan = detail(owner, deck, session).path("plan");
        assertThat(plan.path("items")).hasSize(1);
        assertThat(plan.path("items").get(0).path("source").stringValue(null)).isEqualTo(noteOne.toString());
        assertThat(plan.path("notes").get(0).path("code").stringValue(null)).isEqualTo("TRIMMED_TO_BUDGET");
        assertThat(plan.path("cost").path("batchCredits").intValue()).isEqualTo(10);
        assertThat(plan.path("cost").path("holdCredits").intValue()).isEqualTo(10);
        assertThat(planCalls("Первая тема").getFirst().prompt()).contains("<budget>не больше 10 кредитов");
    }

    @Test
    void aPromptOnlyMaterialsPlanFitsItsHoldHasNoSourcesAndTheOwnerMayAddItemsWhichExtendsTheHold() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = planned(owner, deck, materialsSpec("Шесть тем по сетям"));
        JsonNode shown = detail(owner, deck, session);
        // the hold of a prompt-only session is one material, so the plan the model made is trimmed to it
        assertThat(shown.path("plan").path("items")).hasSize(1);
        assertThat(shown.path("plan").path("notes").get(0).path("code").stringValue(null)).isEqualTo("TRIMMED_TO_BUDGET");
        assertThat(shown.path("plan").path("items").get(0).path("source").isNull()).isTrue();
        assertThat(shown.path("plan").path("sources")).isEmpty();
        ObjectNode named = planOf(shown.path("plan"), List.of(0), row -> row.put("source", UUID.randomUUID().toString()));
        problem(approvePlan(owner, deck, session, UUID.randomUUID(), shown.path("rowVersion").stringValue(null), named), 400, "INVALID_REQUEST");
        // the owner adds a second material: the cost is 20, above the hold of 10, and the hold is extended
        ObjectNode two = planOf(shown.path("plan"), List.of(0, 0), row -> { });
        ((ObjectNode) two.path("items").get(1)).put("title", "Вторая тема");
        MockHttpServletResponse launched = approvePlan(owner, deck, session, UUID.randomUUID(), shown.path("rowVersion").stringValue(null), two);
        assertThat(launched.getStatus()).as(launched.getContentAsString()).isEqualTo(200);
        assertThat(json(launched).path("artifacts")).hasSize(2);
        assertThat(json(launched).path("usage").path("reservedCredits").intValue()).isEqualTo(20);
    }

    // ----------------------------------------------------------------------------- failures

    @Test
    void aPlanThatIsInvalidOnceIsRepairedAndOneThatStaysInvalidCancelsTheSessionAndReleasesEverything() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID repaired = planned(owner, deck, materialsSpec(null, noteSource(note(owner, deck, "Тема [[stub:plan-invalid]]"), 0)));
        List<GenerationTestConfiguration.Call> calls = planCalls("[[stub:plan-invalid]]");
        assertThat(calls).extracting(GenerationTestConfiguration.Call::repair).containsExactly(false, true);
        assertThat(calls).extracting(GenerationTestConfiguration.Call::route).containsOnly(AiRoute.PLAN);
        assertThat(detail(owner, deck, repaired).path("plan").path("items")).hasSize(1);
        assertThat(debits(owner)).isEqualTo(20);

        UUID other = UUID.randomUUID();
        UUID otherDeck = deck(other);
        UUID failed = start(other, otherDeck, materialsSpec(null, noteSource(note(other, otherDeck, "Тема [[stub:plan-invalid-always]]"), 0)));
        awaitState(failed, "CANCELLED");
        JsonNode detail = detail(other, otherDeck, failed);
        assertThat(detail.path("endReason").stringValue(null)).isEqualTo("PLAN_FAILED");
        assertThat(detail.path("plan").isNull()).isTrue();
        assertThat(detail.path("artifacts")).isEmpty();
        // the repair, then the strong route, then the end: nothing was debited, nothing is held, the cap was not used
        assertThat(planCalls("[[stub:plan-invalid-always]]")).extracting(GenerationTestConfiguration.Call::route)
                .containsExactly(AiRoute.PLAN, AiRoute.PLAN, AiRoute.PLAN_STRONG);
        assertThat(debits(other)).isZero();
        assertThat(smartPlans(other)).isZero();
        assertThat(reservationsOf(other)).doesNotContain("ACTIVE");
        assertThat(notificationKinds(other)).containsExactly("GENERATION_FAILED");
        assertThat(detail.path("usage").path("reservedCredits").intValue()).isZero();
        assertThat(stepKinds(failed)).containsExactly("PLAN");
        assertThat(jdbc.sql("SELECT state FROM app_learning.generation_step WHERE session_id=:id").param("id", failed).query(String.class).single())
                .isEqualTo("FAILED");
    }

    @Test
    void cancellingWhilePlanningStopsTheCallAndPaysNothingAndCancellingAPlanKeepsItsDebit() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID waiting = start(owner, deck, materialsSpec(null, noteSource(note(owner, deck, "Ожидание [[fake:block]]"), 0)));
        assertThat(provider.blockedEntered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(sessionState(waiting)).isEqualTo("PLANNING");
        assertThat(json(send(owner, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base(deck, waiting) + "/events")))
                .path("activeSteps").get(0).path("kind").stringValue(null)).isEqualTo("PLAN");
        MockHttpServletResponse cancelled = cancel(owner, deck, waiting, UUID.randomUUID());
        assertThat(cancelled.getStatus()).isEqualTo(200);
        assertThat(json(cancelled).path("state").stringValue(null)).isEqualTo("CANCELLED");
        assertThat(json(cancelled).path("endReason").stringValue(null)).isEqualTo("USER_CANCELLED");
        provider.release.countDown();
        await("the step to be cancelled", Duration.ofSeconds(10), () -> steps.activeSteps(waiting).isEmpty());
        assertThat(debits(owner)).isZero();
        assertThat(smartPlans(owner)).isZero();
        assertThat(reservationsOf(owner)).doesNotContain("ACTIVE");
        assertThat(sessionState(waiting)).isEqualTo("CANCELLED");

        UUID ready = planned(owner, deck, materialsSpec(null, noteSource(note(owner, deck, "План"), 0)));
        assertThat(debits(owner)).isEqualTo(20);
        MockHttpServletResponse stopped = cancel(owner, deck, ready, UUID.randomUUID());
        assertThat(json(stopped).path("state").stringValue(null)).isEqualTo("CANCELLED");
        // the plan was delivered and stays paid; its holds are gone and it can be deleted
        assertThat(debits(owner)).isEqualTo(20);
        assertThat(reservationsOf(owner)).doesNotContain("ACTIVE");
        assertThat(deleteSession(owner, deck, ready).getStatus()).isEqualTo(204);
    }

    @Test
    void thePlanCountsAgainstTheSmartPlanCapAndASessionInPlanReadyCountsAsActive() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        // three sessions that wait in PLAN_READY fill the active limit
        for (int index = 0; index < 3; index++) planned(owner, deck, materialsSpec("Тема " + index));
        problem(create(owner, deck, materialsSpec("Четвёртая"), UUID.randomUUID()), 422, "RESOURCE_LIMIT_EXCEEDED");
        assertThat(smartPlans(owner)).isEqualTo(3);

        UUID capped = UUID.randomUUID();
        UUID cappedDeck = deck(capped);
        new TransactionTemplate(transactions).executeWithoutResult(status -> ledger.consume(capped, Bucket.SMART_PLAN, 4, "plan:cap:" + capped, null));
        MockHttpServletResponse refused = create(capped, cappedDeck, materialsSpec("Тема"), UUID.randomUUID());
        problem(refused, 409, "USAGE_LIMIT_REACHED");
        assertThat(json(refused).path("bucket").stringValue(null)).isEqualTo("SMART_PLAN");
        assertThat(reservationsOf(capped)).isEmpty();
        // a session without the plan is not touched by the cap
        assertThat(create(capped, cappedDeck, spec("Без плана"), UUID.randomUUID()).getStatus()).isEqualTo(201);
    }

    @Test
    void aWorkerThatCrashedMidPlanIsRecoveredAndAProviderThatStaysDownEndsThePlanAndCancelsTheSession() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        // the first claim crashes (no write): the lease runs out, the step is claimed again and the plan is made and paid once
        UUID recovered = planned(owner, deck, materialsSpec(null, noteSource(note(owner, deck, "Тема [[fake:crash-once]]"), 0)));
        assertThat(planCalls("[[fake:crash-once]]")).hasSize(2);
        assertThat(detail(owner, deck, recovered).path("plan").path("items")).hasSize(1);
        assertThat(debits(owner)).isEqualTo(20);
        assertThat(smartPlans(owner)).isEqualTo(1);

        // a provider that is down on every attempt: the step is retried with backoff and fails after its last attempt, the session is cancelled
        UUID other = UUID.randomUUID();
        UUID otherDeck = deck(other);
        UUID down = start(other, otherDeck, materialsSpec(null, noteSource(note(other, otherDeck, "Тема [[fake:transient]]"), 0)));
        awaitState(down, "CANCELLED");
        assertThat(detail(other, otherDeck, down).path("endReason").stringValue(null)).isEqualTo("PLAN_FAILED");
        assertThat(planCalls("[[fake:transient]]")).hasSize(3);
        assertThat(debits(other)).isZero();
        assertThat(smartPlans(other)).isZero();
        assertThat(reservationsOf(other)).doesNotContain("ACTIVE");
        assertThat(notificationKinds(other)).containsExactly("GENERATION_FAILED");
    }

    @Test
    void aPlanReadySessionExpiresLikeTheOthersAndALapsedBatchHoldIsReservedAgainByTheApproval() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID lapsing = planned(owner, deck, exercisesSpec("{\"mode\":\"EXACT\",\"perTarget\":2}", null, material(owner, deck, "[[t:lapse]]")));
        JsonNode shown = detail(owner, deck, lapsing);
        // the hold of a plan nobody launched is not renewed: it lapses by its time to live, the plan's debit stays
        jdbc.sql("UPDATE app_learning.usage_reservation SET created_at=CURRENT_TIMESTAMP - interval '3 hours',expires_at=CURRENT_TIMESTAMP - interval '1 minute' WHERE owner_id=:owner AND state='ACTIVE'")
                .param("owner", owner).update();
        new TransactionTemplate(transactions).executeWithoutResult(status -> ledger.expireDue(100));
        assertThat(reservationsOf(owner)).doesNotContain("ACTIVE");
        MockHttpServletResponse launched = approvePlan(owner, deck, lapsing, UUID.randomUUID(), shown.path("rowVersion").stringValue(null),
                planOf(shown.path("plan"), List.of(0), row -> { }));
        assertThat(launched.getStatus()).as(launched.getContentAsString()).isEqualTo(200);
        assertThat(json(launched).path("artifacts")).hasSize(2);
        assertThat(reservationState(lapsing)).isEqualTo("ACTIVE");

        UUID waiting = planned(owner, deck, materialsSpec("Тема на хранение"));
        jdbc.sql("UPDATE app_learning.generation_session SET expires_at=CURRENT_TIMESTAMP - interval '1 hour' WHERE session_id=:id").param("id", waiting).update();
        assertThat(retention.run().expired()).isGreaterThanOrEqualTo(1);
        JsonNode expired = detail(owner, deck, waiting);
        assertThat(expired.path("state").stringValue(null)).isEqualTo("EXPIRED");
        // the plan stays readable, nothing can launch it
        assertThat(expired.path("plan").path("items")).hasSize(1);
        problem(approvePlan(owner, deck, waiting, UUID.randomUUID(), expired.path("rowVersion").stringValue(null),
                planOf(expired.path("plan"), List.of(0), row -> { })), 409, "GENERATION_STATE_CONFLICT");
        assertThat(reservationState(waiting)).isNotEqualTo("ACTIVE");
    }
}
