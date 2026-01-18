package app.mnema.core.deck.controller;

import app.mnema.core.deck.domain.dto.CardTemplateDTO;
import app.mnema.core.deck.domain.dto.UserCardDTO;
import app.mnema.core.deck.domain.dto.UserDeckDTO;
import app.mnema.core.deck.service.SearchService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SearchController.class)
@ActiveProfiles("test")
class SearchControllerWebMvcTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    SearchService searchService;

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void searchUserDecks_returnsPage() throws Exception {
        UUID userId = UUID.randomUUID();

        UserDeckDTO dto = new UserDeckDTO(
                UUID.randomUUID(),
                userId,
                UUID.randomUUID(),
                1,
                1,
                true,
                null,
                null,
                "Spanish verbs",
                "Basics",
                Instant.now(),
                null,
                false
        );

        Page<UserDeckDTO> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 10), 1);

        when(currentUserProvider.getUserId(any(Jwt.class))).thenReturn(userId);
        when(searchService.searchUserDecks(eq(userId), eq("spanish"), eq(List.of("verbs")), eq(1), eq(10)))
                .thenReturn(page);

        mockMvc.perform(get("/search/decks")
                        .with(jwt().jwt(j -> j.claim("sub", "user-123"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_user.read")))
                        .param("query", "spanish")
                        .param("tags", "verbs")
                        .param("page", "1")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].displayName").value("Spanish verbs"));
    }

    @Test
    void searchUserCards_returnsPage() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID userDeckId = UUID.randomUUID();

        ObjectNode content = objectMapper.createObjectNode();
        content.put("front", "Q");
        content.put("back", "A");

        UserCardDTO dto = new UserCardDTO(
                UUID.randomUUID(),
                null,
                true,
                false,
                "note",
                content
        );

        Page<UserCardDTO> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 50), 1);

        when(currentUserProvider.getUserId(any(Jwt.class))).thenReturn(userId);
        when(searchService.searchUserCards(eq(userId), eq(userDeckId), eq("note"), isNull(), eq(1), eq(50)))
                .thenReturn(page);

        mockMvc.perform(get("/search/decks/{userDeckId}/cards", userDeckId)
                        .with(jwt().jwt(j -> j.claim("sub", "user-123"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_user.read")))
                        .param("query", "note")
                        .param("page", "1")
                        .param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].personalNote").value("note"));
    }

    @Test
    void searchTemplates_returnsPage() throws Exception {
        UUID userId = UUID.randomUUID();

        CardTemplateDTO dto = new CardTemplateDTO(
                UUID.randomUUID(),
                userId,
                "Basic",
                "Template",
                true,
                Instant.now(),
                null,
                null,
                null,
                null,
                List.of()
        );

        Page<CardTemplateDTO> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 10), 1);

        when(currentUserProvider.getUserId(any(Jwt.class))).thenReturn(userId);
        when(searchService.searchTemplates(eq(userId), eq("basic"), eq("public"), eq(1), eq(10)))
                .thenReturn(page);

        mockMvc.perform(get("/search/templates")
                        .with(jwt().jwt(j -> j.claim("sub", "user-123"))
                                .authorities(new SimpleGrantedAuthority("SCOPE_user.read")))
                        .param("query", "basic")
                        .param("scope", "public")
                        .param("page", "1")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Basic"));
    }
}
