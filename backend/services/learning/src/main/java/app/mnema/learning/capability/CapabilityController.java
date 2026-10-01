package app.mnema.learning.capability;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Safe capability booleans and reason codes for authenticated clients; never provider details. */
@RestController
@RequestMapping(value = "/capabilities", produces = MediaType.APPLICATION_JSON_VALUE)
public class CapabilityController {
    private final LearningCapabilities capabilities;

    public CapabilityController(LearningCapabilities capabilities) { this.capabilities = capabilities; }

    @GetMapping
    ResponseEntity<Capabilities> read() {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
                .body(new Capabilities(capabilities.aiAssessment(), capabilities.speechToText()));
    }

    public record Capabilities(LearningCapabilities.Status aiAssessment, LearningCapabilities.Status speechToText) { }
}
