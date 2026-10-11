package app.mnema.learning.platform.security;

import app.mnema.learning.platform.api.IdentityUnavailableException;
import app.mnema.learning.platform.api.RateLimitedException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.json.ContentJsonReader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ProfileDirectory} over the bounded Identity transport. No bearer is sent (the endpoint is public), nothing is cached (Identity already
 * caches public reads for a minute and a withdrawn consent must hide the profile at once), and the answer is trusted only when it names the login that was asked.
 * Identity's 429 carries no {@code Retry-After}, so none is invented here: the {@link RateLimitedException} carries Identity's header when there is one (else the minimum), and
 * the real wait (the rest of Identity's 15-minute window) is put on it by {@link ProfileLookupBudget#lookup}, the only caller.
 */
@Component
final class IdentityProfileDirectory implements ProfileDirectory {
    private static final int MAX_BODY_BYTES = 8_192;

    private final IdentityHttp http;
    private final IdentityEndpoints endpoints;
    private final ContentJsonReader json = new ContentJsonReader(MAX_BODY_BYTES, 8, 256);

    IdentityProfileDirectory(IdentityHttp http, IdentityEndpoints endpoints) {
        this.http = http;
        this.endpoints = endpoints;
    }

    @Override
    public Optional<UUID> accountOf(String username) {
        if (!ProfileDirectory.validUsername(username)) return Optional.empty();
        if (endpoints.base() == null) throw new IdentityUnavailableException();
        try {
            var response = http.get(endpoints.endpoint("/api/accounts/profiles/by-username/" + username), null, MAX_BODY_BYTES);
            return switch (response.statusCode()) {
                case 200 -> card(username, json.read(response.body()));
                case 404 -> Optional.empty();
                case 429 -> throw new RateLimitedException(response.headers().firstValueAsLong("Retry-After").orElse(0));
                default -> throw new IdentityUnavailableException();
            };
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof RateLimitedException limited) throw limited;
            if (failure instanceof IdentityUnavailableException unavailable) throw unavailable;
            throw new IdentityUnavailableException();
        }
    }

    private static Optional<UUID> card(String username, JsonNode card) {
        String login = card.path("profileUsername").stringValue(null);
        String id = card.path("accountId").stringValue(null);
        if (login == null || id == null || !login.equalsIgnoreCase(username) || id.length() != 36) throw new IdentityUnavailableException();
        return Optional.of(UuidPolicy.requireEntityId(UUID.fromString(id), "accountId"));
    }
}
