package app.mnema.ai.client.core;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Component
public class CoreApiClient {

    private static final ParameterizedTypeReference<List<CoreUserCardResponse>> CARD_LIST_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final Logger LOGGER = LoggerFactory.getLogger(CoreApiClient.class);

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
        return addCards(userDeckId, requests, accessToken, null);
    }

    public List<CoreUserCardResponse> addCards(UUID userDeckId,
                                               List<CreateCardRequestPayload> requests,
                                               String accessToken,
                                               UUID operationId) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        List<CoreUserCardResponse> response = restClient.post()
                .uri(uriBuilder -> uriBuilder.path("/decks/{userDeckId}/cards/batch")
                        .queryParamIfPresent("operationId", java.util.Optional.ofNullable(operationId))
                        .build(userDeckId))
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
        return updateUserCard(userDeckId, userCardId, request, accessToken, null, null);
    }

    public CoreUserCardResponse updateUserCard(UUID userDeckId,
                                               UUID userCardId,
                                               UpdateUserCardRequest request,
                                               String accessToken,
                                               String scope) {
        return updateUserCard(userDeckId, userCardId, request, accessToken, scope, null);
    }

    public CoreUserCardResponse updateUserCard(UUID userDeckId,
                                               UUID userCardId,
                                               UpdateUserCardRequest request,
                                               String accessToken,
                                               String scope,
                                               UUID operationId) {
        try {
            return doUpdateUserCard(userDeckId, userCardId, request, accessToken, scope, operationId);
        } catch (RestClientResponseException ex) {
            if (scope != null && scope.equalsIgnoreCase("global") && shouldFallbackGlobalUpdate(ex)) {
                LOGGER.warn("Global card update failed, retrying locally deckId={} cardId={} status={} reason={}",
                        userDeckId, userCardId, ex.getRawStatusCode(), summarizeError(ex));
                return doUpdateUserCard(userDeckId, userCardId, request, accessToken, "local", null);
            }
            throw ex;
        }
    }

    private CoreUserCardResponse doUpdateUserCard(UUID userDeckId,
                                                  UUID userCardId,
                                                  UpdateUserCardRequest request,
                                                  String accessToken,
                                                  String scope,
                                                  UUID operationId) {
        CoreUserCardResponse response = restClient.patch()
                .uri(uriBuilder -> {
                    uriBuilder.path("/decks/{userDeckId}/cards/{userCardId}");
                    if (scope != null && !scope.isBlank()) {
                        uriBuilder.queryParam("scope", scope);
                    }
                    if (operationId != null) {
                        uriBuilder.queryParam("operationId", operationId);
                    }
                    return uriBuilder.build(userDeckId, userCardId);
                })
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .body(request)
                .retrieve()
                .body(CoreUserCardResponse.class);
        if (response == null) {
            throw new IllegalStateException("Core card update response is empty");
        }
        return response;
    }

    private boolean shouldFallbackGlobalUpdate(RestClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        String message = body == null || body.isBlank() ? ex.getMessage() : body;
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("checksum is not unique")
                || normalized.contains("public card not found in latest version")
                || normalized.contains("public card checksum is not unique")
                || normalized.contains("failed to create updated public card");
    }

    private String summarizeError(RestClientResponseException ex) {
        if (ex == null) {
            return "unknown";
        }
        String message = ex.getResponseBodyAsString();
        if (message == null || message.isBlank()) {
            message = ex.getMessage();
        }
        if (message == null) {
            return "unknown";
        }
        message = message.replaceAll("\\s+", " ").trim();
        return message.length() > 200 ? message.substring(0, 200) : message;
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

    public CoreUserCardDetail getUserCard(UUID userDeckId, UUID userCardId, String accessToken) {
        CoreUserCardDetail response = restClient.get()
                .uri("/decks/{userDeckId}/cards/{userCardId}", userDeckId, userCardId)
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .retrieve()
                .body(CoreUserCardDetail.class);
        if (response == null) {
            throw new IllegalStateException("Core card response is empty");
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
            UUID authorId,
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
            UUID publicCardId,
            boolean isCustom,
            JsonNode effectiveContent
    ) {
    }

    public record CoreUserCardDetail(
            UUID userCardId,
            UUID publicCardId,
            boolean isCustom,
            boolean isDeleted,
            String personalNote,
            String[] tags,
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
            Integer limit,
            List<FieldLimit> fieldLimits
    ) {
        public record FieldLimit(
                String field,
                Integer limit
        ) {
        }
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
