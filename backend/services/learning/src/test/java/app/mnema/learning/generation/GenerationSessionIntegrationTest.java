package app.mnema.learning.generation;

import app.mnema.learning.catalog.content.NativeDocumentReader;
import app.mnema.learning.generation.GenerationTestConfiguration.Call;
import app.mnema.learning.ai.AiRoute;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Acceptance of #287 on the Stub: the happy path, repairs, cancellation, notifications and the transaction rule. */
class GenerationSessionIntegrationTest extends GenerationIntegrationTest {

    @Test
    void aPromptSessionRunsToReviewWithAValidRevisionAndTheShapesOfTheContract() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        MockHttpServletResponse created = create(owner, deck, spec("20 глаголов движения"), UUID.randomUUID());

        assertThat(created.getStatus()).isEqualTo(201);
        assertThat(created.getHeader("Cache-Control")).isEqualTo("private, no-store");
        assertThat(created.getHeader("ETag")).isEqualTo("\"1\"");
        JsonNode body = json(created);
        UUID session = UUID.fromString(body.path("sessionId").stringValue(null));
        assertThat(created.getHeader("Location")).isEqualTo("/api/decks/" + deck + "/generation-sessions/" + session);
        assertThat(body.path("kind").stringValue(null)).isEqualTo("MATERIALS");
        assertThat(body.path("rowVersion").stringValue(null)).isEqualTo("1");
        assertThat(body.path("endReason").isNull()).isTrue();
        assertThat(body.path("usage").path("reservedCredits").intValue()).isEqualTo(10);
        assertThat(body.path("spec").path("prompt").stringValue(null)).isEqualTo("20 глаголов движения");
        assertThat(body.path("artifacts")).hasSize(1);
        JsonNode queued = body.path("artifacts").get(0);
        assertThat(queued.path("state").stringValue(null)).isEqualTo("QUEUED");
        assertThat(queued.path("rowVersion").stringValue(null)).isEqualTo("0");
        assertThat(queued.path("title").stringValue(null)).isEmpty();
        assertThat(queued.path("currentRevisionId").isNull()).isTrue();

        awaitState(session, "REVIEW");

        MockHttpServletResponse read = getSession(owner, deck, session);
        JsonNode detail = json(read);
        assertThat(read.getHeader("ETag")).isEqualTo("\"" + detail.path("rowVersion").stringValue(null) + "\"");
        assertThat(detail.path("state").stringValue(null)).isEqualTo("REVIEW");
        assertThat(detail.path("approvableCount").intValue()).isEqualTo(1);
        assertThat(detail.path("usage").path("spentCredits").intValue()).isEqualTo(10);
        assertThat(detail.path("usage").path("reservedCredits").intValue()).isZero();
        JsonNode artifact = detail.path("artifacts").get(0);
        assertThat(artifact.path("state").stringValue(null)).isEqualTo("PROPOSED");
        assertThat(artifact.path("title").stringValue(null)).isNotBlank();

        UUID artifactId = UUID.fromString(artifact.path("artifactId").stringValue(null));
        MockHttpServletResponse artifactResponse = send(owner, get("/decks/" + deck + "/generation-sessions/" + session
                + "/artifacts/" + artifactId));
        JsonNode full = json(artifactResponse);
        assertThat(artifactResponse.getHeader("ETag")).isEqualTo("\"" + full.path("rowVersion").stringValue(null) + "\"");
        assertThat(full.path("revision").path("cause").stringValue(null)).isEqualTo("INITIAL");
        assertThat(full.path("revision").path("payload").path("kind").stringValue(null)).isEqualTo("NATIVE_DOCUMENT");
        // the stored revision is a valid native-v1 document
        new NativeDocumentReader().read(full.path("revision").path("payload").path("document").toString()
                .getBytes(StandardCharsets.UTF_8));
        assertThat(full.path("revision").path("validation").path("warnings").isArray()).isTrue();
        assertThat(full.path("revisions")).hasSize(1);
        assertThat(full.path("mediaSlots")).isEmpty();
        assertThat(full.toString()).doesNotContain("modelRoute").doesNotContain("promptVersion").doesNotContain("stub");
        // an exact revision reads the same payload and leaves the pointer alone
        JsonNode exact = json(send(owner, get("/decks/" + deck + "/generation-sessions/" + session + "/artifacts/" + artifactId
                + "?revisionId=" + full.path("currentRevisionId").stringValue(null))));
        assertThat(exact.path("revision").path("payload")).isEqualTo(full.path("revision").path("payload"));

