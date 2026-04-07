package app.mnema.ai.provider.anki;

import app.mnema.ai.client.core.CoreApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnkiTemplateSupportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void applyIfPresentAddsRenderedAnkiPayload() {
        AnkiTemplateSupport support = new AnkiTemplateSupport(objectMapper);
        ObjectNode content = objectMapper.createObjectNode();
        content.put("Front", "日本語[にほんご;]");
        content.put("Back", "{{c1::language}}");

        ObjectNode layout = objectMapper.createObjectNode();
        layout.put("renderMode", "anki");
        ObjectNode anki = layout.putObject("anki");
        anki.put("frontTemplate", "{{furigana:Front}}");
        anki.put("backTemplate", "{{FrontSide}}<hr>{{cloze:Back}}{{#Back}}!{{/Back}}");
        anki.put("css", ".card { color: black; }");
        anki.put("modelId", "basic");
        anki.put("modelName", "Basic");
        anki.put("templateName", "Card 1");

        CoreApiClient.CoreTemplateResponse template = new CoreApiClient.CoreTemplateResponse(
                UUID.randomUUID(),
                1,
                1,
                "Template",
                null,
                layout,
                null,
                List.of()
        );

        ObjectNode updated = support.applyIfPresent(content, template);

        assertSame(content, updated);
        assertNotNull(updated.get("_anki"));
        assertTrue(updated.path("_anki").path("front").asText().contains("<ruby>日本語<rt>にほんご</rt></ruby>"));
        assertTrue(updated.path("_anki").path("back").asText().contains("<span class=\"cloze\">language</span>"));
        assertEquals("basic", updated.path("_anki").path("modelId").asText());
        assertEquals("Basic", updated.path("_anki").path("modelName").asText());
        assertEquals("Card 1", updated.path("_anki").path("templateName").asText());
    }

    @Test
    void applyIfPresentLeavesContentUntouchedWhenLayoutIsNotAnki() {
        AnkiTemplateSupport support = new AnkiTemplateSupport(objectMapper);
        ObjectNode content = objectMapper.createObjectNode();
        content.put("Front", "question");

        ObjectNode layout = objectMapper.createObjectNode();
        layout.put("renderMode", "plain");

        CoreApiClient.CoreTemplateResponse template = new CoreApiClient.CoreTemplateResponse(
                UUID.randomUUID(),
                1,
                1,
                "Template",
                null,
                layout,
                null,
                List.of()
        );

        ObjectNode updated = support.applyIfPresent(content, template);

        assertSame(content, updated);
        assertTrue(updated.get("_anki") == null);
    }
}
