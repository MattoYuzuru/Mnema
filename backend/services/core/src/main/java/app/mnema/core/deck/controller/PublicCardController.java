package app.mnema.core.deck.controller;

import app.mnema.core.deck.domain.dto.PublicCardDTO;
import app.mnema.core.deck.service.CardService;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/decks/public/cards")
public class PublicCardController {

    private final CardService cardService;

    public PublicCardController(CardService cardService) {
        this.cardService = cardService;
    }

    // GET /api/core/decks/public/cards/{cardId}
    @GetMapping("/{cardId}")
    public PublicCardDTO getPublicCard(@PathVariable UUID cardId) {
        return cardService.getPublicCardById(cardId);
    }
}
