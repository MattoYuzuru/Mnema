package app.mnema.learning.generation;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The review operations against {@code contracts/generation/http.json}: the examples are executable expectations (every
 * answer has exactly the members of its example, recursively), and so are the statuses and headers each operation lists.
 */
class GenerationReviewContractTest extends GenerationReviewSupport {
    private static final JsonNode HTTP = http();

    private static JsonNode http() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/generation/http.json"))) root = root.getParent();
        try {
            return JSON.readTree(Files.readString(root.resolve("contracts/generation/http.json")));
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static JsonNode operation(String id) {
        for (JsonNode endpoint : HTTP.path("endpoints")) if (endpoint.path("operationId").stringValue("").equals(id)) return endpoint;
        throw new AssertionError("No operation " + id);
    }

    private static JsonNode example(String name) {
        return HTTP.path("examples").path(name);
    }

    /** The success body of an operation: an inline object or a {@code $ref} to an example. */
    private static JsonNode body(JsonNode success) {
        JsonNode body = success.path("body");
        if (body.has("$ref")) return example(body.path("$ref").stringValue("").substring("#/examples/".length()));
        return body;
    }

    /**
     * The same members at every object, the same kind of value at every scalar; a null on either side is a nullable member and
     * matches anything. Arrays are compared by their first element.
     */
    private static void sameShape(JsonNode expected, JsonNode actual, String path) {
        if (expected.isNull() || actual.isNull()) return;
        if (expected.isObject()) {
            assertThat(actual.isObject()).as(path + " is an object").isTrue();
            assertThat(new TreeSet<>(expected.propertyNames())).as(path + " members").isEqualTo(new TreeSet<>(actual.propertyNames()));
            expected.propertyNames().forEach(name -> sameShape(expected.path(name), actual.path(name), path + "/" + name));
        } else if (expected.isArray()) {
            assertThat(actual.isArray()).as(path + " is an array").isTrue();
            if (!expected.isEmpty() && !actual.isEmpty()) sameShape(expected.get(0), actual.get(0), path + "[0]");
        } else {
            assertThat(actual.getNodeType()).as(path + " kind").isEqualTo(expected.getNodeType());
        }
    }

    private static void listsStatus(String operation, int status) {
        assertThat(operation(operation).path("success").path("status").intValue()).isEqualTo(status);
    }

    @Test
    void everyReviewAnswerHasTheMembersOfItsExampleAndTheStatusAndHeadersTheOperationLists() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        UUID noteA = note(owner, deck, "первая заметка");
        UUID noteB = note(owner, deck, "вторая заметка");
        UUID noteC = note(owner, deck, "третья заметка");
        UUID noteD = note(owner, deck, "четвёртая заметка");
        UUID session = start(owner, deck, spec(null, noteSource(noteA, 0), noteSource(noteB, 0), noteSource(noteC, 0), noteSource(noteD, 0)));
        awaitState(session, "REVIEW");
        List<Proposal> all = proposals(owner, deck, session);

        // approveArtifact (ITEM)
        MockHttpServletResponse single = approve(owner, deck, all.get(0), UUID.randomUUID());
        listsStatus("approveArtifact", 200);
        assertThat(single.getStatus()).isEqualTo(200);
        sameShape(example("approvalAckItem"), json(single), "approveArtifact");
        assertThat(operation("approveArtifact").path("success").path("headers").path("ETag").stringValue("")).contains("Deck rowVersion");
        assertThat(single.getHeader("ETag")).isEqualTo("\"" + json(single).path("deckVersion").stringValue(null) + "\"");

        // approveArtifacts
        MockHttpServletResponse bulk = approveMany(owner, deck, session, bulkBody(UUID.randomUUID(), deckRevision(deck), List.of(fresh(owner, deck, all.get(1)))),
                "\"" + deckVersion(deck) + "\"");
        listsStatus("approveArtifacts", 200);
        sameShape(example("approvalAckBulk"), json(bulk), "approveArtifacts");
        assertThat(bulk.getStatus()).isEqualTo(200);

        // rejectArtifact and undoRejectArtifact
        Proposal third = fresh(owner, deck, all.get(2));
        MockHttpServletResponse rejected = reject(owner, deck, third, UUID.randomUUID(), third.version());
        listsStatus("rejectArtifact", 200);
        sameShape(body(operation("rejectArtifact").path("success")), json(rejected), "rejectArtifact");
        assertThat(rejected.getHeader("ETag")).isEqualTo("\"" + json(rejected).path("rowVersion").stringValue(null) + "\"");
        Proposal afterReject = fresh(owner, deck, third);
        MockHttpServletResponse undone = undo(owner, deck, afterReject, "\"" + afterReject.version() + "\"");
        listsStatus("undoRejectArtifact", 200);
        sameShape(example("artifactSummaryProposed"), json(undone), "undoRejectArtifact");
        assertThat(undone.getHeader("ETag")).isEqualTo("\"" + json(undone).path("rowVersion").stringValue(null) + "\"");

        // handoffArtifact: 201 with Location and the draft's ETag
        Proposal fourth = fresh(owner, deck, all.get(3));
        MockHttpServletResponse handed = handoff(owner, deck, fourth, UUID.randomUUID());
        listsStatus("handoffArtifact", 201);
        assertThat(handed.getStatus()).isEqualTo(201);
        sameShape(body(operation("handoffArtifact").path("success")), json(handed), "handoffArtifact");
        assertThat(handed.getHeader("Location")).startsWith("/api/editing-drafts/");
        assertThat(handed.getHeader("ETag")).matches("\"[0-9]+\"");

        // archiveUsedNotes: the two published and the handed-off notes, one of which changed
        jdbc.sql("UPDATE app_learning.capture_note SET row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE note_id=:id").param("id", noteB).update();
        MockHttpServletResponse archived = archiveNotes(owner, deck, session, UUID.randomUUID());
        listsStatus("archiveUsedNotes", 200);
        sameShape(example("noteArchival"), json(archived), "archiveUsedNotes");
        assertThat(json(archived).path("archived")).isNotEmpty();
        assertThat(json(archived).path("skipped")).isNotEmpty();
        assertThat(archived.getHeader("ETag")).isNull();
        assertThat(operation("archiveUsedNotes").path("requestBody").propertyNames()).containsExactly("commandId");

        // the session detail carries the notes block of its examples
        JsonNode detail = json(getSession(owner, deck, session));
        // the spec is the client's own (optional members), everything else has the example's members
        sameShape(((tools.jackson.databind.node.ObjectNode) example("sessionDetail").deepCopy()).without("spec"),
                ((tools.jackson.databind.node.ObjectNode) detail.deepCopy()).without("spec"), "getSession");
        assertThat(detail.path("notes").path("used").intValue()).isEqualTo(3);

        // retryArtifact on a failed artifact
        provider.outage = true;
        UUID failing = start(owner, deck, spec("20 глаголов движения"));
        awaitState(failing, "REVIEW");
        provider.outage = false;
        Proposal failed = proposals(owner, deck, failing).getFirst();
        MockHttpServletResponse retried = retry(owner, deck, failed, UUID.randomUUID(), failed.version());
        listsStatus("retryArtifact", 200);
        sameShape(body(operation("retryArtifact").path("success")), json(retried), "retryArtifact");
        assertThat(retried.getHeader("ETag")).isEqualTo("\"" + json(retried).path("rowVersion").stringValue(null) + "\"");
        awaitState(failing, "REVIEW");

        // deleteSession: 204 and then the opaque 404
        listsStatus("deleteSession", 204);
        assertThat(deleteSession(owner, deck, failing).getStatus()).isEqualTo(204);
        assertThat(deleteSession(owner, deck, failing).getStatus()).isEqualTo(404);
    }

