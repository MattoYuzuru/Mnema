package app.mnema.learning.promo;

import app.mnema.learning.platform.api.InvalidRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;

/**
 * The admin-scope promo endpoints (no UI yet): a valid Learning token ({@code learning.read} to list, {@code learning.write} to change) and an
 * administrator account in Identity. Every method checks the second condition first and fails closed.
 */
@RestController
@RequestMapping(value = "/admin/promo-codes", produces = MediaType.APPLICATION_JSON_VALUE)
public final class PromoAdminController {
    private static final Set<String> CREATE = Set.of("type", "plan", "days", "months", "percent", "validFrom", "validUntil",
            "maxRedemptions", "oncePerAccount", "channel", "code");

    private final PromoAdminService admin;

    PromoAdminController(PromoAdminService admin) {
        this.admin = admin;
    }

    @GetMapping
    ResponseEntity<JsonNode> list(@AuthenticationPrincipal Jwt identity, @RequestParam(name = "after", required = false) String after) {
        admin.requireAdmin(identity);
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(admin.list(after == null ? null : uuid(after)));
    }

    /** Creates a code and returns its plain text once: {@code {code, codeId, hint, ...}}. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> create(@AuthenticationPrincipal Jwt identity, InputStream body) {
        admin.requireAdmin(identity);
        UUID owner = PromoBodies.owner(identity);
        JsonNode command = PromoBodies.object(body, Set.of("type", "maxRedemptions"), CREATE);
        PromoType type;
        try {
            type = PromoType.valueOf(PromoBodies.text(command, "type"));
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw InvalidRequestException.because("type");
        }
        Integer max = PromoBodies.integer(command, "maxRedemptions");
        if (max == null) throw InvalidRequestException.because("maxRedemptions");
        Boolean once = PromoBodies.flag(command, "oncePerAccount");
        var create = new PromoAdminService.Create(type, PromoBodies.text(command, "plan"), PromoBodies.integer(command, "days"),
                PromoBodies.integer(command, "months"), PromoBodies.integer(command, "percent"),
                instant(PromoBodies.text(command, "validFrom")), instant(PromoBodies.text(command, "validUntil")), max,
                once == null || once, PromoBodies.text(command, "channel"), PromoBodies.text(command, "code"));
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "private, no-store").body(admin.create(owner, create));
    }

    /** {@code {"enabled": boolean}}: the kill switch. */
    @PatchMapping(value = "/{codeId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> patch(@AuthenticationPrincipal Jwt identity, @PathVariable String codeId, InputStream body) {
        admin.requireAdmin(identity);
        UUID owner = PromoBodies.owner(identity);
        Boolean enabled = PromoBodies.flag(PromoBodies.object(body, Set.of("enabled"), Set.of("enabled")), "enabled");
        if (enabled == null) throw new InvalidRequestException();
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(admin.setEnabled(owner, uuid(codeId), enabled));
    }

    private static UUID uuid(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    private static Instant instant(String text) {
        if (text == null) return null;
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException failure) {
            throw new InvalidRequestException();
        }
    }
}
