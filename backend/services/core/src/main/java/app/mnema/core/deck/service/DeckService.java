package app.mnema.core.deck.service;


import app.mnema.core.deck.domain.Deck;
import app.mnema.core.deck.domain.DeckDTO;
import app.mnema.core.deck.repository.DeckRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DeckService {
    private final DeckRepository deckRepository;

    public DeckService(DeckRepository deckRepository) {
        this.deckRepository = deckRepository;
    }

    public Page<DeckDTO> getAllDecks(int page, int limit) {
        Pageable pageable = PageRequest.of(page - 1, limit);
        return deckRepository.findAll(pageable).map(this::toDTO);
    }

    private DeckDTO toDTO(Deck deck) {
        Deck convertedDeck = new Deck();

        deck.set

        return convertedDeck;
    }
}
