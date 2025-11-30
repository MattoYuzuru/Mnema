package app.mnema.core.deck.controller;

import app.mnema.core.deck.domain.dto.PublicDeckDTO;
import app.mnema.core.deck.domain.dto.UserDeckDTO;
import app.mnema.core.deck.service.DeckService;
import app.mnema.core.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
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

@WebMvcTest(UserDeckController.class)
@ActiveProfiles("test")
class UserDeckControllerWebMvcTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DeckService deckService;

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @Test
    void getAllUserDecksByPage_returnsPageForAuthenticatedUser() throws Exception {
        UUID userId = UUID.randomUUID();

        UserDeckDTO dto = new UserDeckDTO(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                1,
                true,
                null,
                null,
                "My deck",
                "Description",
                Instant.now(),
                Instant.now(),
                false
        );

        Page<UserDeckDTO> page = new PageImpl<>(
                List.of(dto),
                PageRequest.of(0, 10),
                1
        );

        when(currentUserProvider.getUserId(any(Jwt.class))).thenReturn(userId);
        when(deckService.getUserDecksByPage(userId, 1, 10)).thenReturn(page);

        mockMvc.perform(get("/decks/mine")
                        .with(jwt().jwt(j -> j.claim("sub", "user-123")))
                        .param("page", "1")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].displayName").value("My deck"))
                .andExpect(jsonPath("$.content[0].displayDescription").value("Description"));
    }

    @Test
    void createDeck_createsDeckForAuthenticatedUser() throws Exception {
        UUID userId = UUID.randomUUID();

        UserDeckDTO responseDto = new UserDeckDTO(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                1,
                true,
                null,
                null,
                "My deck",
                "Description",
                Instant.now(),
                Instant.now(),
                false
        );

        when(currentUserProvider.getUserId(any(Jwt.class))).thenReturn(userId);
        when(deckService.createNewDeck(eq(userId), any(PublicDeckDTO.class)))
                .thenReturn(responseDto);

        String requestBody = """
                {
                  "name": "My deck",
                  "description": "Description"
                }
                """;

        mockMvc.perform(post("/decks")
                        .with(jwt().jwt(j -> j.claim("sub", "user-123")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("My deck"))
                .andExpect(jsonPath("$.displayDescription").value("Description"));
    }
}
