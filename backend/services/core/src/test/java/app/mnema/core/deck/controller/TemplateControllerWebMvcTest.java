package app.mnema.core.deck.controller;

import app.mnema.core.deck.domain.dto.CardTemplateDTO;
import app.mnema.core.deck.domain.type.CardFieldType;
import app.mnema.core.deck.service.TemplateService;
import app.mnema.core.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TemplateController.class)
@ActiveProfiles("test")
class TemplateControllerWebMvcTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    TemplateService templateService;

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @Test
    void createTemplate_acceptsReleaseSmokeFixtureContract() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        when(currentUserProvider.getUserId(any(Jwt.class))).thenReturn(userId);
        when(templateService.createNewTemplate(eq(userId), any(CardTemplateDTO.class), isNull()))
                .thenAnswer(invocation -> {
                    CardTemplateDTO request = invocation.getArgument(1);
                    return new CardTemplateDTO(
                            templateId,
                            1,
                            1,
                            userId,
                            request.name(),
                            request.description(),
                            request.isPublic(),
                            Instant.parse("2026-08-25T19:22:03Z"),
                            null,
                            request.layout(),
                            request.aiProfile(),
                            request.iconUrl(),
                            request.fields()
                    );
                });

        String requestBody = """
                {
                  "name": "Release smoke template",
                  "description": "Disposable release verification fixture",
                  "isPublic": false,
                  "layout": {"front": ["front"], "back": ["back"]},
                  "fields": [
                    {
                      "name": "front",
                      "label": "Front",
                      "fieldType": "text",
                      "isRequired": true,
                      "isOnFront": true,
                      "orderIndex": 0,
                      "defaultValue": null,
                      "helpText": null
                    },
                    {
                      "name": "back",
                      "label": "Back",
                      "fieldType": "text",
                      "isRequired": true,
                      "isOnFront": false,
                      "orderIndex": 0,
                      "defaultValue": null,
                      "helpText": null
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/templates")
                        .with(jwt().jwt(token -> token.claim("sub", "release-smoke")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templateId").value(templateId.toString()))
                .andExpect(jsonPath("$.fields.length()").value(2));

        verify(templateService).createNewTemplate(eq(userId),
                argThat(request ->
                        !request.isPublic()
                                && request.fields().size() == 2
                                && request.fields().getFirst().fieldType() == CardFieldType.text
                                && request.fields().getFirst().isOnFront()
                                && !request.fields().getLast().isOnFront()
                                && request.layout().path("front").get(0).asText().equals("front")
                                && request.layout().path("back").get(0).asText().equals("back")
                ),
                isNull());
    }
}
