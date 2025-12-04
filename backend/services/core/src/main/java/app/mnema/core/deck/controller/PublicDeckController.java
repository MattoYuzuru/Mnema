package app.mnema.core.deck.controller;

import app.mnema.core.deck.domain.dto.*;
import app.mnema.core.deck.service.CardService;
import app.mnema.core.deck.service.DeckService;
import app.mnema.core.security.CurrentUserProvider;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    // {
    //   "content": [ карты ],
    //   "fields":  [ описания полей шаблона ],
    //   поля пагинации Page
    // }
    @GetMapping("/{deckId}/cards")
    public Map<String, Object> getPublicDeckCards(
            @PathVariable UUID deckId,
            @RequestParam(required = false) Integer version,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit
    ) {
        Page<PublicCardDTO> cardsPage = cardService.getPublicCards(deckId, version, page, limit);
        List<FieldTemplateDTO> fields = cardService.getFieldTemplatesForPublicDeck(deckId, version);

        Map<String, Object> response = new LinkedHashMap<>();

        response.put("content", cardsPage.getContent());
        response.put("fields", fields);

        response.put("pageable", cardsPage.getPageable());
        response.put("last", cardsPage.isLast());
        response.put("totalElements", cardsPage.getTotalElements());
        response.put("totalPages", cardsPage.getTotalPages());
        response.put("size", cardsPage.getSize());
        response.put("number", cardsPage.getNumber());
        response.put("sort", cardsPage.getSort());
        response.put("first", cardsPage.isFirst());
        response.put("numberOfElements", cardsPage.getNumberOfElements());
        response.put("empty", cardsPage.isEmpty());

        return response;
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

    // GET /decks/public/{deckId}/size?version=...
    @GetMapping("/{deckId}/size")
    public DeckSizeDTO getPublicDeckSize(
            @PathVariable UUID deckId,
            @RequestParam(required = false) Integer version
    ) {
        return deckService.getPublicDeckSize(deckId, version);
    }

}