    @Test
    void everyErrorTheReviewOperationsListIsAnswered() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deck = deck(owner);
        Proposal proposal = proposal(owner, deck, spec("20 глаголов движения"));
        UUID absent = UUID.randomUUID();
        String base = "/decks/" + deck + "/generation-sessions/" + proposal.session();
        // the codes of each listed operation are the ones the contract names; here every route's 400 / 404 / 428
        for (String path : List.of("/artifacts/" + proposal.artifact() + "/approval", "/approvals", "/artifacts/" + proposal.artifact() + "/rejection",
                "/artifacts/" + proposal.artifact() + "/handoff", "/artifacts/" + proposal.artifact() + "/retry", "/note-archival")) {
            problem(send(owner, post(base + path).contentType("application/json").content("{nope")), 400, "INVALID_REQUEST");
            problem(send(UUID.randomUUID(), post(base + path).contentType("application/json").content("{nope")), 404, "RESOURCE_NOT_FOUND");
            problem(send(owner, post("/decks/" + deck + "/generation-sessions/" + absent + path.replace(proposal.artifact().toString(), absent.toString()))
                    .contentType("application/json").content("{}")), 404, "RESOURCE_NOT_FOUND");
        }
        // an artifact of the session that does not exist is the same 404, and so is an artifact id of another session
        problem(send(owner, post(base + "/artifacts/" + absent + "/approval").contentType("application/json").content("{}")), 404, "RESOURCE_NOT_FOUND");
        UUID other = start(owner, deck, spec("20 глаголов движения"));
        awaitState(other, "REVIEW");
        Proposal elsewhere = proposals(owner, deck, other).getFirst();
        problem(send(owner, post(base + "/artifacts/" + elsewhere.artifact() + "/rejection").contentType("application/json").content("{}")), 404, "RESOURCE_NOT_FOUND");
        problem(undo(owner, deck, new Proposal(proposal.session(), absent, null, 0), "\"1\""), 404, "RESOURCE_NOT_FOUND");
        problem(send(owner, get(base + "/artifacts/" + elsewhere.artifact())), 404, "RESOURCE_NOT_FOUND");
        // a request needs its content type; a command identifier is a version-4 or version-7 UUID
        assertThat(send(owner, post(base + "/artifacts/" + proposal.artifact() + "/approval").content("{}")).getStatus()).isEqualTo(415);
        problem(reject(owner, deck, proposal, UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8"), proposal.version()), 400, "INVALID_REQUEST");
        List<String> codes = new ArrayList<>();
        operation("approveArtifact").path("errors").forEach(error -> codes.add(error.path("code").stringValue("")));
        assertThat(codes).contains("VERSION_CONFLICT", "PRECONDITION_REQUIRED", "GENERATION_STATE_CONFLICT", "IDEMPOTENCY_CONFLICT");
    }
}
