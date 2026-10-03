package app.mnema.learning.ai;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Stub's answer to an exercise revision (the prompt carries {@code <task kind="exercise-edit">}): the exercise of
 * {@code <current_exercise>} returned whole as {@code {"exercises": [exercise]}}, with the first text block of its prompt extended by
 * one sentence ({@code Переформулировано.}), so a revision visibly changes the exercise while every identifier, handle and answer stays as
 * it was and the exercise stays valid. Two markers in the instruction break it on purpose, like the exercise draft's:
 * {@code [[stub:broken-key]]} (the first answer is not an exercise of the schema, the repair is the valid revision) and
 * {@code [[stub:broken-key-always]]} (every answer is not, so the turn ends {@code INVALID_OUTPUT}).
 */
final class StubExerciseEdits {
    static final String TASK = "<task kind=\"exercise-edit\">";
    static final String SENTENCE = "Переформулировано.";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern CURRENT = Pattern.compile("<current_exercise>\\n(.*?)\\n</current_exercise>", Pattern.DOTALL);

    private StubExerciseEdits() { }

    static boolean isExerciseEditRequest(String prompt) {
        return prompt.contains(TASK);
    }

    static String answer(String prompt, boolean repair) {
        if (prompt.contains("[[stub:broken-key-always]]") || (!repair && prompt.contains("[[stub:broken-key]]"))) {
            return "{\"exercises\":[{\"mechanic\":\"NOT_A_MECHANIC\"}]}";
        }
        Matcher current = CURRENT.matcher(prompt);
        if (!current.find()) return "{\"exercises\":[]}";
        try {
            // the prompt escaped & < > : the answer may carry them as written, the server decodes them
            JsonNode exercise = JSON.readTree(current.group(1).replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&"));
            if (!(exercise instanceof ObjectNode revised)) return "{\"exercises\":[]}";
            ArrayNode prompts = revised.path("prompt") instanceof ArrayNode array ? array : revised.putArray("prompt");
            boolean extended = false;
            for (JsonNode block : prompts) {
                if (block instanceof ObjectNode text && "TEXT".equals(text.path("kind").stringValue(""))) {
                    text.put("text", text.path("text").stringValue("").stripTrailing() + " " + SENTENCE);
                    extended = true;
                    break;
                }
            }
            if (!extended) {
                ObjectNode lead = JSON.createObjectNode().put("kind", "TEXT").put("text", SENTENCE);
                ArrayNode rebuilt = JSON.createArrayNode().add(lead);
                prompts.forEach(rebuilt::add);
                revised.set("prompt", rebuilt);
            }
            return JSON.createObjectNode().set("exercises", JSON.createArrayNode().add(revised)).toString();
        } catch (JacksonException unreadable) {
            return "{\"exercises\":[]}";
        }
    }
}
