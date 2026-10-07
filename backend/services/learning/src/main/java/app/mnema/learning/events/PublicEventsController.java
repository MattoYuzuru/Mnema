package app.mnema.learning.events;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping(value = "/events", produces = MediaType.APPLICATION_JSON_VALUE)
public final class PublicEventsController {
    private final EventService events;

    PublicEventsController(EventService events) { this.events = events; }

    @GetMapping
    ResponseEntity<JsonNode> page(HttpServletRequest request) {
        return ResponseEntity.ok().header("Cache-Control", "public, max-age=60")
                .body(events.publicPage(EventRequests.parameter(request, "cursor")));
    }
}
