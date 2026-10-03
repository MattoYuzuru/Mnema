package app.mnema.learning.generation.exercise;

import app.mnema.learning.ai.prompt.Redactor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Undoes what the prompt did to the material before the model saw it, so that the strings of an answer compare with the
 * material as it really is. {@code PromptBlocks} escapes {@code & < > "} to entities and redacts e-mail, phone and card patterns
 * to {@code [email]}, {@code [phone]} and {@code [card]}; a model that copies a fragment copies the entities and the
 * placeholders. Before the lint every string of an exercise (never the ids and enums, which have no such characters) gets exactly
 * those four entities decoded, in one pass (so {@code &amp;lt;} becomes {@code &lt;}); a string that holds a placeholder gets it
 * replaced by the original only when the result is a fragment of a block of the material <em>and</em> redacting that fragment gives
 * the string back. Anything else keeps its placeholder and fails the lint (a fragment that is not in the material) like any
 * other invented text.
 */
final class ModelStrings {
    private static final Pattern ENTITY = Pattern.compile("&(quot|lt|gt|amp);");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\[(?:email|phone|card)]");
    private static final int MAX_ORIGINAL = 64;

    private ModelStrings() { }

    /** A copy of {@code exercise} with every string decoded and restored; the input is not changed. */
    static JsonNode normalize(JsonNode exercise, ExerciseContext context) {
        List<String> blocks = new ArrayList<>();
        context.materials().values().forEach(material -> material.blocks().values().forEach(block -> blocks.add(ExerciseTexts.collapse(block.text()))));
        return walk(exercise, blocks);
    }

    private static JsonNode walk(JsonNode node, List<String> blocks) {
        if (node.isString()) return JsonNodeFactory.instance.stringNode(text(node.stringValue(""), blocks));
        if (node.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            node.properties().forEach(entry -> copy.set(entry.getKey(), walk(entry.getValue(), blocks)));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> copy.add(walk(child, blocks)));
            return copy;
        }
        return node;
    }

    static String text(String value, List<String> blocks) {
        String decoded = decode(value);
        return PLACEHOLDER.matcher(decoded).find() ? restore(decoded, blocks) : decoded;
    }

    static String decode(String value) {
        if (value.indexOf('&') < 0) return value;
        Matcher matcher = ENTITY.matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement = switch (matcher.group(1)) {
                case "quot" -> "\"";
                case "lt" -> "<";
                case "gt" -> ">";
                default -> "&";
            };
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        return matcher.appendTail(out).toString();
    }

    /**
     * The original of each placeholder, found by matching the string, with each placeholder as "up to 64 characters", against the
     * collapsed text of every block; a candidate counts only if redacting it gives the string back.
     */
    private static String restore(String value, List<String> blocks) {
        String wanted = ExerciseTexts.collapse(value);
        StringBuilder regex = new StringBuilder();
        Matcher placeholder = PLACEHOLDER.matcher(wanted);
        int copied = 0;
        while (placeholder.find()) {
            regex.append(Pattern.quote(wanted.substring(copied, placeholder.start()))).append("(.{1,").append(MAX_ORIGINAL).append("}?)");
            copied = placeholder.end();
        }
        regex.append(Pattern.quote(wanted.substring(copied)));
        Pattern pattern = Pattern.compile(regex.toString(), Pattern.DOTALL);
        for (String block : blocks) {
            Matcher found = pattern.matcher(block);
            while (found.find()) {
                String candidate = found.group();
                if (Redactor.redact(candidate).equals(wanted)) return candidate;
            }
        }
        return value;
    }
}
