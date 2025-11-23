package app.mnema.core.deck.controller;

import app.mnema.core.deck.domain.DeckDTO;
import app.mnema.core.deck.service.DeckService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/core/decks")
public class DeckController {

    private final DeckService deckService;

    public DeckController(DeckService ds) {
        this.deckService = ds;
    }

    @GetMapping("/")
    public List<DeckDTO> getAllDecks() {
        return deckService.getAllDecks();
    }

}
