package app.mnema.learning.platform.security;

import app.mnema.learning.platform.api.ApiSecurityErrors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The filter keeps the public-profile claim of the Identity answer that authorized the request, and reads it strictly: anything but a JSON {@code true}
 * (absent, false, a string, a number, null) is "not ready", and a subject that differs from the token's is never trusted.
 */
class CurrentIdentityFilterClaimTest {
    private final UUID subject = UUID.randomUUID();

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @SuppressWarnings("unchecked")
    private MockHttpServletRequest run(int status, String body) throws Exception {
        IdentityHttp http = mock(IdentityHttp.class);
        HttpResponse<byte[]> answer = mock(HttpResponse.class);
        when(answer.statusCode()).thenReturn(status);
        when(answer.body()).thenReturn(body.replace("SUB", subject.toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(http.get(any(URI.class), anyString(), anyInt())).thenReturn(answer);
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject(subject.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        var filter = new CurrentIdentityFilter(http, URI.create("http://identity.test/userinfo"), new ApiSecurityErrors(JsonMapper.builder().build()));
        var request = new MockHttpServletRequest("GET", "/decks");
        var chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).as("the chain ran").isNotNull();
        return request;
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"sub\":\"SUB\",\"mnema_public_profile\":true}|true",
            "{\"sub\":\"SUB\",\"mnema_public_profile\":false}|false",
            "{\"sub\":\"SUB\"}|false",
            "{\"sub\":\"SUB\",\"mnema_public_profile\":\"true\"}|false",
            "{\"sub\":\"SUB\",\"mnema_public_profile\":1}|false",
            "{\"sub\":\"SUB\",\"mnema_public_profile\":null}|false",
            "{\"sub\":\"SUB\",\"mnema_public_profile\":[true]}|false"
    })
    void onlyAJsonTrueIsReady(String body, boolean expected) throws Exception {
        assertThat(IdentityClaims.publicProfileReady(run(200, body))).isEqualTo(expected);
    }

    @Test
    void theClaimNameIsTheOneIdentityWritesAccordingToTheSharedContractFile() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.exists(root.resolve("contracts/decks/publication.json"))) root = root.getParent();
        assertThat(root).isNotNull();
        String contract = JsonMapper.builder().build().readTree(java.nio.file.Files.readString(root.resolve("contracts/decks/publication.json")))
                .path("constants").path("identityClaim").stringValue(null);
        assertThat(IdentityClaims.PUBLIC_PROFILE_CLAIM).isEqualTo(contract);
    }

    @Test
    void aRequestWithoutAnIdentityCheckHasNoClaim() throws Exception {
        var request = new MockHttpServletRequest("GET", "/public/decks/x");
        new CurrentIdentityFilter(mock(IdentityHttp.class), URI.create("http://identity.test/userinfo"), new ApiSecurityErrors(JsonMapper.builder().build()))
                .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(IdentityClaims.publicProfileReady(request)).isFalse();
    }
}