        assertThat(debits(owner)).isEqualTo(10);
        assertThat(reservationState(session)).isEqualTo("SETTLED");
        assertThat(notificationKinds(owner)).containsExactly("GENERATION_READY");
        JsonNode params = JSON.readTree(jdbc.sql("SELECT params::text FROM app_learning.notification WHERE owner_id=:owner")
                .param("owner", owner).query(String.class).single());
        assertThat(params.path("sessionId").stringValue(null)).isEqualTo(session.toString());
        assertThat(params.path("artifactCount").intValue()).isEqualTo(1);
        assertThat(params.path("approvableCount").intValue()).isEqualTo(1);
        // the journal row of the provider call carries the step id and no text
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.ai_provider_call c JOIN app_learning.generation_step s "
                + "ON s.step_id=c.step_id WHERE s.session_id=:id AND c.outcome='OK'").param("id", session)
                .query(Integer.class).single()).isEqualTo(1);
        // the provider saw the deck brief and the request, as untrusted data
        Call call = calls(owner).getFirst();
        assertThat(call.route()).isEqualTo(AiRoute.TEXT_FAST);
        assertThat(call.prompt()).contains("<title>Движение</title>").contains("<request>20 глаголов движения</request>");
    }

    @Test
    void eventsAreReadByCursorWithoutDuplicatesGapsOrReordering() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = start(owner, deck, spec("[[fake:multiblock]] блоки"));
        awaitState(session, "REVIEW");

        JsonNode everything = json(events(owner, deck, session, ""));
        List<JsonNode> all = new ArrayList<>();
        everything.path("events").forEach(all::add);
        for (int i = 0; i < all.size(); i++) assertThat(all.get(i).path("seq").stringValue(null)).isEqualTo(Integer.toString(i + 1));
        assertThat(everything.path("cursor").stringValue(null)).isEqualTo(Integer.toString(all.size()));
        assertThat(everything.path("session").path("state").stringValue(null)).isEqualTo("REVIEW");
        assertThat(everything.path("activeSteps")).isEmpty();

        // page by page with a small limit and the returned cursor: the same events once each
        List<JsonNode> paged = new ArrayList<>();
        String cursor = "0";
        for (int guard = 0; guard < 50; guard++) {
            JsonNode page = json(events(owner, deck, session, "?after=" + cursor + "&limit=2"));
            assertThat(page.path("events").size()).isLessThanOrEqualTo(2);
            page.path("events").forEach(paged::add);
            if (page.path("events").isEmpty()) {
                assertThat(page.path("cursor").stringValue(null)).isEqualTo(cursor);
                break;
            }
            cursor = page.path("cursor").stringValue(null);
        }
        assertThat(paged).isEqualTo(all);
        // reading the same cursor twice returns the same events: no consumer state on the server
        assertThat(json(events(owner, deck, session, "?after=2&limit=3")).path("events"))
                .isEqualTo(json(events(owner, deck, session, "?after=2&limit=3")).path("events"));

        List<String> types = all.stream().map(event -> event.path("type").stringValue(null)).toList();
        assertThat(types.getFirst()).isEqualTo("SESSION_STATE");
        assertThat(types).contains("ARTIFACT_STATE", "BLOCKS_APPENDED", "USAGE_UPDATED");
        assertThat(types.getLast()).isEqualTo("SESSION_STATE");
        JsonNode last = all.getLast().path("payload");
        assertThat(last.path("state").stringValue(null)).isEqualTo("REVIEW");
        assertThat(last.path("artifactCounts").path("PROPOSED").intValue()).isEqualTo(1);

        // checkpoints: contiguous startIndex per generation, valid blocks, each event within 32 KiB
        List<JsonNode> blocks = all.stream().filter(event -> event.path("type").stringValue("").equals("BLOCKS_APPENDED")).toList();
        int expectedStart = 0;
        int generation = blocks.getFirst().path("payload").path("generation").intValue();
        for (JsonNode event : blocks) {
            JsonNode payload = event.path("payload");
            assertThat(payload.path("generation").intValue()).isEqualTo(generation);
            assertThat(payload.path("startIndex").intValue()).isEqualTo(expectedStart);
            expectedStart += payload.path("blocks").size();
            assertThat(event.toString().getBytes(StandardCharsets.UTF_8).length).isLessThan(32 * 1024);
            payload.path("blocks").forEach(block -> assertThat(block.path("id").stringValue(null)).isNotBlank());
        }
        assertThat(blocks.getFirst().path("payload").path("blocks").get(0).path("type").stringValue(null)).isEqualTo("heading");
        // the stored revision is the truth: the checkpoints never claim more blocks than it has
        UUID artifactId = UUID.fromString(json(getSession(owner, deck, session)).path("artifacts").get(0).path("artifactId").stringValue(null));
        JsonNode content = json(send(owner, get("/decks/" + deck + "/generation-sessions/" + session + "/artifacts/" + artifactId)))
                .path("revision").path("payload").path("document").path("root").path("content");
        assertThat(expectedStart).isLessThanOrEqualTo(content.size());
    }

    @Test
    void noDatabaseTransactionOrConnectionIsHeldWhileTheProviderIsCalled() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = start(owner, deck, spec("[[fake:multiblock]] проверка транзакций"));
        awaitState(session, "REVIEW");

        assertThat(calls(owner)).isNotEmpty();
        assertThat(calls(owner)).allSatisfy(call -> {
            assertThat(call.transactionAtCall()).isFalse();
            assertThat(call.connectionsAtCall()).isZero();
        });
        // the Stub path streams through the same worker: every delta arrives on a thread with no transaction and no connection
        UUID plain = start(owner, deck, spec("20 глаголов движения"));
        awaitState(plain, "REVIEW");
        assertThat(deltas(owner)).isNotEmpty();
        assertThat(deltas(owner)).allSatisfy(delta -> {
            assertThat(delta.transaction()).isFalse();
            assertThat(delta.connections()).isZero();
        });
    }

    @Test
    void oneArtifactPerNoteEachWrittenFromItsOwnNoteAndMergedNotesMakeOne() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID first = note(owner, deck, "ТЕКСТ-ПЕРВОЙ-ЗАМЕТКИ");
        UUID second = note(owner, deck, "ТЕКСТ-ВТОРОЙ-ЗАМЕТКИ");
        UUID session = start(owner, deck, spec(null, noteSource(first, 0), noteSource(second, 0)));
        awaitState(session, "REVIEW");

        assertThat(artifactStates(session)).containsExactly("PROPOSED", "PROPOSED");
        assertThat(debits(owner)).isEqualTo(20);
        assertThat(calls(owner)).hasSize(2);
        assertThat(calls(owner)).anySatisfy(call -> assertThat(call.prompt()).contains("ТЕКСТ-ПЕРВОЙ-ЗАМЕТКИ").doesNotContain("ТЕКСТ-ВТОРОЙ-ЗАМЕТКИ"));
        assertThat(calls(owner)).anySatisfy(call -> assertThat(call.prompt()).contains("ТЕКСТ-ВТОРОЙ-ЗАМЕТКИ").doesNotContain("ТЕКСТ-ПЕРВОЙ-ЗАМЕТКИ"));
        assertThat(calls(owner)).allSatisfy(call -> assertThat(call.prompt()).contains("<request>по источникам выше</request>"));

        UUID merged = start(owner, deck, mergeSpec(first, second));
        awaitState(merged, "REVIEW");
        assertThat(artifactStates(merged)).containsExactly("PROPOSED");
        assertThat(calls(owner).getLast().prompt()).contains("ТЕКСТ-ПЕРВОЙ-ЗАМЕТКИ").contains("ТЕКСТ-ВТОРОЙ-ЗАМЕТКИ");
    }

    private tools.jackson.databind.node.ObjectNode mergeSpec(UUID first, UUID second) {
        tools.jackson.databind.node.ObjectNode spec = spec("объедини", noteSource(first, 0), noteSource(second, 0));
        ((tools.jackson.databind.node.ObjectNode) spec.path("settings")).put("notesMode", "MERGE_INTO_ONE");
        return spec;
    }

    @Test
    void anInvalidAnswerIsRepairedOnceAndTheRepairIsCharged() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = start(owner, deck, spec("[[fake:invalid-once]] глаголы"));
        awaitState(session, "REVIEW");

        assertThat(artifactStates(session)).containsExactly("PROPOSED");
        assertThat(calls(owner)).hasSize(2);
        assertThat(calls(owner).get(0).repair()).isFalse();
        assertThat(calls(owner).get(1).repair()).isTrue();
        assertThat(calls(owner).get(1).route()).isEqualTo(AiRoute.TEXT_FAST);
        // the repair prompt names the rule, never the offending content
        assertThat(calls(owner).get(1).prompt()).contains("<repair>").contains("MBM_UNKNOWN_DIRECTIVE");
        assertThat(debits(owner)).isEqualTo(10);
    }

    @Test
    void anAnswerThatStaysInvalidGoesToTheStrongRouteAndFailsWithoutAnyDebit() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = start(owner, deck, spec("[[fake:always-invalid-mbm]] глаголы"));
        awaitState(session, "REVIEW");

        assertThat(artifactStates(session)).containsExactly("FAILED");
        assertThat(artifactErrors(session)).containsExactly("INVALID_OUTPUT");
        // the repair and the strong route are provider calls like any other: no transaction, no connection, at the call and per delta
        assertThat(calls(owner)).hasSize(3).allSatisfy(call -> {
            assertThat(call.transactionAtCall()).isFalse();
            assertThat(call.connectionsAtCall()).isZero();
        });
        assertThat(deltas(owner)).isNotEmpty().allSatisfy(delta -> {
            assertThat(delta.transaction()).isFalse();
            assertThat(delta.connections()).isZero();
        });
        // one plain call, one repair on the same route, then the strong route with a repair
        assertThat(calls(owner).stream().map(Call::route).toList()).containsExactly(AiRoute.TEXT_FAST, AiRoute.TEXT_FAST, AiRoute.TEXT_STRONG);
        assertThat(calls(owner).stream().map(Call::repair).toList()).containsExactly(false, true, true);
        assertThat(debits(owner)).isZero();
        assertThat(reservationState(session)).isEqualTo("RELEASED");
        assertThat(notificationKinds(owner)).containsExactly("GENERATION_FAILED");
        JsonNode params = JSON.readTree(jdbc.sql("SELECT params::text FROM app_learning.notification WHERE owner_id=:owner")
                .param("owner", owner).query(String.class).single());
        assertThat(params.path("errorCode").stringValue(null)).isEqualTo("INVALID_OUTPUT");
        // the failed artifact is retryable, so the session stays in review
        assertThat(sessionState(session)).isEqualTo("REVIEW");
        JsonNode last = json(events(owner, deck, session, "")).path("events");
        assertThat(last.toString()).contains("\"errorCode\":\"INVALID_OUTPUT\"");
    }

    @Test
    void aRefusalIsNotRetriedAndWhenNothingIsLeftToReviewTheSessionClosesWithAFailureNotice() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = start(owner, deck, spec("[[stub:refusal]] запрещённое"));
        awaitState(session, "CLOSED");

        assertThat(artifactErrors(session)).containsExactly("REFUSAL");
        assertThat(calls(owner)).hasSize(1);
        assertThat(debits(owner)).isZero();
        assertThat(reservationState(session)).isEqualTo("RELEASED");
        assertThat(notificationKinds(owner)).containsExactly("GENERATION_FAILED");
    }

    @Test
    void aSessionWithOneFailureAndOneProposalIsPartial() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID good = note(owner, deck, "хорошая заметка");
        UUID bad = note(owner, deck, "[[stub:refusal]] плохая заметка");
        UUID session = start(owner, deck, spec(null, noteSource(good, 0), noteSource(bad, 0)));
        awaitState(session, "REVIEW");

        assertThat(artifactStates(session)).containsExactly("PROPOSED", "FAILED");
        assertThat(notificationKinds(owner)).containsExactly("GENERATION_PARTIAL");
        JsonNode params = JSON.readTree(jdbc.sql("SELECT params::text FROM app_learning.notification WHERE owner_id=:owner")
                .param("owner", owner).query(String.class).single());
        assertThat(params.path("approvableCount").intValue()).isEqualTo(1);
        assertThat(params.path("failedCount").intValue()).isEqualTo(1);
        assertThat(debits(owner)).isEqualTo(10);
        assertThat(reservationState(session)).isEqualTo("SETTLED");
    }

    @Test
    void transientProviderFailuresAreRetriedWithBackoffThenFailTheArtifactWithoutADebit() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = start(owner, deck, spec("[[fake:transient]] недоступен"));
        awaitState(session, "REVIEW");

        assertThat(artifactErrors(session)).containsExactly("PROVIDER_UNAVAILABLE");
        // three claims of the step (learning.generation.step.max-attempts), each with the router's own retries inside
        assertThat(jdbc.sql("SELECT attempts FROM app_learning.generation_step WHERE session_id=:id").param("id", session)
                .query(Integer.class).single()).isEqualTo(3);
        assertThat(debits(owner)).isZero();
        assertThat(reservationState(session)).isEqualTo("RELEASED");
    }

    @Test
    void aWorkerThatDiesMidStepLeavesTheStepToBeRecoveredAndRunAgain() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = start(owner, deck, spec("[[fake:crash-once]] упадёт один раз"));
        awaitState(session, "REVIEW");

        assertThat(artifactStates(session)).containsExactly("PROPOSED");
        // two claims: the crashed one and the recovered one; one revision, one debit
        assertThat(jdbc.sql("SELECT attempts FROM app_learning.generation_step WHERE session_id=:id AND kind='TEXT_DRAFT'")
                .param("id", session).query(Integer.class).single()).isEqualTo(2);
        assertThat(calls(owner)).hasSize(2);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_artifact_revision WHERE session_id=:id").param("id", session)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(debits(owner)).isEqualTo(10);
    }

    @Test
    void cancellingARunningSessionAbortsTheProviderCallAndReleasesTheReservation() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = start(owner, deck, spec("[[fake:block]] долго"));
        assertThat(provider.blockedEntered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(sessionState(session)).isEqualTo("RUNNING");

        UUID command = UUID.randomUUID();
        MockHttpServletResponse response = cancel(owner, deck, session, command);
        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode cancelled = json(response);
        assertThat(cancelled.path("state").stringValue(null)).isEqualTo("CANCELLED");
        assertThat(cancelled.path("endReason").stringValue(null)).isEqualTo("USER_CANCELLED");
        assertThat(cancelled.path("artifacts").get(0).path("state").stringValue(null)).isEqualTo("FAILED");
        assertThat(cancelled.path("artifacts").get(0).path("errorCode").stringValue(null)).isEqualTo("CANCELLED");
        assertThat(cancelled.path("usage").path("reservedCredits").intValue()).isZero();
        assertThat(response.getHeader("ETag")).isEqualTo("\"" + cancelled.path("rowVersion").stringValue(null) + "\"");

        // the heartbeat sees cancel_requested and interrupts the blocked call: the step ends CANCELLED, nothing is debited
        await("the running step to be cancelled", Duration.ofSeconds(10), () -> jdbc.sql(
                "SELECT state FROM app_learning.generation_step WHERE session_id=:id").param("id", session)
                .query(String.class).single().equals("CANCELLED"));
        assertThat(debits(owner)).isZero();
        assertThat(reservationState(session)).isEqualTo("RELEASED");
        assertThat(sessionState(session)).isEqualTo("CANCELLED");
        assertThat(notificationKinds(owner)).isEmpty();

        // a repeat of the same command replays the stored answer; a new command on a cancelled session is state-idempotent
        MockHttpServletResponse replay = cancel(owner, deck, session, command);
        assertThat(replay.getStatus()).isEqualTo(200);
        assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(replay.getHeader("ETag")).isNull();
        assertThat(json(replay)).isEqualTo(cancelled);
        MockHttpServletResponse again = cancel(owner, deck, session, UUID.randomUUID());
        assertThat(again.getStatus()).isEqualTo(200);
        assertThat(json(again).path("state").stringValue(null)).isEqualTo("CANCELLED");
    }

    @Test
    void cancellingKeepsWhatWasConsumedAndLeavesFinishedProposalsApprovable() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID done = note(owner, deck, "готовая заметка");
        UUID slow = note(owner, deck, "[[fake:block]] долгая заметка");
        UUID session = start(owner, deck, spec(null, noteSource(done, 0), noteSource(slow, 0)));
        assertThat(provider.blockedEntered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        await("the first proposal", Duration.ofSeconds(10), () -> artifactStates(session).getFirst().equals("PROPOSED"));

        MockHttpServletResponse response = cancel(owner, deck, session, UUID.randomUUID());
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(artifactStates(session)).containsExactly("PROPOSED", "FAILED");
        assertThat(artifactErrors(session)).containsExactly("-", "CANCELLED");
        assertThat(json(response).path("usage").path("spentCredits").intValue()).isEqualTo(10);
        assertThat(json(response).path("approvableCount").intValue()).isEqualTo(1);
        await("the running step to stop", Duration.ofSeconds(10), () -> jdbc.sql(
                "SELECT count(*) FROM app_learning.generation_step WHERE session_id=:id AND state='RUNNING'").param("id", session)
                .query(Integer.class).single() == 0);
        // consumed credits stay, the unspent remainder returned
        assertThat(debits(owner)).isEqualTo(10);
        assertThat(reservationState(session)).isEqualTo("SETTLED");
        assertThat(jdbc.sql("SELECT reserved FROM app_learning.usage_balance WHERE owner_id=:owner").param("owner", owner)
                .query(Integer.class).list()).allMatch(reserved -> reserved == 0);
    }

    @Test
    void aSessionWithMediaCreatesItsSlotsAndTheirStepsAndASlotWaitsForItsClipInTheWorkshop() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        tools.jackson.databind.node.ObjectNode spec = spec("[[fake:audio-hold]] с аудио");
        ((tools.jackson.databind.node.ObjectNode) spec.path("settings")).putObject("media")
                .putObject("audio").put("enabled", true).put("lang", "ja");
        UUID session = start(owner, deck, spec);
        awaitState(session, "REVIEW");
        assertThat(speech.entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(artifactStates(session)).containsExactly("PROPOSED");
        JsonNode detail = json(getSession(owner, deck, session));
        assertThat(detail.path("approvableCount").intValue()).isZero();
        JsonNode artifact = detail.path("artifacts").get(0);
        assertThat(artifact.path("mediaSlotCounts").path("total").intValue()).isEqualTo(1);
        assertThat(artifact.path("mediaSlotCounts").path("ready").intValue()).isZero();
        JsonNode full = json(send(owner, get("/decks/" + deck + "/generation-sessions/" + session + "/artifacts/"
                + artifact.path("artifactId").stringValue(null))));
        JsonNode slot = full.path("mediaSlots").get(0);
        assertThat(slot.path("slotKey").stringValue(null)).isEqualTo("a1");
        assertThat(slot.path("kind").stringValue(null)).isEqualTo("AUDIO");
        // the speech executor claimed the step: the slot is being made, the clip is held in the provider call
        assertThat(slot.path("state").stringValue(null)).isEqualTo("GENERATING");
        assertThat(slot.path("voice").stringValue(null)).isEqualTo("female");
        assertThat(slot.path("lang").stringValue(null)).isEqualTo("ja");
        // the node of the document already references the pre-allocated asset
        assertThat(full.path("revision").path("payload").toString()).contains(slot.path("assetId").stringValue(null));
        assertThat(jdbc.sql("SELECT state FROM app_learning.generation_step WHERE session_id=:id AND kind='TTS'").param("id", session)
                .query(String.class).list()).containsExactly("RUNNING");
        assertThat(json(events(owner, deck, session, "")).toString()).contains("\"type\":\"MEDIA_SLOT_STATE\"");
        // not approvable yet: no ready notification is published for a proposal whose media is still pending
        assertThat(notificationKinds(owner)).isEmpty();
        Set<String> kinds = new HashSet<>();
        json(events(owner, deck, session, "")).path("activeSteps").forEach(step -> kinds.add(step.path("kind").stringValue(null)));
        assertThat(kinds).containsExactly("TTS");
        speech.release.countDown();
    }

    @Test
    void failuresAreAnnouncedOnlyWhenNothingProposedIsStillWaitingForMedia() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID withMedia = note(owner, deck, "[[fake:audio-hold]] с аудио");
        UUID refused = note(owner, deck, "[[stub:refusal]] отказ");
        tools.jackson.databind.node.ObjectNode spec = spec(null, noteSource(withMedia, 0), noteSource(refused, 0));
        ((tools.jackson.databind.node.ObjectNode) spec.path("settings")).putObject("media").putObject("audio").put("enabled", true).put("lang", "ja");
        UUID session = start(owner, deck, spec);
        awaitState(session, "REVIEW");

        assertThat(artifactStates(session)).containsExactly("PROPOSED", "FAILED");
        // one proposal waits for its audio (not approvable), one failed: not a "failed" session, so no GENERATION_FAILED yet
        assertThat(notificationKinds(owner)).isEmpty();
        speech.release.countDown();

        // when its audio is done the approvable proposal and the failed artifact are announced as PARTIAL, once
        await("the partial notification", java.time.Duration.ofSeconds(20), () -> !notificationKinds(owner).isEmpty());
        assertThat(notificationKinds(owner)).containsExactly("GENERATION_PARTIAL");
        JsonNode params = notificationParams(owner, "GENERATION_PARTIAL");
        assertThat(params.path("approvableCount").intValue()).isEqualTo(1);
        assertThat(params.path("failedCount").intValue()).isEqualTo(1);
    }

    @Test
    void aFailedArtifactAndAProposalWhoseAudioFailedAreAnnouncedAsFailedBecauseNothingIsApprovable() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID withMedia = note(owner, deck, "[[fake:audio-down]] с аудио");
        UUID refused = note(owner, deck, "[[stub:refusal]] отказ");
        UUID session = start(owner, deck, withAudio(spec(null, noteSource(withMedia, 0), noteSource(refused, 0))));
        awaitState(session, "REVIEW");
        await("the failed slot", java.time.Duration.ofSeconds(20), () -> jdbc.sql("SELECT count(*)::integer FROM app_learning.generation_media_slot "
                + "WHERE session_id=:id AND state='FAILED'").param("id", session).query(Integer.class).single() == 1);
        await("the notification", java.time.Duration.ofSeconds(20), () -> !notificationKinds(owner).isEmpty());

        // the contract: GENERATION_FAILED when no artifact is approvable and one failed, whatever the order the text and the audio ended in
        assertThat(notificationKinds(owner)).containsExactly("GENERATION_FAILED");
        assertThat(notificationParams(owner, "GENERATION_FAILED").path("errorCode").stringValue(null)).isEqualTo("REFUSAL");
    }

    private tools.jackson.databind.node.ObjectNode withAudio(tools.jackson.databind.node.ObjectNode spec) {
        ((tools.jackson.databind.node.ObjectNode) spec.path("settings")).putObject("media").putObject("audio").put("enabled", true).put("lang", "ja");
        return spec;
    }

    private JsonNode notificationParams(UUID owner, String kind) throws Exception {
        return JSON.readTree(jdbc.sql("SELECT params::text FROM app_learning.notification WHERE owner_id=:owner AND kind=:kind").param("owner", owner)
                .param("kind", kind).query(String.class).single());
    }

    @Test
    void readyIsAnnouncedOnceWhenTheLastMediaSlotOfAReviewSessionEnds() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = start(owner, deck, withAudio(spec("[[fake:audio-hold]] с аудио")));
        awaitState(session, "REVIEW");
        assertThat(speech.entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(notificationKinds(owner)).as("the clip is still being made").isEmpty();

        speech.release.countDown();

        await("the ready notification", java.time.Duration.ofSeconds(20), () -> !notificationKinds(owner).isEmpty());
        assertThat(notificationKinds(owner)).containsExactly("GENERATION_READY");
        JsonNode params = notificationParams(owner, "GENERATION_READY");
        assertThat(params.path("sessionId").stringValue(null)).isEqualTo(session.toString());
        assertThat(params.path("artifactCount").intValue()).isEqualTo(1);
        assertThat(params.path("approvableCount").intValue()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT dedupe_key FROM app_learning.notification WHERE owner_id=:owner").param("owner", owner).query(String.class).single())
                .isEqualTo("generation:" + session + ":ready");
    }

    @Test
    void twoSlotsOfOneSessionEndingTogetherAnnounceOneReady() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID first = note(owner, deck, "[[fake:audio-hold]] первая с аудио");
        UUID second = note(owner, deck, "[[fake:audio-hold]] вторая с аудио");
        UUID session = start(owner, deck, withAudio(spec(null, noteSource(first, 0), noteSource(second, 0))));
        awaitState(session, "REVIEW");
        assertThat(speech.entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(notificationKinds(owner)).isEmpty();

        speech.release.countDown();

        await("both slots READY", java.time.Duration.ofSeconds(20), () -> jdbc.sql("SELECT count(*)::integer FROM app_learning.generation_media_slot "
                + "WHERE session_id=:id AND state='READY'").param("id", session).query(Integer.class).single() == 2);
        await("the ready notification", java.time.Duration.ofSeconds(20), () -> !notificationKinds(owner).isEmpty());
        // give a duplicate every chance to appear: the session lock and the deduplication key allow exactly one
        Thread.sleep(500);
        assertThat(notificationKinds(owner)).containsExactly("GENERATION_READY");
        assertThat(notificationParams(owner, "GENERATION_READY").path("artifactCount").intValue()).isEqualTo(2);
    }

    @Test
    void aFailedMediaSlotStillEndsTheWaitWithOneReadyForTheOwnerToReview() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = start(owner, deck, withAudio(spec("[[fake:audio-down]] с аудио")));
        awaitState(session, "REVIEW");
        await("the failed slot", java.time.Duration.ofSeconds(20), () -> jdbc.sql("SELECT count(*)::integer FROM app_learning.generation_media_slot "
                + "WHERE session_id=:id AND state='FAILED'").param("id", session).query(Integer.class).single() == 1);
        await("the ready notification", java.time.Duration.ofSeconds(20), () -> !notificationKinds(owner).isEmpty());

        assertThat(notificationKinds(owner)).containsExactly("GENERATION_READY");
        JsonNode params = notificationParams(owner, "GENERATION_READY");
        assertThat(params.path("artifactCount").intValue()).isEqualTo(1);
        assertThat(params.path("approvableCount").intValue()).as("the failed audio must be retried or removed first").isZero();
    }

    @Test
    void cancellingSettlesTheMediaSlotsOfTheCancelledStepsAndAnnouncesThem() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        tools.jackson.databind.node.ObjectNode spec = spec("[[fake:audio-hold]] с аудио");
        ((tools.jackson.databind.node.ObjectNode) spec.path("settings")).putObject("media").putObject("audio").put("enabled", true).put("lang", "ja");
        UUID session = start(owner, deck, spec);
        awaitState(session, "REVIEW");
        assertThat(speech.entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(cancel(owner, deck, session, UUID.randomUUID()).getStatus()).isEqualTo(200);

        var slot = jdbc.sql("SELECT state,error_code FROM app_learning.generation_media_slot WHERE session_id=:id").param("id", session)
                .query((row, ignored) -> row.getString("state") + ":" + row.getString("error_code")).single();
        assertThat(slot).isEqualTo("FAILED:CANCELLED");
        // the running step learns of the cancellation from its heartbeat, which aborts the held provider call
        await("the speech step to end cancelled", java.time.Duration.ofSeconds(15), () -> jdbc.sql("SELECT state FROM app_learning.generation_step "
                + "WHERE session_id=:id AND kind='TTS'").param("id", session).query(String.class).single().equals("CANCELLED"));
        JsonNode events = json(events(owner, deck, session, "")).path("events");
        boolean announced = false;
        for (JsonNode event : events) {
            if (event.path("type").stringValue("").equals("MEDIA_SLOT_STATE") && event.path("payload").path("state").stringValue("").equals("FAILED")) {
                assertThat(event.path("payload").path("errorCode").stringValue(null)).isEqualTo("CANCELLED");
                announced = true;
            }
        }
        assertThat(announced).isTrue();
        // the proposal itself stays: it can still be approved without its audio once AI-05 lets the user remove the slot
        assertThat(artifactStates(session)).containsExactly("PROPOSED");
    }
}
