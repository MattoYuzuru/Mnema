package app.mnema.learning.generation.exercise;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * The lint rules that read the text of the pinned material, run again on a <em>compiled</em> exercise after the server moved
 * its pins to a newer revision of the material (the by-node-id re-pin of the approval, decision 8). Everything else the lint
 * checks is independent of the material text and cannot change in a pure re-pin. The three rules are those that resolve a quoted
 * node or search the material: a {@code MATERIAL} block must still read as text, a {@code SELF_CHECK} reference must not equal
 * its prompt, a {@code FREE_RESPONSE} answer must not occur in its prompt, and the passage of a {@code CLOZE} must still be a
 * fragment of one block.
 */
final class ExerciseRelint {
    private ExerciseRelint() { }

    /**
     * @param exercise {@code {type, content, answerKey, ...}} as compiled
     * @param nodeText the text of a quoted node in the new revision, empty when the node is gone
     * @param blockTexts the quotable top-level blocks of the new revision
     */
    static List<ExerciseFinding> check(JsonNode exercise, Function<UUID, Optional<String>> nodeText, List<String> blockTexts) {
        List<ExerciseFinding> findings = new ArrayList<>();
        JsonNode content = exercise.path("content");
        String type = exercise.path("type").stringValue("");
        Optional<String> prompt = text(content.path("prompt"), nodeText);
        Optional<String> reference = text(content.path("reference"), nodeText);
        if (prompt.isEmpty() || (content.has("reference") && reference.isEmpty())) {
            findings.add(ExerciseFinding.of(ExerciseCode.REF_UNKNOWN_HANDLE, "content"));
            return findings;
        }
        switch (type) {
            case "SELF_CHECK" -> {
                String shown = ExerciseTexts.normalize(prompt.get());
                String revealed = ExerciseTexts.normalize(reference.orElse(""));
                if (revealed.isEmpty()) findings.add(ExerciseFinding.of(ExerciseCode.SELF_CHECK_REFERENCE_BLANK, "reference"));
                else if (shown.equals(revealed)) findings.add(ExerciseFinding.of(ExerciseCode.SELF_CHECK_REFERENCE_EQUALS_PROMPT, "reference"));
            }
            case "FREE_RESPONSE" -> {
                String shown = ExerciseTexts.normalize(prompt.get());
                for (JsonNode accepted : exercise.path("answerKey").path("accepted")) {
                    if (ExerciseTexts.containsWord(shown, ExerciseTexts.normalize(accepted.stringValue("")))) {
                        findings.add(ExerciseFinding.of(ExerciseCode.FREE_RESPONSE_ANSWER_IN_PROMPT, "accepted"));
                        break;
                    }
                }
            }
            case "CLOZE" -> {
                if (!fragment(exercise, blockTexts)) findings.add(ExerciseFinding.of(ExerciseCode.CLOZE_FRAGMENT_NOT_IN_MATERIAL, "passage"));
            }
            default -> { }
        }
        return findings;
    }

    private static boolean fragment(JsonNode exercise, List<String> blockTexts) {
        Map<String, String> first = new HashMap<>();
        for (JsonNode blank : exercise.path("answerKey").path("blanks")) {
            first.put(blank.path("blankId").stringValue(""), blank.path("accepted").path(0).stringValue(""));
        }
        StringBuilder filled = new StringBuilder();
        for (JsonNode segment : exercise.path("content").path("passage")) {
            if (segment.path("kind").stringValue("").equals("TEXT")) filled.append(segment.path("text").stringValue(""));
            else filled.append(first.getOrDefault(segment.path("blankId").stringValue(""), ""));
        }
        String wanted = ExerciseTexts.collapse(filled.toString());
        return !wanted.isEmpty() && blockTexts.stream().anyMatch(block -> ExerciseTexts.collapse(block).contains(wanted));
    }

    /** The text a learner reads in a slot (TEXT verbatim, MATERIAL as the new revision reads), or empty when a quote is gone. */
    private static Optional<String> text(JsonNode blocks, Function<UUID, Optional<String>> nodeText) {
        List<String> parts = new ArrayList<>();
        for (JsonNode block : blocks) {
            if (block.path("kind").stringValue("").equals("MATERIAL")) {
                Optional<String> text = Optional.empty();
                try {
                    text = nodeText.apply(UUID.fromString(block.path("nodeId").stringValue("")));
                } catch (IllegalArgumentException malformed) {
                    return Optional.empty();
                }
                if (text.isEmpty() || text.get().isBlank()) return Optional.empty();
                parts.add(text.get());
            } else if (block.path("kind").stringValue("").equals("TEXT")) {
                parts.add(block.path("text").stringValue(""));
            }
        }
        return Optional.of(String.join("\n", parts));
    }
}
