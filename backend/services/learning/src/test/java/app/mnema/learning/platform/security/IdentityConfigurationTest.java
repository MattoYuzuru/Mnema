package app.mnema.learning.platform.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.BadJwtException;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityConfigurationTest {
    @Test
    void missingIssuerUsesAnAuthenticationFailureRatherThanAnInternalServiceFailure() throws Exception {
        try (var http = new IdentityHttp(Duration.ofSeconds(1), 1)) {
            var decoder = new LearningSecurityConfiguration().learningJwtDecoder(new IdentityEndpoints("", null), http);
            assertThatThrownBy(() -> decoder.decode("untrusted-token")).isInstanceOf(BadJwtException.class);
        }
    }

    @Test
    void oversizedTokenUsesAnAuthenticationFailureWithoutFetchingKeys() throws Exception {
        try (var http = new IdentityHttp(Duration.ofSeconds(1), 1)) {
            var endpoints = IdentityEndpoints.configured("https://identity.invalid", "", false);
            var decoder = new LearningSecurityConfiguration().learningJwtDecoder(endpoints, http);
            assertThatThrownBy(() -> decoder.decode("a".repeat(16_385))).isInstanceOf(BadJwtException.class);
        }
    }

    @Test
    void keepsConfiguredIssuerAndAppendsEndpointsToItsPath() {
        var endpoints = IdentityEndpoints.configured("https://identity.example/issuer/", "", false);
        assertThat(endpoints.issuer()).isEqualTo("https://identity.example/issuer/");
        assertThat(endpoints.endpoint("/userinfo")).isEqualTo(URI.create("https://identity.example/issuer/userinfo"));
        var internal = IdentityEndpoints.configured("https://issuer.example", "https://internal.example/auth", false);
        assertThat(internal.issuer()).isEqualTo("https://issuer.example");
        assertThat(internal.endpoint("/oauth2/jwks")).isEqualTo(URI.create("https://internal.example/auth/oauth2/jwks"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://issuer.example", "https://user:password@issuer.example", "https://issuer.example?q=x",
            "https://issuer.example#fragment", "https://issuer.example/a/../b", "file:///tmp/key", "/relative"})
    void rejectsUnsafeIssuerConfiguration(String uri) {
        assertThatThrownBy(() -> IdentityEndpoints.configured(uri, "", true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void plaintextRequiresExplicitLiteralLoopbackOptIn() {
        assertThatThrownBy(() -> IdentityEndpoints.configured("https://issuer.example", "http://127.0.0.1:8000", false))
                .isInstanceOf(IllegalArgumentException.class);
        for (String host : List.of("localhost", "identity", "10.0.0.1", "127.0.0.1.attacker.test")) {
            assertThatThrownBy(() -> IdentityEndpoints.configured("https://issuer.example", "http://" + host, true))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String host : List.of("127.0.0.1", "[::1]")) {
            assertThat(IdentityEndpoints.configured("https://issuer.example", "http://" + host + ":8000", true).base())
                    .isEqualTo(URI.create("http://" + host + ":8000"));
        }
    }

    @Test
    void rejectsInvalidTransportLimitsBeforeCreatingClient() {
        for (Duration timeout : List.of(Duration.ZERO, Duration.ofMillis(-1), Duration.ofSeconds(11))) {
            assertThatThrownBy(() -> new IdentityHttp(timeout, 1)).isInstanceOf(IllegalArgumentException.class);
        }
        for (int concurrency : new int[]{0, -1, 257}) {
            assertThatThrownBy(() -> new IdentityHttp(Duration.ofSeconds(1), concurrency))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new IdentityHttp(null, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonCanonicalAndInvalidSubjectsAndGenerations() {
        var validator = new LearningTokenValidator();
        for (String subject : List.of("00000000-0000-0000-0000-000000000000", "1-1-1-1-1", "not-uuid",
                "11111111-1111-1111-1111-111111111111")) {
            assertThat(validator.validate(jwt(subject, "0")).hasErrors()).isTrue();
        }
        String valid = UUID.randomUUID().toString();
        assertThat(validator.validate(jwt(valid, "0")).hasErrors()).isFalse();
        for (String generation : List.of("-1", "not-a-number", "9223372036854775808")) {
            assertThat(validator.validate(jwt(valid, generation)).hasErrors()).isTrue();
        }
    }

    private static Jwt jwt(String subject, String generation) {
        return Jwt.withTokenValue("fixture").header("typ", "at+jwt").subject(subject)
                .audience(List.of("mnema-api")).claim("generation", generation)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
    }
}
