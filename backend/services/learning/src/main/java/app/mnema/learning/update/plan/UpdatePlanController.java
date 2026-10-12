package app.mnema.learning.update.plan;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.Optional;
import java.util.UUID;

/**
 * The update plan of a copy ({@code GET /decks/{deckId}/update-plan} and the two sides of one unit; contract {@code contracts/decks/updates.json}). Private chain, owner only,
 * {@code learning.read}; every answer is {@code private, no-store}. There is no kill switch: the routes only read. Both take a place of the {@link UpdatePlanBulkhead} first; the excess is
 * {@code 503 UPDATE_PLAN_BUSY} with nothing computed. The plan route also tells an apply command that runs as a job ({@link ApplyProgress}: state {@code APPLYING}, or
 * {@code lastApplyFailed} after a failed one); the command itself is {@code POST /decks/{deckId}/update-plan:apply} (Updates/4, {@code ApplyController}).
 */
@RestController
@RequestMapping(value = "/decks/{deckId}/update-plan", produces = MediaType.APPLICATION_JSON_VALUE)
public class UpdatePlanController {
    private final UpdatePlanService service;
    private final UpdatePlanBulkhead bulkhead;
    private final ApplyProgress progress;

    UpdatePlanController(UpdatePlanService service, UpdatePlanBulkhead bulkhead, ApplyProgress progress) {
        this.service = service;
        this.bulkhead = bulkhead;
        this.progress = progress;
    }

    @GetMapping
    ResponseEntity<JsonNode> plan(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId, HttpServletRequest request) {
        if (!bulkhead.tryAcquire()) throw new UpdatePlanBusyException();
        try {
            UUID owner = entityId(identity.getSubject());
            UUID deck = entityId(deckId);
            String cursor = parameter(request, "cursor");
            String limit = parameter(request, "limit");
            // the apply command of Updates/4 that runs as a job: while it runs the copy is changing and no plan is computed; after a failed one the plan is answered as usual with
            // the code it failed with. A malformed query is a 400 either way.
            Optional<ApplyProgress.Progress> applying = progress.of(owner, deck);
            if (applying.isPresent() && applying.get().running()) {
                UpdatePlanService.pageSize(limit);
                PlanVersion.Cursor.decode(cursor, deck);
                ObjectNode closed = JsonNodeFactory.instance.objectNode().put("state", "APPLYING").put("forkDeckId", deck.toString());
                closed.putArray("units");
                closed.putNull("nextCursor");
                closed.put("commandId", applying.get().commandId().toString());
                return ResponseEntity.ok().headers(privateHeaders()).body(closed);
            }
            ObjectNode page = service.page(owner, deck, cursor, limit);
            if (applying.isPresent() && applying.get().failureCode() != null && page.has("planVersion")) {
                page.putObject("lastApplyFailed").put("code", applying.get().failureCode());
            }
            return ResponseEntity.ok().headers(privateHeaders()).body(page);
        } finally {
            bulkhead.release();
        }
    }

    @GetMapping("/units/{kind}/{key}")
    ResponseEntity<JsonNode> unit(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId, @PathVariable String kind, @PathVariable String key,
                                  HttpServletRequest request) {
        if (!bulkhead.tryAcquire()) throw new UpdatePlanBusyException();
        try {
            UnitKind unitKind = UnitKind.fromWire(kind).orElseThrow(InvalidRequestException::new);
            String version = parameter(request, "planVersion");
            if (version == null) throw new InvalidRequestException();
            return ResponseEntity.ok().headers(privateHeaders()).body(service.unit(entityId(identity.getSubject()), entityId(deckId), unitKind, entityId(key), version));
        } finally {
            bulkhead.release();
        }
    }

    private static String parameter(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null) return null;
        if (values.length != 1) throw new InvalidRequestException();
        return values[0];
    }

    private static HttpHeaders privateHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("private, no-store");
        return headers;
    }

    private static UUID entityId(String value) {
        try {
            if (value == null || value.length() != 36) throw new InvalidRequestException();
            UUID id = UuidPolicy.requireEntityId(UUID.fromString(value), "id");
            if (!id.toString().equalsIgnoreCase(value)) throw new InvalidRequestException();
            return id;
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }
}
