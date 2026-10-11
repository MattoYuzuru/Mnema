package app.mnema.learning.library;

import app.mnema.learning.platform.security.ClientAddresses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;

/**
 * Link-preview metadata ({@code /api/public/meta/...}, contract {@code contracts/decks/public-meta.json}), the input of the web server that puts Open
 * Graph tags into {@code index.html} without SSR. GET and HEAD only, in the public chain of the deck reads (no cookie, no session), switched off with them.
 * The viewer is always a guest and the answer never depends on a token (a present token is validated by the chain like on every public route, then not
 * used), so it is the same for everybody and carries {@code Cache-Control: public, max-age=300} (the problem answers of the admission, 404, 429 and 503,
 * stay {@code no-store}).
 *
 * <ul>
 *   <li>{@code /decks/{code}} and {@code /profiles/{username}}: the JSON card.</li>
 *   <li>{@code /decks/{code}/head} and {@code /profiles/{username}/head}: the same card as an HTML fragment of {@code <head>} with every value escaped
 *       ({@link PreviewHead}). It is served as {@code text/plain;charset=UTF-8} with {@code Content-Security-Policy: default-src 'none'; sandbox}, because
 *       it is reachable on the application origin: a browser shows it as text and never runs it, while the web server's SSI include prints its bytes
 *       whatever the type. Response headers for the web server: {@code X-Mnema-Preview: card|generic}; for a PUBLIC deck
 *       {@code X-Mnema-Canonical: /d/{code}/{slug}} (the web server compares it with the requested path and answers 301 on a difference, so one cacheable
 *       answer serves both the redirect and the card); the generic card also carries {@code X-Robots-Tag: noindex}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/public/meta")
public class PublicMetaController {
    static final String CACHE = "public, max-age=300";
    static final String CANONICAL_HEADER = "X-Mnema-Canonical";
    static final String PREVIEW_HEADER = "X-Mnema-Preview";
    static final String ROBOTS_HEADER = "X-Robots-Tag";
    static final String FRAGMENT_POLICY = "default-src 'none'; sandbox";
    /** Declared on the mapping too, so the fragment is plain text by contract and never content-negotiated into HTML. */
    static final String FRAGMENT_TYPE = "text/plain;charset=UTF-8";
    private static final MediaType FRAGMENT = new MediaType("text", "plain", StandardCharsets.UTF_8);

    private final PublicMetaService service;
    private final ClientAddresses addresses;

    PublicMetaController(PublicMetaService service, ClientAddresses addresses) {
        this.service = service;
        this.addresses = addresses;
    }

    @GetMapping(value = "/decks/{code}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> deck(@PathVariable String code, HttpServletRequest request) {
        return cached().contentType(MediaType.APPLICATION_JSON).body(service.deck(viewer(request), code).preview().toJson());
    }

    @GetMapping(value = "/decks/{code}/head", produces = FRAGMENT_TYPE)
    ResponseEntity<String> deckHead(@PathVariable String code, HttpServletRequest request) {
        PublicMetaService.DeckMeta meta = service.deck(viewer(request), code);
        ResponseEntity.BodyBuilder response = fragment(meta.preview());
        // Only a public deck has a canonical path; it is built from the code and the computed slug.
        if (meta.canonicalPath() != null) response.header(CANONICAL_HEADER, meta.canonicalPath());
        return response.body(PreviewHead.render(meta.preview()));
    }

    @GetMapping(value = "/profiles/{username}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> profile(@PathVariable String username, HttpServletRequest request) {
        return cached().contentType(MediaType.APPLICATION_JSON).body(service.profile(viewer(request), username).toJson());
    }

    @GetMapping(value = "/profiles/{username}/head", produces = FRAGMENT_TYPE)
    ResponseEntity<String> profileHead(@PathVariable String username, HttpServletRequest request) {
        Preview preview = service.profile(viewer(request), username);
        return fragment(preview).body(PreviewHead.render(preview));
    }

    private static ResponseEntity.BodyBuilder fragment(Preview preview) {
        ResponseEntity.BodyBuilder response = cached().contentType(FRAGMENT).header("Content-Security-Policy", FRAGMENT_POLICY);
        boolean generic = preview.kind() == Preview.Kind.GENERIC;
        response.header(PREVIEW_HEADER, generic ? "generic" : "card");
        // The generic card is also noindex as a header, for crawlers that do not read the robots meta tag; the web server passes it on.
        if (generic) response.header(ROBOTS_HEADER, "noindex");
        return response;
    }

    private Viewer viewer(HttpServletRequest request) {
        return Viewer.guest(addresses.resolveNetwork(request).orElse(null), addresses.resolveCoarseNetwork(request).orElse(null));
    }

    private static ResponseEntity.BodyBuilder cached() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CACHE);
        return ResponseEntity.ok().headers(headers);
    }
}
