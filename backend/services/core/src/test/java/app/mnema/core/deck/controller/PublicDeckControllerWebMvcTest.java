package app.mnema.core.deck.controller;

import app.mnema.core.deck.domain.dto.PublicCardDTO;
import app.mnema.core.deck.domain.dto.PublicDeckDTO;
import app.mnema.core.deck.domain.dto.UserDeckDTO;
import app.mnema.core.deck.domain.type.LanguageTag;
import app.mnema.core.deck.service.CardService;
import app.mnema.core.deck.service.DeckService;
import app.mnema.core.security.CurrentUserProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PublicDeckController.class)
@ActiveProfiles("test")
class PublicDeckControllerWebMvcTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DeckService deckService;

    @MockitoBean
    CardService cardService;

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void getPublicDecksPaginated_returnsPageOfPublicDecks() throws Exception {
        PublicDeckDTO deck = new PublicDeckDTO(
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                "Public deck",
                "Public description",
                UUID.randomUUID(),
                true,
                true,
                LanguageTag.en,
                new String[]{"tag1", "tag2"},
                Instant.now(),
                Instant.now(),
                Instant.now(),
                null
        );

        Page<PublicDeckDTO> page = new PageImpl<>(
                List.of(deck),
                PageRequest.of(0, 10),
                1
        );

        when(deckService.getPublicDecksByPage(1, 10)).thenReturn(page);

        mockMvc.perform(get("/api/core/decks/public")
                        .with(jwt().jwt(j -> j.claim("sub", "user-123"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_user.read")))
                        .param("page", "1")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Public deck"))
                .andExpect(jsonPath("$.content[0].description").value("Public description"));
    }

    @Test
    void getPublicDeckCards_returnsPageOfCardsForDeckAndVersion() throws Exception {
        UUID deckId = UUID.randomUUID();

        ObjectNode content = objectMapper.createObjectNode();
        content.put("front", "Q");
        content.put("back", "A");

        PublicCardDTO card = new PublicCardDTO(
                deckId,
                2,
                UUID.randomUUID(),
                content,
                1,
                new String[]{"tag"},
                Instant.now(),
                Instant.now(),
                true,
                "checksum-123"
        );

        Page<PublicCardDTO> page = new PageImpl<>(
                List.of(card),
                PageRequest.of(0, 50),
                1
        );

        when(cardService.getPublicCards(deckId, 2, 1, 50)).thenReturn(page);

        mockMvc.perform(get("/api/core/decks/public/{deckId}/cards", deckId)
                        .with(jwt().jwt(j -> j.claim("sub", "user-123"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_user.read")))
                        .param("version", "2")
                        .param("page", "1")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].deckId").value(deckId.toString()))
                .andExpect(jsonPath("$.content[0].deckVersion").value(2));
    }

    @Test
    void forkFromPublicDeck_createsUserDeckForAuthenticatedUser() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID publicDeckId = UUID.randomUUID();

        UserDeckDTO responseDto = new UserDeckDTO(
                UUID.randomUUID(),
                userId,
                publicDeckId,
                1,
                1,
                true,
                null,
                null,
                "Forked deck",
                "Forked description",
                Instant.now(),
                Instant.now(),
                false
        );

        when(currentUserProvider.getUserId(any(Jwt.class))).thenReturn(userId);
        when(deckService.forkFromPublicDeck(userId, publicDeckId)).thenReturn(responseDto);

        mockMvc.perform(post("/api/core/decks/public/{deckId}/fork", publicDeckId)
                        .with(jwt().jwt(j -> j.claim("sub", "user-123"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_user.write"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.publicDeckId").value(publicDeckId.toString()))
                .andExpect(jsonPath("$.displayName").value("Forked deck"));
    }
}
