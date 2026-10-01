package app.mnema.learning.catalog.exercise;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Strict wire-reading primitives for the exercise command model. Every failure is the same
 * {@link InvalidRequestException}: error text never echoes learner or author content.
 *
 * <p>Text limits count UTF-16 code units ({@code String.length()}), matching TypeScript.
 */
final class StrictJson {
    private StrictJson() { }

    static InvalidRequestException invalid() { return new InvalidRequestException(); }

    /** The object has exactly these fields: no missing and no unknown property. */
    static void fields(JsonNode node, String... expected) {
        if (!node.isObject() || !names(node).equals(Set.of(expected))) throw invalid();
    }

    /** The object has every required field and no field outside required plus optional. */
    static void fields(JsonNode node, Set<String> required, Set<String> optional) {
        Set<String> actual = node.isObject() ? names(node) : null;
        if (actual == null || !actual.containsAll(required)) throw invalid();
        Set<String> unknown = new HashSet<>(actual);
        unknown.removeAll(required);
        unknown.removeAll(optional);
        if (!unknown.isEmpty()) throw invalid();
    }

    static Set<String> names(JsonNode node) {
        return node.properties().stream().map(java.util.Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
    }

    static List<JsonNode> array(JsonNode node, int min, int max) {
        if (!node.isArray() || node.size() < min || node.size() > max) throw invalid();
        List<JsonNode> result = new ArrayList<>(node.size());
        node.forEach(result::add);
        return result;
    }

    /** Text that is present, not blank and at most {@code max} UTF-16 units. */
    static String nonBlank(JsonNode value, int max) {
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > max) throw invalid();
        return value.textValue();
    }

    static String oneOf(JsonNode value, Set<String> allowed) {
        if (!value.isTextual() || !allowed.contains(value.textValue())) throw invalid();
        return value.textValue();
    }

    static int integer(JsonNode value, int min, int max) {
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min
                || value.intValue() > max) throw invalid();
        return value.intValue();
    }

    static boolean bool(JsonNode value) {
        if (!value.isBoolean()) throw invalid();
        return value.booleanValue();
    }

    static UUID id(JsonNode object, String name) { return idValue(object.path(name)); }

    /** Canonical lowercase UUID text; the same value must round-trip. */
    static UUID idValue(JsonNode value) {
        if (!value.isTextual() || value.textValue().length() != 36) throw invalid();
        try {
            UUID id = UuidPolicy.requireEntityId(UUID.fromString(value.textValue()), "id");
            if (!id.toString().equals(value.textValue())) throw invalid();
            return id;
        } catch (IllegalArgumentException exception) { throw invalid(); }
    }

    /** Ordered ids whose values are all distinct. */
    static List<UUID> distinctIds(List<JsonNode> values) {
        List<UUID> result = new ArrayList<>(values.size());
        Set<UUID> seen = new HashSet<>();
        for (JsonNode value : values) {
            UUID id = idValue(value);
            if (!seen.add(id)) throw invalid();
            result.add(id);
        }
        return List.copyOf(result);
    }
}
