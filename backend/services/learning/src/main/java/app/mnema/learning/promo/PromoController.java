package app.mnema.learning.promo;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

/**
 * {@code POST /api/promo-codes/redemptions {code}} with an {@code Idempotency-Key} (UUIDv4 or v7): the owner redeems a code. Needs
 * {@code learning.write}, the blanket rule of the security chain. The owner is the token subject and nothing else.
 */
@RestController
@RequestMapping(value = "/promo-codes", produces = MediaType.APPLICATION_JSON_VALUE)
public final class PromoController {
    private final PromoService promo;
    private final PromoClient.Resolver clients;

    PromoController(PromoService promo, PromoClient.Resolver clients) {
        this.promo = promo;
        this.clients = clients;
    }

    @PostMapping(value = "/redemptions", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> redeem(@AuthenticationPrincipal Jwt identity, InputStream body, HttpServletRequest request) {
        UUID owner = PromoBodies.owner(identity);
        UUID key = idempotencyKey(request.getHeader("Idempotency-Key"));
        String code = PromoBodies.text(PromoBodies.object(body, Set.of("code"), Set.of("code")), "code");
        if (code == null) throw new InvalidRequestException();
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
                .body(promo.redeem(owner, identity, key, code, clients.of(request)));
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
