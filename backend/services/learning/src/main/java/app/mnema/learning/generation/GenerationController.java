package app.mnema.learning.generation;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The HTTP surface of generation sessions ({@code contracts/generation/http.json}) that exists so far: create, list (one
 * deck and the account's active sessions), read, cancel, events, artifact, approval (one and bulk), rejection and its undo,
 * hand-off, retry, edits, the choice of a found image, revert, the plan approval of a plan-first session, delete and the archival of used notes. Every response is
 * {@code Cache-Control: private, no-store}; the owner is the token subject and nothing else; ids and queries are checked
 * before the service is called, and a body is read (bounded) before any transaction starts.
 */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
class GenerationController {
    private final SessionService service;
    private final ReviewService review;
    private final ArtifactEdits edits;
    private final IntentService intents;

    GenerationController(SessionService service, ReviewService review, ArtifactEdits edits, IntentService intents) {
        this.service = service;
        this.review = review;
        this.edits = edits;
        this.intents = intents;
    }

    /**
     * {@code createIntent}: one sentence of «Попросить Мнему…» to a spec, chips and notes. Free (nothing is reserved or debited) and
     * stateless: an ephemeral request is handed to the worker and removed at completion or the deadline; nothing is replayed.
     */
    @PostMapping(value = "/decks/{deckId}/generation-intents", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> intent(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId, InputStream body) {
        UUID owner = owner(identity);
        UUID deck = entity(deckId, "deckId");
        return ResponseEntity.ok().headers(privateHeaders()).body(intents.answer(owner, deck, read(body)));
    }

    @PostMapping(value = "/decks/{deckId}/generation-sessions", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> create(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId, InputStream body) {
        UUID owner = owner(identity);
        UUID deck = entity(deckId, "deckId");
        SessionService.Written written = service.create(owner, deck, read(body));
        var response = ResponseEntity.status(HttpStatus.CREATED).headers(privateHeaders());
        if (written.replayed()) {
            response.header("Idempotency-Replayed", "true");
        } else {
            response.eTag(quoted(written.body().path("rowVersion").stringValue("0")));
        }
        response.location(URI.create("/api/decks/" + deck + "/generation-sessions/" + written.body().path("sessionId").stringValue("")));
        return response.body(written.body());
    }

    @GetMapping("/decks/{deckId}/generation-sessions")
    ResponseEntity<JsonNode> listForDeck(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                         HttpServletRequest request) {
        UUID owner = owner(identity);
        UUID deck = entity(deckId, "deckId");
        query(request, Set.of("limit", "cursor", "active"));
        boolean active = flag(request, "active");
        return page(service.list(owner, deck, active, limit(request), parameter(request, "cursor")));
    }

    @GetMapping("/generation-sessions")
    ResponseEntity<JsonNode> listActive(@AuthenticationPrincipal Jwt identity, HttpServletRequest request) {
        UUID owner = owner(identity);
        query(request, Set.of("state", "limit", "cursor"));
        // "active" is the only value of v1; the account-wide list has no other use, so the parameter is required.
        if (!"active".equals(parameter(request, "state"))) throw new InvalidRequestException();
        return page(service.list(owner, null, true, limit(request), parameter(request, "cursor")));
    }

    @GetMapping("/decks/{deckId}/generation-sessions/{sessionId}")
    ResponseEntity<JsonNode> read(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                  @PathVariable String sessionId, HttpServletRequest request) {
        UUID owner = owner(identity);
        UUID deck = entity(deckId, "deckId");
        UUID session = entity(sessionId, "sessionId");
        query(request, Set.of());
        ObjectNode body = service.read(owner, deck, session);
        return ResponseEntity.ok().headers(privateHeaders()).eTag(quoted(body.path("rowVersion").stringValue("0"))).body(body);
    }

    /**
     * {@code approvePlan}: launches the plan of a PLAN_READY session (the model's plan with the owner's edits): the artifacts and steps of exactly that
     * plan are created and the session is RUNNING. 200 with the session; the ETag is its new version. The version the owner saw is in the body.
     */
    @PostMapping(value = "/decks/{deckId}/generation-sessions/{sessionId}/plan-approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> approvePlan(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                         @PathVariable String sessionId, InputStream body) {
        UUID owner = owner(identity);
        UUID deck = entity(deckId, "deckId");
        UUID session = entity(sessionId, "sessionId");
        SessionService.Written written = service.approvePlan(owner, deck, session, read(body));
        var response = ResponseEntity.ok().headers(privateHeaders());
        if (written.replayed()) {
            response.header("Idempotency-Replayed", "true");
        } else {
            response.eTag(quoted(written.body().path("rowVersion").stringValue("0")));
        }
        return response.body(written.body());
    }

    @PostMapping(value = "/decks/{deckId}/generation-sessions/{sessionId}/cancellation", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> cancel(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                    @PathVariable String sessionId, InputStream body) {
        UUID owner = owner(identity);
        UUID deck = entity(deckId, "deckId");
        UUID session = entity(sessionId, "sessionId");
        SessionService.Written written = service.cancel(owner, deck, session, read(body));
        var response = ResponseEntity.ok().headers(privateHeaders());
        if (written.replayed()) {
            response.header("Idempotency-Replayed", "true");
        } else {
            response.eTag(quoted(written.body().path("rowVersion").stringValue("0")));
        }
        return response.body(written.body());
    }

    @GetMapping("/decks/{deckId}/generation-sessions/{sessionId}/events")
    ResponseEntity<JsonNode> events(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                    @PathVariable String sessionId, HttpServletRequest request) {
        UUID owner = owner(identity);
        UUID deck = entity(deckId, "deckId");
        UUID session = entity(sessionId, "sessionId");
        query(request, Set.of("after", "limit"));
        String after = parameter(request, "after");
        if (after != null && !after.matches("0|[1-9][0-9]{0,17}")) throw new InvalidRequestException();
        String limit = parameter(request, "limit");
        int size = 100;
        if (limit != null) {
            if (!limit.matches("[1-9][0-9]{0,2}") || Integer.parseInt(limit) > 100) throw new InvalidRequestException();
            size = Integer.parseInt(limit);
        }
        return ResponseEntity.ok().headers(privateHeaders())
                .body(service.events(owner, deck, session, after == null ? 0 : Long.parseLong(after), size));
    }

    @GetMapping("/decks/{deckId}/generation-sessions/{sessionId}/artifacts/{artifactId}")
    ResponseEntity<JsonNode> artifact(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                      @PathVariable String sessionId, @PathVariable String artifactId,
                                      HttpServletRequest request) {
        UUID owner = owner(identity);
        UUID deck = entity(deckId, "deckId");
        UUID session = entity(sessionId, "sessionId");
        UUID artifact = entity(artifactId, "artifactId");
        query(request, Set.of("revisionId"));
        String revision = parameter(request, "revisionId");
        ObjectNode body = service.artifact(owner, deck, session, artifact, revision == null ? null : entity(revision, "revisionId"));
        return ResponseEntity.ok().headers(privateHeaders()).eTag(quoted(body.path("rowVersion").stringValue("0"))).body(body);
    }

    // ----------------------------------------------------------- review commands

    private static final String ARTIFACT = "/decks/{deckId}/generation-sessions/{sessionId}/artifacts/{artifactId}";

    @PostMapping(value = ARTIFACT + "/approval", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> approve(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                     @PathVariable String sessionId, @PathVariable String artifactId,
                                     HttpServletRequest request, InputStream body) {
        UUID owner = owner(identity);
        ReviewService.Result result = review.approve(owner, entity(deckId, "deckId"), entity(sessionId, "sessionId"),
                entity(artifactId, "artifactId"), ifMatch(request), read(body));
        return answer(HttpStatus.OK, result);
    }

    @PostMapping(value = "/decks/{deckId}/generation-sessions/{sessionId}/approvals", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> approveMany(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                         @PathVariable String sessionId, HttpServletRequest request, InputStream body) {
        UUID owner = owner(identity);
        ReviewService.Result result = review.approveMany(owner, entity(deckId, "deckId"), entity(sessionId, "sessionId"),
                ifMatch(request), read(body));
        return answer(HttpStatus.OK, result);
    }

    @PostMapping(value = ARTIFACT + "/rejection", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> reject(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                    @PathVariable String sessionId, @PathVariable String artifactId, InputStream body) {
        UUID owner = owner(identity);
        return answer(HttpStatus.OK, review.reject(owner, entity(deckId, "deckId"), entity(sessionId, "sessionId"),
                entity(artifactId, "artifactId"), read(body)));
    }

    @DeleteMapping(ARTIFACT + "/rejection")
    ResponseEntity<JsonNode> undoReject(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                        @PathVariable String sessionId, @PathVariable String artifactId,
                                        HttpServletRequest request) {
        UUID owner = owner(identity);
        UUID deck = entity(deckId, "deckId");
        UUID session = entity(sessionId, "sessionId");
        UUID artifact = entity(artifactId, "artifactId");
        query(request, Set.of());
        return answer(HttpStatus.OK, review.undoReject(owner, deck, session, artifact, ifMatch(request)));
    }

    @PostMapping(value = ARTIFACT + "/handoff", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> handoff(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                     @PathVariable String sessionId, @PathVariable String artifactId, InputStream body) {
        UUID owner = owner(identity);
        ReviewService.Result result = review.handoff(owner, entity(deckId, "deckId"), entity(sessionId, "sessionId"),
                entity(artifactId, "artifactId"), read(body));
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED).headers(privateHeaders());
        String draft = result.body().path("draft").path("draftId").stringValue("");
        response.location(URI.create("/api/editing-drafts/" + draft));
        if (result.replayed()) response.header("Idempotency-Replayed", "true");
        else if (result.etag() != null) response.eTag(quoted(result.etag()));
        return response.body(result.body());
    }

    @PostMapping(value = ARTIFACT + "/retry", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> retry(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                   @PathVariable String sessionId, @PathVariable String artifactId, InputStream body) {
        UUID owner = owner(identity);
        return answer(HttpStatus.OK, review.retry(owner, entity(deckId, "deckId"), entity(sessionId, "sessionId"),
                entity(artifactId, "artifactId"), read(body)));
    }

    /** {@code editArtifact}: 202 with the turn and the artifact; the rewrite itself runs on the worker (poll the events). */
    @PostMapping(value = ARTIFACT + "/edits", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> edit(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                  @PathVariable String sessionId, @PathVariable String artifactId, InputStream body) {
        UUID owner = owner(identity);
        UUID deck = entity(deckId, "deckId");
        UUID session = entity(sessionId, "sessionId");
        UUID artifact = entity(artifactId, "artifactId");
        ReviewService.Result result = edits.edit(owner, deck, session, artifact, read(body));
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.ACCEPTED).headers(privateHeaders());
        response.location(URI.create("/api/decks/" + deck + "/generation-sessions/" + session + "/artifacts/" + artifact));
        if (result.replayed()) response.header("Idempotency-Replayed", "true");
        return response.body(result.body());
    }

    /**
     * {@code selectMediaCandidate} (#296): another found image for an image slot that searches. 200 with the artifact as {@code getArtifact} reads it
     * on the revision the command made; a replay answers with the revision its receipt names.
     */
    @PostMapping(value = ARTIFACT + "/media-slots/{slotKey}/selection", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> selectCandidate(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId, @PathVariable String sessionId,
                                             @PathVariable String artifactId, @PathVariable String slotKey, InputStream body) {
        UUID owner = owner(identity);
        UUID deck = entity(deckId, "deckId");
        UUID session = entity(sessionId, "sessionId");
        UUID artifact = entity(artifactId, "artifactId");
        ReviewService.Result result = edits.select(owner, deck, session, artifact, slotKey, read(body));
        ObjectNode detail = service.artifact(owner, deck, session, artifact, entity(result.body().path("revisionId").stringValue(""), "revisionId"));
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().headers(privateHeaders());
        if (result.replayed()) response.header("Idempotency-Replayed", "true");
        else response.eTag(quoted(detail.path("rowVersion").stringValue("0")));
        return response.body(detail);
    }

    @PostMapping(value = ARTIFACT + "/revert", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> revert(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                    @PathVariable String sessionId, @PathVariable String artifactId, InputStream body) {
        UUID owner = owner(identity);
        return answer(HttpStatus.OK, edits.revert(owner, entity(deckId, "deckId"), entity(sessionId, "sessionId"),
                entity(artifactId, "artifactId"), read(body)));
    }

    @PostMapping(value = "/decks/{deckId}/generation-sessions/{sessionId}/note-archival", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> archiveNotes(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                          @PathVariable String sessionId, InputStream body) {
        UUID owner = owner(identity);
        return answer(HttpStatus.OK, review.archiveNotes(owner, entity(deckId, "deckId"), entity(sessionId, "sessionId"), read(body)));
    }

    @DeleteMapping("/decks/{deckId}/generation-sessions/{sessionId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                @PathVariable String sessionId, HttpServletRequest request) {
        UUID owner = owner(identity);
        UUID deck = entity(deckId, "deckId");
        UUID session = entity(sessionId, "sessionId");
        query(request, Set.of());
        review.delete(owner, deck, session);
        return ResponseEntity.noContent().headers(privateHeaders()).build();
    }

    /** A command's answer: the stored original with {@code Idempotency-Replayed} on a replay, else its {@code ETag}. */
    private static ResponseEntity<JsonNode> answer(HttpStatus status, ReviewService.Result result) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status).headers(privateHeaders());
        if (result.replayed()) response.header("Idempotency-Replayed", "true");
        else if (result.etag() != null) response.eTag(quoted(result.etag()));
        return response.body(result.body());
    }

    private static List<String> ifMatch(HttpServletRequest request) {
        return Collections.list(request.getHeaders(HttpHeaders.IF_MATCH));
    }

    // ------------------------------------------------------------------ helpers

    private static ResponseEntity<JsonNode> page(SessionService.Page page) {
        ObjectNode body = Json.object();
        ArrayNode items = body.putArray("items");
        page.items().forEach(items::add);
        body.put("nextCursor", page.nextCursor());
        return ResponseEntity.ok().headers(privateHeaders()).body(body);
    }

    private static UUID owner(Jwt identity) {
        try {
            return UuidPolicy.requireEntityId(UUID.fromString(identity.getSubject()), "owner");
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    private static UUID entity(String value, String name) {
        try {
            UUID id = UuidPolicy.requireEntityId(UUID.fromString(value), name);
            if (!id.toString().equals(value)) throw new InvalidRequestException();
            return id;
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    /** Reads at most the body limit plus one byte: the service refuses an oversized body after the ownership check. */
    private static byte[] read(InputStream input) {
        try {
            return input.readNBytes(Commands.MAX_BODY_BYTES + 1);
        } catch (IOException failure) {
            throw new InvalidRequestException();
        }
    }

    /** Query parameters outside {@code allowed} are a 400, as an unknown body field is. */
    private static void query(HttpServletRequest request, Set<String> allowed) {
        for (String name : request.getParameterMap().keySet()) {
            if (!allowed.contains(name)) throw new InvalidRequestException();
        }
    }

    private static String parameter(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null) return null;
        if (values.length != 1) throw new InvalidRequestException();
        return values[0];
    }

    private static boolean flag(HttpServletRequest request, String name) {
        String value = parameter(request, name);
        if (value == null) return false;
        if (!value.equals("true") && !value.equals("false")) throw new InvalidRequestException();
        return value.equals("true");
    }

    private static int limit(HttpServletRequest request) {
        String value = parameter(request, "limit");
        if (value == null) return 20;
        if (!value.matches("[1-9][0-9]{0,2}") || Integer.parseInt(value) > 100) throw new InvalidRequestException();
        return Integer.parseInt(value);
    }

    private static String quoted(String version) {
        return "\"" + version + "\"";
    }

    private static HttpHeaders privateHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("private, no-store");
        return headers;
    }
}
