package app.mnema.ai.provider.anki;

import app.mnema.ai.client.core.CoreApiClient.CoreTemplateResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class AnkiTemplateSupport {

    private final ObjectMapper objectMapper;
    private final AnkiTemplateRenderer renderer;

    public AnkiTemplateSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.renderer = new AnkiTemplateRenderer();
    }

    public ObjectNode applyIfPresent(ObjectNode content, CoreTemplateResponse template) {
        if (content == null || template == null) {
            return content;
        }
        AnkiTemplate ankiTemplate = resolveTemplate(template);
        if (ankiTemplate == null) {
            return content;
        }
        Map<String, String> fields = extractFieldValues(content);
        AnkiRendered rendered = renderer.render(ankiTemplate, fields);
        if (rendered == null || (rendered.frontHtml().isBlank() && rendered.backHtml().isBlank())) {
            return content;
        }
        ObjectNode ankiNode = objectMapper.createObjectNode();
        ankiNode.put("front", rendered.frontHtml());
        ankiNode.put("back", rendered.backHtml());
        if (rendered.css() != null && !rendered.css().isBlank()) {
            ankiNode.put("css", rendered.css());
        }
        if (rendered.modelId() != null) {
            ankiNode.put("modelId", rendered.modelId());
        }
        if (rendered.modelName() != null) {
            ankiNode.put("modelName", rendered.modelName());
        }
        if (rendered.templateName() != null) {
            ankiNode.put("templateName", rendered.templateName());
        }
        content.set("_anki", ankiNode);
        return content;
    }

    private AnkiTemplate resolveTemplate(CoreTemplateResponse template) {
        JsonNode layout = template.layout();
        if (layout == null || layout.isNull()) {
            return null;
        }
        String renderMode = layout.path("renderMode").asText("");
        if (!"anki".equalsIgnoreCase(renderMode)) {
            return null;
        }
        JsonNode ankiNode = layout.path("anki");
        if (!ankiNode.isObject()) {
            return null;
        }
        String frontTemplate = textOrNull(ankiNode.get("frontTemplate"));
        if (frontTemplate == null || frontTemplate.isBlank()) {
            return null;
        }
        String backTemplate = textOrNull(ankiNode.get("backTemplate"));
        String css = textOrNull(ankiNode.get("css"));
        String modelId = textOrNull(ankiNode.get("modelId"));
        String modelName = textOrNull(ankiNode.get("modelName"));
        String templateName = textOrNull(ankiNode.get("templateName"));
        return new AnkiTemplate(frontTemplate, backTemplate, css, modelId, modelName, templateName);
    }

    private Map<String, String> extractFieldValues(ObjectNode content) {
        Map<String, String> values = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = content.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isValueNode()) {
                values.put(entry.getKey(), value.asText());
            }
        }
        return values;
    }

    private String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }
}
