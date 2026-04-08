package app.mnema.core.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentUserProviderTest {

    @Test
    void getUserIdReturnsUuidClaimAndRejectsMissingClaim() {
        CurrentUserProvider provider = new CurrentUserProvider();
        UUID userId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").claim("user_id", userId.toString()).build();
        Jwt missingClaimJwt = Jwt.withTokenValue("token").header("alg", "none").claim("sub", "user-123").build();

        assertThat(provider.getUserId(jwt)).isEqualTo(userId);
        assertThatThrownBy(() -> provider.getUserId(missingClaimJwt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("user_id");
    }

    @Test
    void getUsernameUsesUsernamePreferredUsernameAndEmailFallbacks() {
        CurrentUserProvider provider = new CurrentUserProvider();

        Jwt usernameJwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("username", "  root-admin  ")
                .build();
        Jwt preferredUsernameJwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("preferred_username", " preferred ")
                .build();
        Jwt emailJwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("email", "person@example.com")
                .build();
        Jwt rawEmailJwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("email", "service-account")
                .build();

        assertThat(provider.getUsername(usernameJwt)).isEqualTo("root-admin");
        assertThat(provider.getUsername(preferredUsernameJwt)).isEqualTo("preferred");
        assertThat(provider.getUsername(emailJwt)).isEqualTo("person");
        assertThat(provider.getUsername(rawEmailJwt)).isEqualTo("service-account");
    }

    @Test
    void getUsernameRejectsMissingClaims() {
        CurrentUserProvider provider = new CurrentUserProvider();
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("username", "   ")
                .claim("preferred_username", "")
                .build();

        assertThatThrownBy(() -> provider.getUsername(jwt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("username")
                .hasMessageContaining("email");
    }
}
