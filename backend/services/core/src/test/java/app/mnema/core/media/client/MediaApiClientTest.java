package app.mnema.core.media.client;

import app.mnema.core.media.config.MediaClientProps;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MediaApiClientTest {

    @Test
    void resolveReturnsEmptyListForNullOrEmptyInput() {
        MediaApiClient client = new MediaApiClient(RestClient.builder().build(), new MediaClientProps("https://media.mnema.app", "internal-token"));

        assertThat(client.resolve(null)).isEmpty();
        assertThat(client.resolve(List.of())).isEmpty();
    }

    @Test
    void resolvePrefersExplicitBearerToken() {
        UUID mediaId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://media.mnema.app/resolve"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer bearer-token"))
                .andRespond(withSuccess("""
                        [{"mediaId":"%s","kind":"image","url":"https://cdn.example/media.png","mimeType":"image/png","expiresAt":"2026-04-07T10:15:30Z"}]
                        """.formatted(mediaId), MediaType.APPLICATION_JSON));

        MediaApiClient client = new MediaApiClient(
                builder.baseUrl("https://media.mnema.app").build(),
                new MediaClientProps("https://media.mnema.app", "internal-token")
        );

        List<MediaResolved> resolved = client.resolve(List.of(mediaId), "bearer-token");

        assertThat(resolved).singleElement().satisfies(item -> {
            assertThat(item.mediaId()).isEqualTo(mediaId);
            assertThat(item.kind()).isEqualTo("image");
            assertThat(item.url()).isEqualTo("https://cdn.example/media.png");
            assertThat(item.expiresAt()).isEqualTo(Instant.parse("2026-04-07T10:15:30Z"));
        });
        server.verify();
    }

    @Test
    void resolveFallsBackToInternalTokenAndHandlesNullResponse() {
        UUID mediaId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://media.mnema.app/resolve"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer internal-token"))
                .andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

        MediaApiClient client = new MediaApiClient(
                builder.baseUrl("https://media.mnema.app").build(),
                new MediaClientProps("https://media.mnema.app", "internal-token")
        );

        assertThat(client.resolve(List.of(mediaId), " ")).isEmpty();
        server.verify();
    }
}
