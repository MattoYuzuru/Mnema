package app.mnema.learning.catalog.item;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.UUID;

/** Deck-hub commands on materials: the «Эталон» flag and bulk deletion ({@code contracts/decks/hub.json}). */
@RestController
@RequestMapping(value = "/decks/{deckId}/items", produces = MediaType.APPLICATION_JSON_VALUE)
public class ItemHubController {
    private final ItemExemplarService exemplars;
    private final ItemBulkDeleteService deletions;

    public ItemHubController(ItemExemplarService exemplars, ItemBulkDeleteService deletions) {
        this.exemplars = exemplars;
        this.deletions = deletions;
    }

    @PostMapping(value = "/{memberKey}/exemplar", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> exemplar(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                      @PathVariable String memberKey, InputStream body) {
        ItemService.WriteResult result = exemplars.set(ItemIds.entity(identity.getSubject()), ItemIds.entity(deckId),
                ItemIds.entity(memberKey), ExemplarCommand.read(body));
        return reply(result).body(result.acknowledgement());
    }

    @PostMapping(value = "/deletions/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> preview(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId, InputStream body) {
        return ResponseEntity.ok().headers(privateHeaders())
                .body(deletions.preview(ItemIds.entity(identity.getSubject()), ItemIds.entity(deckId),
                        BulkSelection.parse(body)));
    }

    @PostMapping(value = "/deletions", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> delete(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                    InputStream body, HttpServletRequest request) {
        ItemService.WriteResult result = deletions.delete(ItemIds.entity(identity.getSubject()), ItemIds.entity(deckId),
                ItemPrecondition.read(request.getHeaders(HttpHeaders.IF_MATCH)), BulkDeleteCommand.read(body));
        var response = reply(result);
        if (!result.replayed()) response.eTag(result.acknowledgement().path("deckVersion").stringValue(null));
        return response.body(result.acknowledgement());
    }

    private static ResponseEntity.BodyBuilder reply(ItemService.WriteResult result) {
        var response = ResponseEntity.ok().headers(privateHeaders());
        if (result.replayed()) response.header("Idempotency-Replayed", "true");
        return response;
    }

    private static HttpHeaders privateHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("private, no-store");
        return headers;
    }
}
