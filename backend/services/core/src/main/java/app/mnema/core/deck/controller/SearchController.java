package app.mnema.core.deck.controller;

import app.mnema.core.deck.domain.dto.CardTemplateDTO;
import app.mnema.core.deck.domain.dto.UserCardDTO;
import app.mnema.core.deck.domain.dto.UserDeckDTO;
import app.mnema.core.deck.service.SearchService;
import app.mnema.core.security.CurrentUserProvider;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/search")
public class SearchController {

    private final SearchService searchService;
    private final CurrentUserProvider currentUserProvider;

    public SearchController(SearchService searchService, CurrentUserProvider currentUserProvider) {
        this.searchService = searchService;
        this.currentUserProvider = currentUserProvider;
    }

    // GET /search/decks?query=...&tags=tag1&tags=tag2&page=1&limit=10
    @GetMapping("/decks")
    public Page<UserDeckDTO> searchUserDecks(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit
    ) {
        var userId = currentUserProvider.getUserId(jwt);
        return searchService.searchUserDecks(userId, query, tags, page, limit);
    }

    // GET /search/decks/{userDeckId}/cards?query=...&tags=tag1&page=1&limit=50
    @GetMapping("/decks/{userDeckId}/cards")
    public Page<UserCardDTO> searchUserCards(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userDeckId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit
    ) {
        var userId = currentUserProvider.getUserId(jwt);
        return searchService.searchUserCards(userId, userDeckId, query, tags, page, limit);
    }

    // GET /search/templates?query=...&scope=public&page=1&limit=10
    @GetMapping("/templates")
    public Page<CardTemplateDTO> searchTemplates(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "public") String scope,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit
    ) {
        var userId = currentUserProvider.getUserId(jwt);
        return searchService.searchTemplates(userId, query, scope, page, limit);
    }
}
