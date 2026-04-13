package app.mnema.ai.client.media;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MediaApiClientTest {

    @Test
    void directUploadRequiresInternalToken() {
        MediaApiClient client = new MediaApiClient(RestClient.builder(), new MediaClientProps("https://media.mnema.app", " "));

        assertThatThrownBy(() -> client.directUpload(UUID.randomUUID(), "card_audio", "audio/mpeg", "audio.mp3", 4, new ByteArrayInputStream(new byte[]{1})))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("app.media.internal-token is required for direct upload");
    }

    @Test
    void directUploadAndResolveUseExpectedAuthToken() {
        UUID ownerId = UUID.randomUUID();
        UUID mediaId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("https://media.mnema.app");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        server.expect(requestTo("https://media.mnema.app/internal/uploads"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer internal-token"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andRespond(withSuccess("""
                        {"mediaId":"%s","status":"ready"}
                        """.formatted(mediaId), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://media.mnema.app/resolve"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer internal-token"))
                .andExpect(jsonPath("$.urlTarget").value("INTERNAL"))
                .andRespond(withSuccess("""
                        [{"mediaId":"%s","kind":"ai_import","mimeType":"text/plain","url":"https://cdn/%s.txt","sizeBytes":12}]
                        """.formatted(mediaId, mediaId), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://media.mnema.app/resolve"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer internal-token"))
                .andExpect(jsonPath("$.urlTarget").value("INTERNAL"))
                .andRespond(withSuccess("""
                        [{"mediaId":"%s","kind":"ai_import","mimeType":"text/plain","url":"https://cdn/%s.txt","sizeBytes":12}]
                        """.formatted(mediaId, mediaId), MediaType.APPLICATION_JSON));

        MediaApiClient client = new MediaApiClient(builder, new MediaClientProps("https://media.mnema.app", "internal-token"));

        UUID uploaded = client.directUpload(ownerId, "card_audio", "audio/mpeg", "audio.mp3", 4, new ByteArrayInputStream(new byte[]{1, 2, 3, 4}));
        List<MediaResolved> external = client.resolve(List.of(mediaId), "access-token");
        List<MediaResolved> internal = client.resolve(List.of(mediaId), " ");

        assertThat(uploaded).isEqualTo(mediaId);
        assertThat(external).singleElement().extracting(MediaResolved::mediaId).isEqualTo(mediaId);
        assertThat(internal).singleElement().extracting(MediaResolved::sizeBytes).isEqualTo(12L);
        server.verify();
    }

    @Test
    void directUploadRejectsEmptyResponse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://media.mnema.app");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://media.mnema.app/internal/uploads"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        MediaApiClient client = new MediaApiClient(builder, new MediaClientProps("https://media.mnema.app", "internal-token"));

        assertThatThrownBy(() -> client.directUpload(UUID.randomUUID(), "card_audio", "audio/mpeg", "audio.mp3", 4, new ByteArrayInputStream(new byte[]{1, 2, 3, 4})))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Media upload failed");
        server.verify();
    }
}
