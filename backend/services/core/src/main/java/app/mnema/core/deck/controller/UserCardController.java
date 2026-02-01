package app.mnema.core.deck.controller;

import app.mnema.core.deck.domain.dto.UserCardDTO;
import app.mnema.core.deck.domain.dto.MissingFieldSummaryDTO;
import app.mnema.core.deck.domain.dto.DuplicateGroupDTO;
import app.mnema.core.deck.domain.request.CreateCardRequest;
import app.mnema.core.deck.domain.request.DuplicateSearchRequest;
import app.mnema.core.deck.domain.request.MissingFieldCardsRequest;
import app.mnema.core.deck.domain.request.MissingFieldSummaryRequest;
import app.mnema.core.deck.service.CardService;
import app.mnema.core.security.CurrentUserProvider;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/decks/{userDeckId}/cards")
public class UserCardController {

    private final CurrentUserProvider currentUserProvider;
    private final CardService cardService;

    public UserCardController(CurrentUserProvider currentUserProvider,
                              CardService cardService) {
        this.currentUserProvider = currentUserProvider;
        this.cardService = cardService;
    }

    // GET /decks/{userDeckId}/cards?page=1&limit=50
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

    // GET /decks/{userDeckId}/cards/{cardId}
    @GetMapping("/{cardId}")
    public UserCardDTO getCard(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userDeckId,
            @PathVariable UUID cardId
    ) {
        var userId = currentUserProvider.getUserId(jwt);
        return cardService.getUserCard(userId, userDeckId, cardId);
    }

    // POST /decks/{userDeckId}/cards/missing-fields
    @PostMapping("/missing-fields")
    public MissingFieldSummaryDTO getMissingFields(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userDeckId,
            @RequestBody MissingFieldSummaryRequest request
    ) {
        var userId = currentUserProvider.getUserId(jwt);
        return cardService.getMissingFieldSummary(userId, userDeckId, request);
    }

    // POST /decks/{userDeckId}/cards/missing-fields/cards
    @PostMapping("/missing-fields/cards")
    public List<UserCardDTO> getMissingFieldCards(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userDeckId,
            @RequestBody MissingFieldCardsRequest request
    ) {
        var userId = currentUserProvider.getUserId(jwt);
        return cardService.getMissingFieldCards(userId, userDeckId, request);
    }

    // POST /decks/{userDeckId}/cards/duplicates
    @PostMapping("/duplicates")
    public List<DuplicateGroupDTO> getDuplicateGroups(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userDeckId,
            @RequestBody DuplicateSearchRequest request
    ) {
        var userId = currentUserProvider.getUserId(jwt);
        return cardService.getDuplicateGroups(userId, userDeckId, request);
    }

    // POST /decks/{userDeckId}/cards
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

    // POST /decks/{userDeckId}/cards/batch
    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public List<UserCardDTO> addCardsBatch(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userDeckId,
            @RequestBody List<CreateCardRequest> requests
    ) {
        var userId = currentUserProvider.getUserId(jwt);
        return cardService.addNewCardsToDeckBatch(userId, userDeckId, requests);
    }

    // PATCH /decks/{userDeckId}/cards/{cardId}
    @PatchMapping("/{cardId}")
    public UserCardDTO updateCard(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userDeckId,
            @PathVariable UUID cardId,
            @RequestBody UserCardDTO dto,
            @RequestParam(defaultValue = "local") String scope
    ) {
        var userId = currentUserProvider.getUserId(jwt);
        boolean updateGlobally;
        if ("global".equalsIgnoreCase(scope)) {
            updateGlobally = true;
        } else if ("local".equalsIgnoreCase(scope)) {
            updateGlobally = false;
        } else {
            throw new IllegalArgumentException("Unknown scope: " + scope);
        }
        return cardService.updateUserCard(userId, userDeckId, cardId, dto, updateGlobally);
    }

    // DELETE /decks/{userDeckId}/cards/{cardId}
    @DeleteMapping("/{cardId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCard(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userDeckId,
            @PathVariable UUID cardId,
            @RequestParam(defaultValue = "local") String scope
    ) {
        var userId = currentUserProvider.getUserId(jwt);
        boolean deleteGlobally;
        if ("global".equalsIgnoreCase(scope)) {
            deleteGlobally = true;
        } else if ("local".equalsIgnoreCase(scope)) {
            deleteGlobally = false;
        } else {
            throw new IllegalArgumentException("Unknown delete scope: " + scope);
        }
        cardService.deleteUserCard(userId, userDeckId, cardId, deleteGlobally);
    }
}
