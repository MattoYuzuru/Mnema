package app.mnema.core.user.client;

import app.mnema.core.user.config.UserClientProps;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

@Component
public class UserApiClient {

    private final RestClient restClient;
    private final UserClientProps props;

    public UserApiClient(@Qualifier("userRestClient") RestClient userRestClient, UserClientProps props) {
        this.restClient = userRestClient;
        this.props = props;
    }

    public InternalUserModerationState getModerationState(UUID userId) {
        RestClient.RequestHeadersSpec<?> request = restClient.get()
                .uri("/internal/users/{userId}/moderation", userId);

        String bearer = resolveBearerToken();
        if (bearer != null && !bearer.isBlank()) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }

        InternalUserModerationState response = request.retrieve()
                .body(InternalUserModerationState.class);
        if (response == null) {
            throw new IllegalStateException("User moderation response is empty for " + userId);
        }
        return response;
    }

    private String resolveBearerToken() {
        if (props.internalToken() != null && !props.internalToken().isBlank()) {
            return props.internalToken();
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
            return jwtAuthenticationToken.getToken().getTokenValue();
        }

        return null;
    }
}
