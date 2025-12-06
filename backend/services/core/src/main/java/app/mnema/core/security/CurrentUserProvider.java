package app.mnema.core.security;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CurrentUserProvider {

    public UUID getUserId(Jwt jwt) {
        String claim = jwt.getClaimAsString("user_id");
        if (claim == null) {
            throw new IllegalStateException("JWT does not contain 'user_id' claim");
        }
        return UUID.fromString(claim);
    }
}
