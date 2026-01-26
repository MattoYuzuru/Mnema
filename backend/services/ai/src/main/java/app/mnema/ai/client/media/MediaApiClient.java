package app.mnema.ai.client.media;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.util.UUID;

@Component
public class MediaApiClient {

    private final RestClient restClient;
    private final MediaClientProps props;

    public MediaApiClient(RestClient.Builder restClientBuilder, MediaClientProps props) {
        this.restClient = restClientBuilder.baseUrl(props.baseUrl()).build();
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
