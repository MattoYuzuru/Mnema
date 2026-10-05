package app.mnema.learning.generation;

import app.mnema.learning.ai.AiResult;
import app.mnema.learning.ai.WebSearch;
import app.mnema.learning.usage.AdmissionPricing;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Acceptance of #299 (AI-18) on the Stub providers, PostgreSQL and the real catalog: the {@code RESEARCH} step before the draft (caps per effort, the hold
 * and the debit per request, dedupe and numbering, the sources section that holds only what was found), a research that fails and never blocks the
 * material, cancellation, recovery, retry and a planned session. The live providers are not reached: no key exists (see {@code WebSearchLiveTest}).
 */
@TestPropertySource(properties = "learning.features.web-search.enabled=true")
class GenerationResearchIntegrationTest extends GenerationEditsSupport {
    @org.springframework.beans.factory.annotation.Autowired private AdmissionPricing pricing;
    @org.springframework.beans.factory.annotation.Autowired private ResearchRepository researchRepository;

    // ------------------------------------------------------------------ helpers

    private static ObjectNode factSpec(String prompt, String effort) {
        ObjectNode spec = spec(prompt);
        ((ObjectNode) spec.path("settings")).put("effort", effort).put("factCheck", true);
        return spec;
    }

    /** {@code KIND:STATE} of the steps of the session, research before draft. */
    private List<String> steps(UUID session) {
        return jdbc.sql("SELECT kind||':'||state FROM app_learning.generation_step WHERE session_id=:id ORDER BY kind,idempotency_key")
                .param("id", session).query(String.class).list();
    }

    private UUID researchStep(UUID session) {
        return jdbc.sql("SELECT step_id FROM app_learning.generation_step WHERE session_id=:id AND kind='RESEARCH' ORDER BY idempotency_key DESC LIMIT 1")
                .param("id", session).query(UUID.class).single();
    }

    private int researchDebits(UUID owner) {
        return jdbc.sql("SELECT COALESCE(-sum(credits),0)::integer FROM app_learning.usage_ledger_entry WHERE owner_id=:owner AND kind='DEBIT' "
                + "AND operation='WEB_SEARCH_QUERY'").param("owner", owner).query(Integer.class).single();
    }

    private JsonNode research(UUID owner, UUID deck, UUID session) throws Exception {
        return detail(owner, deck, proposals(owner, deck, session).getFirst()).path("research");
    }

    private String documentText(UUID owner, UUID deck, UUID session) throws Exception {
        return text(detail(owner, deck, proposals(owner, deck, session).getFirst()).path("revision").path("payload").path("document").path("root"));
    }

    /** The planner calls whose prompt carries {@code marker} (the doubles are shared by every session of the context, so a test names its own). */
    private List<GenerationTestConfiguration.Call> plannerCalls(String marker) {
        return provider.calls.stream().filter(call -> call.prompt().contains("<task kind=\"research\">") && call.prompt().contains(marker)).toList();
    }

    private List<GenerationTestConfiguration.Call> draftCalls(String marker) {
        return provider.calls.stream().filter(call -> call.prompt().contains("<task kind=\"material\">") && call.prompt().contains(marker)).toList();
    }

    /** The searches the research steps of this session made. */
    private List<WebSearch.Request> searchCalls(UUID session) {
        List<UUID> stepIds = jdbc.sql("SELECT step_id FROM app_learning.generation_step WHERE session_id=:id AND kind='RESEARCH'").param("id", session)
                .query(UUID.class).list();
        return search.calls.stream().filter(call -> stepIds.contains(call.stepId())).toList();
    }

