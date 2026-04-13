package app.mnema.importer.client.media;

import app.mnema.importer.config.MediaClientProps;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MediaApiClientTest {

    @Test
    void directUploadRequiresInternalToken() {
        MediaApiClient client = new MediaApiClient(RestClient.builder().build(), new MediaClientProps("https://media.mnema.app", " "));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> client.directUpload(UUID.randomUUID(), "import_file", "text/csv", "deck.csv", 4, new ByteArrayInputStream(new byte[]{1})));

        assertThat(ex).hasMessage("app.media.internal-token is required for direct upload");
    }

    @Test
    void directUploadPostsMultipartAndReturnsMediaId() {
        UUID ownerId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("https://media.mnema.app");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://media.mnema.app/internal/uploads"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer internal-token"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andRespond(withSuccess("""
                        {"mediaId":"%s","status":"ready"}
                        """.formatted(mediaId), MediaType.APPLICATION_JSON));

        MediaApiClient client = new MediaApiClient(builder.build(), new MediaClientProps("https://media.mnema.app", "internal-token"));
        UUID uploaded = client.directUpload(ownerId, "import_file", "text/csv", "deck.csv", 4, new ByteArrayInputStream(new byte[]{1, 2, 3, 4}));

        assertThat(uploaded).isEqualTo(mediaId);
        server.verify();
    }

    @Test
    void resolveAndResolveMapUseInternalTokenWhenConfigured() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("https://media.mnema.app");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        server.expect(requestTo("https://media.mnema.app/resolve"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer internal-token"))
                .andExpect(jsonPath("$.urlTarget").value("INTERNAL"))
                .andRespond(withSuccess("""
                        [{"mediaId":"%s","url":"https://cdn.example/%s.png"}]
                        """.formatted(first, first), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://media.mnema.app/resolve"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer internal-token"))
                .andExpect(jsonPath("$.urlTarget").value("INTERNAL"))
                .andRespond(withSuccess("""
                        [{"mediaId":"%s","url":"https://cdn.example/%s.png"},{"mediaId":"%s","url":"https://cdn.example/%s.png"}]
                        """.formatted(first, first, second, second), MediaType.APPLICATION_JSON));

        MediaApiClient client = new MediaApiClient(builder.build(), new MediaClientProps("https://media.mnema.app", "internal-token"));

        List<MediaResolved> resolved = client.resolve(List.of(first));
        Map<UUID, MediaResolved> resolvedMap = client.resolveMap(List.of(first, second));

        assertThat(resolved).hasSize(1);
        assertThat(resolved.getFirst().mediaId()).isEqualTo(first);
        assertThat(resolvedMap).containsKeys(first, second);
        server.verify();
    }
}
