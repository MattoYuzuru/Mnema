package app.mnema.core.deck.controller;

import app.mnema.core.deck.domain.dto.PublicCardDTO;
import app.mnema.core.deck.domain.dto.PublicDeckDTO;
import app.mnema.core.deck.domain.dto.UserDeckDTO;
import app.mnema.core.deck.service.CardService;
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
    private final CardService cardService;

    public PublicDeckController(DeckService deckService,
                                CurrentUserProvider currentUserProvider,
                                CardService cardService) {
        this.deckService = deckService;
        this.currentUserProvider = currentUserProvider;
        this.cardService = cardService;
    }

    // GET /decks/public?page=1&limit=10
    @GetMapping
    public Page<PublicDeckDTO> getPublicDecksPaginated(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return deckService.getPublicDecksByPage(page, limit);
    }

    // GET /decks/public/{deckId}?version=...
    @GetMapping("/{deckId}")
    public PublicDeckDTO getPublicDeck(
            @PathVariable UUID deckId,
            @RequestParam(required = false) Integer version
    ) {
        return deckService.getPublicDeck(deckId, version);
    }

    // GET /decks/public/{deckId}/cards?version=...&page=1&limit=50
    @GetMapping("/{deckId}/cards")
    public Page<PublicCardDTO> getPublicDeckCards(
            @PathVariable UUID deckId,
            @RequestParam(required = false) Integer version,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return cardService.getPublicCards(deckId, version, page, limit);
    }

    // PATCH /decks/public/{deckId}?version=...
    @PatchMapping("/{deckId}")
    public PublicDeckDTO updatePublicDeck(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID deckId,
            @RequestParam(required = false) Integer version,
            @RequestBody PublicDeckDTO dto
    ) {
        var userId = currentUserProvider.getUserId(jwt);
        return deckService.updatePublicDeckMeta(userId, deckId, version, dto);
    }

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
