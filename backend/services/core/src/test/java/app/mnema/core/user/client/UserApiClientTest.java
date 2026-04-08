package app.mnema.core.user.client;

import app.mnema.core.user.config.UserClientProps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class UserApiClientTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getModerationState_prefersConfiguredInternalToken() {
        UUID userId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://user.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UserApiClient client = new UserApiClient(builder.build(), new UserClientProps("http://user.test", "internal-secret"));

        server.expect(requestTo("http://user.test/internal/users/" + userId + "/moderation"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer internal-secret"))
                .andRespond(withSuccess(
                        "{\"id\":\"" + userId + "\",\"admin\":true,\"banned\":false}",
                        MediaType.APPLICATION_JSON
                ));

        InternalUserModerationState state = client.getModerationState(userId);

        assertThat(state.id()).isEqualTo(userId);
        assertThat(state.admin()).isTrue();
        server.verify();
    }

    @Test
    void getModerationState_fallsBackToCurrentJwtToken() {
        UUID userId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://user.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UserApiClient client = new UserApiClient(builder.build(), new UserClientProps("http://user.test", ""));
        Jwt jwt = Jwt.withTokenValue("user-jwt")
                .header("alg", "none")
                .claim("sub", "user")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        server.expect(requestTo("http://user.test/internal/users/" + userId + "/moderation"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer user-jwt"))
                .andRespond(withSuccess(
                        "{\"id\":\"" + userId + "\",\"admin\":false,\"banned\":false}",
                        MediaType.APPLICATION_JSON
                ));

        InternalUserModerationState state = client.getModerationState(userId);

        assertThat(state.id()).isEqualTo(userId);
        assertThat(state.admin()).isFalse();
        server.verify();
    }

    @Test
    void getModerationState_rejectsEmptyBody() {
        UUID userId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://user.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        UserApiClient client = new UserApiClient(builder.build(), new UserClientProps("http://user.test", ""));

        server.expect(requestTo("http://user.test/internal/users/" + userId + "/moderation"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("null"));

        assertThatThrownBy(() -> client.getModerationState(userId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
        server.verify();
    }
}
