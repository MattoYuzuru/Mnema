package app.mnema.importer.client.core;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

@Component
public class CoreApiClient {

    private final RestClient restClient;

    public CoreApiClient(RestClient coreRestClient) {
        this.restClient = coreRestClient;
    }

    public CoreCardTemplateResponse createTemplate(String accessToken, CoreCardTemplateRequest request) {
        return restClient.post()
                .uri("/templates")
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .body(request)
                .retrieve()
                .body(CoreCardTemplateResponse.class);
    }

    public CorePublicDeckResponse getPublicDeck(String accessToken, UUID deckId, Integer version) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/decks/public/{deckId}")
                        .queryParamIfPresent("version", java.util.Optional.ofNullable(version))
                        .build(deckId))
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .retrieve()
                .body(CorePublicDeckResponse.class);
    }

    public CoreUserDeckResponse getUserDeck(String accessToken, UUID userDeckId) {
        return restClient.get()
                .uri("/decks/{deckId}", userDeckId)
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .retrieve()
                .body(CoreUserDeckResponse.class);
    }

    public CoreUserDeckResponse createDeck(String accessToken, CorePublicDeckRequest request) {
        return restClient.post()
                .uri("/decks")
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .body(request)
                .retrieve()
                .body(CoreUserDeckResponse.class);
    }

    public List<CoreUserCardResponse> addCardsBatch(String accessToken, UUID userDeckId, List<CoreCreateCardRequest> requests) {
        return restClient.post()
                .uri("/decks/{deckId}/cards/batch", userDeckId)
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .body(requests)
                .retrieve()
                .body(new ParameterizedTypeReference<List<CoreUserCardResponse>>() {});
    }

    public CoreCardTemplateResponse getTemplate(String accessToken, UUID templateId) {
        return restClient.get()
                .uri("/templates/{templateId}", templateId)
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .retrieve()
                .body(CoreCardTemplateResponse.class);
    }

    public CorePageResponse<CoreUserCardResponse> getUserCards(String accessToken, UUID userDeckId, int page, int limit) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/decks/{deckId}/cards")
                        .queryParam("page", page)
                        .queryParam("limit", limit)
                        .build(userDeckId))
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .retrieve()
                .body(new ParameterizedTypeReference<CorePageResponse<CoreUserCardResponse>>() {});
    }

    public void seedProgress(String accessToken, UUID userDeckId, List<CoreCardProgressRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return;
        }
        restClient.post()
                .uri("/review/decks/{deckId}/states/import", userDeckId)
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .body(requests)
                .retrieve()
                .toBodilessEntity();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
