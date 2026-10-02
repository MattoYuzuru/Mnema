package app.mnema.learning.study.attempt;

import tools.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;

/**
 * Author-preview evaluation for the exercise editor: the same evaluator as Study without a deck, session, attempt
 * or any stored row. Authenticated with the authoring write scope by the security chain; the response is private
 * and never cached. Nothing identifies the caller here because nothing is owned or persisted.
 */
@RestController
@RequestMapping(value = "/exercise-previews", produces = MediaType.APPLICATION_JSON_VALUE)
public class ExercisePreviewController {
    private final ExercisePreviewService service;

    public ExercisePreviewController(ExercisePreviewService service) { this.service = service; }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<JsonNode> evaluate(InputStream body) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
                .body(service.evaluate(ExercisePreviewCommand.read(body)));
    }
}
