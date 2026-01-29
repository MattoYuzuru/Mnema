package app.mnema.ai.client.core;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

@Component
public class CoreApiClient {

    private static final ParameterizedTypeReference<List<CoreUserCardResponse>> CARD_LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public CoreApiClient(RestClient.Builder restClientBuilder, CoreClientProps props) {
        this.restClient = restClientBuilder.baseUrl(props.baseUrl()).build();
    }

    public CoreUserDeckResponse getUserDeck(UUID userDeckId, String accessToken) {
        CoreUserDeckResponse response = restClient.get()
                .uri("/decks/{userDeckId}", userDeckId)
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .retrieve()
                .body(CoreUserDeckResponse.class);
        if (response == null) {
            throw new IllegalStateException("Core deck response is empty");
        }
        return response;
    }

    public CorePublicDeckResponse getPublicDeck(UUID deckId, Integer version) {
        CorePublicDeckResponse response = restClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/decks/public/{deckId}");
                    if (version != null) {
                        uriBuilder.queryParam("version", version);
                    }
                    return uriBuilder.build(deckId);
                })
                .retrieve()
                .body(CorePublicDeckResponse.class);
        if (response == null) {
            throw new IllegalStateException("Core public deck response is empty");
        }
        return response;
    }

    public CoreTemplateResponse getTemplate(UUID templateId, Integer version, String accessToken) {
        CoreTemplateResponse response = restClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/templates/{templateId}");
                    if (version != null) {
                        uriBuilder.queryParam("version", version);
                    }
                    return uriBuilder.build(templateId);
                })
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .retrieve()
                .body(CoreTemplateResponse.class);
        if (response == null) {
            throw new IllegalStateException("Core template response is empty");
        }
        return response;
    }

    public List<CoreUserCardResponse> addCards(UUID userDeckId,
                                               List<CreateCardRequestPayload> requests,
                                               String accessToken) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        List<CoreUserCardResponse> response = restClient.post()
                .uri("/decks/{userDeckId}/cards/batch", userDeckId)
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .body(requests)
                .retrieve()
                .body(CARD_LIST_TYPE);
        return response == null ? List.of() : response;
    }

    public CoreUserCardResponse updateUserCard(UUID userDeckId,
                                               UUID userCardId,
                                               UpdateUserCardRequest request,
                                               String accessToken) {
        CoreUserCardResponse response = restClient.patch()
                .uri("/decks/{userDeckId}/cards/{userCardId}", userDeckId, userCardId)
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .body(request)
                .retrieve()
                .body(CoreUserCardResponse.class);
        if (response == null) {
            throw new IllegalStateException("Core card update response is empty");
        }
        return response;
    }

    public CoreUserCardPage getUserCards(UUID userDeckId, int page, int limit, String accessToken) {
        CoreUserCardPage response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/decks/{userDeckId}/cards")
                        .queryParam("page", page)
                        .queryParam("limit", limit)
                        .build(userDeckId))
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .retrieve()
                .body(CoreUserCardPage.class);
        if (response == null) {
            return new CoreUserCardPage(List.of());
        }
        return response;
    }

    public List<CoreUserCardResponse> getMissingFieldCards(UUID userDeckId,
                                                           MissingFieldCardsRequest request,
                                                           String accessToken) {
        if (request == null || request.fields() == null || request.fields().isEmpty()) {
            return List.of();
        }
        List<CoreUserCardResponse> response = restClient.post()
                .uri("/decks/{userDeckId}/cards/missing-fields/cards", userDeckId)
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .body(request)
                .retrieve()
                .body(CARD_LIST_TYPE);
        return response == null ? List.of() : response;
    }

    private String bearer(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Missing access token for core API");
        }
        return "Bearer " + token;
    }

    public record CoreUserDeckResponse(
            UUID userDeckId,
            UUID publicDeckId,
            Integer currentVersion,
            Integer templateVersion
    ) {
    }

    public record CorePublicDeckResponse(
            UUID deckId,
            Integer version,
            String name,
            String description,
            String language,
            UUID templateId,
            Integer templateVersion
    ) {
    }

    public record CoreTemplateResponse(
            UUID templateId,
            Integer version,
            Integer latestVersion,
            String name,
            String description,
            JsonNode layout,
            JsonNode aiProfile,
            List<CoreFieldTemplate> fields
    ) {
    }

    public record CoreFieldTemplate(
            UUID fieldId,
            String name,
            String label,
            String fieldType,
            boolean isRequired,
            boolean isOnFront,
            Integer orderIndex
    ) {
    }

    public record CoreUserCardResponse(
            UUID userCardId,
            JsonNode effectiveContent
    ) {
    }

    public record UpdateUserCardRequest(
            UUID userCardId,
            UUID publicCardId,
            boolean isCustom,
            boolean isDeleted,
            String personalNote,
            JsonNode effectiveContent
    ) {
    }

    public record CoreUserCardPage(
            List<CoreUserCardResponse> content
    ) {
    }

    public record MissingFieldCardsRequest(
            List<String> fields,
            Integer limit
    ) {
    }

    public record CreateCardRequestPayload(
            JsonNode content,
            Integer orderIndex,
            String[] tags,
            String personalNote,
            JsonNode contentOverride,
            String checksum
    ) {
    }
}
