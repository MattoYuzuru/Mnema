package app.mnema.learning.moderation;

import app.mnema.learning.platform.api.InvalidRequestException;
import app.mnema.learning.platform.id.UuidPolicy;
import app.mnema.learning.platform.json.ContentJsonReader;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The strict, bounded bodies of the complaint routes: exactly the documented fields, no unknown or duplicate field, no coercion. A body is never echoed in
 * an error (a complaint text is personal data). Unicode that JSON cannot carry is rejected by {@link ContentJsonReader}.
 */
final class ComplaintCommands {
    static final int MAX_FILING_BYTES = 4096;
    static final int MAX_DECISION_BYTES = 256 * 1024;
    static final int MAX_TEXT = 1000;
    static final int MAX_UNITS = 1000;
    private static final Set<String> REASONS = Set.of("COPYRIGHT", "PERSONAL_DATA", "ILLEGAL", "SPAM", "OTHER");
    private static final Set<String> DECISIONS = Set.of("REJECTED", "HIDDEN", "TAKEN_DOWN", "ACCOUNT_REFERRED");
    private static final ContentJsonReader FILING = new ContentJsonReader(MAX_FILING_BYTES, 3, 16);
    private static final ContentJsonReader DECISION = new ContentJsonReader(MAX_DECISION_BYTES, 5, 20_000);

    private ComplaintCommands() { }

    /** A complaint to file: the closed reason and the optional text (null when absent or blank). */
    record Filing(String reason, String text) { }

    /** A takedown unit as the operator names it: the revision is null for "the published one". */
    record UnitSpec(String kind, UUID key, UUID revisionId) { }

    /** A decision of the operator. */
    record Decision(String decision, List<UnitSpec> units, boolean hide) { }

    static Filing filing(InputStream input) {
        JsonNode body = read(FILING, input, MAX_FILING_BYTES);
        Set<String> names = fieldNames(body);
        if (!names.contains("reason") || !Set.of("reason", "text").containsAll(names)) throw new InvalidRequestException();
        JsonNode reason = body.path("reason");
        if (!reason.isString() || !REASONS.contains(reason.stringValue(null))) throw new InvalidRequestException();
        String text = null;
        if (names.contains("text")) {
            JsonNode value = body.path("text");
            if (!value.isString()) throw new InvalidRequestException();
            text = clean(value.stringValue(null));
        }
        return new Filing(reason.stringValue(null), text);
    }

    static Decision decision(InputStream input) {
        JsonNode body = read(DECISION, input, MAX_DECISION_BYTES);
        Set<String> names = fieldNames(body);
        JsonNode decision = body.path("decision");
        if (!names.contains("decision") || !decision.isString() || !DECISIONS.contains(decision.stringValue(null))) throw new InvalidRequestException();
        String value = decision.stringValue(null);
        Set<String> allowed = "TAKEN_DOWN".equals(value) ? Set.of("decision", "units", "hide") : Set.of("decision");
        if (!allowed.containsAll(names)) throw new InvalidRequestException();
        if (!"TAKEN_DOWN".equals(value)) return new Decision(value, List.of(), false);
        boolean hide = false;
        if (names.contains("hide")) {
            if (!body.path("hide").isBoolean()) throw new InvalidRequestException();
            hide = body.path("hide").booleanValue(false);
        }
        JsonNode units = body.path("units");
        if (!units.isArray() || units.isEmpty() || units.size() > MAX_UNITS) throw new InvalidRequestException();
        List<UnitSpec> specs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode unit : units) {
            Set<String> unitNames = fieldNames(unit);
            if (!unit.isObject() || !unitNames.contains("kind") || !unitNames.contains("key") || !Set.of("kind", "key", "revisionId").containsAll(unitNames)) {
                throw new InvalidRequestException();
            }
            String kind = unit.path("kind").isString() ? unit.path("kind").stringValue(null) : null;
            if (!"ITEM".equals(kind) && !"EXERCISE".equals(kind)) throw new InvalidRequestException();
            UUID key = uuid(unit.path("key"));
            UUID revision = unit.path("revisionId").isMissingNode() || unit.path("revisionId").isNull() ? null : uuid(unit.path("revisionId"));
            // the same unit twice with the same target revision is one unit; ambiguity (two different revisions of a key) is allowed: both are named
            if (seen.add(kind + "/" + key + "/" + revision)) specs.add(new UnitSpec(kind, key, revision));
        }
        return new Decision(value, List.copyOf(specs), hide);
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        if (node.isObject()) node.properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }

    private static JsonNode read(ContentJsonReader reader, InputStream input, int max) {
        try {
            return reader.read(input.readNBytes(max + 1));
        } catch (IOException | IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    private static UUID uuid(JsonNode value) {
        if (!value.isString()) throw new InvalidRequestException();
        try {
            String text = value.stringValue(null);
            UUID id = UuidPolicy.requireEntityId(UUID.fromString(text), "id");
            if (!id.toString().equals(text)) throw new InvalidRequestException();
            return id;
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException();
        }
    }

    /** Trims; blank is absent; a longer text, a control character other than line feed and tab, or a Unicode format character is the 400. */
    static String clean(String text) {
        String trimmed = text.strip();
        if (trimmed.isEmpty()) return null;
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_TEXT) throw new InvalidRequestException();
        for (int index = 0; index < trimmed.length(); ) {
            int point = trimmed.codePointAt(index);
            if (Character.isISOControl(point) && point != '\n' && point != '\t') throw new InvalidRequestException();
            // Unicode FORMAT characters (bidi overrides and isolates, zero-width space and joiners, the byte order mark, the soft hyphen) change what the operator
            // reads in the console without showing: a complaint text is plain text. (This also refuses the zero-width joiner of emoji sequences: accepted.)
            if (Character.getType(point) == Character.FORMAT) throw new InvalidRequestException();
            index += Character.charCount(point);
        }
        return trimmed;
    }
}
