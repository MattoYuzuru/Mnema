package app.mnema.ai.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentUserProviderTest {

    private final CurrentUserProvider provider = new CurrentUserProvider();

    @Test
    void returnsOptionalUserIdWhenClaimPresent() {
        UUID userId = UUID.randomUUID();
        Jwt jwt = jwt(userId.toString());

        assertThat(provider.getUserId(jwt)).contains(userId);
        assertThat(provider.requireUserId(jwt)).isEqualTo(userId);
    }

    @Test
    void returnsEmptyForMissingJwtOrClaimAndRequireThrows() {
        assertThat(provider.getUserId(null)).isEmpty();
        assertThat(provider.getUserId(jwt(" "))).isEmpty();
        assertThatThrownBy(() -> provider.requireUserId(jwt(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("user_id claim missing");
    }

    private Jwt jwt(String claim) {
        Instant now = Instant.now();
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60));
        if (claim != null) {
            builder.claim("user_id", claim);
        }
        return builder.build();
    }
}
