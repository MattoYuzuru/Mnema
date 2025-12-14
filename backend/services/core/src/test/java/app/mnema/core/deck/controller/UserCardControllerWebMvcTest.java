package app.mnema.core.deck.controller;

import app.mnema.core.deck.domain.dto.UserCardDTO;
import app.mnema.core.deck.domain.request.CreateCardRequest;
import app.mnema.core.deck.service.CardService;
import app.mnema.core.security.CurrentUserProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserCardController.class)
@ActiveProfiles("test")
class UserCardControllerWebMvcTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CardService cardService;

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void getCards_returnsPageForAuthenticatedUserAndDeck() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID userDeckId = UUID.randomUUID();

        ObjectNode content = objectMapper.createObjectNode();
        content.put("front", "Q");
        content.put("back", "A");

        UserCardDTO dto = new UserCardDTO(
                UUID.randomUUID(),
                UUID.randomUUID(),
                false,
                false,
                "note",
                content
        );

        Page<UserCardDTO> page = new PageImpl<>(
                List.of(dto),
                PageRequest.of(0, 50),
                1
        );

        when(currentUserProvider.getUserId(any(Jwt.class))).thenReturn(userId);
        when(cardService.getUserCardsByDeck(userId, userDeckId, 1, 50)).thenReturn(page);

        mockMvc.perform(get("/decks/{userDeckId}/cards", userDeckId)
                        .with(jwt().jwt(j -> j.claim("sub", "user-123"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_user.read")))
                        .param("page", "1")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].personalNote").value("note"));
    }

    @Test
    void addCard_createsCardForAuthenticatedUserAndDeck() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID userDeckId = UUID.randomUUID();

        ObjectNode effectiveContent = objectMapper.createObjectNode();
        effectiveContent.put("front", "Q");
        effectiveContent.put("back", "A");

        UserCardDTO responseDto = new UserCardDTO(
                UUID.randomUUID(),
                null,
                true,
                false,
                "my note",
                effectiveContent
        );

        when(currentUserProvider.getUserId(any(Jwt.class))).thenReturn(userId);
        when(cardService.addNewCardToDeck(eq(userId), eq(userDeckId), any(CreateCardRequest.class)))
                .thenReturn(responseDto);

        String requestBody = """
                {
                  "content": {
                    "front": "Q",
                    "back": "A"
                  },
                  "orderIndex": 1,
                  "tags": ["tag1"],
                  "personalNote": "my note",
                  "contentOverride": null,
                  "checksum": "checksum-123"
                }
                """;

        mockMvc.perform(post("/decks/{userDeckId}/cards", userDeckId)
                        .with(jwt().jwt(j -> j.claim("sub", "user-123"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_user.write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.personalNote").value("my note"))
                .andExpect(jsonPath("$.isDeleted").value(false));
    }
}
