package app.mnema.learning.update.history;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.update.plan.UnitKind;
import app.mnema.learning.update.plan.UpdatePlanBulkhead;
import app.mnema.learning.update.plan.UpdatePlanBusyException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.UUID;

/**
 * «Что нового» of a copy (Updates/6 part B, #445; contract {@code contracts/decks/updates.json}): the applies of the update plan with the units each changed, the owner's mark that
 * he has seen one, and the return of one unit of an apply. Private chain, owner only (anything that is not the caller's live copy is the opaque 404), every answer
 * {@code private, no-store}; the reads need {@code learning.read}, the commands {@code learning.write} (the blanket rule for non-GET routes). A preparing copy is
 * {@code 409 DECK_PREPARING} for the commands before this controller is reached ({@code PreparingGuardInterceptor}).
 */
@RestController
@RequestMapping(value = "/decks/{deckId}", produces = MediaType.APPLICATION_JSON_VALUE)
public class UpdateHistoryController {
    private final UpdateHistoryService history;
    private final RevertService reverts;
    private final UpdatePlanBulkhead bulkhead;

    UpdateHistoryController(UpdateHistoryService history, RevertService reverts, UpdatePlanBulkhead bulkhead) {
        this.history = history;
        this.reverts = reverts;
        this.bulkhead = bulkhead;
    }

    @GetMapping("/update-history")
    ResponseEntity<JsonNode> history(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId, HttpServletRequest request) {
        String[] limit = request.getParameterValues("limit");
        if (limit != null && limit.length != 1) throw new InvalidRequestException();
        // the places of the plan routes: the titles of the units are read from the immutable store, which holds a pooled connection
        if (!bulkhead.tryAcquire()) throw new UpdatePlanBusyException();
        try {
            return noStore(history.history(entityId(identity.getSubject()), entityId(deckId), limit == null ? null : limit[0]));
        } finally {
            bulkhead.release();
        }
    }

    @PostMapping("/update-history/{deckRevisionId}:seen")
    ResponseEntity<JsonNode> seen(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId, @PathVariable String deckRevisionId) {
        return noStore(history.seen(entityId(identity.getSubject()), entityId(deckId), entityId(deckRevisionId)));
    }

    @PostMapping(value = "/update-history/{deckRevisionId}/units/{kind}/{key}:revert", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> revert(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId, @PathVariable String deckRevisionId, @PathVariable String kind,
                                    @PathVariable String key, InputStream body) {
        RevertCommand command = RevertCommand.read(body);
        UUID owner = entityId(identity.getSubject());
        UUID deck = entityId(deckId);
        UUID revision = entityId(deckRevisionId);
        RevertCommand.Unit unit = new RevertCommand.Unit(UnitKind.fromWire(kind).orElseThrow(InvalidRequestException::new), RevertCommand.uuid(key));
        if (!bulkhead.tryAcquire()) throw new UpdatePlanBusyException();
        RevertService.Result result;
        try {
            result = reverts.revert(owner, deck, revision, unit, command);
        } finally {
            bulkhead.release();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("private, no-store");
        if (result.replayed()) headers.set("Idempotency-Replayed", "true");
        return ResponseEntity.ok().headers(headers).body(result.answer());
    }

    private static ResponseEntity<JsonNode> noStore(JsonNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("private, no-store");
        return ResponseEntity.ok().headers(headers).body(body);
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
