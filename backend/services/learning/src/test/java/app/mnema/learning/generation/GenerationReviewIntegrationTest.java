package app.mnema.learning.generation;

import app.mnema.learning.catalog.content.NativeDocumentReader;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Acceptance of #288 on the Stub, PostgreSQL and the real catalog: approval (one and bulk) in one transaction with the
 * publication, rejection and undo, hand-off, retry, delete and the archival of used notes.
 */
class GenerationReviewIntegrationTest extends GenerationReviewSupport {

    // ----------------------------------------------------------------- approve

    @Test
    void approvingCreatesTheMaterialInTheDeckInOneTransactionAndReplayDoesNotCreateASecondOne() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, spec("20 глаголов движения"));
        long versionBefore = deckVersion(deck);
        UUID revisionBefore = deckRevision(deck);
        UUID command = UUID.randomUUID();

        MockHttpServletResponse response = approve(owner, deck, proposal, command);

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
        JsonNode ack = json(response);
        assertThat(response.getHeader("ETag")).isEqualTo("\"" + ack.path("deckVersion").stringValue(null) + "\"");
        assertThat(Long.parseLong(ack.path("deckVersion").stringValue(null))).isEqualTo(versionBefore + 1);
        assertThat(ack.path("commandId").stringValue(null)).isEqualTo(command.toString());
        assertThat(ack.path("deckId").stringValue(null)).isEqualTo(deck.toString());
        assertThat(UUID.fromString(ack.path("deckRevisionId").stringValue(null))).isNotEqualTo(revisionBefore).isEqualTo(deckRevision(deck));
        JsonNode published = ack.path("artifacts").get(0);
        assertThat(published.path("artifactId").stringValue(null)).isEqualTo(proposal.artifact().toString());
        assertThat(published.path("state").stringValue(null)).isEqualTo("PUBLISHED");
        JsonNode reference = published.path("publishedRef");
        assertThat(reference.path("kind").stringValue(null)).isEqualTo("ITEM");
        assertThat(reference.path("ordinal").intValue()).isZero();

        // the material is an ordinary one: Browse lists it and it reads back as the proposal's document
        UUID member = UUID.fromString(reference.path("memberKey").stringValue(null));
        assertThat(materials(owner, deck)).isEqualTo(1);
        JsonNode material = items.read(owner, deck, member, null);
        assertThat(material.path("itemRevisionId").stringValue(null)).isEqualTo(reference.path("itemRevisionId").stringValue(null));
        JsonNode proposed = json(send(owner, get(base(deck, proposal.session()) + "/artifacts/" + proposal.artifact())));
        // compared as parsed text: the catalog hands back numbers as longs where the stored proposal has ints
        assertThat(JSON.readTree(material.path("document").toString()))
                .isEqualTo(JSON.readTree(proposed.path("revision").path("payload").path("document").toString()));
        assertThat(proposed.path("state").stringValue(null)).isEqualTo("PUBLISHED");
        assertThat(proposed.path("publishedRef")).isEqualTo(reference);
        assertThat(sessionState(proposal.session())).isEqualTo("CLOSED");

        // one event per change, in the one numbered log, ending with the session's close
        JsonNode events = json(events(owner, deck, proposal.session(), "?after=0&limit=100")).path("events");
        List<String> tail = new ArrayList<>();
        events.forEach(event -> tail.add(event.path("type").stringValue(null) + ":" + event.path("payload").path("state").stringValue("-")));
        assertThat(tail).contains("ARTIFACT_STATE:PUBLISHED").endsWith("SESSION_STATE:CLOSED");

        // provenance is stored for audit and never shown
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_provenance WHERE artifact_id=:id AND revision_id=:revision "
                + "AND model_routes=ARRAY['stub:stub'] AND owner_id=:owner").param("id", proposal.artifact())
                .param("revision", proposal.revision()).param("owner", owner).query(Integer.class).single()).isEqualTo(1);
        assertThat(response.getContentAsString() + proposed + json(getSession(owner, deck, proposal.session())))
                .doesNotContain("provenance").doesNotContain("modelRoute").doesNotContain("promptVersion");

        // an exact retry is the stored acknowledgement: no second material, no ETag, the replay header
        MockHttpServletResponse replay = send(owner, post(base(deck, proposal.session()) + "/artifacts/" + proposal.artifact() + "/approval")
                .contentType("application/json").header("If-Match", "\"" + versionBefore + "\"")
                .content(approvalBody(command, proposal, revisionBefore).toString()));
        assertThat(replay.getStatus()).isEqualTo(200);
        assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(replay.getHeader("ETag")).isNull();
        assertThat(json(replay)).isEqualTo(ack);
        assertThat(materials(owner, deck)).isEqualTo(1);
        assertThat(deckVersion(deck)).isEqualTo(versionBefore + 1);

        // the same command with another body is a different command
        ObjectNode changed = approvalBody(command, proposal, revisionBefore).put("expectedArtifactVersion", "99");
        problem(approve(owner, deck, proposal, changed, "\"" + versionBefore + "\""), 409, "IDEMPOTENCY_CONFLICT");
        // a new command on the published artifact is a state conflict, not a second material
        Proposal now = fresh(owner, deck, proposal);
        problem(approve(owner, deck, now, approvalBody(UUID.randomUUID(), now, deckRevision(deck)), "\"" + deckVersion(deck) + "\""), 409,
                "GENERATION_STATE_CONFLICT");
        assertThat(materials(owner, deck)).isEqualTo(1);
    }

    @Test
    void aStaleDeckOrArtifactVersionIsA412AndTheMissingOrMalformedPreconditionsAreRefusedFirstWithNothingPublished() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, spec("20 глаголов движения"));
        long version = deckVersion(deck);
        UUID revision = deckRevision(deck);
        String current = "\"" + version + "\"";

        problem(approve(owner, deck, proposal, approvalBody(UUID.randomUUID(), proposal, revision), null), 428, "PRECONDITION_REQUIRED");
        for (String malformed : new String[] {"W/\"1\"", "*", version + "", "\"01\"", "\"-1\"", "\"1\", \"2\"", "\"\""}) {
            problem(approve(owner, deck, proposal, approvalBody(UUID.randomUUID(), proposal, revision), malformed), 400, "INVALID_REQUEST");
        }
        // validation comes before the preconditions: an unknown field with a stale version is a 400
        problem(approve(owner, deck, proposal, approvalBody(UUID.randomUUID(), proposal, revision).put("extra", 1), "\"" + (version + 5) + "\""),
                400, "INVALID_REQUEST");
        problem(approve(owner, deck, proposal, approvalBody(UUID.randomUUID(), proposal, revision).put("expectedArtifactVersion", 4), current),
                400, "INVALID_REQUEST");
        problem(approve(owner, deck, proposal, approvalBody(UUID.randomUUID(), proposal, revision).put("expectedRevisionId", "nope"), current),
                400, "INVALID_REQUEST");
        // stale deck version, stale deck revision, stale artifact version, stale revision: 412 each
        problem(approve(owner, deck, proposal, approvalBody(UUID.randomUUID(), proposal, revision), "\"" + (version + 1) + "\""), 412, "VERSION_CONFLICT");
        problem(approve(owner, deck, proposal, approvalBody(UUID.randomUUID(), proposal, UUID.randomUUID()), current), 412, "VERSION_CONFLICT");
        problem(approve(owner, deck, proposal, approvalBody(UUID.randomUUID(), proposal, revision).put("expectedArtifactVersion",
                Long.toString(proposal.version() + 1)), current), 412, "VERSION_CONFLICT");
        problem(approve(owner, deck, proposal, approvalBody(UUID.randomUUID(), proposal, revision).put("expectedRevisionId",
                UUID.randomUUID().toString()), current), 412, "VERSION_CONFLICT");

        assertThat(materials(owner, deck)).isZero();
        assertThat(artifactState(proposal.artifact())).isEqualTo("PROPOSED");
        assertThat(deckVersion(deck)).isEqualTo(version);
        // with the right versions it works, and a repeat with a new command now meets the advanced deck: 412
        assertThat(approve(owner, deck, proposal, approvalBody(UUID.randomUUID(), proposal, revision), current).getStatus()).isEqualTo(200);
        problem(approve(owner, deck, proposal, approvalBody(UUID.randomUUID(), proposal, revision), current), 412, "VERSION_CONFLICT");
    }

    @Test
    void aSlotThatIsNotReadyBlocksTheApprovalAndAReadySlotBindsItsAssetToTheMaterial() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, audioSpec("[[fake:audio]] с аудио"));
        assertThat(json(getSession(owner, deck, proposal.session())).path("approvableCount").intValue()).isZero();

        MockHttpServletResponse blocked = approve(owner, deck, proposal, UUID.randomUUID());
        problem(blocked, 409, "GENERATION_STATE_CONFLICT");
        assertThat(json(blocked).path("reason").stringValue(null)).isEqualTo("MEDIA_NOT_READY");
        assertThat(json(blocked).has("artifactIds")).isFalse();
        assertThat(materials(owner, deck)).isZero();
        assertThat(artifactState(proposal.artifact())).isEqualTo("PROPOSED");

        UUID asset = readyAsset(owner, proposal.artifact());
        assertThat(json(getSession(owner, deck, proposal.session())).path("approvableCount").intValue()).isEqualTo(1);
        assertThat(approve(owner, deck, proposal, UUID.randomUUID()).getStatus()).isEqualTo(200);

        // the catalog bound the asset to the revision it stored, and the Workshop's hold on it is gone
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.content_media_ref WHERE asset_id=:asset AND owner_id=:owner")
                .param("asset", asset).param("owner", owner).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_media_ref WHERE artifact_id=:id").param("id", proposal.artifact())
                .query(Integer.class).single()).isZero();
        assertThat(materials(owner, deck)).isEqualTo(1);
    }

    @Test
    void approvingAnArtifactWhoseNoteChangedMakesItStaleWithoutPublishingAndStaleHasNoKeepAnyway() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID note = note(owner, deck, "заметка про глаголы");
        Proposal proposal = proposal(owner, deck, spec(null, noteSource(note, 0)));
        jdbc.sql("UPDATE app_learning.capture_note SET row_version=row_version+1,note_text='заметка изменилась',"
                + "updated_at=CURRENT_TIMESTAMP WHERE note_id=:id").param("id", note).update();

        MockHttpServletResponse stale = approve(owner, deck, proposal, UUID.randomUUID());
        problem(stale, 409, "GENERATION_STATE_CONFLICT");
        assertThat(json(stale).path("reason").stringValue(null)).isEqualTo("SOURCE_STALE");
        // the short transaction before the publish transaction committed: the artifact is STALE and says why
        assertThat(artifactState(proposal.artifact())).isEqualTo("STALE");
        JsonNode artifact = json(send(owner, get(base(deck, proposal.session()) + "/artifacts/" + proposal.artifact())));
        assertThat(artifact.path("state").stringValue(null)).isEqualTo("STALE");
        assertThat(artifact.path("repinStatus").stringValue(null)).isEqualTo("NEEDS_USER_DECISION");
        assertThat(materials(owner, deck)).isZero();
        assertThat(sessionState(proposal.session())).isEqualTo("REVIEW");
        List<String> states = new ArrayList<>();
        json(events(owner, deck, proposal.session(), "")).path("events").forEach(event -> {
            if (event.path("type").stringValue("").equals("ARTIFACT_STATE")) states.add(event.path("payload").path("state").stringValue(null));
        });
        assertThat(states).endsWith("STALE");

        // there is no "keep anyway": the stale proposal is refused again, whatever the versions
        Proposal now = fresh(owner, deck, proposal);
        MockHttpServletResponse again = approve(owner, deck, now, UUID.randomUUID());
        problem(again, 409, "GENERATION_STATE_CONFLICT");
        assertThat(json(again).path("reason").stringValue(null)).isEqualTo("SOURCE_STALE");
        assertThat(materials(owner, deck)).isZero();
        // a GET never changes state, and a STALE artifact can still be rejected
        assertThat(reject(owner, deck, now, UUID.randomUUID(), now.version()).getStatus()).isEqualTo(200);
    }

    @Test
    void aBulkApprovalPublishesEveryArtifactOrNoneWithOneDeckRevisionAndNamesTheOnesThatBlockIt() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID first = note(owner, deck, "первая заметка");
        UUID second = note(owner, deck, "вторая заметка");
        UUID audio = note(owner, deck, "[[fake:audio]] третья заметка с аудио");
        UUID session = start(owner, deck, audioSpec(null, noteSource(first, 0), noteSource(second, 0), noteSource(audio, 0)));
        awaitState(session, "REVIEW");
        List<Proposal> all = proposals(owner, deck, session);
        assertThat(all).hasSize(3);
        long version = deckVersion(deck);
        UUID revision = deckRevision(deck);
        String current = "\"" + version + "\"";

        // one artifact waits for its audio: the whole command is refused and names it; the others are not published
        MockHttpServletResponse unready = approveMany(owner, deck, session, bulkBody(UUID.randomUUID(), revision, all), current);
        problem(unready, 409, "GENERATION_STATE_CONFLICT");
        assertThat(json(unready).path("reason").stringValue(null)).isEqualTo("MEDIA_NOT_READY");
        assertThat(json(unready).path("artifactIds")).hasSize(1);
        assertThat(json(unready).path("artifactIds").get(0).stringValue(null)).isEqualTo(all.get(2).artifact().toString());
        assertThat(materials(owner, deck)).isZero();
        assertThat(artifactStates(session)).containsExactly("PROPOSED", "PROPOSED", "PROPOSED");

        // a stale artifact version names that artifact only; a stale deck names none
        List<Proposal> two = all.subList(0, 2);
        ObjectNode staleBody = bulkBody(UUID.randomUUID(), revision, two);
        ((ObjectNode) staleBody.path("artifacts").get(1)).put("expectedArtifactVersion", Long.toString(two.get(1).version() + 7));
        MockHttpServletResponse stale = approveMany(owner, deck, session, staleBody, current);
        problem(stale, 412, "VERSION_CONFLICT");
        assertThat(json(stale).path("artifactIds")).hasSize(1);
        assertThat(json(stale).path("artifactIds").get(0).stringValue(null)).isEqualTo(two.get(1).artifact().toString());
        problem(approveMany(owner, deck, session, bulkBody(UUID.randomUUID(), revision, two), "\"" + (version + 1) + "\""), 412, "VERSION_CONFLICT");
        assertThat(json(approveMany(owner, deck, session, bulkBody(UUID.randomUUID(), revision, two), "\"" + (version + 1) + "\"")).has("artifactIds")).isFalse();
        // a rejected artifact in the list blocks the command with ILLEGAL_STATE
        Proposal rejected = all.get(1);
        assertThat(reject(owner, deck, rejected, UUID.randomUUID(), rejected.version()).getStatus()).isEqualTo(200);
        MockHttpServletResponse illegal = approveMany(owner, deck, session, bulkBody(UUID.randomUUID(), revision, proposals(owner, deck, session).subList(0, 2)), current);
        problem(illegal, 409, "GENERATION_STATE_CONFLICT");
        assertThat(json(illegal).path("reason").stringValue(null)).isEqualTo("ILLEGAL_STATE");
        assertThat(json(illegal).path("artifactIds").get(0).stringValue(null)).isEqualTo(rejected.artifact().toString());
        assertThat(json(undo(owner, deck, rejected, "\"" + (rejected.version() + 1) + "\"")).path("state").stringValue(null)).isEqualTo("PROPOSED");
        assertThat(materials(owner, deck)).isZero();

        // the list is checked as a body: duplicates, an empty list and more than 20 are 400, an unknown artifact is the opaque 404
        List<Proposal> ready = proposals(owner, deck, session).subList(0, 2);
        problem(approveMany(owner, deck, session, bulkBody(UUID.randomUUID(), revision, List.of(ready.get(0), ready.get(0))), current), 400, "INVALID_REQUEST");
        problem(approveMany(owner, deck, session, bulkBody(UUID.randomUUID(), revision, List.of()), current), 400, "INVALID_REQUEST");
        List<Proposal> tooMany = new ArrayList<>();
        for (int i = 0; i < 21; i++) tooMany.add(new Proposal(session, UUID.randomUUID(), revision, 0));
        problem(approveMany(owner, deck, session, bulkBody(UUID.randomUUID(), revision, tooMany), current), 400, "INVALID_REQUEST");
        problem(approveMany(owner, deck, session, bulkBody(UUID.randomUUID(), revision, List.of(new Proposal(session, UUID.randomUUID(), revision, 0))), current),
                404, "RESOURCE_NOT_FOUND");
        problem(approveMany(owner, deck, session, bulkBody(UUID.randomUUID(), revision, ready), null), 428, "PRECONDITION_REQUIRED");

        // the two ready ones go together: one deck revision, in the order listed, one acknowledgement each
        UUID command = UUID.randomUUID();
        MockHttpServletResponse done = approveMany(owner, deck, session, bulkBody(command, revision, ready), current);
        assertThat(done.getStatus()).as(done.getContentAsString()).isEqualTo(200);
        JsonNode ack = json(done);
        assertThat(done.getHeader("ETag")).isEqualTo("\"" + (version + 1) + "\"");
        assertThat(ack.path("deckVersion").stringValue(null)).isEqualTo(Long.toString(version + 1));
        assertThat(ack.path("artifacts")).hasSize(2);
        assertThat(ack.path("artifacts").get(0).path("artifactId").stringValue(null)).isEqualTo(ready.get(0).artifact().toString());
        assertThat(ack.path("artifacts").get(0).path("publishedRef").path("ordinal").intValue()).isZero();
        assertThat(ack.path("artifacts").get(1).path("publishedRef").path("ordinal").intValue()).isEqualTo(1);
        assertThat(materials(owner, deck)).isEqualTo(2);
        assertThat(artifactStates(session)).containsExactly("PUBLISHED", "PUBLISHED", "PROPOSED");
        assertThat(sessionState(session)).isEqualTo("REVIEW");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.deck_revision WHERE deck_id=:id").param("id", deck).query(Integer.class).single())
                .isEqualTo(2);

        MockHttpServletResponse replay = approveMany(owner, deck, session, bulkBody(command, revision, ready), current);
        assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(json(replay)).isEqualTo(ack);
        assertThat(materials(owner, deck)).isEqualTo(2);
    }

    // ----------------------------------------------------------------- hand-off

    @Test
    void handingOffOpensAnOrdinaryDraftWithTheSameDocumentAndTheArtifactNoLongerAcceptsCommands() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, audioSpec("[[fake:audio]] с аудио"));
        UUID command = UUID.randomUUID();

        MockHttpServletResponse response = handoff(owner, deck, proposal, command);

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        JsonNode body = json(response);
        UUID draft = UUID.fromString(body.path("draft").path("draftId").stringValue(null));
        assertThat(response.getHeader("Location")).isEqualTo("/api/editing-drafts/" + draft);
        assertThat(response.getHeader("ETag")).isEqualTo("\"" + jdbc.sql("SELECT row_version FROM app_learning.editing_draft WHERE draft_id=:id")
                .param("id", draft).query(Long.class).single() + "\"");
        assertThat(body.path("artifact").path("state").stringValue(null)).isEqualTo("HANDED_OFF");
        assertThat(body.path("draft").path("deckId").stringValue(null)).isEqualTo(deck.toString());
        assertThat(body.path("draft").path("memberKey").isNull()).isTrue();
        assertThat(body.path("draft").path("baseRevisionId").isNull()).isTrue();
        assertThat(sessionState(proposal.session())).isEqualTo("CLOSED");

        // the draft is the proposal's document; the media node whose asset does not exist yet is left out (it cannot be saved)
        JsonNode stored = JSON.readTree(jdbc.sql("SELECT document::text FROM app_learning.editing_draft WHERE draft_id=:id AND owner_id=:owner")
                .param("id", draft).param("owner", owner).query(String.class).single());
        new NativeDocumentReader().read(stored.toString().getBytes(StandardCharsets.UTF_8));
        JsonNode proposed = json(send(owner, get(base(deck, proposal.session()) + "/artifacts/" + proposal.artifact())))
                .path("revision").path("payload").path("document");
        assertThat(proposed.path("root").path("content").size()).isEqualTo(stored.path("root").path("content").size() + 1);
        assertThat(stored.toString()).doesNotContain("\"audio\"");
        assertThat(materials(owner, deck)).isZero();

        // the artifact no longer edits: nothing but reads
        Proposal now = fresh(owner, deck, proposal);
        problem(approve(owner, deck, now, approvalBody(UUID.randomUUID(), now, deckRevision(deck)), "\"" + deckVersion(deck) + "\""), 409,
                "GENERATION_STATE_CONFLICT");
        problem(reject(owner, deck, now, UUID.randomUUID(), now.version()), 409, "GENERATION_STATE_CONFLICT");
        problem(handoff(owner, deck, now, UUID.randomUUID()), 409, "GENERATION_STATE_CONFLICT");
        problem(retry(owner, deck, now, UUID.randomUUID(), now.version()), 409, "GENERATION_STATE_CONFLICT");

        MockHttpServletResponse replay = handoff(owner, deck, proposal, command);
        assertThat(replay.getStatus()).isEqualTo(201);
        assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(json(replay)).isEqualTo(body);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.editing_draft WHERE owner_id=:owner").param("owner", owner).query(Integer.class).single())
                .isEqualTo(1);
    }

    @Test
    void aStaleProposalCanBeHandedOffToEditItByHandAndThePreconditionsAndTheDraftQuotaAreEnforced() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID note = note(owner, deck, "заметка");
        Proposal proposal = proposal(owner, deck, spec(null, noteSource(note, 0)));
        problem(handoff(owner, deck, new Proposal(proposal.session(), proposal.artifact(), proposal.revision(), proposal.version() + 1), UUID.randomUUID()),
                412, "VERSION_CONFLICT");
        problem(handoff(owner, deck, new Proposal(proposal.session(), proposal.artifact(), UUID.randomUUID(), proposal.version()), UUID.randomUUID()),
                412, "VERSION_CONFLICT");
        problem(send(owner, post(base(deck, proposal.session()) + "/artifacts/" + proposal.artifact() + "/handoff").contentType("application/json")
                .content("{\"commandId\":\"" + UUID.randomUUID() + "\"}")), 400, "INVALID_REQUEST");

        // the account's draft quota is a 422 with the limit named, and nothing changed
        var sample = json(send(owner, get(base(deck, proposal.session()) + "/artifacts/" + proposal.artifact()))).path("revision").path("payload").path("document");
        var document = new NativeDocumentReader().read(sample.toString().getBytes(StandardCharsets.UTF_8));
        TransactionTemplate tx = new TransactionTemplate(transactions);
        for (int i = 0; i < 200; i++) tx.executeWithoutResult(status -> draftOpener.open(owner, deck, UUID.randomUUID(), document));
        MockHttpServletResponse full = handoff(owner, deck, proposal, UUID.randomUUID());
        problem(full, 422, "RESOURCE_LIMIT_EXCEEDED");
        assertThat(json(full).path("limit").stringValue(null)).isEqualTo("EDITING_DRAFTS");
        assertThat(artifactState(proposal.artifact())).isEqualTo("PROPOSED");
        jdbc.sql("DELETE FROM app_learning.editing_draft WHERE owner_id=:owner").param("owner", owner).update();

        // a stale proposal (its note changed) is handed off the same way
        jdbc.sql("UPDATE app_learning.capture_note SET row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE note_id=:id").param("id", note).update();
        problem(approve(owner, deck, proposal, UUID.randomUUID()), 409, "GENERATION_STATE_CONFLICT");
        Proposal stale = fresh(owner, deck, proposal);
        assertThat(artifactState(stale.artifact())).isEqualTo("STALE");
        assertThat(handoff(owner, deck, stale, UUID.randomUUID()).getStatus()).isEqualTo(201);
        assertThat(artifactState(stale.artifact())).isEqualTo("HANDED_OFF");
    }

    // ---------------------------------------------------------- reject and undo

    @Test
    void rejectingAndTakingTheRejectionBackKeepTheSessionInReviewWhileSomethingIsLeftAndFollowTheContractsPreconditions() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID first = note(owner, deck, "первая");
        UUID second = note(owner, deck, "вторая");
        UUID session = start(owner, deck, spec(null, noteSource(first, 0), noteSource(second, 0)));
        awaitState(session, "REVIEW");
        Proposal one = proposals(owner, deck, session).get(0);

        problem(reject(owner, deck, one, UUID.randomUUID(), one.version() + 1), 412, "VERSION_CONFLICT");
        problem(send(owner, post(base(deck, session) + "/artifacts/" + one.artifact() + "/rejection").contentType("application/json")
                .content("{\"commandId\":\"" + UUID.randomUUID() + "\",\"expectedArtifactVersion\":\"x\"}")), 400, "INVALID_REQUEST");
        UUID command = UUID.randomUUID();
        MockHttpServletResponse rejected = reject(owner, deck, one, command, one.version());
        assertThat(rejected.getStatus()).isEqualTo(200);
        JsonNode summary = json(rejected);
        assertThat(summary.path("state").stringValue(null)).isEqualTo("REJECTED");
        assertThat(summary.path("rowVersion").stringValue(null)).isEqualTo(Long.toString(one.version() + 1));
        assertThat(rejected.getHeader("ETag")).isEqualTo("\"" + (one.version() + 1) + "\"");
        assertThat(sessionState(session)).isEqualTo("REVIEW");
        MockHttpServletResponse replay = reject(owner, deck, one, command, one.version());
        assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(json(replay)).isEqualTo(summary);
        // a second rejection with fresh versions is a state conflict
        Proposal now = fresh(owner, deck, one);
        problem(reject(owner, deck, now, UUID.randomUUID(), now.version()), 409, "GENERATION_STATE_CONFLICT");

        // undo: If-Match is the artifact version
        problem(undo(owner, deck, now, null), 428, "PRECONDITION_REQUIRED");
        problem(undo(owner, deck, now, "W/\"" + now.version() + "\""), 400, "INVALID_REQUEST");
        problem(undo(owner, deck, now, "\"" + (now.version() + 1) + "\""), 412, "VERSION_CONFLICT");
        MockHttpServletResponse undone = undo(owner, deck, now, "\"" + now.version() + "\"");
        assertThat(undone.getStatus()).isEqualTo(200);
        assertThat(json(undone).path("state").stringValue(null)).isEqualTo("PROPOSED");
        assertThat(undone.getHeader("ETag")).isEqualTo("\"" + (now.version() + 1) + "\"");
        Proposal back = fresh(owner, deck, one);
        problem(undo(owner, deck, back, "\"" + back.version() + "\""), 409, "GENERATION_STATE_CONFLICT");

        // rejecting every artifact closes the session; the undo still works there and reopens it
        for (Proposal p : proposals(owner, deck, session)) {
            assertThat(reject(owner, deck, p, UUID.randomUUID(), p.version()).getStatus()).isEqualTo(200);
        }
        assertThat(sessionState(session)).isEqualTo("CLOSED");
        jdbc.sql("UPDATE app_learning.generation_session SET last_activity_at=CURRENT_TIMESTAMP - interval '2 days',"
                + "expires_at=CURRENT_TIMESTAMP + interval '1 day' WHERE session_id=:id").param("id", session).update();
        Proposal last = fresh(owner, deck, one);
        MockHttpServletResponse reopened = undo(owner, deck, last, "\"" + last.version() + "\"");
        assertThat(reopened.getStatus()).isEqualTo(200);
        assertThat(sessionState(session)).isEqualTo("REVIEW");
        assertThat(jdbc.sql("SELECT expires_at>CURRENT_TIMESTAMP + interval '20 days' FROM app_learning.generation_session WHERE session_id=:id")
                .param("id", session).query(Boolean.class).single()).isTrue();
        List<String> tail = new ArrayList<>();
        json(events(owner, deck, session, "")).path("events").forEach(event -> tail.add(event.path("type").stringValue(null) + ":"
                + event.path("payload").path("state").stringValue("-")));
        assertThat(tail).endsWith("ARTIFACT_STATE:PROPOSED", "SESSION_STATE:REVIEW");
        // an expired session does not take it back
        Proposal again = fresh(owner, deck, one);
        assertThat(reject(owner, deck, again, UUID.randomUUID(), again.version()).getStatus()).isEqualTo(200);
        Proposal other = proposals(owner, deck, session).stream().filter(p -> !p.artifact().equals(one.artifact())).findFirst().orElseThrow();
        jdbc.sql("UPDATE app_learning.generation_session SET state='EXPIRED',end_reason='EXPIRED' WHERE session_id=:id").param("id", session).update();
        Proposal rejectedNow = fresh(owner, deck, one);
        problem(undo(owner, deck, rejectedNow, "\"" + rejectedNow.version() + "\""), 409, "GENERATION_STATE_CONFLICT");
        assertThat(other).isNotNull();
        assertThat(materials(owner, deck)).isZero();
    }

    // -------------------------------------------------------------------- retry

    @Test
    void retryingAFailedArtifactReservesAgainRunsItAndTheSessionGoesBackToReviewWithTheDebitOfTheRetryOnly() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        provider.outage = true;
        UUID session = start(owner, deck, spec("20 глаголов движения"));
        awaitState(session, "REVIEW");
        assertThat(artifactStates(session)).containsExactly("FAILED");
        assertThat(artifactErrors(session)).containsExactly("PROVIDER_UNAVAILABLE");
        assertThat(debits(owner)).isZero();
        Proposal failed = proposals(owner, deck, session).getFirst();
        assertThat(notificationKinds(owner)).containsExactly("GENERATION_FAILED");

        problem(retry(owner, deck, failed, UUID.randomUUID(), failed.version() + 1), 412, "VERSION_CONFLICT");
        provider.outage = false;
        UUID command = UUID.randomUUID();
        MockHttpServletResponse response = retry(owner, deck, failed, command, failed.version());
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        JsonNode queued = json(response);
        assertThat(queued.path("state").stringValue(null)).isEqualTo("QUEUED");
        assertThat(queued.path("errorCode").isNull()).isTrue();
        assertThat(response.getHeader("ETag")).isEqualTo("\"" + queued.path("rowVersion").stringValue(null) + "\"");
        MockHttpServletResponse replay = retry(owner, deck, failed, command, failed.version());
        assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(json(replay)).isEqualTo(queued);

        awaitState(session, "REVIEW");
        assertThat(artifactStates(session)).containsExactly("PROPOSED");
        assertThat(debits(owner)).isEqualTo(10);
        // the retry paid from its own reservation, which ended with the session's return to review; the session shows the sum
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.usage_reservation WHERE owner_id=:owner AND state='ACTIVE'").param("owner", owner)
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.usage_reservation WHERE owner_id=:owner AND scope='STEP' AND state='SETTLED'")
                .param("owner", owner).query(Integer.class).single()).isEqualTo(1);
        JsonNode detail = json(getSession(owner, deck, session));
        assertThat(detail.path("usage").path("spentCredits").intValue()).isEqualTo(10);
        assertThat(detail.path("usage").path("reservedCredits").intValue()).isZero();
        // the draft is a new generation of the same artifact: the checkpoints say so
        assertThat(jdbc.sql("SELECT draft_generation FROM app_learning.generation_artifact WHERE session_id=:id").param("id", session)
                .query(Integer.class).single()).isGreaterThan(1);
        Proposal proposed = proposals(owner, deck, session).getFirst();
        assertThat(approve(owner, deck, proposed, UUID.randomUUID()).getStatus()).isEqualTo(200);
    }

    @Test
    void aRetryThatDoesNotFitTheBudgetIsRefusedBeforeAnythingChangesAndTheOtherRefusalsFollowTheContract() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        provider.outage = true;
        UUID session = start(owner, deck, spec("20 глаголов движения"));
        awaitState(session, "REVIEW");
        provider.outage = false;
        Proposal failed = proposals(owner, deck, session).getFirst();
        int stepsBefore = jdbc.sql("SELECT count(*) FROM app_learning.generation_step WHERE session_id=:id").param("id", session)
                .query(Integer.class).single();
        long sessionVersion = jdbc.sql("SELECT row_version FROM app_learning.generation_session WHERE session_id=:id").param("id", session)
                .query(Long.class).single();

        // the account's credits are held elsewhere: 409 before any state change
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                ledger.reserve(owner, app.mnema.learning.usage.ReservationScope.SESSION, UUID.randomUUID(), null, 355));
        MockHttpServletResponse refused = retry(owner, deck, failed, UUID.randomUUID(), failed.version());
        problem(refused, 409, "USAGE_LIMIT_REACHED");
        assertThat(artifactStates(session)).containsExactly("FAILED");
        assertThat(sessionState(session)).isEqualTo("REVIEW");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_step WHERE session_id=:id").param("id", session).query(Integer.class)
                .single()).isEqualTo(stepsBefore);
        assertThat(jdbc.sql("SELECT row_version FROM app_learning.generation_session WHERE session_id=:id").param("id", session)
                .query(Long.class).single()).isEqualTo(sessionVersion);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.usage_reservation WHERE owner_id=:owner AND scope='STEP'").param("owner", owner)
                .query(Integer.class).single()).isZero();

        // a refusal is not retryable at all, whatever the budget
        jdbc.sql("UPDATE app_learning.generation_artifact SET error_code='REFUSAL' WHERE artifact_id=:id").param("id", failed.artifact()).update();
        MockHttpServletResponse refusal = retry(owner, deck, failed, UUID.randomUUID(), failed.version());
        problem(refusal, 409, "GENERATION_STATE_CONFLICT");
        assertThat(json(refusal).path("reason").stringValue(null)).isEqualTo("NOT_RETRYABLE");
    }

    @Test
    void aProposalAndAnyArtifactOfACancelledSessionAreNotRetryable() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, spec("20 глаголов движения"));
        problem(retry(owner, deck, proposal, UUID.randomUUID(), proposal.version()), 409, "GENERATION_STATE_CONFLICT");
        assertThat(artifactState(proposal.artifact())).isEqualTo("PROPOSED");

        provider.outage = true;
        UUID session = start(owner, deck, spec("20 глаголов движения"));
        awaitState(session, "REVIEW");
        provider.outage = false;
        assertThat(cancel(owner, deck, session, UUID.randomUUID()).getStatus()).isEqualTo(200);
        Proposal failed = proposals(owner, deck, session).getFirst();
        MockHttpServletResponse refused = retry(owner, deck, failed, UUID.randomUUID(), failed.version());
        problem(refused, 409, "GENERATION_STATE_CONFLICT");
        assertThat(json(refused).path("reason").stringValue(null)).isEqualTo("ILLEGAL_STATE");
    }

    @Test
    void retryingAStaleArtifactWritesItAgainAgainstTheNewNoteAndAFailedOneNeedsItsPinsToHold() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID note = note(owner, deck, "[[fake:audio]] первая версия заметки");
        Proposal proposal = proposal(owner, deck, audioSpec(null, noteSource(note, 0)));
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_media_slot WHERE artifact_id=:id").param("id", proposal.artifact())
                .query(Integer.class).single()).isEqualTo(1);
        jdbc.sql("UPDATE app_learning.capture_note SET row_version=row_version+1,note_text='[[fake:audio]] вторая версия',"
                + "updated_at=CURRENT_TIMESTAMP WHERE note_id=:id").param("id", note).update();
        problem(approve(owner, deck, proposal, UUID.randomUUID()), 409, "GENERATION_STATE_CONFLICT");
        Proposal stale = fresh(owner, deck, proposal);

        MockHttpServletResponse response = retry(owner, deck, stale, UUID.randomUUID(), stale.version());
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(json(response).path("state").stringValue(null)).isEqualTo("QUEUED");
        awaitState(proposal.session(), "REVIEW");
        Proposal rewritten = fresh(owner, deck, proposal);
        assertThat(artifactState(proposal.artifact())).isEqualTo("PROPOSED");
        assertThat(rewritten.revision()).isNotEqualTo(proposal.revision());
        JsonNode detail = json(send(owner, get(base(deck, proposal.session()) + "/artifacts/" + proposal.artifact())));
        assertThat(detail.path("sourceRefs").get(0).path("noteRowVersion").stringValue(null)).isEqualTo("1");
        // the earlier draft's revision is history, not listed (only the revisions a revert may restore are)
        assertThat(detail.path("revisions")).hasSize(1);
        assertThat(detail.path("repinStatus").isNull()).isTrue();
        assertThat(provider.calls.getLast().prompt()).contains("вторая версия");
        // the slots of the replaced revision are gone; the new revision has its own
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_media_slot WHERE artifact_id=:id AND revision_id=:revision")
                .param("id", proposal.artifact()).param("revision", rewritten.revision()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_media_slot WHERE artifact_id=:id").param("id", proposal.artifact())
                .query(Integer.class).single()).isEqualTo(1);

        // a failed artifact does not re-pin: its note moved, so the pin no longer holds
        provider.outage = true;
        UUID other = note(owner, deck, "другая заметка");
        UUID session = start(owner, deck, spec(null, noteSource(other, 0)));
        awaitState(session, "REVIEW");
        provider.outage = false;
        Proposal failed = proposals(owner, deck, session).getFirst();
        jdbc.sql("UPDATE app_learning.capture_note SET row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE note_id=:id").param("id", other).update();
        MockHttpServletResponse moved = retry(owner, deck, failed, UUID.randomUUID(), failed.version());
        problem(moved, 409, "SOURCE_UNAVAILABLE");
        assertThat(json(moved).path("sources").get(0).path("id").stringValue(null)).isEqualTo(other.toString());
        // and a deleted note is gone for good
        jdbc.sql("DELETE FROM app_learning.capture_note WHERE note_id=:id").param("id", other).update();
        problem(retry(owner, deck, failed, UUID.randomUUID(), failed.version()), 409, "SOURCE_UNAVAILABLE");
        assertThat(artifactStates(session)).containsExactly("FAILED");
    }

    // ------------------------------------------------------------------- delete

    @Test
    void deletingASessionRemovesTheUnpublishedRowsAndHoldsAndKeepsWhatWasPublishedAndAnswersNotFoundAfterwards() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID first = note(owner, deck, "первая");
        UUID second = note(owner, deck, "[[fake:audio]] вторая с аудио");
        UUID session = start(owner, deck, audioSpec(null, noteSource(first, 0), noteSource(second, 0)));
        awaitState(session, "REVIEW");
        List<Proposal> all = proposals(owner, deck, session);
        UUID asset = readyAsset(owner, all.get(1).artifact());
        assertThat(approve(owner, deck, all.get(0), UUID.randomUUID()).getStatus()).isEqualTo(200);
        assertThat(sessionState(session)).isEqualTo("REVIEW");
        problem(deleteSession(UUID.randomUUID(), deck, session), 404, "RESOURCE_NOT_FOUND");
        problem(send(owner, delete(base(deck, session) + "?x=1")), 400, "INVALID_REQUEST");

        MockHttpServletResponse deleted = deleteSession(owner, deck, session);
        assertThat(deleted.getStatus()).isEqualTo(204);
        assertThat(deleted.getHeader("Cache-Control")).isEqualTo("private, no-store");
        problem(deleteSession(owner, deck, session), 404, "RESOURCE_NOT_FOUND");
        problem(getSession(owner, deck, session), 404, "RESOURCE_NOT_FOUND");
        problem(events(owner, deck, session, ""), 404, "RESOURCE_NOT_FOUND");
        problem(send(owner, get(base(deck, session) + "/artifacts/" + all.get(0).artifact())), 404, "RESOURCE_NOT_FOUND");
        for (String table : List.of("generation_session", "generation_session_source", "generation_artifact", "generation_artifact_revision",
                "generation_media_slot", "generation_media_ref", "generation_step", "generation_event")) {
            assertThat(jdbc.sql("SELECT count(*) FROM app_learning." + table + " WHERE session_id=:id").param("id", session)
                    .query(Integer.class).single()).as(table).isZero();
        }
        // the published material and its provenance stay, and so does the asset the unpublished artifact had
        assertThat(materials(owner, deck)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_provenance WHERE session_id=:id").param("id", session)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT state FROM app_learning.media_asset WHERE asset_id=:id").param("id", asset).query(String.class).single())
                .isEqualTo("READY");
        // the session's hold ended with the session; what it had debited stays in the ledger
        assertThat(reservationsOf(owner)).doesNotContain("ACTIVE");
        assertThat(debits(owner)).isEqualTo(20);
    }

    @Test
    void deletingARunningSessionStopsItsWorkAndReleasesTheReservationWithoutAnyDebit() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID session = start(owner, deck, spec("[[fake:block]] долго"));
        assertThat(provider.blockedEntered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(deleteSession(owner, deck, session).getStatus()).isEqualTo(204);
        provider.release.countDown();
        Thread.sleep(600);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_artifact WHERE session_id=:id").param("id", session)
                .query(Integer.class).single()).isZero();
        assertThat(debits(owner)).isZero();
        assertThat(reservationsOf(owner)).containsExactly("RELEASED");
        assertThat(notificationKinds(owner)).isEmpty();
    }


    // -------------------------------------------------------------- note archival

    @Test
    void archivingUsedNotesArchivesExactlyTheOnesOfPublishedAndHandedOffArtifactsByTheirPinsAndIsIdempotent() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID published = note(owner, deck, "опубликованная");
        UUID changed = note(owner, deck, "изменённая после");
        UUID handed = note(owner, deck, "в черновик");
        UUID pending = note(owner, deck, "ещё не одобрена");
        UUID session = start(owner, deck, spec(null, noteSource(published, 0), noteSource(changed, 0), noteSource(handed, 0), noteSource(pending, 0)));
        awaitState(session, "REVIEW");
        assertThat(json(getSession(owner, deck, session)).path("notes").path("used").intValue()).isZero();
        assertThat(json(getSession(owner, deck, session)).path("notes").path("archivable").intValue()).isZero();
        List<Proposal> all = proposals(owner, deck, session);
        ObjectNode bulk = bulkBody(UUID.randomUUID(), deckRevision(deck), all.subList(0, 2));
        assertThat(approveMany(owner, deck, session, bulk, "\"" + deckVersion(deck) + "\"").getStatus()).isEqualTo(200);
        assertThat(handoff(owner, deck, fresh(owner, deck, all.get(2)), UUID.randomUUID()).getStatus()).isEqualTo(201);

        JsonNode notes = json(getSession(owner, deck, session)).path("notes");
        assertThat(notes.path("used").intValue()).isEqualTo(3);
        assertThat(notes.path("archivable").intValue()).isEqualTo(3);
        // one of them changes after the session read it: it is skipped, never archived silently
        jdbc.sql("UPDATE app_learning.capture_note SET row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE note_id=:id").param("id", changed).update();
        assertThat(json(getSession(owner, deck, session)).path("notes").path("archivable").intValue()).isEqualTo(2);

        UUID command = UUID.randomUUID();
        MockHttpServletResponse response = archiveNotes(owner, deck, session, command);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
        JsonNode result = json(response);
        List<String> archived = new ArrayList<>();
        result.path("archived").forEach(entry -> archived.add(entry.path("noteId").stringValue(null)));
        assertThat(archived).containsExactlyInAnyOrder(published.toString(), handed.toString());
        assertThat(result.path("skipped")).hasSize(1);
        assertThat(result.path("skipped").get(0).path("noteId").stringValue(null)).isEqualTo(changed.toString());
        assertThat(result.path("skipped").get(0).path("reason").stringValue(null)).isEqualTo("CHANGED");
        assertThat(archivedNotes(owner, deck)).containsExactlyInAnyOrder(published, handed);
        assertThat(archivedNotes(owner, deck)).doesNotContain(changed, pending);
        // archiving moved the notes' row versions: one each
        assertThat(jdbc.sql("SELECT row_version FROM app_learning.capture_note WHERE note_id=:id").param("id", published).query(Long.class).single()).isEqualTo(1);

        // the exact retry is the stored answer; a new command finds everything already done
        MockHttpServletResponse replay = archiveNotes(owner, deck, session, command);
        assertThat(replay.getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(json(replay)).isEqualTo(result);
        MockHttpServletResponse again = archiveNotes(owner, deck, session, UUID.randomUUID());
        assertThat(again.getStatus()).isEqualTo(200);
        assertThat(json(again).path("archived")).isEmpty();
        List<String> reasons = new ArrayList<>();
        json(again).path("skipped").forEach(entry -> reasons.add(entry.path("reason").stringValue(null)));
        assertThat(reasons).containsExactlyInAnyOrder("ALREADY_ARCHIVED", "ALREADY_ARCHIVED", "CHANGED");
        assertThat(json(getSession(owner, deck, session)).path("notes").path("archivable").intValue()).isZero();
        assertThat(json(getSession(owner, deck, session)).path("notes").path("used").intValue()).isEqualTo(3);

        // a deleted note is reported, the command with another body under the same id is a conflict, the opaque 404 holds
        jdbc.sql("DELETE FROM app_learning.capture_note WHERE note_id=:id").param("id", changed).update();
        JsonNode deleted = json(archiveNotes(owner, deck, session, UUID.randomUUID()));
        assertThat(deleted.toString()).contains("DELETED");
        problem(send(owner, post(base(deck, UUID.randomUUID()) + "/note-archival").contentType("application/json").content("{\"commandId\":\""
                + UUID.randomUUID() + "\"}")), 404, "RESOURCE_NOT_FOUND");
        problem(archiveNotes(UUID.randomUUID(), deck, session, UUID.randomUUID()), 404, "RESOURCE_NOT_FOUND");
        problem(send(owner, post(base(deck, session) + "/note-archival").contentType("application/json").content("{\"commandId\":\"" + UUID.randomUUID()
                + "\",\"x\":1}")), 400, "INVALID_REQUEST");
        UUID otherSession = start(owner, deck, spec("20 глаголов движения"));
        problem(archiveNotes(owner, deck, otherSession, command), 409, "IDEMPOTENCY_CONFLICT");
        // a session without notes archives nothing and says so
        JsonNode none = json(archiveNotes(owner, deck, otherSession, UUID.randomUUID()));
        assertThat(none.path("archived")).isEmpty();
        assertThat(none.path("skipped")).isEmpty();
    }


    // ------------------------------------------------------------- session states

    @Test
    void aProposalCanBeApprovedWhileTheSessionIsStillRunningAndTheSessionCarriesOnToReview() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID quick = note(owner, deck, "быстрая заметка");
        UUID slow = note(owner, deck, "[[fake:block]] медленная заметка");
        UUID session = start(owner, deck, spec(null, noteSource(quick, 0), noteSource(slow, 0)));
        await("the quick artifact to be proposed", java.time.Duration.ofSeconds(20), () -> artifactStates(session).contains("PROPOSED"));
        assertThat(sessionState(session)).isEqualTo("RUNNING");
        Proposal proposed = proposals(owner, deck, session).stream().filter(p -> p.revision() != null).findFirst().orElseThrow();

        assertThat(approve(owner, deck, proposed, UUID.randomUUID()).getStatus()).isEqualTo(200);
        assertThat(sessionState(session)).isEqualTo("RUNNING");
        assertThat(materials(owner, deck)).isEqualTo(1);
        provider.release.countDown();
        awaitState(session, "REVIEW");
        assertThat(artifactStates(session)).containsExactlyInAnyOrder("PUBLISHED", "PROPOSED");
    }

    @Test
    void aCancelledSessionKeepsItsProposalsApprovableAndClosesWhenNoneIsLeft() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID quick = note(owner, deck, "быстрая заметка");
        UUID slow = note(owner, deck, "[[fake:block]] медленная заметка");
        UUID session = start(owner, deck, spec(null, noteSource(quick, 0), noteSource(slow, 0)));
        await("the quick artifact to be proposed", java.time.Duration.ofSeconds(20), () -> artifactStates(session).contains("PROPOSED"));
        assertThat(cancel(owner, deck, session, UUID.randomUUID()).getStatus()).isEqualTo(200);
        provider.release.countDown();
        assertThat(sessionState(session)).isEqualTo("CANCELLED");
        Proposal proposed = proposals(owner, deck, session).stream().filter(p -> p.revision() != null).findFirst().orElseThrow();
        // reject and undo and approve all still work; a retry does not (nothing new is generated)
        Proposal failed = proposals(owner, deck, session).stream().filter(p -> p.revision() == null).findFirst().orElseThrow();
        problem(retry(owner, deck, failed, UUID.randomUUID(), failed.version()), 409, "GENERATION_STATE_CONFLICT");
        assertThat(reject(owner, deck, proposed, UUID.randomUUID(), proposed.version()).getStatus()).isEqualTo(200);
        assertThat(sessionState(session)).isEqualTo("CLOSED");
        // the undo reopens the session; it ended through cancellation, so it is CANCELLED again
        assertThat(undo(owner, deck, fresh(owner, deck, proposed), "\"" + (proposed.version() + 1) + "\"").getStatus()).isEqualTo(200);
        assertThat(sessionState(session)).isEqualTo("CANCELLED");
    }

    @Test
    void aNoteThatASiblingArtifactStillPinsIsNotUsedAndIsNeitherCountedNorArchived() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID shared = note(owner, deck, "общая заметка");
        UUID own = note(owner, deck, "своя заметка");
        UUID session = start(owner, deck, spec(null, noteSource(shared, 0), noteSource(own, 0)));
        awaitState(session, "REVIEW");
        List<Proposal> all = proposals(owner, deck, session);
        // the second artifact also pins the first one's note (as a regenerated or merged sibling would)
        jdbc.sql("UPDATE app_learning.generation_artifact SET source_refs=source_refs || jsonb_build_array(jsonb_build_object("
                + "'type','NOTE','noteId',CAST(:note AS text),'noteRowVersion','0')) WHERE artifact_id=:id")
                .param("note", shared.toString()).param("id", all.get(1).artifact()).update();
        assertThat(approve(owner, deck, all.get(0), UUID.randomUUID()).getStatus()).isEqualTo(200);
        assertThat(json(getSession(owner, deck, session)).path("notes").path("used").intValue()).isZero();
        JsonNode none = json(archiveNotes(owner, deck, session, UUID.randomUUID()));
        assertThat(none.path("archived")).isEmpty();
        assertThat(none.path("skipped")).isEmpty();
        assertThat(archivedNotes(owner, deck)).isEmpty();
        assertThat(artifactState(all.get(1).artifact())).isEqualTo("PROPOSED");

        // once the sibling has left too, both notes are used and archivable
        Proposal sibling = fresh(owner, deck, all.get(1));
        assertThat(approve(owner, deck, sibling, UUID.randomUUID()).getStatus()).isEqualTo(200);
        assertThat(json(getSession(owner, deck, session)).path("notes").path("used").intValue()).isEqualTo(2);
        assertThat(json(archiveNotes(owner, deck, session, UUID.randomUUID())).path("archived")).hasSize(2);
    }

    @Test
    void handingOffStopsTheArtifactsMediaWorkAndSettlesItsOpenSlots() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, audioSpec("[[fake:audio]] с аудио"));
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_step WHERE artifact_id=:id AND state='READY' AND kind='TTS'")
                .param("id", proposal.artifact()).query(Integer.class).single()).isEqualTo(1);
        assertThat(handoff(owner, deck, proposal, UUID.randomUUID()).getStatus()).isEqualTo(201);
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.generation_step WHERE artifact_id=:id AND state='CANCELLED' AND kind='TTS'")
                .param("id", proposal.artifact()).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT state||':'||error_code FROM app_learning.generation_media_slot WHERE artifact_id=:id")
                .param("id", proposal.artifact()).query(String.class).single()).isEqualTo("FAILED:CANCELLED");
        List<String> slotEvents = new ArrayList<>();
        json(events(owner, deck, proposal.session(), "")).path("events").forEach(event -> {
            if (event.path("type").stringValue("").equals("MEDIA_SLOT_STATE")) slotEvents.add(event.path("payload").path("state").stringValue(null));
        });
        assertThat(slotEvents).endsWith("FAILED");
    }

    @Test
    void retryingALeftoverSessionIsRefusedWhenItWouldExceedTheActiveSessionLimitBeforeAnyUsage() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        provider.outage = true;
        UUID leftover = start(owner, deck, spec("20 глаголов движения"));
        awaitState(leftover, "REVIEW");
        provider.outage = false;
        Proposal failed = proposals(owner, deck, leftover).getFirst();
        // three live sessions with something to review fill the limit; the leftover one did not count
        for (int i = 0; i < 3; i++) proposal(owner, deck, spec("20 глаголов движения"));
        MockHttpServletResponse refused = retry(owner, deck, failed, UUID.randomUUID(), failed.version());
        problem(refused, 422, "RESOURCE_LIMIT_EXCEEDED");
        assertThat(json(refused).path("limit").stringValue(null)).isEqualTo("ACTIVE_SESSIONS");
        assertThat(json(refused).path("activeSessionIds")).hasSize(3);
        assertThat(artifactStates(leftover)).containsExactly("FAILED");
        assertThat(sessionState(leftover)).isEqualTo("REVIEW");
        assertThat(jdbc.sql("SELECT count(*) FROM app_learning.usage_reservation WHERE owner_id=:owner AND scope='STEP'").param("owner", owner)
                .query(Integer.class).single()).isZero();
    }

    @Test
    void aCancelledSessionClosesWhenItsLastProposalIsApproved() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID quick = note(owner, deck, "быстрая заметка");
        UUID slow = note(owner, deck, "[[fake:block]] медленная заметка");
        UUID session = start(owner, deck, spec(null, noteSource(quick, 0), noteSource(slow, 0)));
        await("the quick artifact to be proposed", java.time.Duration.ofSeconds(20), () -> artifactStates(session).contains("PROPOSED"));
        assertThat(cancel(owner, deck, session, UUID.randomUUID()).getStatus()).isEqualTo(200);
        provider.release.countDown();
        Proposal proposed = proposals(owner, deck, session).stream().filter(p -> p.revision() != null).findFirst().orElseThrow();
        assertThat(approve(owner, deck, proposed, UUID.randomUUID()).getStatus()).isEqualTo(200);
        assertThat(sessionState(session)).isEqualTo("CLOSED");
        assertThat(materials(owner, deck)).isEqualTo(1);
    }
}
