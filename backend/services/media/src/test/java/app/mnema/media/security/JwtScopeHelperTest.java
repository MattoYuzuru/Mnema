package app.mnema.media.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JwtScopeHelperTest {

    JwtScopeHelper helper = new JwtScopeHelper();

    @Test
    void hasAnyScope_readsScopesFromScpAndScopeClaims() {
        Jwt jwt = new Jwt(
                "token",
                Instant.parse("2026-04-07T00:00:00Z"),
                Instant.parse("2026-04-08T00:00:00Z"),
                Map.of("alg", "none"),
                Map.of("scp", List.of("media.internal"), "scope", "user.read media.read_all")
        );

        assertThat(helper.hasAnyScope(jwt, Set.of("media.internal"))).isTrue();
        assertThat(helper.hasAnyScope(jwt, Set.of("media.read_all"))).isTrue();
        assertThat(helper.hasAnyScope(jwt, Set.of("missing.scope"))).isFalse();
    }

    @Test
    void hasAnyScope_returnsFalseForMissingInputs() {
        Jwt jwt = new Jwt(
                "token",
                Instant.parse("2026-04-07T00:00:00Z"),
                Instant.parse("2026-04-08T00:00:00Z"),
                Map.of("alg", "none"),
                Map.of("sub", "x")
        );

        assertThat(helper.hasAnyScope(null, Set.of("media.internal"))).isFalse();
        assertThat(helper.hasAnyScope(jwt, Set.of())).isFalse();
        assertThat(helper.hasAnyScope(jwt, null)).isFalse();
    }
}
