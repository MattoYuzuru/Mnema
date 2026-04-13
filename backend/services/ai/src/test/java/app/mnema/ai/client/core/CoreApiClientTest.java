package app.mnema.ai.client.core;

import app.mnema.ai.client.core.CoreApiClient.CoreFieldTemplate;
import app.mnema.ai.client.core.CoreApiClient.CorePublicDeckResponse;
import app.mnema.ai.client.core.CoreApiClient.CoreTemplateResponse;
import app.mnema.ai.client.core.CoreApiClient.CoreUserCardDetail;
import app.mnema.ai.client.core.CoreApiClient.CoreUserCardPage;
import app.mnema.ai.client.core.CoreApiClient.CoreUserCardResponse;
import app.mnema.ai.client.core.CoreApiClient.CoreUserDeckResponse;
import app.mnema.ai.client.core.CoreApiClient.CreateCardRequestPayload;
import app.mnema.ai.client.core.CoreApiClient.MissingFieldCardsRequest;
import app.mnema.ai.client.core.CoreApiClient.UpdateUserCardRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class CoreApiClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsDeckTemplateCardsAndMissingFields() {
        UUID userDeckId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        UUID userCardId = UUID.randomUUID();
        UUID detailId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("https://core.mnema.app");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        server.expect(requestTo("https://core.mnema.app/decks/%s".formatted(userDeckId)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andRespond(withSuccess("""
                        {"userDeckId":"%s","publicDeckId":"%s","currentVersion":7,"templateVersion":3}
                        """.formatted(userDeckId, publicDeckId), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://core.mnema.app/decks/public/%s?version=7".formatted(publicDeckId)))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"deckId":"%s","version":7,"templateId":"%s","templateVersion":3}
                        """.formatted(publicDeckId, templateId), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://core.mnema.app/templates/%s?version=3".formatted(templateId)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andRespond(withSuccess("""
                        {"templateId":"%s","version":3,"fields":[{"id":"%s","name":"Front","fieldType":"text","required":true,"ordinal":1}]}
                        """.formatted(templateId, UUID.randomUUID()), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://core.mnema.app/decks/%s/cards?page=2&limit=5".formatted(userDeckId)))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"content":[{"userCardId":"%s","effectiveContent":{"Front":"Q"}}]}
                        """.formatted(userCardId), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://core.mnema.app/decks/%s/cards/%s".formatted(userDeckId, detailId)))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"userCardId":"%s","effectiveContent":{"Front":"Q","Back":"A"}}
                        """.formatted(detailId), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://core.mnema.app/decks/%s/cards/missing-fields/cards".formatted(userDeckId)))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess("""
                        [{"userCardId":"%s","effectiveContent":{"Front":"Q"}}]
                        """.formatted(userCardId), MediaType.APPLICATION_JSON));

        CoreApiClient client = new CoreApiClient(builder, new CoreClientProps("https://core.mnema.app", ""));

        CoreUserDeckResponse deck = client.getUserDeck(userDeckId, "access-token");
        CorePublicDeckResponse publicDeck = client.getPublicDeck(publicDeckId, 7);
        CoreTemplateResponse template = client.getTemplate(templateId, 3, "access-token");
        CoreUserCardPage page = client.getUserCards(userDeckId, 2, 5, "access-token");
        CoreUserCardDetail detail = client.getUserCard(userDeckId, detailId, "access-token");
        List<CoreUserCardResponse> missing = client.getMissingFieldCards(userDeckId, new MissingFieldCardsRequest(List.of("Front"), 5, null), "access-token");

        assertThat(deck.publicDeckId()).isEqualTo(publicDeckId);
        assertThat(publicDeck.templateId()).isEqualTo(templateId);
        assertThat(template.fields()).singleElement().extracting(CoreFieldTemplate::name).isEqualTo("Front");
        assertThat(page.content()).hasSize(1);
        assertThat(detail.userCardId()).isEqualTo(detailId);
        assertThat(missing).singleElement().extracting(CoreUserCardResponse::userCardId).isEqualTo(userCardId);
        server.verify();
    }

    @Test
    void addCardsUpdateCardAndFallbackGlobalScope() {
        UUID userDeckId = UUID.randomUUID();
        UUID userCardId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("https://core.mnema.app");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        server.expect(requestTo("https://core.mnema.app/decks/%s/cards/batch?operationId=%s".formatted(userDeckId, operationId)))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andRespond(withSuccess("""
                        [{"userCardId":"%s","effectiveContent":{"Front":"Q"}}]
                        """.formatted(userCardId), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://core.mnema.app/decks/%s/cards/%s?scope=global".formatted(userDeckId, userCardId)))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(withStatus(org.springframework.http.HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"checksum is not unique\"}"));
        server.expect(requestTo("https://core.mnema.app/decks/%s/cards/%s?scope=local".formatted(userDeckId, userCardId)))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(withSuccess("""
                        {"userCardId":"%s","effectiveContent":{"Front":"Updated"}}
                        """.formatted(userCardId), MediaType.APPLICATION_JSON));

        CoreApiClient client = new CoreApiClient(builder, new CoreClientProps("https://core.mnema.app", ""));
        ObjectNode content = objectMapper.createObjectNode().put("Front", "Q");

        List<CoreUserCardResponse> created = client.addCards(
                userDeckId,
                List.of(new CreateCardRequestPayload(content, null, null, null, null, null)),
                "access-token",
                operationId
        );
        CoreUserCardResponse updated = client.updateUserCard(
                userDeckId,
                userCardId,
                new UpdateUserCardRequest(userCardId, null, false, false, null, content),
                "access-token",
                "global"
        );

        assertThat(created).hasSize(1);
        assertThat(updated.effectiveContent().path("Front").asText()).isEqualTo("Updated");
        server.verify();
    }

    @Test
    void handlesNullResponsesAndMissingAccessToken() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://core.mnema.app");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        UUID userDeckId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        server.expect(requestTo("https://core.mnema.app/decks/public/%s".formatted(userDeckId)))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://core.mnema.app/decks/%s/cards".formatted(userDeckId) + "?page=1&limit=10"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        CoreApiClient client = new CoreApiClient(builder, new CoreClientProps("https://core.mnema.app", ""));

        assertThat(client.addCards(userDeckId, List.of(), "access-token")).isEmpty();
        assertThat(client.getUserCards(userDeckId, 1, 10, "access-token").content()).isEmpty();
        assertThat(client.getMissingFieldCards(userDeckId, new MissingFieldCardsRequest(List.of(), 10, null), "access-token")).isEmpty();
        assertThatThrownBy(() -> client.getTemplate(templateId, null, " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing access token for core API");
        assertThatThrownBy(() -> client.getPublicDeck(userDeckId, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Core public deck response is empty");
        server.verify();
    }

    @Test
    void usesInternalRoutesWhenInternalTokenConfigured() {
        UUID userDeckId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        UUID userCardId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("https://core.mnema.app");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        server.expect(requestTo("https://core.mnema.app/internal/decks/%s".formatted(userDeckId)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer core-internal"))
                .andRespond(withSuccess("""
                        {"userDeckId":"%s","publicDeckId":"%s","currentVersion":7,"templateVersion":3}
                        """.formatted(userDeckId, UUID.randomUUID()), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://core.mnema.app/internal/templates/%s?version=3".formatted(templateId)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer core-internal"))
                .andRespond(withSuccess("""
                        {"templateId":"%s","version":3,"fields":[]}
                        """.formatted(templateId), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://core.mnema.app/internal/decks/%s/cards?page=1&limit=3".formatted(userDeckId)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer core-internal"))
                .andRespond(withSuccess("""
                        {"content":[{"userCardId":"%s","effectiveContent":{"Front":"Q"}}]}
                        """.formatted(userCardId), MediaType.APPLICATION_JSON));

        CoreApiClient client = new CoreApiClient(builder, new CoreClientProps("https://core.mnema.app", "core-internal"));

        assertThat(client.getUserDeck(userDeckId, "expired-user-token").userDeckId()).isEqualTo(userDeckId);
        assertThat(client.getTemplate(templateId, 3, "expired-user-token").templateId()).isEqualTo(templateId);
        assertThat(client.getUserCards(userDeckId, 1, 3, "expired-user-token").content())
                .singleElement()
                .extracting(CoreUserCardResponse::userCardId)
                .isEqualTo(userCardId);
        server.verify();
    }
}
