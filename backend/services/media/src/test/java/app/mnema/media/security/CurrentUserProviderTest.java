package app.mnema.media.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentUserProviderTest {

    CurrentUserProvider provider = new CurrentUserProvider();

    @Test
    void getUserId_readsValidClaim() {
        UUID userId = UUID.randomUUID();
        Jwt jwt = new Jwt(
                "token",
                Instant.parse("2026-04-07T00:00:00Z"),
                Instant.parse("2026-04-08T00:00:00Z"),
                Map.of("alg", "none"),
                Map.of("user_id", userId.toString())
        );

        assertThat(provider.getUserId(jwt)).contains(userId);
        assertThat(provider.requireUserId(jwt)).isEqualTo(userId);
    }

    @Test
    void requireUserId_rejectsMissingClaim() {
        Jwt jwt = new Jwt(
                "token",
                Instant.parse("2026-04-07T00:00:00Z"),
                Instant.parse("2026-04-08T00:00:00Z"),
                Map.of("alg", "none"),
                Map.of("sub", "user")
        );

        assertThat(provider.getUserId(jwt)).isEmpty();
        assertThatThrownBy(() -> provider.requireUserId(jwt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("user_id claim missing");
    }
}
