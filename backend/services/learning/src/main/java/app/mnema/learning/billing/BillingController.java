package app.mnema.learning.billing;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.promo.PromoBodies;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
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
import java.util.Set;
import java.util.UUID;

/**
 * {@code POST /api/billing/checkout {plan, period}} with an {@code Idempotency-Key} (UUIDv4 or v7) and {@code GET /api/billing/orders/{orderId}}
 * ({@code contracts/billing}). They need {@code learning.write} and {@code learning.read}, the blanket rules of the security chain; the owner is the token
 * subject and nothing else, and an order of another account is indistinguishable from a missing one.
 */
@RestController
@RequestMapping(value = "/billing", produces = MediaType.APPLICATION_JSON_VALUE)
public final class BillingController {
    private static final Set<String> MEMBERS = Set.of("plan", "period");

    private final BillingService billing;

    BillingController(BillingService billing) {
        this.billing = billing;
    }

    @PostMapping(value = "/checkout", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OrderView> checkout(@AuthenticationPrincipal Jwt identity, InputStream body, HttpServletRequest request) {
        UUID owner = PromoBodies.owner(identity);
        UUID key = idempotencyKey(request.getHeader("Idempotency-Key"));
        JsonNode command = PromoBodies.object(body, MEMBERS, MEMBERS);
        String plan = PromoBodies.text(command, "plan");
        String period = PromoBodies.text(command, "period");
        if (plan == null || period == null) throw new InvalidRequestException();
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "private, no-store").body(billing.checkout(owner, key, plan, period));
    }

    @GetMapping("/orders/{orderId}")
    ResponseEntity<OrderView> order(@AuthenticationPrincipal Jwt identity, @PathVariable String orderId) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(billing.read(PromoBodies.owner(identity), orderId));
    }

    private static UUID idempotencyKey(String header) {
        try {
            if (header == null) throw InvalidRequestException.because("idempotency_key_required");
            return UuidPolicy.requireCommandId(UUID.fromString(header.strip()));
        } catch (IllegalArgumentException failure) {
            throw InvalidRequestException.because("idempotency_key_required");
        }
    }
}
