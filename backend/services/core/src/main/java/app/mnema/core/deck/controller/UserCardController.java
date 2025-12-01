package app.mnema.core.deck.controller;

import app.mnema.core.deck.domain.dto.UserCardDTO;
import app.mnema.core.deck.domain.request.CreateCardRequest;
import app.mnema.core.deck.service.CardService;
import app.mnema.core.deck.service.DeckService;
import app.mnema.core.security.CurrentUserProvider;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/decks/{userDeckId}/cards")
public class UserCardController {

    private final CurrentUserProvider currentUserProvider;
    private final CardService cardService;

    public UserCardController(DeckService deckService, CurrentUserProvider currentUserProvider, CardService cardService) {
        this.currentUserProvider = currentUserProvider;
        this.cardService = cardService;
    }

    // GET /api/core/decks/{userDeckId}/cards?page=1&limit=50 - мои карты в колоде
    @GetMapping
    public Page<UserCardDTO> getCards(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userDeckId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit
    ) {
        var userId = currentUserProvider.getUserId(jwt);
        return cardService.getUserCardsByDeck(userId, userDeckId, page, limit);
    }

    // POST /api/core/decks/{userDeckId}/cards - добавить карту
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserCardDTO addCard(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userDeckId,
            @RequestBody CreateCardRequest request
    ) {
        var userId = currentUserProvider.getUserId(jwt);
        return cardService.addNewCardToDeck(userId, userDeckId, request);
    }
}
