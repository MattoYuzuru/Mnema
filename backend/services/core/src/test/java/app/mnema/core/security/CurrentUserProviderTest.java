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
}
