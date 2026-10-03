package app.mnema.learning.generation.exercise;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Validator of the model output against {@code contracts/generation/exercises/output.schema.json} (the classpath copy
 * {@code ai/exercises/output.schema.json}; a test keeps the two identical). It implements exactly the keywords that file
 * uses and refuses a schema with any other keyword ({@link #requireSupported}), so a constraint is never silently ignored.
 *
 * <p>Every exercise is validated on its own against {@code $defs/exercise}: one bad exercise fails only its own artifact.
 * A result is the sorted list of JSON paths that violate the schema ({@code $.options[1].correct}); it never contains a
 * value from the answer.
 */
public final class ExerciseOutputSchema {
    private static final String RESOURCE = "/ai/exercises/output.schema.json";
    private static final Set<String> KNOWN_KEYWORDS = Set.of("$schema", "$id", "$defs", "$ref", "title", "description", "type",
            "properties", "required", "additionalProperties", "items", "minItems", "maxItems", "uniqueItems", "minLength",
            "maxLength", "pattern", "minimum", "maximum", "enum", "const", "oneOf", "anyOf");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JsonNode schema;
    private final String text;

    private ExerciseOutputSchema(JsonNode schema, String text) {
        requireSupported(schema, "$");
        this.schema = schema;
        this.text = text;
    }

    /** The schema of the classpath. */
    public static ExerciseOutputSchema load() {
        try (InputStream stream = ExerciseOutputSchema.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException("Missing exercise output schema");
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return new ExerciseOutputSchema(JSON.readTree(text), text);
        } catch (IOException | JacksonException failure) {
            throw new IllegalStateException("Unreadable exercise output schema", failure);
        }
    }

    /** Any schema of the supported subset (tests). */
    public static ExerciseOutputSchema of(JsonNode schema) {
        return new ExerciseOutputSchema(schema, schema.toString());
    }

    /** The schema text, rendered by code into the {@code {{schema}}} placeholder of the exercise prompt. */
    public String text() {
        return text;
    }

    /**
     * The shape of the whole answer: an object with exactly one member {@code exercises}, an array of 1 to 20 items. The items
     * themselves are not looked at here (see {@link #exercise}).
     */
    public boolean rootShape(JsonNode root) {
        JsonNode exercises = schema.path("properties").path("exercises");
        if (!root.isObject() || root.size() != 1 || !root.has("exercises") || !root.path("exercises").isArray()) return false;
        int size = root.path("exercises").size();
        return size >= exercises.path("minItems").intValue(1) && size <= exercises.path("maxItems").intValue(Integer.MAX_VALUE);
    }

    /** The paths of {@code exercise} that violate {@code $defs/exercise}; empty when it is valid. */
    public List<String> exercise(JsonNode exercise) {
        TreeSet<String> violations = new TreeSet<>();
        validate(exercise, schema.path("$defs").path("exercise"), "$", violations);
        return List.copyOf(violations);
    }

    /** The paths of the whole {@code root} that violate the schema (fixtures and tests); empty when it is valid. */
    public List<String> violations(JsonNode root) {
        TreeSet<String> violations = new TreeSet<>();
        validate(root, schema, "$", violations);
        return List.copyOf(violations);
    }

    // ---------------------------------------------------------------- the schema itself

    /** Fails loudly on a keyword this validator does not implement. */
    public static void requireSupported(JsonNode schema, String path) {
        if (!schema.isObject()) return;
        schema.propertyNames().forEach(keyword -> {
            if (!KNOWN_KEYWORDS.contains(keyword)) throw new IllegalStateException("Unsupported schema keyword " + keyword + " at " + path);
        });
        for (String container : List.of("properties", "$defs")) {
            schema.path(container).properties().forEach(entry -> requireSupported(entry.getValue(), path + "/" + container + "/" + entry.getKey()));
        }
        requireSupported(schema.path("items"), path + "/items");
        for (String combinator : List.of("oneOf", "anyOf")) {
            int index = 0;
            for (JsonNode branch : schema.path(combinator)) requireSupported(branch, path + "/" + combinator + "/" + index++);
        }
    }

    // --------------------------------------------------------------------- validation

    private void validate(JsonNode value, JsonNode rule, String path, Set<String> out) {
        if (rule.has("$ref")) {
            validate(value, schema.at(rule.path("$ref").stringValue("").substring(1)), path, out);
            return;
        }
        if (rule.has("oneOf") || rule.has("anyOf")) {
            combinator(value, rule, path, out);
            return;
        }
        if (rule.has("const") && !value.equals(rule.path("const"))) {
            out.add(path);
            return;
        }
        if (rule.has("enum") && !listed(rule.path("enum"), value)) {
            out.add(path);
            return;
        }
        if (rule.has("type") && !matchesType(value, rule.path("type"))) {
            out.add(path);
            return;
        }
        if (value.isString()) text(value.stringValue(""), rule, path, out);
        if (value.isIntegralNumber()) {
            if (rule.has("minimum") && value.longValue() < rule.path("minimum").longValue()) out.add(path);
            if (rule.has("maximum") && value.longValue() > rule.path("maximum").longValue()) out.add(path);
        }
        if (value.isObject()) object(value, rule, path, out);
        if (value.isArray()) array(value, rule, path, out);
    }

    private static boolean listed(JsonNode options, JsonNode value) {
        for (JsonNode option : options) if (option.equals(value)) return true;
        return false;
    }

    private static void text(String text, JsonNode rule, String path, Set<String> out) {
        if (rule.has("minLength") && text.length() < rule.path("minLength").intValue()) out.add(path);
        if (rule.has("maxLength") && text.length() > rule.path("maxLength").intValue()) out.add(path);
        if (rule.has("pattern") && !text.matches(rule.path("pattern").stringValue(""))) out.add(path);
    }

    private void object(JsonNode value, JsonNode rule, String path, Set<String> out) {
        for (JsonNode required : rule.path("required")) {
            if (!value.has(required.stringValue(""))) out.add(path + "." + required.stringValue(""));
        }
        JsonNode properties = rule.path("properties");
        boolean closed = rule.path("additionalProperties").isBoolean() && !rule.path("additionalProperties").booleanValue(true);
        for (String name : value.propertyNames()) {
            if (properties.has(name)) validate(value.path(name), properties.path(name), path + "." + name, out);
            else if (closed) out.add(path + "." + name);
        }
    }

    private void array(JsonNode value, JsonNode rule, String path, Set<String> out) {
        if (rule.has("minItems") && value.size() < rule.path("minItems").intValue()) out.add(path);
        if (rule.has("maxItems") && value.size() > rule.path("maxItems").intValue()) out.add(path);
        if (rule.path("uniqueItems").booleanValue(false)) {
            Set<JsonNode> seen = new HashSet<>();
            value.forEach(item -> {
                if (!seen.add(item)) out.add(path);
            });
        }
        if (rule.has("items")) {
            for (int index = 0; index < value.size(); index++) validate(value.get(index), rule.path("items"), path + "[" + index + "]", out);
        }
    }

    /**
     * {@code anyOf} and {@code oneOf}: valid when the branches say so. When none matches, the violations of the branch the
     * value was aiming at are reported: the one whose {@code mechanic} constant matches the value's, else the closest one.
     */
    private void combinator(JsonNode value, JsonNode rule, String path, Set<String> out) {
        boolean exactlyOne = rule.has("oneOf");
        List<TreeSet<String>> results = new ArrayList<>();
        for (JsonNode branch : rule.path(exactlyOne ? "oneOf" : "anyOf")) {
            TreeSet<String> branchViolations = new TreeSet<>();
            validate(value, branch, path, branchViolations);
            results.add(branchViolations);
        }
        long matching = results.stream().filter(Set::isEmpty).count();
        if (exactlyOne ? matching == 1 : matching >= 1) return;
        if (exactlyOne && matching > 1) {
            out.add(path);
            return;
        }
        int chosen = aimedBranch(value, rule.path(exactlyOne ? "oneOf" : "anyOf"), results);
        if (chosen < 0) out.add(path + ".mechanic");
        else out.addAll(results.get(chosen));
    }

    private int aimedBranch(JsonNode value, JsonNode branches, List<TreeSet<String>> results) {
        String mechanic = value.isObject() ? value.path("mechanic").stringValue(null) : null;
        int closest = -1;
        for (int index = 0; index < results.size(); index++) {
            JsonNode branch = branches.get(index);
            JsonNode target = branch.has("$ref") ? schema.at(branch.path("$ref").stringValue("").substring(1)) : branch;
            JsonNode constant = target.path("properties").path("mechanic").path("const");
            if (mechanic != null && constant.isString() && mechanic.equals(constant.stringValue(""))) return index;
            if (mechanic == null && (closest < 0 || results.get(index).size() < results.get(closest).size())) closest = index;
        }
        return mechanic == null ? closest : -1;
    }

    private static boolean matchesType(JsonNode value, JsonNode type) {
        if (type.isArray()) {
            for (JsonNode candidate : type) if (matchesType(value, candidate)) return true;
            return false;
        }
        return switch (type.stringValue("")) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isString();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> false;
        };
    }
}
