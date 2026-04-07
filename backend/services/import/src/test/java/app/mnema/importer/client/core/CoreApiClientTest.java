package app.mnema.importer.client.core;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CoreApiClientTest {

    @Test
    void getPublicDeckIncludesOptionalVersion() {
        UUID deckId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("https://core.mnema.app");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://core.mnema.app/decks/public/%s?version=3".formatted(deckId)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andRespond(withSuccess("""
                        {"deckId":"%s","version":3,"templateId":"%s"}
                        """.formatted(deckId, UUID.randomUUID()), MediaType.APPLICATION_JSON));

        CoreApiClient client = new CoreApiClient(builder.build());
        CorePublicDeckResponse response = client.getPublicDeck("access-token", deckId, 3);

        assertThat(response.deckId()).isEqualTo(deckId);
        assertThat(response.version()).isEqualTo(3);
        server.verify();
    }

    @Test
    void getUserDeckAndTemplateUseBearerToken() {
        UUID deckId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("https://core.mnema.app");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        server.expect(requestTo("https://core.mnema.app/decks/%s".formatted(deckId)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andRespond(withSuccess("""
                        {"userDeckId":"%s","publicDeckId":"%s","currentVersion":7}
                        """.formatted(deckId, UUID.randomUUID()), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://core.mnema.app/templates/%s".formatted(templateId)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andRespond(withSuccess("""
                        {"templateId":"%s","fields":[]}
                        """.formatted(templateId), MediaType.APPLICATION_JSON));

        CoreApiClient client = new CoreApiClient(builder.build());
        CoreUserDeckResponse deck = client.getUserDeck("access-token", deckId);
        CoreCardTemplateResponse template = client.getTemplate("access-token", templateId);

        assertThat(deck.userDeckId()).isEqualTo(deckId);
        assertThat(deck.currentVersion()).isEqualTo(7);
        assertThat(template.templateId()).isEqualTo(templateId);
        server.verify();
    }

    @Test
    void createTemplateSendsBearerTokenAndBody() {
        UUID templateId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("https://core.mnema.app");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://core.mnema.app/templates"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess("""
                        {"templateId":"%s","fields":[]}
                        """.formatted(templateId), MediaType.APPLICATION_JSON));

        CoreApiClient client = new CoreApiClient(builder.build());
        CoreCardTemplateResponse response = client.createTemplate(
                "access-token",
                new CoreCardTemplateRequest(null, null, "Name", "Description", true, null, null, null, List.of())
        );

        assertThat(response.templateId()).isEqualTo(templateId);
        server.verify();
    }

    @Test
    void createDeckAndAddCardsBatchUseExpectedUris() {
        UUID deckId = UUID.randomUUID();
        UUID userCardId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("https://core.mnema.app");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        server.expect(requestTo("https://core.mnema.app/decks"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andRespond(withSuccess("""
                        {"userDeckId":"%s","publicDeckId":"%s","currentVersion":1}
                        """.formatted(deckId, UUID.randomUUID()), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://core.mnema.app/decks/%s/cards/batch?operationId=%s".formatted(deckId, operationId)))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andRespond(withSuccess("""
                        [{"userCardId":"%s","effectiveContent":{"Front":"Q"}}]
                        """.formatted(userCardId), MediaType.APPLICATION_JSON));

        CoreApiClient client = new CoreApiClient(builder.build());
        CoreUserDeckResponse deck = client.createDeck(
                "access-token",
                new CorePublicDeckRequest(null, null, null, "Deck", "Description", null, UUID.randomUUID(), true, true, "en", new String[]{"tag"}, null)
        );
        List<CoreUserCardResponse> cards = client.addCardsBatch(
                "access-token",
                deckId,
                List.of(new CoreCreateCardRequest(null, 0, null, null, null, null)),
                operationId
        );

        assertThat(deck.userDeckId()).isEqualTo(deckId);
        assertThat(cards).hasSize(1);
        assertThat(cards.getFirst().userCardId()).isEqualTo(userCardId);
        server.verify();
    }

    @Test
    void getUserCardsBuildsPagingQuery() {
        UUID deckId = UUID.randomUUID();
        UUID cardId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("https://core.mnema.app");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://core.mnema.app/decks/%s/cards?page=2&limit=50".formatted(deckId)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andRespond(withSuccess("""
                        {"content":[{"userCardId":"%s","effectiveContent":{"Front":"Q"}}],"last":true}
                        """.formatted(cardId), MediaType.APPLICATION_JSON));

        CoreApiClient client = new CoreApiClient(builder.build());
        CorePageResponse<CoreUserCardResponse> response = client.getUserCards("access-token", deckId, 2, 50);

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().getFirst().userCardId()).isEqualTo(cardId);
        assertThat(response.last()).isTrue();
        server.verify();
    }

    @Test
    void seedProgressSkipsEmptyPayloadAndPostsNonEmptyPayload() {
        UUID deckId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("https://core.mnema.app");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        server.expect(requestTo("https://core.mnema.app/review/decks/%s/states/import".formatted(deckId)))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token"))
                .andRespond(withNoContent());

        CoreApiClient client = new CoreApiClient(builder.build());
        client.seedProgress("access-token", deckId, List.of());

        client.seedProgress("access-token", deckId, List.of(new CoreCardProgressRequest(
                UUID.randomUUID(),
                0.91,
                5.0,
                3,
                java.time.Instant.parse("2026-04-06T10:15:30Z"),
                java.time.Instant.parse("2026-04-10T10:15:30Z"),
                false
        )));

        server.verify();
    }
}
