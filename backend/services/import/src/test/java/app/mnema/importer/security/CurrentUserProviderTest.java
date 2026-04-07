package app.mnema.importer.security;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentUserProviderTest {

    private final CurrentUserProvider provider = new CurrentUserProvider();

    @Test
    void resolvesUserIdFromClaim() {
        UUID userId = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("user_id", userId.toString())
                .build();

        assertTrue(provider.getUserId(jwt).isPresent());
        assertEquals(userId, provider.requireUserId(jwt));
    }

    @Test
    void throwsWhenClaimIsMissing() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", "user")
                .build();

        assertFalse(provider.getUserId(jwt).isPresent());
        assertThrows(IllegalStateException.class, () -> provider.requireUserId(jwt));
    }
}
