package app.mnema.learning.platform.security;

import app.mnema.learning.platform.id.UuidPolicy;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

final class LearningTokenValidator implements OAuth2TokenValidator<Jwt> {
    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        try {
            String subject = jwt.getSubject();
            UUID actor = UuidPolicy.requireEntityId(UUID.fromString(subject), "subject");
            if (!actor.toString().equals(subject) || !jwt.getAudience().contains("mnema-api")
                    || jwt.getExpiresAt() == null || jwt.getIssuedAt() == null
                    || !(jwt.getClaim("generation") instanceof String generation)
                    || Long.parseLong(generation) < 0) {
                return invalid();
            }
            return OAuth2TokenValidatorResult.success();
        } catch (RuntimeException exception) {
            return invalid();
        }
    }

    private static OAuth2TokenValidatorResult invalid() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    }
}
