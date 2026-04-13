package app.mnema.core.deck.controller;

import app.mnema.core.deck.domain.dto.CardTemplateDTO;
import app.mnema.core.deck.domain.dto.UserCardDTO;
import app.mnema.core.deck.domain.dto.UserDeckDTO;
import app.mnema.core.deck.domain.request.CreateCardRequest;
import app.mnema.core.deck.domain.request.MissingFieldCardsRequest;
import app.mnema.core.deck.service.CardService;
import app.mnema.core.deck.service.DeckService;
import app.mnema.core.deck.service.TemplateService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal")
public class InternalAiController {

    private final DeckService deckService;
    private final CardService cardService;
    private final TemplateService templateService;

    public InternalAiController(DeckService deckService,
                                CardService cardService,
                                TemplateService templateService) {
        this.deckService = deckService;
        this.cardService = cardService;
        this.templateService = templateService;
    }

    @GetMapping("/decks/{userDeckId}")
    public UserDeckDTO getUserDeck(@PathVariable UUID userDeckId) {
        return deckService.getUserDeckInternal(userDeckId);
    }

    @GetMapping("/templates/{templateId}")
    public CardTemplateDTO getTemplate(@PathVariable UUID templateId,
                                       @RequestParam(required = false) Integer version) {
        return templateService.getCardTemplateByIdInternal(templateId, version);
    }

    @GetMapping("/decks/{userDeckId}/cards")
    public Page<UserCardDTO> getCards(@PathVariable UUID userDeckId,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "50") int limit) {
        return cardService.getUserCardsByDeckInternal(userDeckId, page, limit);
    }

    @GetMapping("/decks/{userDeckId}/cards/{cardId}")
    public UserCardDTO getCard(@PathVariable UUID userDeckId,
                               @PathVariable UUID cardId) {
        return cardService.getUserCardInternal(userDeckId, cardId);
    }

    @PostMapping("/decks/{userDeckId}/cards/missing-fields/cards")
    public List<UserCardDTO> getMissingFieldCards(@PathVariable UUID userDeckId,
                                                  @RequestBody MissingFieldCardsRequest request) {
        return cardService.getMissingFieldCardsInternal(userDeckId, request);
    }

    @PostMapping("/decks/{userDeckId}/cards/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public List<UserCardDTO> addCardsBatch(@PathVariable UUID userDeckId,
                                           @RequestBody List<CreateCardRequest> requests,
                                           @RequestParam(required = false) UUID operationId) {
        return cardService.addNewCardsToDeckBatchInternal(userDeckId, requests, operationId);
    }

    @PatchMapping("/decks/{userDeckId}/cards/{cardId}")
    public UserCardDTO updateCard(@PathVariable UUID userDeckId,
                                  @PathVariable UUID cardId,
                                  @RequestBody UserCardDTO dto,
                                  @RequestParam(defaultValue = "local") String scope,
                                  @RequestParam(required = false) UUID operationId) {
        boolean updateGlobally;
        if ("global".equalsIgnoreCase(scope)) {
            updateGlobally = true;
        } else if ("local".equalsIgnoreCase(scope)) {
            updateGlobally = false;
        } else {
            throw new IllegalArgumentException("Unknown scope: " + scope);
        }
        return cardService.updateUserCardInternal(userDeckId, cardId, dto, updateGlobally, operationId);
    }
}
