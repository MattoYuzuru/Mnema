package app.mnema.learning.community;

import app.mnema.learning.admin.AdminConsoleAccess;
import app.mnema.learning.admin.AdminReportRange;
import app.mnema.learning.catalog.deck.DeckPrecondition;
import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.json.ContentJsonReader;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The owner console's editorial shelf ({@code GET} and {@code PUT /api/admin/community/editorial}, contract {@code contracts/community/catalog.json}, {@code editorial}). Every route
 * requires the configured console owner AND an access token of the admin client ({@link AdminConsoleAccess}, a 403 for anyone else, before anything is read); the private chain gives
 * {@code learning.read} to GET and {@code learning.write} to PUT. Every answer is {@code private, no-store}, the PUT answer also carries the new version as {@code ETag}. The request
 * takes no query parameter; the body is exactly {@code {commandId, codes}}; {@code If-Match} is the quoted {@code rowVersion} of the list read last (428 when missing, 400 when malformed).
 * These routes do not depend on the catalogue kill switch: the owner keeps editing the shelf while the public routes are off.
 */
@RestController
@RequestMapping(value = "/admin/community/editorial", produces = MediaType.APPLICATION_JSON_VALUE)
public class EditorialAdminController {
    private static final int MAX_BODY = 4_096;
    private static final ContentJsonReader BODY = new ContentJsonReader(MAX_BODY, 3, 80);

    private final AdminConsoleAccess access;
    private final EditorialService service;

    EditorialAdminController(AdminConsoleAccess access, EditorialService service) {
        this.access = access;
        this.service = service;
    }

    @GetMapping
    ResponseEntity<JsonNode> state(@AuthenticationPrincipal Jwt token, HttpServletRequest request) {
        access.require(token);
        AdminReportRange.query(request, Set.of());
        return answer(service.state());
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> replace(@AuthenticationPrincipal Jwt token, HttpServletRequest request, InputStream body) {
        UUID actor = access.require(token);
        AdminReportRange.query(request, Set.of());
        long version = DeckPrecondition.read(request.getHeaders(HttpHeaders.IF_MATCH));
        JsonNode command = read(body);
        UUID commandId = commandId(command);
        List<String> codes = codes(command);
        return answer(service.replace(actor, commandId, version, codes));
    }

    private static ResponseEntity<JsonNode> answer(JsonNode state) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("private, no-store");
        headers.setETag("\"" + state.path("rowVersion").stringValue(null) + "\"");
        return ResponseEntity.ok().headers(headers).contentType(MediaType.APPLICATION_JSON).body(ordered(state));
    }

    /** The state with its members in the order of the contract: a stored answer comes back from JSONB with its keys sorted by length. */
    private static JsonNode ordered(JsonNode state) {
        ObjectNode document = JsonNodeFactory.instance.objectNode();
        document.put("rowVersion", state.path("rowVersion").stringValue(null));
        ArrayNode entries = document.putArray("entries");
        for (JsonNode entry : state.path("entries")) {
            entries.addObject().put("code", entry.path("code").stringValue(null)).put("title", entry.path("title").stringValue(null)).put("listed", entry.path("listed").booleanValue())
                    .put("addedAt", entry.path("addedAt").stringValue(null));
        }
        return document;
    }

    private static JsonNode read(InputStream input) {
        try {
            JsonNode body = BODY.read(input.readNBytes(MAX_BODY + 1));
            if (!body.isObject() || body.size() != 2) throw new InvalidRequestException();
            return body;
        } catch (IOException | IllegalArgumentException | JacksonException failure) {
            throw new InvalidRequestException();
        }
    }

    private static UUID commandId(JsonNode body) {
        if (!body.path("commandId").isString()) throw new InvalidRequestException();
        String text = body.path("commandId").stringValue(null);
        try {
            if (text.length() != 36) throw new InvalidRequestException();
            UUID id = UUID.fromString(text);
            if (!id.toString().equalsIgnoreCase(text)) throw new InvalidRequestException();
            return UuidPolicy.requireCommandId(id);
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    private static List<String> codes(JsonNode body) {
        JsonNode list = body.path("codes");
        if (!list.isArray() || list.size() > EditorialService.MAX) throw new InvalidRequestException();
        List<String> codes = new ArrayList<>();
        for (JsonNode code : list) {
            if (!code.isString()) throw new InvalidRequestException();
            codes.add(code.stringValue(null));
        }
        return codes;
    }
}
