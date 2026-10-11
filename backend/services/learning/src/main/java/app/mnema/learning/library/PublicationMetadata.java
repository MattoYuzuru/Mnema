package app.mnema.learning.library;

import app.mnema.learning.platform.api.InvalidRequestException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The publication metadata of a deck as the owner states it: a topic of the directory, the language of the text and the language being learned (the
 * BCP 47 primary subtag, lowercase), an optional level from one closed list and at most five normalized tags. Values are validated in shape here; whether
 * a topic exists and is a leaf is decided against the directory by the service.
 */
public record PublicationMetadata(String topicId, String contentLanguage, String targetLanguage, String level, List<String> tags) {
    /** One closed list: CEFR for languages and exams, three words for everything else. */
    public static final List<String> LEVELS = List.of("A1", "A2", "B1", "B2", "C1", "C2", "BEGINNER", "INTERMEDIATE", "ADVANCED");
    public static final int MAX_TAGS = 5;
    static final int MAX_TAG_CODE_POINTS = 32;
    static final PublicationMetadata EMPTY = new PublicationMetadata(null, null, null, null, List.of());
    private static final Pattern TOPIC = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}");
    private static final Pattern TAG = Pattern.compile("[\\p{L}\\p{M}\\p{N} _+#.-]+");

    public PublicationMetadata {
        tags = List.copyOf(tags);
    }

    /** Reads and validates the {@code metadata} object of a command: exactly the five fields, each value of its shape. */
    static PublicationMetadata read(JsonNode node) {
        if (!node.isObject() || node.size() != 5) throw new InvalidRequestException();
        String topic = text(node, "topicId");
        if (topic != null && (topic.length() > 40 || !TOPIC.matcher(topic).matches())) throw new InvalidRequestException();
        String content = text(node, "contentLanguage");
        String target = text(node, "targetLanguage");
        if ((content != null && !LANGUAGE.matcher(content).matches()) || (target != null && !LANGUAGE.matcher(target).matches())) {
            throw new InvalidRequestException();
        }
        String level = text(node, "level");
        if (level != null && !LEVELS.contains(level)) throw new InvalidRequestException();
        JsonNode array = node.path("tags");
        if (!array.isArray() || array.size() > MAX_TAGS) throw new InvalidRequestException();
        List<String> tags = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode element : array) {
            if (!element.isString()) throw new InvalidRequestException();
            String tag = tag(element.stringValue(null));
            if (!seen.add(tag)) throw new InvalidRequestException();
            tags.add(tag);
        }
        return new PublicationMetadata(topic, content, target, level, tags);
    }

    /** NFKC, lower case, white space collapsed to one space, trimmed; 1..32 code points of letters, digits, marks and {@code space _ + # . -}. */
    static String tag(String raw) {
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 1 || length > MAX_TAG_CODE_POINTS || !TAG.matcher(normalized).matches()) throw new InvalidRequestException();
        return normalized;
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (value.isNull()) return null;
        if (!node.has(name) || !value.isString()) throw new InvalidRequestException();
        return value.stringValue(null);
    }

    ObjectNode toJson() {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.put("topicId", topicId).put("contentLanguage", contentLanguage).put("targetLanguage", targetLanguage).put("level", level);
        var array = result.putArray("tags");
        tags.forEach(array::add);
        return result;
    }
}
