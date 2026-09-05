package app.mnema.identityaccount.federation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

/**
 * Provider tokens are used only during login; Mnema never calls provider APIs on a user's behalf later.
 */
public final class ProviderTokenDiscarder implements OAuth2AuthorizedClientRepository {
    @Override
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String registrationId,
                                                                     Authentication principal,
                                                                     HttpServletRequest request) {
        return null;
    }

    @Override
    public void saveAuthorizedClient(OAuth2AuthorizedClient client, Authentication principal,
                                     HttpServletRequest request, HttpServletResponse response) {
        // Deliberately retain no provider access/refresh token in memory, sessions or the account database.
    }

    @Override
    public void removeAuthorizedClient(String registrationId, Authentication principal,
                                       HttpServletRequest request, HttpServletResponse response) {
        // Nothing was retained by saveAuthorizedClient.
    }
}
