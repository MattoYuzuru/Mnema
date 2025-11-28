package app.mnema.core.deck.controller;

import app.mnema.core.deck.domain.dto.PublicDeckDTO;
import app.mnema.core.deck.domain.dto.UserDeckDTO;
import app.mnema.core.deck.service.DeckService;
import app.mnema.core.security.CurrentUserProvider;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/decks/public")
public class PublicDeckController {

    private final DeckService deckService;
    private final CurrentUserProvider currentUserProvider;

    public PublicDeckController(DeckService deckService, CurrentUserProvider currentUserProvider) {
        this.deckService = deckService;
        this.currentUserProvider = currentUserProvider;
    }

    // GET /api/core/decks/public?page=1&limit=10
    @GetMapping
    public Page<PublicDeckDTO> getPublicDecksPaginated(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return deckService.getPublicDecksByPage(page, limit);
    }

    // GET /decks/public/{deckId}/cards
    // TODO


    // POST /decks/public/{deckId}/fork
    @PostMapping("/{deckId}/fork")
    public UserDeckDTO forkFromPublicDeck(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID deckId
    ) {
        var userId = currentUserProvider.getUserId(jwt);
        return deckService.forkFromPublicDeck(userId, deckId);
    }

}
