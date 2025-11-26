package app.mnema.core.deck.controller;

import app.mnema.core.deck.domain.dto.UserDeckDTO;
import app.mnema.core.deck.service.DeckService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/core/decks")
public class DeckController {

    private final DeckService deckService;

    public DeckController(DeckService ds) {
        this.deckService = ds;
    }

    @GetMapping
    public Page<UserDeckDTO> getAllUserDecksByPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return deckService.getAllUserDecksByPage(page, limit);
    }

}
