package app.mnema.identityaccount.profile;

import app.mnema.identityaccount.contract.AccountFailure;
import app.mnema.identityaccount.security.BrowserSessions;
import app.mnema.identityaccount.security.ClientAddresses;
import app.mnema.identityaccount.security.RateLimits;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Own consent for the public profile and the public author cards it gates. Public reads need no authentication and
 * answer the same 404 for an unknown, hidden or non-consenting account.
 */
@RestController
@RequestMapping("/api/accounts")
public class PublicProfileController {
    /** Rate limits per 15-minute window (the {@link RateLimits} window). */
    static final int BATCH_LIMIT = 600;
    static final int USERNAME_LIMIT = 60;
    private static final String PUBLIC_CACHE = "public, max-age=60";
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    public record Batch(List<PublicProfiles.Card> profiles) {
    }

    private final PublicProfiles profiles;
    private final RateLimits limits;
    private final ClientAddresses clientAddresses;

    public PublicProfileController(PublicProfiles profiles, RateLimits limits, ClientAddresses clientAddresses) {
        this.profiles = profiles;
        this.limits = limits;
        this.clientAddresses = clientAddresses;
    }

    @GetMapping("/me/public-profile")
    PublicProfiles.Consent consent(Authentication authentication) {
        return profiles.consent(BrowserSessions.access(authentication));
    }

    @PutMapping("/me/public-profile")
    PublicProfiles.Consent update(Authentication authentication, @RequestBody JsonNode body) {
        return profiles.update(BrowserSessions.access(authentication), parse(body));
    }

    @GetMapping("/profiles/{id}")
    ResponseEntity<PublicProfiles.Card> card(@PathVariable UUID id) {
        return cached(profiles.card(id).orElseThrow(PublicProfileController::notFound));
    }

    @GetMapping("/profiles")
    ResponseEntity<Batch> batch(@RequestParam(name = "ids", required = false) String ids,
                                HttpServletRequest request) {
        var parsed = ids(ids);
        if (!limits.allow("profile-batch", clientAddresses.rateKey(request), BATCH_LIMIT))
            throw new AccountFailure(429, "try_later");
        return cached(new Batch(profiles.cards(parsed)));
    }

    @GetMapping("/profiles/by-username/{username}")
    ResponseEntity<PublicProfiles.Card> byUsername(@PathVariable String username, Authentication authentication,
                                                   HttpServletRequest request) {
        // The network bucket always applies, so alternating anonymous and signed-in requests cannot double the
        // budget; an authenticated caller additionally spends a per-account bucket.
        if (!limits.allow("profile-username", "ip:" + clientAddresses.rateKey(request), USERNAME_LIMIT))
            throw new AccountFailure(429, "try_later");
        boolean signedIn = authentication != null && authentication.isAuthenticated() &&
                !(authentication instanceof AnonymousAuthenticationToken);
        if (signedIn && !limits.allow("profile-username",
                "account:" + BrowserSessions.access(authentication).accountId(), USERNAME_LIMIT))
            throw new AccountFailure(429, "try_later");
        return cached(profiles.byUsername(username).orElseThrow(PublicProfileController::notFound));
    }

    private static <T> ResponseEntity<T> cached(T body) {
        return ResponseEntity.ok().header("Cache-Control", PUBLIC_CACHE).body(body);
    }

    static AccountFailure notFound() {
        return new AccountFailure(404, "profile_not_found");
    }

    /** One to {@link PublicProfiles#MAX_BATCH} distinct canonical UUIDs; anything else is a malformed request. */
    static List<UUID> ids(String raw) {
        if (raw == null || raw.isEmpty()) throw invalid();
        String[] parts = raw.split(",", -1);
        if (parts.length > PublicProfiles.MAX_BATCH) throw invalid();
        var seen = new HashSet<UUID>();
        var result = new ArrayList<UUID>(parts.length);
        for (String part : parts) {
            if (!CANONICAL_UUID.matcher(part).matches()) throw invalid();
            UUID id = UUID.fromString(part);
            if (!seen.add(id)) throw invalid();
            result.add(id);
        }
        return result;
    }

    /** Exactly the five documented fields with their documented types: no coercion, no unknown or missing field. */
    static PublicProfiles.Update parse(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 5) throw invalid();
        JsonNode version = body.get("textVersion");
        if (version == null || !version.isString()) throw invalid();
        return new PublicProfiles.Update(flag(body, "enabled"), flag(body, "showDisplayName"),
                flag(body, "showAvatar"), flag(body, "showBio"), version.stringValue());
    }

    private static boolean flag(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null || !value.isBoolean()) throw invalid();
        return value.booleanValue();
    }

    private static AccountFailure invalid() {
        return new AccountFailure(400, "invalid_request");
    }
}
