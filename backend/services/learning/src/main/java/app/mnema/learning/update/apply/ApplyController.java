package app.mnema.learning.update.apply;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.update.plan.UpdatePlanBulkhead;
import app.mnema.learning.update.plan.UpdatePlanBusyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.UUID;

/**
 * {@code POST /api/decks/{forkDeckId}/update-plan:apply} (Updates/4, #443; contract {@code contracts/decks/updates.json}): apply the owner's choices on the update plan of his copy.
 * Private chain, owner only, {@code learning.write} (the blanket rule for non-GET routes), body at most 64 KiB, no {@code If-Match} (the plan version in the body is the
 * precondition). Every answer is {@code private, no-store}. A preparing copy is {@code 409 DECK_PREPARING} before this controller is reached ({@code PreparingGuardInterceptor}).
 *
 * <p>{@code 200 {commandId, state: APPLIED, ...}} when it was applied in the request (flagged {@code Idempotency-Replayed: true} when this is the stored answer of an earlier
 * identical request), {@code 202 {commandId, state: APPLYING}} when it runs as a job; the plan route then answers {@code APPLYING} until the job ends. The route shares the places
 * of the plan routes' bulkhead: it recomputes the plan, which holds a pooled connection, so the excess is {@code 503 UPDATE_PLAN_BUSY} with nothing computed.
 */
@RestController
@RequestMapping(value = "/decks/{deckId}", produces = MediaType.APPLICATION_JSON_VALUE)
public class ApplyController {
    private final ApplyService service;
    private final UpdatePlanBulkhead bulkhead;

    ApplyController(ApplyService service, UpdatePlanBulkhead bulkhead) {
        this.service = service;
        this.bulkhead = bulkhead;
    }

    @PostMapping(value = "/update-plan:apply", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> apply(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId, InputStream body) {
        ApplyCommand command = ApplyCommand.read(body);
        UUID owner = entityId(identity.getSubject());
        UUID deck = entityId(deckId);
        if (!bulkhead.tryAcquire()) throw new UpdatePlanBusyException();
        ApplyService.Outcome outcome;
        try {
            outcome = service.apply(owner, deck, command);
        } finally {
            bulkhead.release();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("private, no-store");
        return switch (outcome) {
            case ApplyService.Applied applied -> {
                if (applied.replayed()) headers.set("Idempotency-Replayed", "true");
                yield ResponseEntity.ok().headers(headers).body(applied.answer());
            }
            case ApplyService.Queued queued -> ResponseEntity.status(HttpStatus.ACCEPTED).headers(headers).body(queued.answer());
        };
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