    private static WebSearch.Result result(String url, int query, int rank) {
        return new WebSearch.Result(url, "Заголовок " + url, "фрагмент", "2026-01-01", WebSearch.Provider.YANDEX, query, rank);
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) count++;
        return count;
    }

    // ------------------------------------------------------------------- the flow

    @Test
    void aFactCheckedMaterialHoldsItsSearchRequestsResearchesBeforeTheDraftAndCitesOnlyWhatWasFound() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = start(owner, deck, factSpec("планировщик запросов postgresql [[fake:research-block]]", "MEDIUM"));
        assertThat(provider.researchEntered.await(20, TimeUnit.SECONDS)).isTrue();

        // while the planner is thinking: the research runs, the draft waits for it, the artifact is still queued, and the hold is the material plus two requests
        assertThat(steps(session)).containsExactly("RESEARCH:RUNNING", "TEXT_DRAFT:WAITING_DEPENDENCIES");
        assertThat(artifactStates(session)).containsExactly("QUEUED");
        UUID research = researchStep(session);
        assertThat(jdbc.sql("SELECT depends_on[1] FROM app_learning.generation_step WHERE session_id=:id AND kind='TEXT_DRAFT'").param("id", session)
                .query(UUID.class).single()).isEqualTo(research);
        assertThat(jdbc.sql("SELECT capability FROM app_learning.generation_step WHERE step_id=:id").param("id", research).query(String.class).single())
                .isEqualTo("SEARCH");
        JsonNode input = JSON.readTree(jdbc.sql("SELECT input::text FROM app_learning.generation_step WHERE step_id=:id").param("id", research)
                .query(String.class).single());
        assertThat(input.path("cap").intValue()).isEqualTo(2);
        assertThat(input.path("credits").intValue()).isEqualTo(10);
        assertThat(input.path("draftCredits").intValue()).isEqualTo(10);
        JsonNode running = json(getSession(owner, deck, session));
        assertThat(running.path("usage").path("reservedCredits").intValue()).isEqualTo(20);
        assertThat(running.path("usage").path("spentCredits").intValue()).isZero();
        UUID artifact = UUID.fromString(running.path("artifacts").get(0).path("artifactId").stringValue(null));
        JsonNode active = json(events(owner, deck, session, "")).path("activeSteps");
        assertThat(active).hasSize(1);
        assertThat(active.get(0).path("kind").stringValue(null)).isEqualTo("RESEARCH");
        assertThat(active.get(0).path("state").stringValue(null)).isEqualTo("RUNNING");
        assertThat(active.get(0).path("artifactId").stringValue(null)).isEqualTo(artifact.toString());

        provider.researchRelease.countDown();
        awaitState(session, "REVIEW");

        assertThat(steps(session)).containsExactly("RESEARCH:SUCCEEDED", "TEXT_DRAFT:SUCCEEDED");
        assertThat(artifactStates(session)).containsExactly("PROPOSED");
        // two queries, three results each, numbered in query order then rank; nothing but pointers is stored
        JsonNode found = research(owner, deck, session);
        assertThat(found.path("requests").intValue()).isEqualTo(2);
        assertThat(found.path("results")).hasSize(6);
        for (int index = 0; index < 6; index++) {
            JsonNode each = found.path("results").get(index);
            assertThat(each.path("n").intValue()).isEqualTo(index + 1);
            assertThat(each.path("url").stringValue(null)).isEqualTo("https://example.org/stub/research/" + (index + 1));
            assertThat(each.path("title").stringValue(null)).startsWith("Источник " + (index + 1));
            assertThat(each.path("provider").stringValue(null)).isEqualTo("STUB");
            assertThat(each.propertyNames()).containsExactlyInAnyOrder("n", "url", "title", "provider");
        }
        // the debit is the material and the two requests; the hold ended with the session
        assertThat(debits(owner)).isEqualTo(20);
        assertThat(researchDebits(owner)).isEqualTo(10);
        assertThat(reservationState(session)).isEqualTo("SETTLED");
        JsonNode done = json(getSession(owner, deck, session));
        assertThat(done.path("usage").path("spentCredits").intValue()).isEqualTo(20);
        assertThat(done.path("usage").path("reservedCredits").intValue()).isZero();

        // the search ran once, for the planner's two queries, outside any transaction
        assertThat(searchCalls(session)).hasSize(1);
        WebSearch.Request request = searchCalls(session).getFirst();
        assertThat(request.queries()).hasSize(2);
        assertThat(request.lang()).isEqualTo("ru");
        assertThat(request.stepId()).isEqualTo(research);
        assertThat(search.transactionAtCall).containsOnly(false);
        // the planner was told the cap; the draft was given the results as data and the allowlist
        assertThat(plannerCalls("планировщик запросов postgresql")).hasSize(1);
        assertThat(plannerCalls("планировщик запросов postgresql").getFirst().prompt()).contains("Не больше запросов: 2.");
        assertThat(plannerCalls("планировщик запросов postgresql").getFirst().transactionAtCall()).isFalse();
        assertThat(draftCalls("планировщик запросов postgresql")).hasSize(1);
        String prompt = draftCalls("планировщик запросов postgresql").getFirst().prompt();
        assertThat(occurrences(prompt, "<search_result n=\"")).isEqualTo(6);
        assertThat(prompt).contains("<search_result n=\"1\" url=\"https://example.org/stub/research/1\"", "https://example.org/stub/research/6\n</allowed_links>");

        // the material cites its sources, and every link of the document is one of the results
        String text = documentText(owner, deck, session);
        assertThat(text).contains("Источники", "Сведения проверены по источникам [1].");
        Proposal proposal = proposals(owner, deck, session).getFirst();
        List<String> hrefs = new ArrayList<>();
        links(detail(owner, deck, proposal).path("revision").path("payload").path("document"), hrefs);
        assertThat(hrefs).hasSize(6).allSatisfy(href -> assertThat(href).startsWith("https://example.org/stub/research/"));
        assertThat(approve(owner, deck, proposal, UUID.randomUUID()).getStatus()).isEqualTo(200);
    }

    private static void links(JsonNode node, List<String> hrefs) {
        if (node.path("type").stringValue("").equals("link")) hrefs.add(node.path("attrs").path("href").stringValue(null));
        for (JsonNode mark : node.path("attrs").path("marks")) if (mark.path("type").stringValue("").equals("link")) hrefs.add(mark.path("attrs").path("href").stringValue(null));
        node.path("content").forEach(child -> links(child, hrefs));
        if (node.has("root")) links(node.path("root"), hrefs);
    }

    @Test
    void theNumberOfRequestsFollowsTheEffortAndShortOrNoFactCheckDoesNoResearch() throws Exception {
        // an owner and a deck for each case: a session in review counts as active and three is the limit
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);

        // detailed: six requests, the price of six held and debited with the material
        assertThat(pricing.hold(owner, deck, factSpec("тема", "DETAILED")).credits()).isEqualTo(22 + 6 * 5);
        UUID detailed = start(owner, deck, factSpec("широкая тема", "DETAILED"));
        awaitState(detailed, "REVIEW");
        assertThat(research(owner, deck, detailed).path("requests").intValue()).isEqualTo(6);
        assertThat(research(owner, deck, detailed).path("results")).hasSize(18);
        assertThat(debits(owner)).isEqualTo(22 + 30);
        assertThat(searchCalls(detailed).getFirst().queries()).hasSize(6);

        // auto without a plan: written as a medium material, researched with the cap of auto
        UUID autoOwner = UUID.randomUUID();
        UUID autoDeck = deck(autoOwner);
        assertThat(pricing.hold(autoOwner, autoDeck, factSpec("тема", "AUTO")).credits()).isEqualTo(10 + 3 * 5);
        UUID auto = start(autoOwner, autoDeck, factSpec("автоматическая тема", "AUTO"));
        awaitState(auto, "REVIEW");
        assertThat(research(autoOwner, autoDeck, auto).path("requests").intValue()).isEqualTo(3);
        assertThat(searchCalls(auto).getFirst().queries()).hasSize(3);

        // short does no research, and neither does a material without a fact check: no RESEARCH step, no research, no search
        UUID plainOwner = UUID.randomUUID();
        UUID plainDeck = deck(plainOwner);
        UUID shortened = start(plainOwner, plainDeck, factSpec("короткая тема", "SHORT"));
        UUID plain = start(plainOwner, plainDeck, spec("обычная тема"));
        awaitState(shortened, "REVIEW");
        awaitState(plain, "REVIEW");
        assertThat(steps(shortened)).containsExactly("TEXT_DRAFT:SUCCEEDED");
        assertThat(steps(plain)).containsExactly("TEXT_DRAFT:SUCCEEDED");
        assertThat(research(plainOwner, plainDeck, shortened).isNull()).isTrue();
        assertThat(research(plainOwner, plainDeck, plain).isNull()).isTrue();
        assertThat(searchCalls(shortened)).isEmpty();
        assertThat(searchCalls(plain)).isEmpty();
        assertThat(documentText(plainOwner, plainDeck, plain)).doesNotContain("Источники");
    }

    @Test
    void resultsAreDeduplicatedByNormalizedUrlAndNumberedInQueryOrderThenRankAndTheDebitIsTheRequestsMade() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        search.script = request -> AiResult.ok(new WebSearch.Answer(List.of(
                result("https://example.org/a", 0, 1), result("https://www.example.org/b/", 0, 2), result("https://example.org/c", 0, 3),
                result("https://example.org/d", 1, 1), result("https://example.org/b?utm_source=feed", 1, 2), result("https://example.org/a#part", 1, 3),
                result("http://insecure.example.org/e", 1, 4)), 2, 11_484));
        UUID session = start(owner, deck, factSpec("повторяющиеся источники", "MEDIUM"));
        awaitState(session, "REVIEW");

        JsonNode found = research(owner, deck, session);
        assertThat(found.path("requests").intValue()).isEqualTo(2);
        // the first occurrence stays; the insecure result is not a link target at all
        assertThat(found.path("results")).extracting(node -> node.path("n").intValue() + " " + node.path("url").stringValue(null))
                .containsExactly("1 https://example.org/a", "2 https://www.example.org/b/", "3 https://example.org/c", "4 https://example.org/d");
        assertThat(found.path("results")).extracting(node -> node.path("provider").stringValue(null)).containsOnly("YANDEX");
        // two requests: ten credits and the cost of the provider in millionths of a rouble (11 484 micro-dollars at 85)
        assertThat(researchDebits(owner)).isEqualTo(10);
        assertThat(jdbc.sql("SELECT cost_micros FROM app_learning.usage_ledger_entry WHERE owner_id=:owner AND operation='WEB_SEARCH_QUERY'")
                .param("owner", owner).query(Long.class).single()).isEqualTo(11_484L * 85);
        String text = documentText(owner, deck, session);
        assertThat(text).contains("Источники");
        assertThat(draftCalls("повторяющиеся источники").getFirst().prompt()).doesNotContain("insecure.example.org").contains("<search_result n=\"4\"");
    }

    // ------------------------------------------------------------------- failures

    @Test
    void aSearchThatIsDownDoesNotBlockTheMaterialWhichIsWrittenWithoutSourcesAndNothingIsDebitedForIt() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = start(owner, deck, factSpec("тема без поиска [[stub:search-down]]", "MEDIUM"));
        awaitState(session, "REVIEW");

        assertThat(steps(session)).containsExactly("RESEARCH:SUCCEEDED", "TEXT_DRAFT:SUCCEEDED");
        assertThat(artifactStates(session)).containsExactly("PROPOSED");
        JsonNode found = research(owner, deck, session);
        assertThat(found.path("requests").intValue()).isZero();
        assertThat(found.path("results")).isEmpty();
        assertThat(documentText(owner, deck, session)).doesNotContain("Источники");
        assertThat(draftCalls("тема без поиска").getFirst().prompt()).doesNotContain("<search_result n=");
        // only the material is paid, and the unspent price of the research went back with the hold
        assertThat(debits(owner)).isEqualTo(10);
        assertThat(researchDebits(owner)).isZero();
        assertThat(json(getSession(owner, deck, session)).path("usage").path("reservedCredits").intValue()).isZero();
        assertThat(notificationKinds(owner)).containsExactly("GENERATION_READY");
    }

    @Test
    void requestsThatWereAnsweredAreDebitedEvenWhenNothingWasFound() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = start(owner, deck, factSpec("тема без выдачи [[stub:search-empty]]", "MEDIUM"));
        awaitState(session, "REVIEW");

        JsonNode found = research(owner, deck, session);
        assertThat(found.path("requests").intValue()).isEqualTo(2);
        assertThat(found.path("results")).isEmpty();
        assertThat(documentText(owner, deck, session)).doesNotContain("Источники");
        assertThat(researchDebits(owner)).isEqualTo(10);
        assertThat(debits(owner)).isEqualTo(20);
    }

    @Test
    void aPlannerThatIsDownOrCannotAnswerInTheFormatEndsTheResearchWithoutSources() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);

        UUID down = start(owner, deck, factSpec("планировщик недоступен [[fake:research-down]]", "MEDIUM"));
        awaitState(down, "REVIEW");
        assertThat(research(owner, deck, down).path("requests").intValue()).isZero();
        assertThat(searchCalls(down)).isEmpty();
        assertThat(artifactStates(down)).containsExactly("PROPOSED");

        // garbage after the one repair: two planner calls, no search
        UUID garbage = start(owner, deck, factSpec("планировщик путается [[stub:research-invalid-always]]", "MEDIUM"));
        awaitState(garbage, "REVIEW");
        assertThat(plannerCalls("планировщик путается")).hasSize(2);
        assertThat(plannerCalls("планировщик путается").get(1).repair()).isTrue();
        assertThat(searchCalls(garbage)).isEmpty();
        assertThat(research(owner, deck, garbage).path("results")).isEmpty();
        assertThat(researchDebits(owner)).isZero();
    }

    @Test
    void aPlannerThatRepairsItsAnswerIsHeardAndOneThatProposesTooManyQueriesIsClamped() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID repaired = start(owner, deck, factSpec("исправление [[stub:research-invalid]]", "MEDIUM"));
        awaitState(repaired, "REVIEW");
        assertThat(plannerCalls("исправление")).hasSize(2);
        assertThat(research(owner, deck, repaired).path("requests").intValue()).isEqualTo(2);

        UUID over = start(owner, deck, factSpec("многословие [[stub:research-over]]", "MEDIUM"));
        awaitState(over, "REVIEW");
        // five queries proposed, the cap of a medium material is two
        assertThat(searchCalls(over)).hasSize(1);
        assertThat(searchCalls(over).getFirst().queries()).hasSize(2);
        assertThat(research(owner, deck, over).path("requests").intValue()).isEqualTo(2);
    }

    @Test
    void aHoldThatCannotPayForAnyRequestBeyondTheDraftSkipsTheResearch() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        ObjectNode spec = factSpec("мало кредитов", "MEDIUM");
        // 4% of the balance holds 14 credits: the material (10) fits, a request (5) on top of it does not
        ((ObjectNode) spec.path("settings")).put("budgetPercent", 4);
        assertThat(pricing.hold(owner, deck, spec).credits()).isBetween(10, 14);
        UUID session = start(owner, deck, spec);
        awaitState(session, "REVIEW");

        assertThat(searchCalls(session)).isEmpty();
        assertThat(plannerCalls("мало кредитов")).isEmpty();
        assertThat(research(owner, deck, session).path("requests").intValue()).isZero();
        assertThat(artifactStates(session)).containsExactly("PROPOSED");
        assertThat(debits(owner)).isEqualTo(10);
    }

    // ------------------------------------------------------------ cancel, recover, retry

    @Test
    void cancellingWhileTheResearchRunsStopsItAndItsDraftAndDebitsNothing() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = start(owner, deck, factSpec("долгий поиск [[fake:research-block]]", "MEDIUM"));
        assertThat(provider.researchEntered.await(20, TimeUnit.SECONDS)).isTrue();

        MockHttpServletResponse response = cancel(owner, deck, session, UUID.randomUUID());
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(json(response).path("artifacts").get(0).path("errorCode").stringValue(null)).isEqualTo("CANCELLED");
        await("the research and its draft to be cancelled", Duration.ofSeconds(10), () -> steps(session).equals(List.of("RESEARCH:CANCELLED", "TEXT_DRAFT:CANCELLED")));

        assertThat(debits(owner)).isZero();
        assertThat(reservationState(session)).isEqualTo("RELEASED");
        assertThat(searchCalls(session)).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_research WHERE session_id=:id").param("id", session).query(Integer.class).single()).isZero();
    }

    @Test
    void aResearchWhoseWorkerCrashedIsClaimedAgainAndPaidOnce() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = start(owner, deck, factSpec("падение [[fake:crash-once]]", "MEDIUM"));
        awaitState(session, "REVIEW");

        assertThat(steps(session)).containsExactly("RESEARCH:SUCCEEDED", "TEXT_DRAFT:SUCCEEDED");
        assertThat(jdbc.sql("SELECT attempts FROM app_learning.generation_step WHERE step_id=:id").param("id", researchStep(session)).query(Integer.class).single())
                .isEqualTo(2);
        assertThat(research(owner, deck, session).path("requests").intValue()).isEqualTo(2);
        assertThat(researchDebits(owner)).isEqualTo(10);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.usage_ledger_entry WHERE owner_id=:owner AND operation='WEB_SEARCH_QUERY'").param("owner", owner)
                .query(Integer.class).single()).isEqualTo(1);
    }

    /** A research held inside its planner call, so that a test can play what happens to its step: the planner waits until the latch is released or the call is aborted. */
    private UUID heldResearch(UUID owner, UUID deck, String topic) throws Exception {
        provider.researchEntered = new java.util.concurrent.CountDownLatch(1);
        UUID session = start(owner, deck, factSpec(topic + " [[fake:research-block]]", "MEDIUM"));
        assertThat(provider.researchEntered.await(20, TimeUnit.SECONDS)).isTrue();
        return session;
    }

    private int attempts(UUID step) {
        return jdbc.sql("SELECT attempts FROM app_learning.generation_step WHERE step_id=:id").param("id", step).query(Integer.class).single();
    }

    @Test
    void aResearchThatCannotBeRunAnymoreEndsAsOneThatFoundNothingSoItsDraftIsNotLeftWaiting() throws Exception {
        // its worker is gone after the last attempt: the lease is taken from the running step and has run out
        UUID ownerOne = UUID.randomUUID();
        UUID deckOne = deck(ownerOne);
        UUID lost = heldResearch(ownerOne, deckOne, "потерянный воркер");
        jdbc.sql("UPDATE app_learning.generation_step SET lease_token=:token,lease_until=CURRENT_TIMESTAMP - interval '1 second',attempts=3 WHERE step_id=:id")
                .param("token", UUID.randomUUID()).param("id", researchStep(lost)).update();
        awaitState(lost, "REVIEW");
        assertThat(steps(lost)).containsExactly("RESEARCH:SUCCEEDED", "TEXT_DRAFT:SUCCEEDED");
        assertThat(research(ownerOne, deckOne, lost).path("requests").intValue()).isZero();
        assertThat(research(ownerOne, deckOne, lost).path("results")).isEmpty();
        assertThat(artifactStates(lost)).containsExactly("PROPOSED");
        assertThat(searchCalls(lost)).isEmpty();
        assertThat(debits(ownerOne)).isEqualTo(10);

        // a worker that is gone with attempts left: the step is claimed again (its first run was aborted) and finishes the research
        UUID ownerTwo = UUID.randomUUID();
        UUID deckTwo = deck(ownerTwo);
        UUID again = heldResearch(ownerTwo, deckTwo, "воркер вернётся");
        UUID step = researchStep(again);
        provider.researchEntered = new java.util.concurrent.CountDownLatch(1);
        jdbc.sql("UPDATE app_learning.generation_step SET lease_token=:token,lease_until=CURRENT_TIMESTAMP - interval '1 second' WHERE step_id=:id")
                .param("token", UUID.randomUUID()).param("id", step).update();
        assertThat(provider.researchEntered.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(attempts(step)).isEqualTo(2);
        provider.researchRelease.countDown();
        awaitState(again, "REVIEW");
        assertThat(research(ownerTwo, deckTwo, again).path("requests").intValue()).isEqualTo(2);
        assertThat(researchDebits(ownerTwo)).isEqualTo(10);

        // a research that waited so long in the queue that its lifetime ran out is never run
        provider.researchRelease = new java.util.concurrent.CountDownLatch(1);
        UUID ownerThree = UUID.randomUUID();
        UUID deckThree = deck(ownerThree);
        UUID late = heldResearch(ownerThree, deckThree, "слишком поздно");
        jdbc.sql("UPDATE app_learning.generation_step SET state='READY',lease_token=NULL,lease_until=NULL,next_attempt_at=CURRENT_TIMESTAMP,"
                + "first_claimed_at=CURRENT_TIMESTAMP - interval '2 hours' WHERE step_id=:id").param("id", researchStep(late)).update();
        awaitState(late, "REVIEW");
        assertThat(steps(late)).containsExactly("RESEARCH:SUCCEEDED", "TEXT_DRAFT:SUCCEEDED");
        assertThat(research(ownerThree, deckThree, late).path("requests").intValue()).isZero();
        assertThat(searchCalls(late)).isEmpty();
    }

    @Test
    void retryingAnArtifactResearchesItAgainAndHoldsTheResearchWithTheRetry() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        // the provider is down for the first attempt: the planner fails (no sources) and so does the draft
        provider.outage = true;
        UUID session = start(owner, deck, factSpec("повтор с поиском", "MEDIUM"));
        awaitState(session, "REVIEW");
        assertThat(artifactStates(session)).containsExactly("FAILED");
        // the research ended without sources (a fact check never blocks), so it is recorded as one that found nothing
        assertThat(research(owner, deck, session).path("requests").intValue()).isZero();
        assertThat(research(owner, deck, session).path("results")).isEmpty();
        Proposal failed = proposals(owner, deck, session).getFirst();
        assertThat(debits(owner)).isZero();

        provider.outage = false;
        MockHttpServletResponse response = retry(owner, deck, failed, UUID.randomUUID(), failed.version());
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        // the retry holds its own research: the material and two requests
        assertThat(jdbc.sql("SELECT held_credits FROM app_learning.usage_reservation WHERE owner_id=:owner AND scope='STEP'").param("owner", owner)
                .query(Integer.class).single()).isEqualTo(20);
        awaitState(session, "REVIEW");

        assertThat(steps(session)).containsExactly("RESEARCH:SUCCEEDED", "RESEARCH:SUCCEEDED", "TEXT_DRAFT:FAILED", "TEXT_DRAFT:SUCCEEDED");
        assertThat(artifactStates(session)).containsExactly("PROPOSED");
        // the results of the earlier attempt were replaced, not added to
        assertThat(research(owner, deck, session).path("requests").intValue()).isEqualTo(2);
        assertThat(research(owner, deck, session).path("results")).hasSize(6);
        assertThat(documentText(owner, deck, session)).contains("Источники");
        assertThat(debits(owner)).isEqualTo(20);
        assertThat(researchDebits(owner)).isEqualTo(10);
    }

    // ------------------------------------------------------------------- planned

    @Test
    void aPlannedMaterialIsResearchedAtTheEffortThePlanChoseAndTheShownPricesIncludeTheResearch() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        ObjectNode spec = spec(null, noteSource(note(owner, deck, "Планировщик выбирает план выполнения запроса"), 0));
        ((ObjectNode) spec.path("settings")).put("effort", "AUTO").put("factCheck", true).put("planFirst", true);
        UUID session = start(owner, deck, spec);
        awaitState(session, "PLAN_READY");
        JsonNode shown = json(getSession(owner, deck, session));
        // the plan shows what each effort costs with its research: material plus the requests of the effort
        JsonNode prices = shown.path("plan").path("items").get(0).path("creditsByEffort");
        assertThat(prices.path("SHORT").intValue()).isEqualTo(4);
        assertThat(prices.path("MEDIUM").intValue()).isEqualTo(10 + 2 * 5);
        assertThat(prices.path("DETAILED").intValue()).isEqualTo(22 + 6 * 5);

        ObjectNode plan = JSON.createObjectNode();
        ArrayNode items = plan.putArray("items");
        JsonNode item = shown.path("plan").path("items").get(0);
        items.addObject().set("source", item.path("source").deepCopy());
        ((ObjectNode) items.get(0)).put("title", item.path("title").stringValue(null)).put("effort", "DETAILED");
        ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString()).put("expectedSessionVersion", shown.path("rowVersion").stringValue(null));
        body.set("plan", plan);
        MockHttpServletResponse approved = send(owner, post(base(deck, session) + "/plan-approval").contentType("application/json").content(body.toString()));
        assertThat(approved.getStatus()).as(approved.getContentAsString()).isEqualTo(200);
        assertThat(json(approved).path("plan").path("cost").path("holdCredits").intValue()).isEqualTo(22 + 6 * 5);

        awaitState(session, "REVIEW");
        assertThat(research(owner, deck, session).path("requests").intValue()).isEqualTo(6);
        assertThat(researchDebits(owner)).isEqualTo(30);
        assertThat(searchCalls(session).getFirst().queries()).hasSize(6);
    }

    @Test
    void internationalizedResultsAreNormalizedNumberedShownAndCitedAndTheResearchIsHiddenFromOtherOwners() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        search.script = request -> AiResult.ok(new WebSearch.Answer(List.of(
                result("https://ru.wikipedia.org/wiki/Москва", 0, 1), result("https://президент.рф/новости?q=привет&a=1", 0, 2),
                result("https://example.org/a b", 0, 3)), 1, 5_742));
        UUID session = start(owner, deck, factSpec("международные источники", "MEDIUM"));
        awaitState(session, "REVIEW");

        JsonNode found = research(owner, deck, session);
        String moscow = "https://ru.wikipedia.org/wiki/%D0%9C%D0%BE%D1%81%D0%BA%D0%B2%D0%B0";
        assertThat(found.path("results")).extracting(node -> node.path("n").intValue() + " " + node.path("url").stringValue(null).replaceFirst("^(https://[^/]+).*", "$1"))
                .containsExactly("1 https://ru.wikipedia.org", "2 https://xn--d1abbgf6aiiy.xn--p1ai");
        assertThat(found.path("results").get(0).path("url").stringValue(null)).isEqualTo(moscow);
        // the compiler took both as links of the document, in the sources section too
        Proposal proposal = proposals(owner, deck, session).getFirst();
        List<String> hrefs = new ArrayList<>();
        links(detail(owner, deck, proposal).path("revision").path("payload").path("document"), hrefs);
        assertThat(hrefs).contains(moscow).hasSize(2);
        assertThat(documentText(owner, deck, session)).contains("Источники");

        // another owner cannot read the artifact, so neither its research
        MockHttpServletResponse foreign = send(UUID.randomUUID(), org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                base(deck, session) + "/artifacts/" + proposal.artifact()));
        assertThat(foreign.getStatus()).isEqualTo(404);
        assertThat(foreign.getContentAsString()).doesNotContain("wikipedia");
    }

    @Test
    void theStoredDocumentStaysUnderTheTableBoundInItsTextFormAndASearchEndedByACancelDebitsNothingTheReleasedHoldCannotPay() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID done = start(owner, deck, factSpec("большая выдача", "MEDIUM"));
        awaitState(done, "REVIEW");
        Proposal proposal = proposals(owner, deck, done).getFirst();
        List<ResearchRepository.Source> many = new ArrayList<>();
        for (int n = 1; n <= 100; n++) {
            many.add(new ResearchRepository.Source(n, "https://example.org/" + "я".repeat(0) + "a".repeat(1_900) + n, "т".repeat(300), "ф".repeat(300), "2026-01-01",
                    "YANDEX", 0));
        }
        // the compact form that fits 60 KiB is far below the 64 KiB of jsonb's text form, which adds a space after every colon and comma
        ResearchRepository.Research stored = researchRepository.upsert(proposal.artifact(), done, owner, 2, many);
        assertThat(stored.results()).isNotEmpty().hasSizeLessThan(100);
        assertThat(jdbc.sql("SELECT octet_length(results::text) FROM app_learning.generation_research WHERE artifact_id=:id").param("id", proposal.artifact())
                .query(Integer.class).single()).isLessThanOrEqualTo(65_536);

        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        search.script = request -> {
            entered.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return AiResult.ok(new WebSearch.Answer(List.of(result("https://example.org/late", 0, 1)), 1, 5_742));
        };
        UUID owner2 = UUID.randomUUID();
        UUID deck2 = deck(owner2);
        UUID session = start(owner2, deck2, factSpec("поиск и отмена", "MEDIUM"));
        assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(cancel(owner2, deck2, session, UUID.randomUUID()).getStatus()).isEqualTo(200);
        release.countDown();
        await("the research and its draft to be cancelled", Duration.ofSeconds(10), () -> steps(session).equals(List.of("RESEARCH:CANCELLED", "TEXT_DRAFT:CANCELLED")));
        assertThat(researchDebits(owner2)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_research WHERE session_id=:id").param("id", session).query(Integer.class).single()).isZero();
    }
}
