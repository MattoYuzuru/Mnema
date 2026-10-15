package app.mnema.learning.community;

import app.mnema.learning.platform.api.InvalidRequestException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/** Only a random opaque snapshot handle and a list offset; all account/query/profile/generation binding lives in SQL. */
record RecommendationCursor(UUID snapshot, int offset) {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    String encode() {
        String document = JsonNodeFactory.instance.objectNode().put("v", 1).put("s", snapshot.toString()).put("o", offset).toString();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(document.getBytes(StandardCharsets.UTF_8));
    }

    static RecommendationCursor decode(String text) {
        if (text == null) return null;
        try {
            if (text.length() > 200) throw new InvalidRequestException();
            JsonNode node = JSON.readTree(Base64.getUrlDecoder().decode(text));
            if (!node.isObject() || node.size() != 3 || !node.path("v").isIntegralNumber() || node.path("v").intValue() != 1 || !node.path("s").isString() || !node.path("o").isIntegralNumber() || !node.path("o").canConvertToInt()) throw new InvalidRequestException();
            UUID id = UUID.fromString(node.path("s").stringValue(null));
            int offset = node.path("o").intValue();
            if (offset < 1 || offset > 12) throw new InvalidRequestException();
            RecommendationCursor cursor = new RecommendationCursor(id, offset);
            if (!cursor.encode().equals(text)) throw new InvalidRequestException();
            return cursor;
        } catch (IllegalArgumentException | tools.jackson.core.JacksonException failure) {
            throw new InvalidRequestException();
        }
    }
}
