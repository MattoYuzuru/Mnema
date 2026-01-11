package app.mnema.importer.client.media;

import app.mnema.importer.config.MediaClientProps;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class MediaApiClient {

    private final RestClient restClient;
    private final MediaClientProps props;

    public MediaApiClient(RestClient mediaRestClient, MediaClientProps props) {
        this.restClient = mediaRestClient;
        this.props = props;
    }

    public UUID directUpload(UUID ownerUserId,
                             String kind,
                             String contentType,
                             String fileName,
                             long contentLength,
                             InputStream inputStream) {
        if (props.internalToken() == null || props.internalToken().isBlank()) {
            throw new IllegalStateException("app.media.internal-token is required for direct upload");
        }

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("meta", new MediaDirectUploadRequest(kind, contentType, fileName, ownerUserId))
                .contentType(MediaType.APPLICATION_JSON);
        builder.part("file", new SizedInputStreamResource(inputStream, contentLength, fileName))
                .contentType(MediaType.parseMediaType(contentType));

        MediaUploadResponse response = restClient.post()
                .uri("/internal/uploads")
                .header(HttpHeaders.AUTHORIZATION, bearer(props.internalToken()))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(builder.build())
                .retrieve()
                .body(MediaUploadResponse.class);

        if (response == null || response.mediaId() == null) {
            throw new IllegalStateException("Media upload failed");
        }
        return response.mediaId();
    }

    public List<MediaResolved> resolve(List<UUID> mediaIds) {
        Map<String, Object> payload = Map.of("mediaIds", mediaIds);
        RestClient.RequestBodySpec request = restClient.post()
                .uri("/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload);
        if (props.internalToken() != null && !props.internalToken().isBlank()) {
            request.header(HttpHeaders.AUTHORIZATION, bearer(props.internalToken()));
        }
        return request.retrieve()
                .body(new ParameterizedTypeReference<List<MediaResolved>>() {});
    }

    public Map<UUID, MediaResolved> resolveMap(List<UUID> mediaIds) {
        List<MediaResolved> resolved = resolve(mediaIds);
        if (resolved == null) {
            return Map.of();
        }
        return resolved.stream().collect(Collectors.toMap(MediaResolved::mediaId, m -> m));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private static class SizedInputStreamResource extends InputStreamResource {
        private final long contentLength;
        private final String filename;

        SizedInputStreamResource(InputStream inputStream, long contentLength, String filename) {
            super(inputStream);
            this.contentLength = contentLength;
            this.filename = filename;
        }

        @Override
        public long contentLength() {
            return contentLength;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
