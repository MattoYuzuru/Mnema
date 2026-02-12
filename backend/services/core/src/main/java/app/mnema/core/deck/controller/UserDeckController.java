package app.mnema.core.deck.controller;

import app.mnema.core.deck.domain.dto.DeckSizeDTO;
import app.mnema.core.deck.domain.dto.PublicDeckDTO;
import app.mnema.core.deck.domain.dto.UserDeckDTO;
import app.mnema.core.deck.service.DeckService;
import app.mnema.core.security.CurrentUserProvider;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/decks")
public class UserDeckController {

    private final DeckService deckService;
    private final CurrentUserProvider currentUserProvider;

    public UserDeckController(DeckService deckService, CurrentUserProvider currentUserProvider) {
        this.deckService = deckService;
        this.currentUserProvider = currentUserProvider;
    }

    // GET /decks/mine?page=1&limit=10
    @GetMapping("/mine")
    public Page<UserDeckDTO> getAllUserDecksByPage(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit
    ) {
        var userId = currentUserProvider.getUserId(jwt);
        return deckService.getUserDecksByPage(userId, page, limit);
    }

    // GET /decks/mine/public-ids
    @GetMapping("/mine/public-ids")
    public UserPublicDeckIdsResponse getPublicDeckIds(
            @AuthenticationPrincipal Jwt jwt
    ) {
        var userId = currentUserProvider.getUserId(jwt);
        return new UserPublicDeckIdsResponse(deckService.getUserPublicDeckIds(userId));
    }

    // GET /decks/{userDeckId}
    @GetMapping("/{userDeckId}")
    public UserDeckDTO getDeckById(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userDeckId
    ) {
        var userId = currentUserProvider.getUserId(jwt);
        return deckService.getUserDeck(userId, userDeckId);
    }

    // GET /decks/deleted
    @GetMapping("/deleted")
    public List<UserDeckDTO> getDeletedDecks(
            @AuthenticationPrincipal Jwt jwt
    ) {
        var userId = currentUserProvider.getUserId(jwt);
        return deckService.getDeletedUserDecks(userId);
    }

    // POST /decks
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserDeckDTO createDeck(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody PublicDeckDTO deckDTO
    ) {
        var userId = currentUserProvider.getUserId(jwt);
        return deckService.createNewDeck(userId, deckDTO);
    }

    // PATCH /decks/{userDeckId}
    @PatchMapping("/{userDeckId}")
    public UserDeckDTO updateDeck(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userDeckId,
            @RequestBody UserDeckDTO dto
    ) {
        var userId = currentUserProvider.getUserId(jwt);
        return deckService.updateUserDeckMeta(userId, userDeckId, dto);
    }

    // POST /decks/{userDeckId}/sync
    @PostMapping("/{userDeckId}/sync")
    public UserDeckDTO syncDeck(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userDeckId
    ) {
        var userId = currentUserProvider.getUserId(jwt);
        return deckService.syncUserDeckToLatestVersion(userId, userDeckId);
    }

    // POST /decks/{userDeckId}/sync-template
    @PostMapping("/{userDeckId}/sync-template")
    public UserDeckDTO syncTemplate(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userDeckId
    ) {
        var userId = currentUserProvider.getUserId(jwt);
        return deckService.syncUserDeckTemplate(userId, userDeckId);
    }

    // DELETE /decks/{userDeckId}
    @DeleteMapping("/{userDeckId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDeck(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userDeckId
    ) {
        var userId = currentUserProvider.getUserId(jwt);
        deckService.deleteUserDeck(userId, userDeckId);
    }

    // DELETE /decks/{userDeckId}/hard
    @DeleteMapping("/{userDeckId}/hard")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void hardDeleteDeck(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userDeckId
    ) {
        var userId = currentUserProvider.getUserId(jwt);
        deckService.hardDeleteUserDeck(userId, userDeckId);
    }

    // GET /decks/{userDeckId}/size
    @GetMapping("/{userDeckId}/size")
    public DeckSizeDTO getDeckSize(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID userDeckId
    ) {
        var userId = currentUserProvider.getUserId(jwt);
        return deckService.getUserDeckSize(userId, userDeckId);
    }

    public record UserPublicDeckIdsResponse(List<UUID> publicDeckIds) {}

}
