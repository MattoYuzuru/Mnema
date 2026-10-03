package app.mnema.learning.generation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Fixtures and requests of the review commands, shared by the review, retention and contract tests. */
abstract class GenerationReviewSupport extends GenerationIntegrationTest {
    @Autowired protected app.mnema.learning.generation.GeneratedDraftOpener draftOpener;

    /** A proposed artifact as the client holds it: ids and the versions it would send back. */
    record Proposal(UUID session, UUID artifact, UUID revision, long version) { }

    // ------------------------------------------------------------------ helpers

    protected Proposal proposal(UUID owner, UUID deck, ObjectNode spec) throws Exception {
        UUID session = start(owner, deck, spec);
        awaitState(session, "REVIEW");
        return proposals(owner, deck, session).getFirst();
    }

    protected List<Proposal> proposals(UUID owner, UUID deck, UUID session) throws Exception {
        List<Proposal> result = new ArrayList<>();
        for (JsonNode artifact : json(getSession(owner, deck, session)).path("artifacts")) {
            result.add(new Proposal(session, UUID.fromString(artifact.path("artifactId").stringValue(null)),
                    artifact.path("currentRevisionId").isNull() ? null : UUID.fromString(artifact.path("currentRevisionId").stringValue(null)),
                    Long.parseLong(artifact.path("rowVersion").stringValue(null))));
        }
        return result;
    }

    /** The proposal as it is now (the version moves with every change of the artifact). */
    protected Proposal fresh(UUID owner, UUID deck, Proposal old) throws Exception {
        return proposals(owner, deck, old.session()).stream().filter(p -> p.artifact().equals(old.artifact())).findFirst().orElseThrow();
    }

    protected long deckVersion(UUID deck) {
        return jdbc.sql("SELECT row_version FROM app_learning.deck WHERE deck_id=:id").param("id", deck).query(Long.class).single();
    }

    protected UUID deckRevision(UUID deck) {
        return jdbc.sql("SELECT head_revision_id FROM app_learning.deck WHERE deck_id=:id").param("id", deck).query(UUID.class).single();
    }

    protected int materials(UUID owner, UUID deck) {
        return items.list(owner, deck, null, null).path("total").intValue();
    }

    protected String artifactState(UUID artifact) {
        return jdbc.sql("SELECT state FROM app_learning.generation_artifact WHERE artifact_id=:id").param("id", artifact)
                .query(String.class).single();
    }

    protected static String base(UUID deck, UUID session) {
        return "/decks/" + deck + "/generation-sessions/" + session;
    }

    protected ObjectNode approvalBody(UUID command, Proposal proposal, UUID deckRevision) {
        return JSON.createObjectNode().put("commandId", command.toString())
                .put("expectedArtifactVersion", Long.toString(proposal.version()))
                .put("expectedRevisionId", proposal.revision().toString()).put("expectedDeckRevisionId", deckRevision.toString());
    }

    protected MockHttpServletResponse approve(UUID owner, UUID deck, Proposal proposal, UUID command) throws Exception {
        return approve(owner, deck, proposal, approvalBody(command, proposal, deckRevision(deck)), "\"" + deckVersion(deck) + "\"");
    }

    protected MockHttpServletResponse approve(UUID owner, UUID deck, Proposal proposal, ObjectNode body, String ifMatch) throws Exception {
        MockHttpServletRequestBuilder request = post(base(deck, proposal.session()) + "/artifacts/" + proposal.artifact() + "/approval")
                .contentType("application/json").content(body.toString());
        if (ifMatch != null) request.header("If-Match", ifMatch);
        return send(owner, request);
    }

    protected ObjectNode bulkBody(UUID command, UUID deckRevision, List<Proposal> proposals) {
        ObjectNode body = JSON.createObjectNode().put("commandId", command.toString()).put("expectedDeckRevisionId", deckRevision.toString());
        ArrayNode list = body.putArray("artifacts");
        for (Proposal p : proposals) {
            list.addObject().put("artifactId", p.artifact().toString()).put("expectedArtifactVersion", Long.toString(p.version()))
                    .put("expectedRevisionId", p.revision().toString());
        }
        return body;
    }

    protected MockHttpServletResponse approveMany(UUID owner, UUID deck, UUID session, ObjectNode body, String ifMatch) throws Exception {
        MockHttpServletRequestBuilder request = post(base(deck, session) + "/approvals").contentType("application/json").content(body.toString());
        if (ifMatch != null) request.header("If-Match", ifMatch);
        return send(owner, request);
    }

