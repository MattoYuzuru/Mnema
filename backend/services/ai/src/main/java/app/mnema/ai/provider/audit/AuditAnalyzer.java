package app.mnema.ai.provider.audit;

import app.mnema.ai.client.core.CoreApiClient.CoreFieldTemplate;
import app.mnema.ai.client.core.CoreApiClient.CoreTemplateResponse;
import app.mnema.ai.client.core.CoreApiClient.CoreUserCardResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public final class AuditAnalyzer {

    private static final int SHORT_THRESHOLD = 3;
    private static final int LONG_THRESHOLD = 200;
    private static final int MAX_ISSUES = 30;
    private static final Pattern NORMALIZE_PATTERN = Pattern.compile("[^a-z0-9]+");

    private AuditAnalyzer() {
    }

    public static AuditContext analyze(ObjectMapper objectMapper,
                                       CoreTemplateResponse template,
                                       List<CoreUserCardResponse> cards,
                                       List<String> fields) {
        ObjectNode summary = objectMapper.createObjectNode();
        ArrayNode fieldStats = summary.putArray("fieldStats");
        ArrayNode issues = objectMapper.createArrayNode();
        summary.put("totalCards", cards == null ? 0 : cards.size());

        Map<String, FieldAccumulator> accumulators = new HashMap<>();
        for (String field : fields) {
            accumulators.put(field, new FieldAccumulator(field));
        }

        int weakCards = 0;
        int identicalPairs = 0;
        for (CoreUserCardResponse card : cards) {
            if (card == null || card.effectiveContent() == null || !card.effectiveContent().isObject()) {
                continue;
            }
            ObjectNode content = (ObjectNode) card.effectiveContent();
            int filled = 0;
            List<String> filledValues = new ArrayList<>();
            for (String field : fields) {
                String value = extractText(content.get(field));
                FieldAccumulator acc = accumulators.get(field);
                if (value == null || value.isBlank()) {
                    acc.missing++;
                    continue;
                }
                filled++;
                acc.count++;
                acc.totalLength += value.length();
                filledValues.add(value);
                if (value.length() < SHORT_THRESHOLD) {
                    acc.shortCount++;
                    addIssue(issues, card.userCardId(), field, "Value is too короткое/short");
                } else if (value.length() > LONG_THRESHOLD) {
                    acc.longCount++;
                    addIssue(issues, card.userCardId(), field, "Value is слишком длинное/too long");
                }
            }
            if (filled <= 1 && fields.size() > 1) {
                weakCards++;
                addIssue(issues, card.userCardId(), null, "Only one field filled");
            }
            if (filledValues.size() >= 2) {
                String first = normalize(filledValues.getFirst());
                for (int i = 1; i < filledValues.size(); i++) {
                    if (first.equals(normalize(filledValues.get(i)))) {
                        identicalPairs++;
                        addIssue(issues, card.userCardId(), null, "Front/back values look identical");
                        break;
                    }
                }
            }
        }

        for (FieldAccumulator acc : accumulators.values()) {
            ObjectNode fieldNode = fieldStats.addObject();
            fieldNode.put("field", acc.field);
            fieldNode.put("missing", acc.missing);
            fieldNode.put("short", acc.shortCount);
            fieldNode.put("long", acc.longCount);
            fieldNode.put("avgLength", acc.count == 0 ? 0 : (double) acc.totalLength / acc.count);
        }
        summary.put("weakCards", weakCards);
        summary.put("identicalPairs", identicalPairs);
        if (template != null && template.fields() != null) {
            summary.put("templateFields", template.fields().size());
        }

        return new AuditContext(summary, issues);
    }

    private static void addIssue(ArrayNode issues, UUID cardId, String field, String reason) {
        if (issues.size() >= MAX_ISSUES) {
            return;
        }
        ObjectNode node = issues.addObject();
        if (cardId != null) {
            node.put("userCardId", cardId.toString());
        }
        if (field != null) {
            node.put("field", field);
        }
        node.put("reason", reason);
    }

    private static String extractText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return null;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return NORMALIZE_PATTERN.matcher(value.toLowerCase()).replaceAll("");
    }

    private static final class FieldAccumulator {
        private final String field;
        private int missing;
        private int shortCount;
        private int longCount;
        private int count;
        private int totalLength;

        private FieldAccumulator(String field) {
            this.field = field;
        }
    }

    public record AuditContext(ObjectNode summary, ArrayNode issues) {
    }
}
