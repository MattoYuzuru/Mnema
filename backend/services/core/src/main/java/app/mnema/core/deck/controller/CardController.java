package app.mnema.core.deck.controller;

import app.mnema.core.deck.domain.dto.UserCardDTO;
import app.mnema.core.deck.domain.request.CreateCardRequest;
import app.mnema.core.deck.service.DeckService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/core/decks/{userDeckId}/cards")
public class CardController {

    private final DeckService deckService;

    public CardController(DeckService deckService) {
        this.deckService = deckService;
    }

    // GET /core/decks/{userDeckId}/cards?page=1&limit=50
    @GetMapping
    public Page<UserCardDTO> getCards(
            @PathVariable UUID userDeckId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return deckService.getUserCardsByDeck(userDeckId, page, limit);
    }

    // POST /core/decks/{userDeckId}/cards?userId=...
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserCardDTO addCard(
            @PathVariable UUID userDeckId,
            @RequestParam UUID userId,
            @RequestBody CreateCardRequest request
    ) {

        return deckService.addNewCardToDeck(userId, userDeckId, request);
    }
}
