package app.mnema.learning.library;

import tools.jackson.databind.JsonNode;

/**
 * The only part of an exercise a non-owner sees in a list: a short plain-text summary of its question. It reads the {@code prompt} blocks (TEXT
 * only; quoted materials, media and every other slot are skipped) and, for a cloze without a prompt text, its passage with each blank written as an
 * ellipsis. It never reads answer keys, options, bindings, references, transcripts or evaluator policies, so none of them can reach a list.
 */
final class ExercisePrompts {
    static final int MAX_CODE_POINTS = 200;

    private ExercisePrompts() { }

    /** @return the summary, or null when the exercise has no plain-text question */
    static String summary(String type, JsonNode content) {
        StringBuilder text = new StringBuilder();
        for (JsonNode block : content.path("prompt")) {
            if ("TEXT".equals(block.path("kind").stringValue(null))) append(text, block.path("text").stringValue(""));
        }
        if (text.isEmpty() && "CLOZE".equals(type)) {
            for (JsonNode segment : content.path("passage")) {
                String kind = segment.path("kind").stringValue(null);
                if ("TEXT".equals(kind)) append(text, segment.path("text").stringValue(""));
                else if ("BLANK".equals(kind)) append(text, "…");
            }
        }
        String collapsed = text.toString().replaceAll("\\s+", " ").strip();
        if (collapsed.isEmpty()) return null;
        if (collapsed.codePointCount(0, collapsed.length()) <= MAX_CODE_POINTS) return collapsed;
        return collapsed.substring(0, collapsed.offsetByCodePoints(0, MAX_CODE_POINTS)).stripTrailing() + "…";
    }

    private static void append(StringBuilder text, String part) {
        if (!text.isEmpty()) text.append(' ');
        text.append(part);
    }
}
