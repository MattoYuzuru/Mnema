package app.mnema.media.security;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtScopeHelperTest {

    private final JwtScopeHelper helper = new JwtScopeHelper();

    @Test
    void readsScopesFromScpClaim() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("scp", List.of("user.read", "media.internal"))
                .build();

        assertTrue(helper.hasAnyScope(jwt, Set.of("media.internal")));
    }

    @Test
    void readsScopesFromSpaceSeparatedScopeClaim() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("scope", "user.read user.write")
                .build();

        assertTrue(helper.hasAnyScope(jwt, Set.of("user.write")));
    }

    @Test
    void returnsFalseWhenScopeIsMissing() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("sub", "user")
                .build();

        assertFalse(helper.hasAnyScope(jwt, Set.of("media.internal")));
    }
}
