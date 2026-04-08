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

    public String getUsername(Jwt jwt) {
        String username = normalize(jwt.getClaimAsString("username"));
        if (username != null) {
            return username;
        }

        String preferredUsername = normalize(jwt.getClaimAsString("preferred_username"));
        if (preferredUsername != null) {
            return preferredUsername;
        }

        String email = normalize(jwt.getClaimAsString("email"));
        if (email != null) {
            int separator = email.indexOf('@');
            return separator > 0 ? email.substring(0, separator) : email;
        }

        throw new IllegalStateException("JWT does not contain 'username' or 'email' claim");
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
