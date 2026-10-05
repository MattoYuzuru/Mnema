package app.mnema.learning.generation;

import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Requests, documents and waiting of the edit tests (#293): edits, reverts, the blocks of a proposal and the rows behind them. */
abstract class GenerationEditsSupport extends GenerationReviewSupport {
    // ------------------------------------------------------------------ requests

    protected ObjectNode editBody(UUID command, UUID revision, String action, String preset, String instruction, UUID... nodes) {
        ObjectNode body = JSON.createObjectNode().put("commandId", command.toString()).put("expectedRevisionId", revision.toString())
                .put("action", action);
        if (preset != null) body.put("preset", preset);
        if (instruction != null) body.put("instruction", instruction);
        ArrayNode ids = body.putObject("target").putArray("nodeIds");
        for (UUID node : nodes) ids.add(node.toString());
        return body;
    }

    protected MockHttpServletResponse edit(UUID owner, UUID deck, Proposal proposal, ObjectNode body) throws Exception {
        return send(owner, post(base(deck, proposal.session()) + "/artifacts/" + proposal.artifact() + "/edits")
                .contentType("application/json").content(body.toString()));
    }

    protected MockHttpServletResponse revert(UUID owner, UUID deck, Proposal proposal, long version, UUID to) throws Exception {
        return send(owner, post(base(deck, proposal.session()) + "/artifacts/" + proposal.artifact() + "/revert")
                .contentType("application/json").content("{\"commandId\":\"" + UUID.randomUUID() + "\",\"expectedArtifactVersion\":\""
                        + version + "\",\"toRevisionId\":\"" + to + "\"}"));
    }

    /** Edits and returns the accepted turn, failing the test unless the answer is 202. */
    protected UUID accepted(UUID owner, UUID deck, Proposal proposal, ObjectNode body) throws Exception {
        MockHttpServletResponse response = edit(owner, deck, proposal, body);
        if (response.getStatus() != 202) throw new AssertionError("editArtifact answered " + response.getStatus() + ": " + response.getContentAsString());
        return UUID.fromString(json(response).path("turn").path("turnId").stringValue(null));
    }

    // ----------------------------------------------------------------- documents

    protected JsonNode detail(UUID owner, UUID deck, Proposal proposal) throws Exception {
        return detail(owner, deck, proposal, "");
    }

    protected JsonNode detail(UUID owner, UUID deck, Proposal proposal, String query) throws Exception {
        MockHttpServletResponse response = send(owner, get(base(deck, proposal.session()) + "/artifacts/" + proposal.artifact() + query));
        if (response.getStatus() != 200) throw new AssertionError("getArtifact answered " + response.getStatus() + ": " + response.getContentAsString());
        return json(response);
    }

    protected static List<JsonNode> blocks(JsonNode detail) {
        List<JsonNode> blocks = new ArrayList<>();
        detail.path("revision").path("payload").path("document").path("root").path("content").forEach(blocks::add);
        return blocks;
    }

    protected static UUID id(JsonNode node) {
        return UUID.fromString(node.path("id").stringValue(null));
    }

    protected static String text(JsonNode node) {
        StringBuilder text = new StringBuilder();
        collect(node, text);
        return text.toString();
    }

    private static void collect(JsonNode node, StringBuilder text) {
        if (node.path("type").stringValue("").equals("text")) text.append(node.path("attrs").path("text").stringValue(""));
        node.path("content").forEach(child -> collect(child, text));
    }

    /** The revision in force now and the version the client would send back. */
    protected Proposal current(UUID owner, UUID deck, Proposal proposal) throws Exception {
        return fresh(owner, deck, proposal);
    }

    // -------------------------------------------------------------------- waiting

    protected void awaitArtifact(UUID artifact, String state) throws InterruptedException {
        await("artifact " + artifact + " to be " + state, Duration.ofSeconds(20), () -> artifactState(artifact).equals(state));
    }

    protected String turnStatus(UUID turn) {
        return jdbc.sql("SELECT status FROM app_learning.generation_artifact_turn WHERE turn_id=:id").param("id", turn)
                .query(String.class).single();
    }

    protected String turnError(UUID turn) {
        return jdbc.sql("SELECT COALESCE(error_code,'-') FROM app_learning.generation_artifact_turn WHERE turn_id=:id").param("id", turn)
                .query(String.class).single();
    }

    protected void awaitTurn(UUID turn, String status) throws InterruptedException {
        await("turn " + turn + " to be " + status, Duration.ofSeconds(20), () -> turnStatus(turn).equals(status));
    }

    /** The turn's reservation, which the step input names. */
    protected String reservationOfTurn(UUID turn) {
        return jdbc.sql("SELECT r.state FROM app_learning.usage_reservation r WHERE r.turn_id=:id").param("id", turn)
                .query(String.class).single();
    }

    protected int revisions(UUID artifact) {
        return jdbc.sql("SELECT count(*)::integer FROM app_learning.generation_artifact_revision WHERE artifact_id=:id")
                .param("id", artifact).query(Integer.class).single();
    }

    /** Provider calls of EDIT steps only (the draft's own call is not one). */
    protected List<GenerationTestConfiguration.Call> editCalls(UUID owner) {
        return calls(owner).stream().filter(call -> call.prompt().contains("<task kind=\"edit\">")).toList();
    }

    // ------------------------------------------------------------ built documents

    protected static ObjectNode node(String type, ObjectNode attrs, JsonNode... children) {
        ObjectNode node = JSON.createObjectNode().put("id", UUID.randomUUID().toString()).put("type", type).put("version", 1);
        node.set("attrs", attrs);
        ArrayNode content = node.putArray("content");
        for (JsonNode child : children) content.add(child);
        return node;
    }

    protected static ObjectNode paragraph(String text) {
        ObjectNode attrs = JSON.createObjectNode().put("text", text);
        attrs.putArray("marks");
        return node("paragraph", JSON.createObjectNode(), node("text", attrs));
    }

    protected static ObjectNode heading(int level, String text) {
        ObjectNode attrs = JSON.createObjectNode().put("text", text);
        attrs.putArray("marks");
        return node("heading", JSON.createObjectNode().put("level", level), node("text", attrs));
    }

    protected static ObjectNode document(JsonNode... blocks) {
        ObjectNode document = JSON.createObjectNode().put("formatVersion", 1);
        document.set("root", node("doc", JSON.createObjectNode(), blocks));
        return document;
    }

    /**
     * Stores {@code document} as a new revision of the artifact (cause EDIT) and makes it the current one, as an earlier edit would
     * have: for the tests that need a document the Stub never writes (a long one, one MBM cannot express, one with an image).
     */
    protected Proposal withDocument(UUID owner, Proposal proposal, JsonNode document) throws Exception {
        UUID revision = UUID.randomUUID();
        int number = revisions(proposal.artifact()) + 1;
        ObjectNode payload = JSON.createObjectNode().put("kind", "NATIVE_DOCUMENT");
        payload.set("document", document);
        jdbc.sql("INSERT INTO app_learning.generation_artifact_revision(revision_id,artifact_id,session_id,owner_id,revision_no,cause,"
                        + "payload,handles,prompt_version,model_route,validation,created_at) VALUES (:id,:artifact,:session,:owner,:no,'EDIT',"
                        + "CAST(:payload AS jsonb),'{}'::jsonb,'v1','stub:stub','{\"warnings\":[]}'::jsonb,CURRENT_TIMESTAMP)")
                .param("id", revision).param("artifact", proposal.artifact()).param("session", proposal.session()).param("owner", owner)
                .param("no", number).param("payload", payload.toString()).update();
        jdbc.sql("UPDATE app_learning.generation_artifact SET current_revision_id=:revision,revision_count=:no,row_version=row_version+1 "
                + "WHERE artifact_id=:artifact").param("revision", revision).param("no", number).param("artifact", proposal.artifact()).update();
        return fresh(owner, jdbcDeck(proposal), proposal);
    }

    protected UUID jdbcDeck(Proposal proposal) {
        return jdbc.sql("SELECT deck_id FROM app_learning.generation_session WHERE session_id=:id").param("id", proposal.session())
                .query(UUID.class).single();
    }
}
