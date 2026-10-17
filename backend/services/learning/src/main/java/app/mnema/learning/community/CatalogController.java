package app.mnema.learning.community;

import app.mnema.learning.library.Viewer;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.security.ClientAddresses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * The public catalogue routes ({@code /api/public/community/...} and {@code /api/public/sitemap.xml}, contract {@code contracts/community/catalog.json}). GET and HEAD only, in the
 * optional-bearer chain of the public deck reads: no token is a guest, a valid token identifies the account (only the author list uses it), an invalid token is 401 before the
 * controller. All routes are 404 unless {@code learning.community.catalog.enabled} (and the public routes switch) is on. JSON answers are {@code no-store} with
 * {@code Vary: Authorization}; the sitemap is the same for everybody and is {@code public, max-age=3600}. The query string of the search is never logged (it is user content).
 */
@RestController
@RequestMapping("/public")
public class CatalogController {
    private static final String SITEMAP_CACHE = "public, max-age=3600";

    private final CatalogService service;
    private final ClientAddresses addresses;

    CatalogController(CatalogService service, ClientAddresses addresses) {
        this.service = service;
        this.addresses = addresses;
    }

    @GetMapping(value = "/community/facets", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> facets(@AuthenticationPrincipal Jwt identity, HttpServletRequest request) {
        return ok(service.facets(viewer(identity, request)));
    }

    @GetMapping(value = "/community/shelves", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> shelves(@AuthenticationPrincipal Jwt identity, HttpServletRequest request) {
        return ok(service.shelves(viewer(identity, request)));
    }

    @GetMapping(value = "/community/decks", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> decks(@AuthenticationPrincipal Jwt identity, HttpServletRequest request) {
        return ok(service.decks(viewer(identity, request), request));
    }

    @GetMapping(value = "/community/authors/{accountId}/decks", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> author(@AuthenticationPrincipal Jwt identity, @PathVariable String accountId, HttpServletRequest request) {
        return ok(service.author(viewer(identity, request), accountId, request));
    }

    @GetMapping(value = "/sitemap.xml", produces = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    ResponseEntity<byte[]> sitemap(@AuthenticationPrincipal Jwt identity, HttpServletRequest request) {
        byte[] body = service.sitemap(viewer(identity, request));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, SITEMAP_CACHE).contentType(new MediaType("application", "xml", java.nio.charset.StandardCharsets.UTF_8)).body(body);
    }

    private Viewer viewer(Jwt identity, HttpServletRequest request) {
        String network = addresses.resolveNetwork(request).orElse(null);
        return identity == null ? Viewer.guest(network, addresses.resolveCoarseNetwork(request).orElse(null)) : Viewer.account(entityId(identity.getSubject()), network);
    }

    private static ResponseEntity<JsonNode> ok(JsonNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        headers.setVary(java.util.List.of(HttpHeaders.AUTHORIZATION));
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private static UUID entityId(String value) {
        try {
            return UuidPolicy.requireEntityId(UUID.fromString(value), "id");
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }
}
