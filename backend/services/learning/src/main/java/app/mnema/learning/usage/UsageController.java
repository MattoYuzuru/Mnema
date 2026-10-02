package app.mnema.learning.usage;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The owner's AI budget ({@code contracts/usage}). Reading needs {@code learning.read}, the blanket rule of the
 * security chain for GET; the owner is the token subject and nothing else.
 */
@RestController
@RequestMapping(value = "/usage", produces = MediaType.APPLICATION_JSON_VALUE)
public final class UsageController {
    private final UsageService usage;

    UsageController(UsageService usage) {
        this.usage = usage;
    }

    @GetMapping
    ResponseEntity<UsageView> read(@AuthenticationPrincipal Jwt identity) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(usage.read(owner(identity)));
    }

    static UUID owner(Jwt identity) {
        try {
            return UuidPolicy.requireEntityId(UUID.fromString(identity.getSubject()), "owner");
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }
}
