package app.mnema.learning.promo;

import app.mnema.learning.platform.api.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;
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
import java.util.Set;
import java.util.UUID;

/**
 * The admin-scope promo campaign endpoints (OB-402; {@code contracts/usage/promo.json}): a valid Learning token ({@code learning.read} to read, {@code learning.write} to change)
 * and an administrator account in Identity, checked first on every call and failing closed, like {@link PromoAdminController}.
 */
@RestController
@RequestMapping(value = "/admin/promo-campaigns", produces = MediaType.APPLICATION_JSON_VALUE)
public final class PromoCampaignController {
    private static final Set<String> CREATE = Set.of("type", "offerType", "percent", "plan", "days");

    private final PromoAdminService admin;
    private final PromoCampaignService campaigns;

    PromoCampaignController(PromoAdminService admin, PromoCampaignService campaigns) {
        this.admin = admin;
        this.campaigns = campaigns;
    }

    @GetMapping
    ResponseEntity<JsonNode> list(@AuthenticationPrincipal Jwt identity) {
        admin.requireAdmin(identity);
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(campaigns.list());
    }

    /** Creates a disabled campaign; needs an {@code Idempotency-Key}. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> create(@AuthenticationPrincipal Jwt identity, InputStream body, HttpServletRequest request) {
        admin.requireAdmin(identity);
        UUID owner = PromoBodies.owner(identity);
        UUID key = PromoAdminController.key(request);
        JsonNode command = PromoBodies.object(body, Set.of("type", "offerType"), CREATE);
        PromoCampaignType type;
        try {
            type = PromoCampaignType.valueOf(PromoBodies.text(command, "type"));
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw InvalidRequestException.because("type");
        }
        PromoType offer;
        try {
            offer = PromoType.valueOf(PromoBodies.text(command, "offerType"));
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw InvalidRequestException.because("offerType");
        }
        if (offer == PromoType.TIER_MONTHS) throw InvalidRequestException.because("offerType");
        var create = new PromoCampaignService.Create(type, offer, PromoBodies.integer(command, "percent"), PromoBodies.text(command, "plan"), PromoBodies.integer(command, "days"));
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "private, no-store").body(campaigns.createCommand(owner, key, command, create));
    }

    /** {@code {"enabled": boolean}}: the kill switch of the campaign. */
    @PatchMapping(value = "/{campaignId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> patch(@AuthenticationPrincipal Jwt identity, @PathVariable String campaignId, InputStream body) {
        admin.requireAdmin(identity);
        UUID owner = PromoBodies.owner(identity);
        Boolean enabled = PromoBodies.flag(PromoBodies.object(body, Set.of("enabled"), Set.of("enabled")), "enabled");
        if (enabled == null) throw new InvalidRequestException();
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(campaigns.setEnabled(owner, PromoAdminController.uuid(campaignId), enabled));
    }

    /** The issuances of a campaign, newest first; {@code after} is the opaque {@code next} of the previous page. */
    @GetMapping("/{campaignId}/issuances")
    ResponseEntity<JsonNode> issuances(@AuthenticationPrincipal Jwt identity, @PathVariable String campaignId, @RequestParam(name = "after", required = false) String after) {
        admin.requireAdmin(identity);
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(campaigns.issuances(PromoAdminController.uuid(campaignId), after));
    }
}
