package app.mnema.learning.generation;

import app.mnema.learning.ai.AiRoute;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Acceptance of #293 (AI-11) on the Stub, PostgreSQL and the real catalog: selection edits of a proposed material (one block rewritten
 * in place, the others untouched), the turn and its own reservation, one edit at a time, failure, cancellation, revert, and the
 * deterministic removal of media.
 */
class GenerationEditsIntegrationTest extends GenerationEditsSupport {
    private static final String MULTI = "[[fake:multiblock]] блоки";

    // ------------------------------------------------------------------- the edit

    @Test
    void aSelectionInsideOneParagraphRewritesOnlyThatBlockAndEveryOtherNodeStaysByteForByte() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, spec(MULTI));
        List<JsonNode> before = blocks(detail(owner, deck, proposal));
        assertThat(before).hasSize(5);
        UUID target = id(before.get(1));
        int debitsBefore = debits(owner);
        UUID command = UUID.randomUUID();

        MockHttpServletResponse response = edit(owner, deck, proposal, editBody(command, proposal.revision(), "REWRITE", "SIMPLER", null, target));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(202);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
        assertThat(response.getHeader("Location")).isEqualTo("/api/decks/" + deck + "/generation-sessions/" + proposal.session()
                + "/artifacts/" + proposal.artifact());
        JsonNode accepted = json(response);
        JsonNode turn = accepted.path("turn");
        assertThat(turn.path("status").stringValue(null)).isEqualTo("QUEUED");
        assertThat(turn.path("action").stringValue(null)).isEqualTo("REWRITE");
        assertThat(turn.path("preset").stringValue(null)).isEqualTo("SIMPLER");
        assertThat(turn.path("instruction").isNull()).isTrue();
        assertThat(turn.path("targetNodeIds")).hasSize(1);
        assertThat(turn.path("targetNodeIds").get(0).stringValue(null)).isEqualTo(target.toString());
        assertThat(turn.path("resultRevisionId").isNull()).isTrue();
        assertThat(turn.path("errorCode").isNull()).isTrue();
        assertThat(turn.path("createdAt").stringValue(null)).isNotBlank();
        JsonNode summary = accepted.path("artifact");
        assertThat(summary.path("state").stringValue(null)).isEqualTo("REVISING");
        assertThat(summary.path("currentRevisionId").stringValue(null)).isEqualTo(proposal.revision().toString());
        assertThat(Long.parseLong(summary.path("rowVersion").stringValue(null))).isGreaterThan(proposal.version());

        UUID turnId = UUID.fromString(turn.path("turnId").stringValue(null));
        awaitTurn(turnId, "APPLIED");
        awaitArtifact(proposal.artifact(), "PROPOSED");

        JsonNode after = detail(owner, deck, proposal);
        List<JsonNode> blocks = blocks(after);
        assertThat(blocks).hasSize(5);
        // the target kept its node id and changed its text; every other block is the very same JSON
        assertThat(id(blocks.get(1))).isEqualTo(target);
        assertThat(text(blocks.get(1))).isEqualTo("Абзац один. Переписано: Проще.");
        for (int index : new int[] {0, 2, 3, 4}) assertThat(blocks.get(index)).isEqualTo(before.get(index));
        assertThat(after.path("revision").path("cause").stringValue(null)).isEqualTo("EDIT");
        assertThat(after.path("revision").path("revisionId").stringValue(null)).isNotEqualTo(proposal.revision().toString());
        assertThat(after.path("currentRevisionId").stringValue(null)).isEqualTo(after.path("revision").path("revisionId").stringValue(null));
        // the turn is listed, finished, with its result; the revision list keeps the history
        JsonNode listed = after.path("turns").get(0);
        assertThat(listed.path("turnId").stringValue(null)).isEqualTo(turnId.toString());
        assertThat(listed.path("status").stringValue(null)).isEqualTo("APPLIED");
        assertThat(listed.path("resultRevisionId").stringValue(null)).isEqualTo(after.path("currentRevisionId").stringValue(null));
        assertThat(after.path("revisions")).hasSize(2);
        // the earlier revision is still readable and still says what it said
        JsonNode earlier = detail(owner, deck, proposal, "?revisionId=" + proposal.revision());
        assertThat(blocks(earlier).get(1)).isEqualTo(before.get(1));
        assertThat(earlier.path("currentRevisionId").stringValue(null)).isEqualTo(after.path("currentRevisionId").stringValue(null));
        // the turn paid for itself from its own hold, which ended settled
        assertThat(debits(owner) - debitsBefore).isEqualTo(4);
        assertThat(reservationOfTurn(turnId)).isEqualTo("SETTLED");
        // no provider call ran inside a transaction or with a connection held
        assertThat(editCalls()).hasSize(1).allSatisfy(call -> {
            assertThat(call.transactionAtCall()).isFalse();
            assertThat(call.connectionsAtCall()).isZero();
        });
        // the log tells the client: REVISING, then PROPOSED on the new revision, then the usage of the turn
        List<String> log = new ArrayList<>();
        json(events(owner, deck, proposal.session(), "?after=0&limit=100")).path("events").forEach(event -> log.add(
                event.path("type").stringValue(null) + ":" + event.path("payload").path("state").stringValue("-")));
        assertThat(log).containsSubsequence("ARTIFACT_STATE:PROPOSED", "ARTIFACT_STATE:REVISING", "USAGE_UPDATED:-", "ARTIFACT_STATE:PROPOSED");
    }

    @Test
    void aRunOfBlocksIsRewrittenTogetherAndThePromptIsTheSameCacheablePrefixAsTheMaterialsOwnWithTheEditAfterIt() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, spec(MULTI));
        List<JsonNode> before = blocks(detail(owner, deck, proposal));

        UUID first = accepted(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "FREE", null, "Добавь пример",
                id(before.get(1)), id(before.get(2))));
        awaitTurn(first, "APPLIED");
        Proposal now = fresh(owner, deck, proposal);
        UUID second = accepted(owner, deck, now, editBody(UUID.randomUUID(), now.revision(), "REWRITE", "SHORTER", null, id(before.get(4))));
        awaitTurn(second, "APPLIED");

        List<JsonNode> after = blocks(detail(owner, deck, proposal));
        assertThat(after).hasSize(5);
        assertThat(text(after.get(1))).isEqualTo("Абзац один. Переписано: нет.");
        assertThat(text(after.get(2))).isEqualTo("Абзац два. Переписано: нет.");
        assertThat(text(after.get(4))).isEqualTo("Конец. Переписано: Короче.");
        assertThat(id(after.get(1))).isEqualTo(id(before.get(1)));
        assertThat(id(after.get(2))).isEqualTo(id(before.get(2)));
        assertThat(id(after.get(4))).isEqualTo(id(before.get(4)));
        assertThat(after.get(0)).isEqualTo(before.get(0));
        assertThat(after.get(3)).isEqualTo(before.get(3));

        // context order (architecture section 7): the core and the deck brief first and shared with the material's own call, then the
        // document (outline, context before, target with its handles, context after), the history and the task with the instruction last
        String draft = provider.calls.stream().filter(call -> !call.prompt().contains("<task kind=\"edit\">")).findFirst().orElseThrow().prompt();
        String firstPrompt = editCalls().get(0).prompt();
        String secondPrompt = editCalls().get(1).prompt();
        String prefix = draft.substring(0, draft.indexOf("<allowed_links>"));
        assertThat(prefix).contains("<deck>").contains("<outline");
        assertThat(firstPrompt).startsWith(prefix);
        assertThat(secondPrompt).startsWith(prefix);
        assertThat(firstPrompt).contains("<material id=\"doc\">\n[[b1]] Заголовок\n[[b2]] Абзац один.\n[[b3]] Абзац два.\n[[b4]] пункт\n[[b5]] Конец.\n</material>")
                .contains("<context_before># Заголовок</context_before>\n<target>[[b2]] Абзац один.\n\n[[b3]] Абзац два.</target>\n<context_after>")
                .contains("<instruction>Добавь пример</instruction>");
        assertThat(firstPrompt.indexOf("<document>")).isLessThan(firstPrompt.indexOf("<history>"));
        assertThat(firstPrompt.indexOf("<history>")).isLessThan(firstPrompt.indexOf("<task kind=\"edit\">"));
        assertThat(firstPrompt).contains("<history>\nнет предыдущих правок\n</history>");
        // the second turn sees the first instruction as history and its own preset
        assertThat(secondPrompt).contains("<history>\nДобавь пример\n</history>").contains("Пресет: Короче.")
                .contains("<target>[[b5]] Конец.</target>").contains("<context_before>- пункт\n- пункт</context_before>");
        // only the target is rewritten: the rest of the material is in the outline and the neighbours only
        assertThat(editCalls()).allSatisfy(call -> assertThat(call.route()).isEqualTo(AiRoute.TEXT_FAST));
    }

    @Test
    void theTargetMustBeConsecutiveTopLevelBlocksOfTheCurrentRevisionAndTheBodyStrictlyValid() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, spec(MULTI));
        List<JsonNode> blocks = blocks(detail(owner, deck, proposal));
        UUID p1 = id(blocks.get(1));
        UUID p2 = id(blocks.get(2));
        UUID inner = id(blocks.get(1).path("content").get(0));

        // not top-level (a text node), unknown, repeated, not consecutive, empty, too many: all 400, nothing changes
        problem(edit(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "REWRITE", "SIMPLER", null, inner)), 400, "INVALID_REQUEST");
        problem(edit(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "REWRITE", "SIMPLER", null, UUID.randomUUID())), 400, "INVALID_REQUEST");
        problem(edit(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "REWRITE", "SIMPLER", null, p1, p1)), 400, "INVALID_REQUEST");
        problem(edit(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "REWRITE", "SIMPLER", null, p1, id(blocks.get(3)))), 400, "INVALID_REQUEST");
        problem(edit(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "REWRITE", "SIMPLER", null)), 400, "INVALID_REQUEST");
        UUID[] many = new UUID[51];
        for (int index = 0; index < many.length; index++) many[index] = UUID.randomUUID();
        problem(edit(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "REWRITE", "SIMPLER", null, many)), 400, "INVALID_REQUEST");

        // the rest of the body: a preset only with REWRITE, an instruction for FREE, its bounds, closed enums, exact fields
        problem(edit(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "FREE", "SIMPLER", "x", p1)), 400, "INVALID_REQUEST");
        problem(edit(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "FREE", null, null, p1)), 400, "INVALID_REQUEST");
        problem(edit(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "FREE", null, "  ", p1)), 400, "INVALID_REQUEST");
        problem(edit(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "FREE", null, "я".repeat(2001), p1)), 400, "INVALID_REQUEST");
        problem(edit(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "REWRITE", "LOUDER", null, p1)), 400, "INVALID_REQUEST");
        problem(edit(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "SHRINK", null, null, p1)), 400, "INVALID_REQUEST");
        problem(edit(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "REWRITE", null, null, p1).put("extra", 1)), 400, "INVALID_REQUEST");
        problem(edit(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "REWRITE", null, null, p1).put("expectedRevisionId", "x")), 400, "INVALID_REQUEST");
        problem(edit(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "REWRITE", null, null, p1).put("commandId", "x")), 400, "INVALID_REQUEST");
        tools.jackson.databind.node.ObjectNode noTarget = editBody(UUID.randomUUID(), proposal.revision(), "REWRITE", null, null, p1);
        noTarget.remove("target");
        problem(edit(owner, deck, proposal, noTarget), 400, "INVALID_REQUEST");
        tools.jackson.databind.node.ObjectNode extraTarget = editBody(UUID.randomUUID(), proposal.revision(), "REWRITE", null, null, p1);
        ((tools.jackson.databind.node.ObjectNode) extraTarget.path("target")).put("range", "all");
        problem(edit(owner, deck, proposal, extraTarget), 400, "INVALID_REQUEST");

        // a stale revision is the 412 of every command, and only after the body is valid
        problem(edit(owner, deck, proposal, editBody(UUID.randomUUID(), UUID.randomUUID(), "REWRITE", null, null, p1)), 412, "VERSION_CONFLICT");
        // nothing above changed anything: the proposal, its revision and the usage are what they were
        assertThat(fresh(owner, deck, proposal)).isEqualTo(proposal);
        assertThat(revisions(proposal.artifact())).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*)::integer FROM app_learning.generation_artifact_turn WHERE artifact_id=:id")
                .param("id", proposal.artifact()).query(Integer.class).single()).isZero();
        assertThat(provider.calls.stream().filter(call -> call.prompt().contains("<task kind=\"edit\">"))).isEmpty();

        // the same two consecutive blocks are fine
        UUID turn = accepted(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "REWRITE", "EXAMPLE", null, p2, p1));
        awaitTurn(turn, "APPLIED");
    }

    // ---------------------------------------------------------- one edit at a time

    @Test
    void aSecondEditWhileTheFirstRunsIsEditInProgressWithTheRunningTurnAndNothingElseMovesUntilItEnds() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, spec(MULTI));
        List<JsonNode> blocks = blocks(detail(owner, deck, proposal));
        UUID target = id(blocks.get(1));
        UUID first = accepted(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "FREE", null, "[[fake:block]] медленно", target));
        assertThat(provider.blockedEntered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(turnStatus(first)).isEqualTo("RUNNING");
        Proposal running = fresh(owner, deck, proposal);
        assertThat(artifactState(proposal.artifact())).isEqualTo("REVISING");

        // the same revision, another command: refused with the turn that holds the artifact, and nothing was reserved or written
        MockHttpServletResponse second = edit(owner, deck, running, editBody(UUID.randomUUID(), proposal.revision(), "REWRITE", "SHORTER", null, id(blocks.get(2))));
        problem(second, 409, "EDIT_IN_PROGRESS");
        assertThat(json(second).path("turnId").stringValue(null)).isEqualTo(first.toString());
        assertThat(jdbc.sql("SELECT count(*)::integer FROM app_learning.generation_artifact_turn WHERE artifact_id=:id")
                .param("id", proposal.artifact()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*)::integer FROM app_learning.usage_reservation WHERE owner_id=:owner AND scope='TURN'")
                .param("owner", owner).query(Integer.class).single()).isEqualTo(1);
        // the proposal cannot be taken out of review or rolled back under the running turn
        MockHttpServletResponse approval = approve(owner, deck, running, UUID.randomUUID());
        problem(approval, 409, "GENERATION_STATE_CONFLICT");
        assertThat(json(approval).path("reason").stringValue(null)).isEqualTo("ILLEGAL_STATE");
        problem(reject(owner, deck, running, UUID.randomUUID(), running.version()), 409, "GENERATION_STATE_CONFLICT");
        MockHttpServletResponse revert = revert(owner, deck, running, running.version(), proposal.revision());
        problem(revert, 409, "GENERATION_STATE_CONFLICT");
        assertThat(json(revert).path("reason").stringValue(null)).isEqualTo("ILLEGAL_STATE");
        // the current text stays readable while it is being rewritten
        assertThat(text(blocks(detail(owner, deck, proposal)).get(1))).isEqualTo("Абзац один.");

        provider.release.countDown();
        awaitTurn(first, "APPLIED");
        awaitArtifact(proposal.artifact(), "PROPOSED");
        Proposal done = fresh(owner, deck, proposal);
        // and now the next edit is accepted
        UUID third = accepted(owner, deck, done, editBody(UUID.randomUUID(), done.revision(), "REWRITE", "SHORTER", null, id(blocks.get(2))));
        awaitTurn(third, "APPLIED");
    }

    // ------------------------------------------------------------------------ usage

    @Test
    void everyEditReservesItsOwnHoldAndTheSameEditAgainIsASecondReservationDebitedOnItsOwn() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, spec(MULTI));
        UUID target = id(blocks(detail(owner, deck, proposal)).get(1));
        int debitsBefore = debits(owner);

        UUID first = accepted(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "REWRITE", "SIMPLER", null, target));
        awaitTurn(first, "APPLIED");
        Proposal now = fresh(owner, deck, proposal);
        // «Ещё раз»: the same target, preset and instruction with a new command id
        UUID again = accepted(owner, deck, now, editBody(UUID.randomUUID(), now.revision(), "REWRITE", "SIMPLER", null, target));
        awaitTurn(again, "APPLIED");

        assertThat(debits(owner) - debitsBefore).isEqualTo(8);
        assertThat(reservationOfTurn(first)).isEqualTo("SETTLED");
        assertThat(reservationOfTurn(again)).isEqualTo("SETTLED");
        assertThat(jdbc.sql("SELECT count(DISTINCT reservation_id)::integer FROM app_learning.usage_reservation WHERE owner_id=:owner AND scope='TURN'")
                .param("owner", owner).query(Integer.class).single()).isEqualTo(2);
        // the session's usage is the sum of all its holds: the batch and the two turns
        JsonNode usage = json(getSession(owner, deck, proposal.session())).path("usage");
        assertThat(usage.path("spentCredits").intValue()).isEqualTo(debits(owner));
        assertThat(usage.path("reservedCredits").intValue()).isZero();
        // each text is the earlier one with one more sentence: the second rewrite started from the first result
        assertThat(text(blocks(detail(owner, deck, proposal)).get(1))).isEqualTo("Абзац один. Переписано: Проще. Переписано: Проще.");
    }

    @Test
    void anEditThatDoesNotFitTheBalanceIsRefusedBeforeAnythingChangesAndTheButtonStaysUsableAfterwards() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, spec(MULTI));
        UUID target = id(blocks(detail(owner, deck, proposal)).get(1));
        int remaining = ledger.remainingCredits(owner);
        UUID hold = new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> ledger.reserve(owner,
                app.mnema.learning.usage.ReservationScope.SESSION, UUID.randomUUID(), null, remaining - 2).reservationId());

        MockHttpServletResponse refused = edit(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "REWRITE", "SIMPLER", null, target));

        problem(refused, 409, "USAGE_LIMIT_REACHED");
        assertThat(json(refused).path("bucket").stringValue(null)).isEqualTo("CREDITS");
        assertThat(json(refused).path("required").intValue()).isEqualTo(4);
        assertThat(fresh(owner, deck, proposal)).isEqualTo(proposal);
        assertThat(artifactState(proposal.artifact())).isEqualTo("PROPOSED");
        assertThat(jdbc.sql("SELECT count(*)::integer FROM app_learning.generation_artifact_turn WHERE artifact_id=:id")
                .param("id", proposal.artifact()).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*)::integer FROM app_learning.generation_step WHERE artifact_id=:id AND kind='EDIT'")
                .param("id", proposal.artifact()).query(Integer.class).single()).isZero();
        assertThat(editCalls()).isEmpty();

        // free the hold: the same request is accepted
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> ledger.release(owner, hold));
        UUID turn = accepted(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "REWRITE", "SIMPLER", null, target));
        awaitTurn(turn, "APPLIED");
    }

    // ---------------------------------------------------------------------- failure

    @Test
    void anAnswerTheCompilerKeepsRejectingIsRepairedOnceThenSentToTheStrongRouteThenTheTurnFailsAndTheProposalIsExactlyWhatItWas() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, spec(MULTI));
        UUID target = id(blocks(detail(owner, deck, proposal)).get(1));
        int debitsBefore = debits(owner);

        UUID turn = accepted(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "FREE", null, "[[fake:always-invalid-mbm]] правь", target));
        awaitTurn(turn, "FAILED");
        awaitArtifact(proposal.artifact(), "PROPOSED");

        assertThat(turnError(turn)).isEqualTo("INVALID_OUTPUT");
        assertThat(editCalls()).extracting(call -> call.route()).containsExactly(AiRoute.TEXT_FAST, AiRoute.TEXT_FAST, AiRoute.TEXT_STRONG);
        assertThat(editCalls()).extracting(call -> call.repair()).containsExactly(false, true, true);
        // nothing was written or debited; the hold ended unspent; the revision is the one the client holds
        assertThat(debits(owner)).isEqualTo(debitsBefore);
        assertThat(reservationOfTurn(turn)).isEqualTo("RELEASED");
        assertThat(revisions(proposal.artifact())).isEqualTo(1);
        Proposal after = fresh(owner, deck, proposal);
        assertThat(after.revision()).isEqualTo(proposal.revision());
        JsonNode detail = detail(owner, deck, proposal);
        assertThat(text(blocks(detail).get(1))).isEqualTo("Абзац один.");
        assertThat(detail.path("turns").get(0).path("status").stringValue(null)).isEqualTo("FAILED");
        assertThat(detail.path("turns").get(0).path("errorCode").stringValue(null)).isEqualTo("INVALID_OUTPUT");
        assertThat(detail.path("turns").get(0).path("resultRevisionId").isNull()).isTrue();
        assertThat(sessionState(proposal.session())).isEqualTo("REVIEW");
        // the failure is announced as the artifact being PROPOSED again, and the proposal is approvable as before
        assertThat(approve(owner, deck, after, UUID.randomUUID()).getStatus()).isEqualTo(200);
    }

    @Test
    void aSingleRejectedAnswerIsRepairedAndDebitedOnceAndAFailingProviderFailsTheTurnWithItsCode() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, spec(MULTI));
        UUID target = id(blocks(detail(owner, deck, proposal)).get(1));
        int debitsBefore = debits(owner);

        UUID repaired = accepted(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "FREE", null, "[[fake:invalid-once]] правь", target));
        awaitTurn(repaired, "APPLIED");
        assertThat(editCalls()).extracting(call -> call.repair()).containsExactly(false, true);
        assertThat(debits(owner) - debitsBefore).isEqualTo(4);

        // the Stub's own invalid-answer marker: the first answer is a document the compiler rejects, the repair is the edit
        Proposal stub = fresh(owner, deck, proposal);
        int callsBefore = editCalls().size();
        UUID stubRepaired = accepted(owner, deck, stub, editBody(UUID.randomUUID(), stub.revision(), "FREE", null, "[[stub:invalid-mbm]] правь", target));
        awaitTurn(stubRepaired, "APPLIED");
        assertThat(editCalls().size() - callsBefore).isEqualTo(2);
        debitsBefore += 4;

        Proposal now = fresh(owner, deck, proposal);
        UUID refused = accepted(owner, deck, now, editBody(UUID.randomUUID(), now.revision(), "FREE", null, "[[stub:refusal]] правь", target));
        awaitTurn(refused, "FAILED");
        assertThat(turnError(refused)).isEqualTo("REFUSAL");

        now = fresh(owner, deck, proposal);
        UUID down = accepted(owner, deck, now, editBody(UUID.randomUUID(), now.revision(), "FREE", null, "[[fake:transient]] правь", target));
        awaitTurn(down, "FAILED");
        assertThat(turnError(down)).isEqualTo("PROVIDER_UNAVAILABLE");
        // a failed turn debits nothing and its hold ended unspent
        assertThat(debits(owner) - debitsBefore).isEqualTo(4);
        assertThat(reservationOfTurn(refused)).isEqualTo("RELEASED");
        assertThat(reservationOfTurn(down)).isEqualTo("RELEASED");
        assertThat(artifactState(proposal.artifact())).isEqualTo("PROPOSED");
    }

    @Test
    void aWorkerThatDiesMidEditLosesItsLeaseAndTheStepIsRecoveredWithoutADoubleDebit() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, spec(MULTI));
        UUID target = id(blocks(detail(owner, deck, proposal)).get(1));
        int debitsBefore = debits(owner);

        UUID turn = accepted(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "FREE", null, "[[fake:crash-once]] правь", target));
        awaitTurn(turn, "APPLIED");

        assertThat(editCalls()).hasSize(2);
        assertThat(debits(owner) - debitsBefore).isEqualTo(4);
        assertThat(reservationOfTurn(turn)).isEqualTo("SETTLED");
        assertThat(revisions(proposal.artifact())).isEqualTo(2);
    }

    // ----------------------------------------------------------- cancel and recovery

    @Test
    void cancellingTheSessionStopsARunningTurnAndLeavesTheProposalAsItWasWithItsHoldReleased() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, spec(MULTI));
        UUID target = id(blocks(detail(owner, deck, proposal)).get(1));
        int debitsBefore = debits(owner);
        UUID turn = accepted(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "FREE", null, "[[fake:block]] медленно", target));
        assertThat(provider.blockedEntered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(cancel(owner, deck, proposal.session(), UUID.randomUUID()).getStatus()).isEqualTo(200);

        assertThat(turnStatus(turn)).isEqualTo("CANCELLED");
        assertThat(artifactState(proposal.artifact())).isEqualTo("PROPOSED");
        assertThat(sessionState(proposal.session())).isEqualTo("CANCELLED");
        assertThat(reservationOfTurn(turn)).isEqualTo("RELEASED");
        // the running call is aborted and its step ends cancelled, writing no revision and no debit
        String step = jdbc.sql("SELECT step_id::text FROM app_learning.generation_step WHERE artifact_id=:id AND kind='EDIT'")
                .param("id", proposal.artifact()).query(String.class).single();
        await("the edit step to end", Duration.ofSeconds(10), () -> jdbc.sql("SELECT state FROM app_learning.generation_step WHERE step_id=CAST(:id AS uuid)")
                .param("id", step).query(String.class).single().equals("CANCELLED"));
        assertThat(revisions(proposal.artifact())).isEqualTo(1);
        assertThat(debits(owner)).isEqualTo(debitsBefore);
        assertThat(turnStatus(turn)).isEqualTo("CANCELLED");
        // a cancelled session keeps its proposals approvable and refuses a rewrite
        Proposal now = fresh(owner, deck, proposal);
        MockHttpServletResponse refused = edit(owner, deck, now, editBody(UUID.randomUUID(), now.revision(), "REWRITE", "SIMPLER", null, target));
        problem(refused, 409, "GENERATION_STATE_CONFLICT");
        assertThat(json(refused).path("reason").stringValue(null)).isEqualTo("ILLEGAL_STATE");
        assertThat(approve(owner, deck, now, UUID.randomUUID()).getStatus()).isEqualTo(200);
    }

    @Test
    void aStepWhoseLeaseIsGoneForGoodFailsItsTurnAndAStepThatWaitedTooLongExpiresItsTurn() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, spec(MULTI));
        UUID target = id(blocks(detail(owner, deck, proposal)).get(1));

        // a claimed step that used all its attempts and whose lease ran out: the sweeper fails the turn, not the artifact
        UUID lost = forgedEdit(owner, proposal, target, "RUNNING");
        jdbc.sql("UPDATE app_learning.generation_step SET state='RUNNING',lease_token=gen_random_uuid(),lease_until=CURRENT_TIMESTAMP - interval '1 minute',"
                + "attempts=3,first_claimed_at=CURRENT_TIMESTAMP WHERE step_id=:id").param("id", stepOfTurn(lost)).update();
        awaitTurn(lost, "FAILED");
        assertThat(turnError(lost)).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(artifactState(proposal.artifact())).isEqualTo("PROPOSED");
        assertThat(reservationOfTurn(lost)).isEqualTo("RELEASED");

        // a READY step whose whole lifetime ran out before it was claimed again
        UUID late = forgedEdit(owner, proposal, target, "READY");
        jdbc.sql("UPDATE app_learning.generation_step SET attempts=1,first_claimed_at=CURRENT_TIMESTAMP - interval '2 hours' WHERE step_id=:id")
                .param("id", stepOfTurn(late)).update();
        awaitTurn(late, "FAILED");
        assertThat(turnError(late)).isEqualTo("DEADLINE_EXCEEDED");
        assertThat(artifactState(proposal.artifact())).isEqualTo("PROPOSED");
        assertThat(reservationOfTurn(late)).isEqualTo("RELEASED");

        // a step claimed after its turn ended is void: it ends cancelled and changes nothing
        UUID voided = forgedEdit(owner, proposal, target, "READY");
        jdbc.sql("UPDATE app_learning.generation_artifact SET state='PROPOSED',row_version=row_version+1 WHERE artifact_id=:id")
                .param("id", proposal.artifact()).update();
        jdbc.sql("UPDATE app_learning.generation_artifact_turn SET status='CANCELLED' WHERE turn_id=:id").param("id", voided).update();
        await("the void step to end", Duration.ofSeconds(10), () -> jdbc.sql("SELECT state FROM app_learning.generation_step WHERE step_id=:id")
                .param("id", stepOfTurn(voided)).query(String.class).single().equals("CANCELLED"));
        assertThat(editCalls()).isEmpty();
        assertThat(revisions(proposal.artifact())).isEqualTo(1);
    }

    private UUID stepOfTurn(UUID turn) {
        return jdbc.sql("SELECT step_id FROM app_learning.generation_artifact_turn WHERE turn_id=:id").param("id", turn).query(UUID.class).single();
    }

    /**
     * An edit as admission leaves it (a QUEUED turn, a step, its hold, the artifact REVISING) but with the step parked far in the future,
     * so a test can set the step to the state it wants to see the sweeper or the queue handle.
     */
    private UUID forgedEdit(UUID owner, Proposal proposal, UUID target, String stepState) {
        UUID turn = UUID.randomUUID();
        UUID step = UUID.randomUUID();
        UUID reservation = new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> ledger.reserve(owner,
                app.mnema.learning.usage.ReservationScope.TURN, proposal.session(), turn, 4).reservationId());
        jdbc.sql("INSERT INTO app_learning.generation_artifact_turn(turn_id,artifact_id,session_id,owner_id,status,action,instruction,"
                        + "target_node_ids,step_id,created_at) VALUES (:turn,:artifact,:session,:owner,'RUNNING','FREE','x',ARRAY[CAST(:target AS uuid)],:step,"
                        + "CURRENT_TIMESTAMP)").param("turn", turn).param("artifact", proposal.artifact()).param("session", proposal.session())
                .param("owner", owner).param("target", target.toString()).param("step", step).update();
        tools.jackson.databind.node.ObjectNode input = JSON.createObjectNode().put("turnId", turn.toString()).put("action", "FREE")
                .put("revisionId", proposal.revision().toString()).put("operation", "EDIT_SELECTION").put("credits", 4)
                .put("reservationId", reservation.toString());
        steps.insert(step, proposal.session(), proposal.artifact(), owner, "EDIT", "TEXT", input, "edit:" + turn);
        jdbc.sql("UPDATE app_learning.generation_step SET next_attempt_at=CURRENT_TIMESTAMP + interval '1 day' WHERE step_id=:id")
                .param("id", step).update();
        jdbc.sql("UPDATE app_learning.generation_artifact SET state='REVISING',row_version=row_version+1 WHERE artifact_id=:id")
                .param("id", proposal.artifact()).update();
        if (stepState.equals("READY")) {
            jdbc.sql("UPDATE app_learning.generation_step SET next_attempt_at=CURRENT_TIMESTAMP WHERE step_id=:id").param("id", step).update();
        }
        return turn;
    }

    // ----------------------------------------------------------------------- revert

    @Test
    void revertMovesThePointerWithoutCreatingOrDeletingAnythingAndEditingAfterwardsBranchesTheHistory() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, spec(MULTI));
        List<JsonNode> before = blocks(detail(owner, deck, proposal));
        UUID target = id(before.get(1));
        UUID turn = accepted(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "REWRITE", "SIMPLER", null, target));
        awaitTurn(turn, "APPLIED");
        awaitArtifact(proposal.artifact(), "PROPOSED");
        Proposal edited = fresh(owner, deck, proposal);
        assertThat(edited.revision()).isNotEqualTo(proposal.revision());

        // back to the first revision: 200, the new version as ETag, the artifact summary on the old revision
        MockHttpServletResponse back = revert(owner, deck, edited, edited.version(), proposal.revision());
        assertThat(back.getStatus()).as(back.getContentAsString()).isEqualTo(200);
        JsonNode summary = json(back);
        assertThat(summary.path("state").stringValue(null)).isEqualTo("PROPOSED");
        assertThat(summary.path("currentRevisionId").stringValue(null)).isEqualTo(proposal.revision().toString());
        assertThat(Long.parseLong(summary.path("rowVersion").stringValue(null))).isGreaterThan(edited.version());
        assertThat(back.getHeader("ETag")).isEqualTo("\"" + summary.path("rowVersion").stringValue(null) + "\"");
        JsonNode detail = detail(owner, deck, proposal);
        assertThat(blocks(detail).get(1)).isEqualTo(before.get(1));
        // nothing was created or deleted: both revisions, the turn and its result are still listed
        assertThat(detail.path("revisions")).hasSize(2);
        assertThat(revisions(proposal.artifact())).isEqualTo(2);
        assertThat(detail.path("turns")).hasSize(1);
        assertThat(detail.path("turns").get(0).path("status").stringValue(null)).isEqualTo("APPLIED");
        assertThat(detail.path("turns").get(0).path("resultRevisionId").stringValue(null)).isEqualTo(edited.revision().toString());
        assertThat(debits(owner)).isEqualTo(debits(owner));

        // the move is undone by another revert; a stale version, an unknown revision and the current one are answered as stated
        Proposal old = fresh(owner, deck, proposal);
        problem(revert(owner, deck, old, old.version() - 1, edited.revision()), 412, "VERSION_CONFLICT");
        problem(revert(owner, deck, old, old.version(), UUID.randomUUID()), 404, "RESOURCE_NOT_FOUND");
        MockHttpServletResponse same = revert(owner, deck, old, old.version(), proposal.revision());
        assertThat(same.getStatus()).isEqualTo(200);
        assertThat(json(same).path("rowVersion").stringValue(null)).isEqualTo(Long.toString(old.version()));
        assertThat(revert(owner, deck, old, old.version(), edited.revision()).getStatus()).isEqualTo(200);
        assertThat(text(blocks(detail(owner, deck, proposal)).get(1))).isEqualTo("Абзац один. Переписано: Проще.");

        // an exact retry of a revert is the stored answer
        Proposal again = fresh(owner, deck, proposal);
        UUID command = UUID.randomUUID();
        String body = "{\"commandId\":\"" + command + "\",\"expectedArtifactVersion\":\"" + again.version() + "\",\"toRevisionId\":\"" + proposal.revision() + "\"}";
        String path = base(deck, proposal.session()) + "/artifacts/" + proposal.artifact() + "/revert";
        MockHttpServletResponse first = send(owner, post(path).contentType("application/json").content(body));
        MockHttpServletResponse replay = send(owner, post(path).contentType("application/json").content(body));
        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(replay.getHeader("ETag")).isNull();
        assertThat(json(replay)).isEqualTo(json(first));

        // an edit from the earlier revision is a new branch: a third revision, the history of the other branch stays
        Proposal base = fresh(owner, deck, proposal);
        assertThat(base.revision()).isEqualTo(proposal.revision());
        UUID branch = accepted(owner, deck, base, editBody(UUID.randomUUID(), base.revision(), "REWRITE", "SHORTER", null, target));
        awaitTurn(branch, "APPLIED");
        assertThat(revisions(proposal.artifact())).isEqualTo(3);
        JsonNode last = detail(owner, deck, proposal);
        assertThat(text(blocks(last).get(1))).isEqualTo("Абзац один. Переписано: Короче.");
        assertThat(last.path("turns")).hasSize(2);
    }

    // ------------------------------------------------------------- state and limits

    @Test
    void anEditNeedsAProposedArtifactOfASessionThatIsStillOpenAndTheLimitsOfAnArtifactAreRefusedWithTheirNames() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, spec(MULTI));
        UUID target = id(blocks(detail(owner, deck, proposal)).get(1));

        // 50 turns and 30 revisions: the 422 names the limit
        jdbc.sql("INSERT INTO app_learning.generation_artifact_turn(turn_id,artifact_id,session_id,owner_id,status,action,preset,target_node_ids,"
                        + "created_at) SELECT gen_random_uuid(),:artifact,:session,:owner,'APPLIED','REWRITE','SIMPLER',ARRAY[CAST(:target AS uuid)],"
                        + "CURRENT_TIMESTAMP FROM generate_series(1,50)").param("artifact", proposal.artifact()).param("session", proposal.session())
                .param("owner", owner).param("target", target.toString()).update();
        MockHttpServletResponse turns = edit(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "REWRITE", "SIMPLER", null, target));
        problem(turns, 422, "RESOURCE_LIMIT_EXCEEDED");
        assertThat(json(turns).path("limit").stringValue(null)).isEqualTo("TURNS_PER_ARTIFACT");
        jdbc.sql("DELETE FROM app_learning.generation_artifact_turn WHERE artifact_id=:id").param("id", proposal.artifact()).update();
        jdbc.sql("UPDATE app_learning.generation_artifact SET revision_count=30 WHERE artifact_id=:id").param("id", proposal.artifact()).update();
        MockHttpServletResponse limit = edit(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "REWRITE", "SIMPLER", null, target));
        problem(limit, 422, "RESOURCE_LIMIT_EXCEEDED");
        assertThat(json(limit).path("limit").stringValue(null)).isEqualTo("REVISIONS_PER_ARTIFACT");
        jdbc.sql("UPDATE app_learning.generation_artifact SET revision_count=1 WHERE artifact_id=:id").param("id", proposal.artifact()).update();
        assertThat(editCalls()).isEmpty();

        // a rejected proposal and a published one are not edited; neither is the closed session's
        Proposal rejected = fresh(owner, deck, proposal);
        assertThat(reject(owner, deck, rejected, UUID.randomUUID(), rejected.version()).getStatus()).isEqualTo(200);
        Proposal gone = fresh(owner, deck, proposal);
        MockHttpServletResponse refused = edit(owner, deck, gone, editBody(UUID.randomUUID(), gone.revision(), "REWRITE", "SIMPLER", null, target));
        problem(refused, 409, "GENERATION_STATE_CONFLICT");
        assertThat(json(refused).path("reason").stringValue(null)).isEqualTo("ILLEGAL_STATE");
        assertThat(sessionState(proposal.session())).isEqualTo("CLOSED");
        Proposal published = proposal(owner, deck, spec(MULTI));
        UUID publishedTarget = id(blocks(detail(owner, deck, published)).get(1));
        assertThat(approve(owner, deck, published, UUID.randomUUID()).getStatus()).isEqualTo(200);
        Proposal done = fresh(owner, deck, published);
        problem(edit(owner, deck, done, editBody(UUID.randomUUID(), done.revision(), "REWRITE", "SIMPLER", null, publishedTarget)), 409, "GENERATION_STATE_CONFLICT");
        problem(revert(owner, deck, done, done.version(), done.revision()), 409, "GENERATION_STATE_CONFLICT");
        // a foreign or unknown artifact is the one opaque 404
        problem(edit(UUID.randomUUID(), deck, published, editBody(UUID.randomUUID(), published.revision(), "REWRITE", null, null, publishedTarget)), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void anExerciseProposalIsRefusedEveryEditUntilTheirOwnFlowExists() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        var material = fixtures.addMaterial(owner, deck, "Планировщик выбирает план выполнения", "Статистика таблицы обновляется командой ANALYZE");
        tools.jackson.databind.node.ObjectNode spec = JSON.createObjectNode().put("kind", "EXERCISES").put("outputLanguage", "ru");
        spec.putArray("targets").addObject().put("memberKey", material.member().toString()).put("itemRevisionId", material.itemRevision().toString());
        spec.putObject("settings").put("mechanics", "AUTO").put("priority", "UNCOVERED_FIRST").putObject("quantity").put("mode", "EXACT").put("perTarget", 1);
        Proposal exercise = proposal(owner, deck, spec);

        tools.jackson.databind.node.ObjectNode body = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedRevisionId", exercise.revision().toString()).put("action", "FREE").put("instruction", "Сделай сложнее");
        body.putNull("target");
        MockHttpServletResponse refused = edit(owner, deck, exercise, body);
        problem(refused, 409, "GENERATION_STATE_CONFLICT");
        assertThat(json(refused).path("reason").stringValue(null)).isEqualTo("ILLEGAL_STATE");
        MockHttpServletResponse revert = revert(owner, deck, exercise, exercise.version(), exercise.revision());
        problem(revert, 409, "GENERATION_STATE_CONFLICT");
        assertThat(artifactState(exercise.artifact())).isEqualTo("PROPOSED");
        assertThat(editCalls()).isEmpty();
    }

    @Test
    void theMediaActionsAreRefusedAsUnavailableUntilTheirExecutorsExistAndOnlyOnTheirOwnKindOfBlock() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, audioSpec("[[fake:audio]] с аудио"));
        List<JsonNode> blocks = blocks(detail(owner, deck, proposal));
        UUID audio = id(blocks.get(2));
        UUID paragraph = id(blocks.get(1));

        MockHttpServletResponse regenerate = edit(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "AUDIO_REGENERATE", null, null, audio));
        problem(regenerate, 409, "CAPABILITY_UNAVAILABLE");
        assertThat(json(regenerate).path("capability").stringValue(null)).isEqualTo("textToSpeech");
        assertThat(json(regenerate).path("reason").stringValue(null)).isEqualTo("PROVIDER_NOT_CONFIGURED");
        // a block of the wrong kind is a bad request, and a text rewrite of media alone has nothing to rewrite
        problem(edit(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "AUDIO_REGENERATE", null, null, paragraph)), 400, "INVALID_REQUEST");
        problem(edit(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "IMAGE_SEARCH", null, null, audio)), 400, "INVALID_REQUEST");
        problem(edit(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "REWRITE", "SIMPLER", null, audio)), 400, "INVALID_REQUEST");
        problem(edit(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "REMOVE_MEDIA", null, null, paragraph)), 400, "INVALID_REQUEST");

        // an image block: the image actions name their capability
        var image = new app.mnema.learning.generation.mbm.MbmCompiler().compile(
                "# Схема\n\nПланировщик.\n\n::image{mode=\"search\" slot=\"i1\" alt=\"Схема\"} схема планировщика\n",
                app.mnema.learning.generation.mbm.MbmOptions.create(), new app.mnema.learning.generation.mbm.RandomIdAllocator());
        tools.jackson.databind.JsonNode document = ((app.mnema.learning.generation.mbm.MbmResult.Success) image).document();
        Proposal withImage = withDocument(owner, proposal, document);
        UUID picture = id(blocks(detail(owner, deck, withImage)).get(2));
        MockHttpServletResponse search = edit(owner, deck, withImage, editBody(UUID.randomUUID(), withImage.revision(), "IMAGE_SEARCH", null, null, picture));
        problem(search, 409, "CAPABILITY_UNAVAILABLE");
        assertThat(json(search).path("capability").stringValue(null)).isEqualTo("imageSearch");
        MockHttpServletResponse create = edit(owner, deck, withImage, editBody(UUID.randomUUID(), withImage.revision(), "IMAGE_GENERATE", null, null, picture));
        problem(create, 409, "CAPABILITY_UNAVAILABLE");
        assertThat(json(create).path("capability").stringValue(null)).isEqualTo("imageGeneration");
        assertThat(editCalls()).isEmpty();
        assertThat(jdbc.sql("SELECT count(*)::integer FROM app_learning.usage_reservation WHERE owner_id=:owner AND scope='TURN'")
                .param("owner", owner).query(Integer.class).single()).isZero();
    }

    // ------------------------------------------------------------------------ media

    @Test
    void removingMediaIsFreeAndAppliedBeforeTheAnswerAndItMakesTheProposalApprovableWithoutIt() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, audioSpec("[[fake:audio]] с аудио"));
        List<JsonNode> before = blocks(detail(owner, deck, proposal));
        UUID audio = id(before.get(2));
        assertThat(before.get(2).path("type").stringValue(null)).isEqualTo("audio");
        // while its audio is not ready the proposal cannot be approved
        problem(approve(owner, deck, proposal, UUID.randomUUID()), 409, "GENERATION_STATE_CONFLICT");
        int debitsBefore = debits(owner);
        int reservationsBefore = reservationsOf(owner).size();
        UUID command = UUID.randomUUID();
        // 50 turns do not stop a removal: it is not counted
        jdbc.sql("INSERT INTO app_learning.generation_artifact_turn(turn_id,artifact_id,session_id,owner_id,status,action,preset,target_node_ids,"
                        + "created_at) SELECT gen_random_uuid(),:artifact,:session,:owner,'APPLIED','REWRITE','SIMPLER',ARRAY[CAST(:target AS uuid)],"
                        + "CURRENT_TIMESTAMP FROM generate_series(1,50)").param("artifact", proposal.artifact()).param("session", proposal.session())
                .param("owner", owner).param("target", id(before.get(1)).toString()).update();

        MockHttpServletResponse response = edit(owner, deck, proposal, editBody(command, proposal.revision(), "REMOVE_MEDIA", null, null, audio));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(202);
        JsonNode accepted = json(response);
        JsonNode turn = accepted.path("turn");
        assertThat(turn.path("status").stringValue(null)).isEqualTo("APPLIED");
        assertThat(turn.path("action").stringValue(null)).isEqualTo("REMOVE_MEDIA");
        assertThat(turn.path("preset").isNull()).isTrue();
        assertThat(turn.path("instruction").isNull()).isTrue();
        assertThat(turn.path("targetNodeIds").get(0).stringValue(null)).isEqualTo(audio.toString());
        String result = turn.path("resultRevisionId").stringValue(null);
        assertThat(result).isNotNull().isNotEqualTo(proposal.revision().toString());
        assertThat(accepted.path("artifact").path("state").stringValue(null)).isEqualTo("PROPOSED");
        assertThat(accepted.path("artifact").path("currentRevisionId").stringValue(null)).isEqualTo(result);
        assertThat(accepted.path("artifact").path("mediaSlotCounts").path("total").intValue()).isZero();

        // a new revision (cause MEDIA) without the node; the slot is REMOVED and its waiting media step stopped; the neighbours are unchanged
        JsonNode detail = detail(owner, deck, proposal);
        assertThat(detail.path("revision").path("cause").stringValue(null)).isEqualTo("MEDIA");
        List<JsonNode> after = blocks(detail);
        assertThat(after).hasSize(2);
        assertThat(after.get(0)).isEqualTo(before.get(0));
        assertThat(after.get(1)).isEqualTo(before.get(1));
        assertThat(jdbc.sql("SELECT state FROM app_learning.generation_media_slot WHERE artifact_id=:id").param("id", proposal.artifact())
                .query(String.class).single()).isEqualTo("REMOVED");
        assertThat(jdbc.sql("SELECT count(*)::integer FROM app_learning.generation_step WHERE artifact_id=:id AND kind='TTS' AND state='CANCELLED'")
                .param("id", proposal.artifact()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT counts_toward_limit FROM app_learning.generation_artifact_turn WHERE turn_id=CAST(:id AS uuid)")
                .param("id", turn.path("turnId").stringValue(null)).query(Boolean.class).single()).isFalse();
        List<String> log = new ArrayList<>();
        json(events(owner, deck, proposal.session(), "?after=0&limit=100")).path("events").forEach(event -> log.add(
                event.path("type").stringValue(null) + ":" + event.path("payload").path("state").stringValue("-")));
        assertThat(log).endsWith("ARTIFACT_STATE:PROPOSED", "MEDIA_SLOT_STATE:REMOVED");
        // free and deterministic: no provider call, no debit, no reservation
        assertThat(editCalls()).isEmpty();
        assertThat(debits(owner)).isEqualTo(debitsBefore);
        assertThat(reservationsOf(owner)).hasSize(reservationsBefore);
        // an exact retry is the stored answer
        MockHttpServletResponse replay = edit(owner, deck, proposal, editBody(command, proposal.revision(), "REMOVE_MEDIA", null, null, audio));
        assertThat(replay.getStatus()).isEqualTo(202);
        assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(json(replay)).isEqualTo(accepted);

        // now every slot is resolved: the proposal is approved without the audio
        Proposal now = fresh(owner, deck, proposal);
        assertThat(approve(owner, deck, now, UUID.randomUUID()).getStatus()).isEqualTo(200);
        assertThat(artifactState(proposal.artifact())).isEqualTo("PUBLISHED");
    }

    @Test
    void aRevisionThatHoldsRemovedMediaAgainNeedsItRemovedAgainAndTheSlotsFollowTheRevisionThatIsShown() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, audioSpec("[[fake:audio]] с аудио"));
        UUID asset = readyAsset(owner, proposal.artifact());
        UUID audio = id(blocks(detail(owner, deck, proposal)).get(2));
        assertThat(heldAssets(proposal.artifact())).containsExactly(asset);

        UUID removal = accepted(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "REMOVE_MEDIA", null, null, audio));
        Proposal removed = fresh(owner, deck, proposal);
        assertThat(turnStatus(removal)).isEqualTo("APPLIED");
        // the hold on the removed asset ended with the slot
        assertThat(heldAssets(proposal.artifact())).isEmpty();
        assertThat(detail(owner, deck, proposal).path("mediaSlots")).isEmpty();
        // the earlier revision still shows its media node, with the slot that says what became of it
        JsonNode earlier = detail(owner, deck, proposal, "?revisionId=" + proposal.revision());
        assertThat(blocks(earlier)).hasSize(3);
        assertThat(earlier.path("mediaSlots").get(0).path("state").stringValue(null)).isEqualTo("REMOVED");

        // undo: the node is back without a ready asset, so approval needs the media removed again
        MockHttpServletResponse back = revert(owner, deck, removed, removed.version(), proposal.revision());
        assertThat(back.getStatus()).as(back.getContentAsString()).isEqualTo(200);
        JsonNode restored = detail(owner, deck, proposal);
        assertThat(blocks(restored)).hasSize(3);
        JsonNode slot = restored.path("mediaSlots").get(0);
        assertThat(slot.path("state").stringValue(null)).isEqualTo("FAILED");
        assertThat(slot.path("errorCode").stringValue(null)).isEqualTo("NO_RESULT");
        assertThat(slot.path("nodeId").stringValue(null)).isEqualTo(audio.toString());
        assertThat(json(back).path("mediaSlotCounts").path("total").intValue()).isEqualTo(1);
        assertThat(json(back).path("mediaSlotCounts").path("failed").intValue()).isEqualTo(1);
        Proposal again = fresh(owner, deck, proposal);
        MockHttpServletResponse notReady = approve(owner, deck, again, UUID.randomUUID());
        problem(notReady, 409, "GENERATION_STATE_CONFLICT");
        assertThat(json(notReady).path("reason").stringValue(null)).isEqualTo("MEDIA_NOT_READY");
        List<String> log = new ArrayList<>();
        json(events(owner, deck, proposal.session(), "?after=0&limit=100")).path("events").forEach(event -> log.add(
                event.path("type").stringValue(null) + ":" + event.path("payload").path("state").stringValue("-")));
        assertThat(log).containsSubsequence("MEDIA_SLOT_STATE:REMOVED", "MEDIA_SLOT_STATE:FAILED");

        // redo the other way: the revision without the media removes the slot again
        MockHttpServletResponse forward = revert(owner, deck, again, again.version(), removed.revision());
        assertThat(forward.getStatus()).isEqualTo(200);
        assertThat(detail(owner, deck, proposal).path("mediaSlots")).isEmpty();
        Proposal last = fresh(owner, deck, proposal);
        assertThat(approve(owner, deck, last, UUID.randomUUID()).getStatus()).isEqualTo(200);
    }

    private List<UUID> heldAssets(UUID artifact) {
        return jdbc.sql("SELECT asset_id FROM app_learning.generation_media_ref WHERE artifact_id=:id").param("id", artifact).query(UUID.class).list();
    }

    @Test
    void aRewriteOfARunThatHoldsMediaNeverShowsItToTheModelAndKeepsTheMediaBlockAndItsSlot() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, audioSpec("[[fake:audio]] с аудио"));
        List<JsonNode> before = blocks(detail(owner, deck, proposal));
        UUID paragraph = id(before.get(1));
        UUID audio = id(before.get(2));

        UUID turn = accepted(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "REWRITE", "SIMPLER", null, paragraph, audio));
        awaitTurn(turn, "APPLIED");

        JsonNode detail = detail(owner, deck, proposal);
        List<JsonNode> after = blocks(detail);
        assertThat(after).hasSize(3);
        assertThat(text(after.get(1))).isEqualTo("Первый абзац. Переписано: Проще.");
        assertThat(after.get(2)).isEqualTo(before.get(2));
        // the model saw only the paragraph, and the media slot follows the new revision
        String prompt = editCalls().getFirst().prompt();
        String document = prompt.substring(prompt.indexOf("<document>\n<material"), prompt.indexOf("</document>\n<history>"));
        assertThat(document).contains("<target>[[b2]] Первый абзац.</target>").contains("[[b3]] [аудио]").doesNotContain("::audio")
                .contains("<context_after></context_after>");
        JsonNode slot = detail.path("mediaSlots").get(0);
        assertThat(slot.path("nodeId").stringValue(null)).isEqualTo(audio.toString());
        assertThat(slot.path("state").stringValue(null)).isEqualTo("PENDING");
        assertThat(jdbc.sql("SELECT revision_id FROM app_learning.generation_media_slot WHERE artifact_id=:id").param("id", proposal.artifact())
                .query(UUID.class).single()).isEqualTo(UUID.fromString(detail.path("currentRevisionId").stringValue(null)));
        assertThat(detail.path("mediaSlotCounts").path("total").intValue()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- idempotency

    @Test
    void anExactRetryOfAnEditIsTheStoredAnswerAndAChangedBodyUnderTheSameCommandIsAConflict() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, spec(MULTI));
        UUID target = id(blocks(detail(owner, deck, proposal)).get(1));
        UUID command = UUID.randomUUID();
        tools.jackson.databind.node.ObjectNode body = editBody(command, proposal.revision(), "REWRITE", "SIMPLER", null, target);

        MockHttpServletResponse first = edit(owner, deck, proposal, body);
        MockHttpServletResponse replay = edit(owner, deck, proposal, body);

        assertThat(first.getStatus()).isEqualTo(202);
        assertThat(first.getHeader("Idempotency-Replayed")).isNull();
        assertThat(replay.getStatus()).isEqualTo(202);
        assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(json(replay)).isEqualTo(json(first));
        awaitTurn(UUID.fromString(json(first).path("turn").path("turnId").stringValue(null)), "APPLIED");
        // still the stored answer once the turn has ended, and there is exactly one turn and one hold
        assertThat(json(edit(owner, deck, proposal, body))).isEqualTo(json(first));
        assertThat(jdbc.sql("SELECT count(*)::integer FROM app_learning.generation_artifact_turn WHERE artifact_id=:id")
                .param("id", proposal.artifact()).query(Integer.class).single()).isEqualTo(1);
        problem(edit(owner, deck, proposal, editBody(command, proposal.revision(), "REWRITE", "SHORTER", null, target)), 409, "IDEMPOTENCY_CONFLICT");
    }

    // ------------------------------------------------------------- what the model gets

    @Test
    void aBlockTheModelCannotBeGivenIsRefusedBeforeAnyCallAndNeighboursItCannotReadAreOnlyContext() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, spec(MULTI));
        // MBM has no syntax for a heading of level 4, and a telephone number would be redacted from the prompt and lost from the text
        ObjectNode deep = heading(4, "Глубокий заголовок");
        ObjectNode plain = paragraph("Обычный текст.");
        ObjectNode phone = paragraph("Позвоните +7 (999) 123-45-67 завтра");
        ObjectNode after = heading(4, "Ещё один");
        Proposal odd = withDocument(owner, proposal, document(heading(1, "Заголовок"), deep, plain, phone, after));
        problem(edit(owner, deck, odd, editBody(UUID.randomUUID(), odd.revision(), "REWRITE", "SIMPLER", null, id(deep))), 400, "INVALID_REQUEST");
        problem(edit(owner, deck, odd, editBody(UUID.randomUUID(), odd.revision(), "REWRITE", "SIMPLER", null, id(phone))), 400, "INVALID_REQUEST");
        problem(edit(owner, deck, odd, editBody(UUID.randomUUID(), odd.revision(), "REWRITE", "SIMPLER", null, id(plain), id(phone))), 400, "INVALID_REQUEST");
        assertThat(editCalls()).isEmpty();
        assertThat(artifactState(proposal.artifact())).isEqualTo("PROPOSED");

        // the plain paragraph between two such headings is fine: the neighbours are shown as their first line
        UUID turn = accepted(owner, deck, odd, editBody(UUID.randomUUID(), odd.revision(), "REWRITE", "SIMPLER", null, id(plain)));
        awaitTurn(turn, "APPLIED");
        assertThat(editCalls().getFirst().prompt()).contains("<context_before>Глубокий заголовок</context_before>")
                .contains("<target>[[b3]] Обычный текст.</target>").contains("<context_after>Позвоните");
    }

    @Test
    void theOutlineIsAtMostTwoHundredLinesAroundTheTargetAndEverythingTheModelSeesIsEscapedAndWhatItAnswersIsUnescapedOnce() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, spec(MULTI));
        tools.jackson.databind.JsonNode[] blocks = new tools.jackson.databind.JsonNode[300];
        for (int index = 0; index < blocks.length; index++) blocks[index] = paragraph("Абзац " + (index + 1));
        ObjectNode special = paragraph("Если a < b & c > d, то \"так\"");
        blocks[149] = special;
        Proposal big = withDocument(owner, proposal, document(blocks));

        UUID turn = accepted(owner, deck, big, editBody(UUID.randomUUID(), big.revision(), "FREE", null, "Проще <b>жирным</b>", id(special)));
        awaitTurn(turn, "APPLIED");

        String prompt = editCalls().getFirst().prompt();
        String outline = prompt.substring(prompt.indexOf("<material id=\"doc\">"), prompt.indexOf("</material>"));
        assertThat(outline.lines().filter(line -> line.startsWith("[[b"))).hasSize(200).contains("[[b150]] Если a &lt; b &amp; c &gt; d, то &quot;так&quot;");
        assertThat(outline).contains("[[b150]]").doesNotContain("[[b1]]").doesNotContain("[[b300]]");
        assertThat(prompt).contains("<target>[[b150]] Если a &lt; b &amp; c &gt; d, то &quot;так&quot;</target>")
                .contains("<context_before>Абзац 149</context_before>").contains("<context_after>Абзац 151</context_after>")
                .contains("<instruction>Проще &lt;b&gt;жирным&lt;/b&gt;</instruction>");
        // the Stub echoes the text it was shown, entities included; the answer is unescaped once, so the author's characters come back as written
        List<JsonNode> result = blocks(detail(owner, deck, proposal));
        assertThat(result).hasSize(300);
        assertThat(text(result.get(149))).isEqualTo("Если a < b & c > d, то \"так\" Переписано: нет.");
        assertThat(id(result.get(149))).isEqualTo(id(special));
        assertThat(result.get(148)).isEqualTo(blocks(detail(owner, deck, proposal, "?revisionId=" + big.revision())).get(148));
    }

    // -------------------------------------------------------- a session still running

    @Test
    void aProposalOfARunningSessionCanBeEditedAndTheHoldOfAnEditInFlightSurvivesTheSessionLeavingRunning() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID first = note(owner, deck, "[[fake:multiblock]] первая");
        UUID second = note(owner, deck, "[[fake:block]] вторая");
        UUID session = start(owner, deck, spec(null, noteSource(first, 0), noteSource(second, 0)));
        UUID artifact = proposals(owner, deck, session).getFirst().artifact();
        awaitArtifact(artifact, "PROPOSED");
        assertThat(provider.blockedEntered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(sessionState(session)).isEqualTo("RUNNING");
        Proposal proposal = fresh(owner, deck, proposals(owner, deck, session).getFirst());
        UUID target = id(blocks(detail(owner, deck, proposal)).get(1));

        // the first proposal is edited while the second is still being written
        UUID quick = accepted(owner, deck, proposal, editBody(UUID.randomUUID(), proposal.revision(), "REWRITE", "SIMPLER", null, target));
        awaitTurn(quick, "APPLIED");
        assertThat(sessionState(session)).isEqualTo("RUNNING");

        // an edit in flight while the session leaves RUNNING keeps its own hold, and pays from it when it ends
        Proposal now = fresh(owner, deck, proposal);
        int debitsBefore = debits(owner);
        UUID slow = accepted(owner, deck, now, editBody(UUID.randomUUID(), now.revision(), "FREE", null, "[[fake:hold-edit]] правь", target));
        assertThat(provider.editEntered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        provider.release.countDown();
        awaitState(session, "REVIEW");
        assertThat(turnStatus(slow)).isEqualTo("RUNNING");
        assertThat(reservationOfTurn(slow)).isEqualTo("ACTIVE");
        provider.editRelease.countDown();
        awaitTurn(slow, "APPLIED");
        assertThat(reservationOfTurn(slow)).isEqualTo("SETTLED");
        assertThat(debits(owner) - debitsBefore).isGreaterThanOrEqualTo(4 + 10);
    }
}
