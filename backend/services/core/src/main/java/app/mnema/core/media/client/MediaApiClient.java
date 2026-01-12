package app.mnema.core.media.client;

import app.mnema.core.media.config.MediaClientProps;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class MediaApiClient {
    private final RestClient restClient;
    private final MediaClientProps props;

    public MediaApiClient(RestClient mediaRestClient, MediaClientProps props) {
        this.restClient = mediaRestClient;
        this.props = props;
    }

    public List<MediaResolved> resolve(List<UUID> mediaIds) {
        return resolve(mediaIds, null);
    }

    public List<MediaResolved> resolve(List<UUID> mediaIds, String bearerToken) {
        if (mediaIds == null || mediaIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> payload = Map.of("mediaIds", mediaIds);
        RestClient.RequestBodySpec request = restClient.post()
                .uri("/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload);

        String token = resolveToken(bearerToken);
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, bearer(token));
        }

        List<MediaResolved> resolved = request.retrieve()
                .body(new ParameterizedTypeReference<List<MediaResolved>>() {});
        return resolved == null ? List.of() : resolved;
    }

    private String resolveToken(String bearerToken) {
        if (bearerToken != null && !bearerToken.isBlank()) {
            return bearerToken;
        }
        if (props.internalToken() != null && !props.internalToken().isBlank()) {
            return props.internalToken();
        }
        return null;
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
