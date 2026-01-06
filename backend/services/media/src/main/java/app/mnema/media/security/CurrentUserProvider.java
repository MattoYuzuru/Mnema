package app.mnema.media.security;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class CurrentUserProvider {

    public Optional<UUID> getUserId(Jwt jwt) {
        if (jwt == null) {
            return Optional.empty();
        }
        String claim = jwt.getClaimAsString("user_id");
        if (claim == null || claim.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(claim));
    }

    public UUID requireUserId(Jwt jwt) {
        return getUserId(jwt)
                .orElseThrow(() -> new IllegalStateException("user_id claim missing"));
    }
}
