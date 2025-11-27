package app.mnema.core.deck.controller;

import app.mnema.core.deck.domain.dto.PublicDeckDTO;
import app.mnema.core.deck.domain.dto.UserDeckDTO;
import app.mnema.core.deck.service.DeckService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/core/decks")
public class DeckController {

    private final DeckService deckService;

    public DeckController(DeckService deckService) {
        this.deckService = deckService;
    }

    // GET /core/decks?userId=...&page=1&limit=10
    @GetMapping
    public Page<UserDeckDTO> getAllUserDecksByPage(
            @RequestParam UUID userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return deckService.getUserDecksByPage(userId, page, limit);
    }

    // POST /core/decks?userId=...
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserDeckDTO createDeck(
            @RequestParam UUID userId,
            @RequestBody PublicDeckDTO deckDTO
            ) {

        return deckService.createNewDeck(userId, deckDTO);
    }
}