    protected MockHttpServletResponse reject(UUID owner, UUID deck, Proposal proposal, UUID command, long version) throws Exception {
        return send(owner, post(base(deck, proposal.session()) + "/artifacts/" + proposal.artifact() + "/rejection")
                .contentType("application/json").content("{\"commandId\":\"" + command + "\",\"expectedArtifactVersion\":\"" + version + "\"}"));
    }

    protected MockHttpServletResponse undo(UUID owner, UUID deck, Proposal proposal, String ifMatch) throws Exception {
        MockHttpServletRequestBuilder request = delete(base(deck, proposal.session()) + "/artifacts/" + proposal.artifact() + "/rejection");
        if (ifMatch != null) request.header("If-Match", ifMatch);
        return send(owner, request);
    }

    protected MockHttpServletResponse handoff(UUID owner, UUID deck, Proposal proposal, UUID command) throws Exception {
        return send(owner, post(base(deck, proposal.session()) + "/artifacts/" + proposal.artifact() + "/handoff")
                .contentType("application/json").content("{\"commandId\":\"" + command + "\",\"expectedArtifactVersion\":\"" + proposal.version()
                        + "\",\"expectedRevisionId\":\"" + proposal.revision() + "\"}"));
    }

    protected MockHttpServletResponse retry(UUID owner, UUID deck, Proposal proposal, UUID command, long version) throws Exception {
        return send(owner, post(base(deck, proposal.session()) + "/artifacts/" + proposal.artifact() + "/retry")
                .contentType("application/json").content("{\"commandId\":\"" + command + "\",\"expectedArtifactVersion\":\"" + version + "\"}"));
    }

    protected MockHttpServletResponse archiveNotes(UUID owner, UUID deck, UUID session, UUID command) throws Exception {
        return send(owner, post(base(deck, session) + "/note-archival").contentType("application/json")
                .content("{\"commandId\":\"" + command + "\"}"));
    }

    protected MockHttpServletResponse deleteSession(UUID owner, UUID deck, UUID session) throws Exception {
        return send(owner, delete(base(deck, session)));
    }

    protected static ObjectNode audioSpec(String prompt, ObjectNode... sources) {
        ObjectNode spec = spec(prompt, sources);
        ((ObjectNode) spec.path("settings")).putObject("media").putObject("audio").put("enabled", true).put("lang", "ja");
        return spec;
    }

    protected static void problem(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(json(response).path("code").stringValue(null)).isEqualTo(code);
    }

    /** A READY audio asset under the id the compiler pre-allocated for the slot, held by the session like the media step will. */
    protected UUID readyAsset(UUID owner, UUID artifact) {
        var slot = jdbc.sql("SELECT slot_key,node_id,asset_id,session_id FROM app_learning.generation_media_slot WHERE artifact_id=:id")
                .param("id", artifact).query((row, ignored) -> new UUID[] {row.getObject("node_id", UUID.class),
                        row.getObject("asset_id", UUID.class), row.getObject("session_id", UUID.class)}).single();
        jdbc.sql("INSERT INTO app_learning.media_asset(asset_id,owner_id,upload_intent_id,origin,created_at,updated_at) "
                        + "VALUES (:asset,:owner,gen_random_uuid(),'import',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
                .param("asset", slot[1]).param("owner", owner).update();
        fixtures.ready(slot[1], "audio/mpeg");
        jdbc.sql("UPDATE app_learning.generation_media_slot SET state='READY' WHERE artifact_id=:id").param("id", artifact).update();
        jdbc.sql("INSERT INTO app_learning.generation_media_ref(artifact_id,node_id,session_id,owner_id,asset_id) "
                        + "VALUES (:artifact,:node,:session,:owner,:asset)")
                .param("artifact", artifact).param("node", slot[0]).param("session", slot[2]).param("owner", owner)
                .param("asset", slot[1]).update();
        return slot[1];
    }

    protected List<String> reservationsOf(UUID owner) {
        return jdbc.sql("SELECT state FROM app_learning.usage_reservation WHERE owner_id=:owner ORDER BY created_at").param("owner", owner)
                .query(String.class).list();
    }

    protected List<UUID> archivedNotes(UUID owner, UUID deck) {
        return jdbc.sql("SELECT note_id FROM app_learning.capture_note WHERE owner_id=:owner AND deck_id=:deck AND archived")
                .param("owner", owner).param("deck", deck).query(UUID.class).list();
    }
}
