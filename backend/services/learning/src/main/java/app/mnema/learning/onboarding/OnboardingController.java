package app.mnema.learning.onboarding;

import app.mnema.learning.platform.json.CanonicalJsonHasher;
import app.mnema.learning.platform.security.IdentityClaims;
import app.mnema.learning.promo.PromoBodies;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * The first-entry journey ({@code GET /api/onboarding}, {@code PUT /api/onboarding/journey}, {@code PUT /api/onboarding/preferences}; contract
 * {@code contracts/onboarding/journey.json}). Reading needs {@code learning.read}, writing {@code learning.write}: the blanket rule of the security chain; the
 * owner is the token subject. Every answer is {@code private, no-store} and carries a strong {@code ETag}, the hash of the canonical JSON of the view, so the
 * tag changes exactly when the view does; a {@code GET} whose {@code If-None-Match} names it is a 304.
 */
@RestController
@RequestMapping(value = "/onboarding", produces = MediaType.APPLICATION_JSON_VALUE)
public final class OnboardingController {
    private final OnboardingService service;
    private final CanonicalJsonHasher hasher;

    OnboardingController(OnboardingService service, CanonicalJsonHasher hasher) {
        this.service = service;
        this.hasher = hasher;
    }

    @GetMapping
    ResponseEntity<JsonNode> read(@AuthenticationPrincipal Jwt identity, HttpServletRequest request) {
        // The anti-abuse signal Identity reported for this request; unknown (absent claim) is not new.
        boolean newAccount = Boolean.TRUE.equals(IdentityClaims.signals(request).newAccount());
        JsonNode view = service.read(PromoBodies.owner(identity), newAccount);
        String tag = etag(view);
        if (matches(request.getHeaders(HttpHeaders.IF_NONE_MATCH), tag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).headers(headers(tag)).build();
        }
        return ResponseEntity.ok().headers(headers(tag)).body(view);
    }

    @PutMapping(value = "/journey", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> journey(@AuthenticationPrincipal Jwt identity, InputStream body) {
        UUID owner = PromoBodies.owner(identity);
        service.requireEnabled();
        return answer(service.journey(owner, OnboardingCommands.journey(body)));
    }

    @PutMapping(value = "/preferences", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> preferences(@AuthenticationPrincipal Jwt identity, InputStream body) {
        UUID owner = PromoBodies.owner(identity);
        service.requireEnabled();
        return answer(service.preferences(owner, OnboardingCommands.preferences(body)));
    }

    private ResponseEntity<JsonNode> answer(OnboardingService.Result result) {
        var response = ResponseEntity.ok().headers(headers(etag(result.view())));
        if (result.replayed()) response.header("Idempotency-Replayed", "true");
        return response.body(result.view());
    }

    private static HttpHeaders headers(String etag) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("private, no-store");
        headers.setVary(List.of(HttpHeaders.AUTHORIZATION));
        headers.setETag(etag);
        return headers;
    }

    /** A strong tag: the first 128 bits of the SHA-256 of the canonical JSON of the view. */
    private String etag(JsonNode view) {
        byte[] digest = hasher.hash(view).sha256();
        return "\"" + HexFormat.of().formatHex(digest, 0, 16) + "\"";
    }

    /** {@code If-None-Match} uses the weak comparison (RFC 9110, 13.1.2): {@code *} or any listed tag equal to ours once a {@code W/} prefix is dropped. */
    private static boolean matches(Enumeration<String> headers, String tag) {
        if (headers == null) return false;
        while (headers.hasMoreElements()) {
            for (String candidate : headers.nextElement().split(",")) {
                String value = candidate.strip();
                if (value.startsWith("W/")) value = value.substring(2);
                if (value.equals("*") || value.equals(tag)) return true;
            }
        }
        return false;
    }
}
