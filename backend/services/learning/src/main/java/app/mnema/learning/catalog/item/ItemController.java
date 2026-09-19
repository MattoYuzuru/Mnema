package app.mnema.learning.catalog.item;

import app.mnema.learning.platform.api.InvalidRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.net.URI;
import java.util.UUID;

/** Canonical private LearningItem and Browse-data boundary. */
@RestController
@RequestMapping(value = "/decks/{deckId}/items", produces = MediaType.APPLICATION_JSON_VALUE)
public class ItemController {
    private final ItemService service;

    public ItemController(ItemService service) { this.service = service; }

    @GetMapping
    ResponseEntity<JsonNode> list(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                  HttpServletRequest request) {
        JsonNode page = service.list(ItemIds.entity(identity.getSubject()), ItemIds.entity(deckId),
                parameter(request, "limit"), parameter(request, "cursor"));
        return ResponseEntity.ok().headers(privateHeaders()).eTag(page.path("deckVersion").textValue()).body(page);
    }

    @GetMapping("/{memberKey}")
    ResponseEntity<JsonNode> read(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                  @PathVariable String memberKey, HttpServletRequest request) {
        String revision = parameter(request, "revisionId");
        JsonNode item = service.read(ItemIds.entity(identity.getSubject()), ItemIds.entity(deckId),
                ItemIds.entity(memberKey), revision == null ? null : ItemIds.entity(revision));
        return ResponseEntity.ok().headers(privateHeaders()).eTag(item.path("deckVersion").textValue()).body(item);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> create(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                    InputStream body, HttpServletRequest request) {
        UUID deck = ItemIds.entity(deckId);
        ItemService.WriteResult result = service.publish(ItemIds.entity(identity.getSubject()), deck,
                ItemPrecondition.read(request.getHeaders(HttpHeaders.IF_MATCH)), ItemPublicationCommand.readCreate(body));
        var response = write(result, HttpStatus.CREATED);
        response.location(URI.create(request.getContextPath() + "/decks/" + deck + "/items/"
                + result.acknowledgement().path("changes").get(0).path("memberKey").textValue()));
        return response.body(result.acknowledgement());
    }

    @PutMapping(value = "/{memberKey}", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> save(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                  @PathVariable String memberKey, InputStream body, HttpServletRequest request) {
        ItemService.WriteResult result = service.publish(ItemIds.entity(identity.getSubject()), ItemIds.entity(deckId),
                ItemPrecondition.read(request.getHeaders(HttpHeaders.IF_MATCH)),
                ItemPublicationCommand.readSave(body, ItemIds.entity(memberKey)));
        return write(result, HttpStatus.OK).body(result.acknowledgement());
    }

    @PostMapping(value = "/publications", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> publish(@AuthenticationPrincipal Jwt identity, @PathVariable String deckId,
                                     InputStream body, HttpServletRequest request) {
        ItemService.WriteResult result = service.publish(ItemIds.entity(identity.getSubject()), ItemIds.entity(deckId),
                ItemPrecondition.read(request.getHeaders(HttpHeaders.IF_MATCH)), ItemPublicationCommand.readBulk(body));
        return write(result, HttpStatus.OK).body(result.acknowledgement());
    }

    private static ResponseEntity.BodyBuilder write(ItemService.WriteResult result, HttpStatus status) {
        var response = ResponseEntity.status(status).headers(privateHeaders());
        if (result.replayed()) response.header("Idempotency-Replayed", "true");
        else response.eTag(result.acknowledgement().path("deckVersion").textValue());
        return response;
    }

    private static HttpHeaders privateHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("private, no-store");
        return headers;
    }

    private static String parameter(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null) return null;
        if (values.length != 1) throw new InvalidRequestException();
        return values[0];
    }
}
