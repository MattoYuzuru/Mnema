package app.mnema.learning.promo;

import app.mnema.learning.platform.api.InvalidRequestException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.Set;

/** {@code GET /api/promo-popup} (eligibility and the campaign) and {@code POST /api/promo-popup/events} (what the learner did with it). */
@RestController
@RequestMapping(value = "/promo-popup", produces = MediaType.APPLICATION_JSON_VALUE)
public final class PromoPopupController {
    private final PromoPopupService popup;

    PromoPopupController(PromoPopupService popup) {
        this.popup = popup;
    }

    @GetMapping
    ResponseEntity<JsonNode> read(@AuthenticationPrincipal Jwt identity) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(popup.read(PromoBodies.owner(identity)));
    }

    /** {@code {campaignId, event: SHOWN|DISMISSED|DECLINED}} → 204. */
    @PostMapping(value = "/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> record(@AuthenticationPrincipal Jwt identity, InputStream body) {
        JsonNode command = PromoBodies.object(body, Set.of("campaignId", "event"), Set.of("campaignId", "event"));
        String campaign = PromoBodies.text(command, "campaignId");
        PromoPopupService.Event event;
        try {
            event = PromoPopupService.Event.valueOf(PromoBodies.text(command, "event"));
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new InvalidRequestException();
        }
        popup.record(PromoBodies.owner(identity), campaign, event);
        return ResponseEntity.noContent().header("Cache-Control", "private, no-store").build();
    }
}
