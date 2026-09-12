package app.mnema.learning.catalog.content;

import app.mnema.learning.platform.json.ContentJsonReader;
import app.mnema.learning.platform.json.CanonicalJsonHasher;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Native format-v1 boundary shared by future publication and durable drafts.
 * Unknown types/versions are preserved without interpreting their attributes or descendants.
 * No HTML, asset fetching, authorization, or editor-engine state belongs in this reader.
 */
public final class NativeDocumentReader {

    public static final int MAX_BYTES = 1_048_576;
    public static final int MAX_NODES = 10_000;
    public static final int MAX_DEPTH = 32;
    public static final int MAX_SCALAR_BYTES = 32_768;
    private static final Set<String> ENVELOPE_FIELDS = Set.of("formatVersion", "root");
    private static final Set<String> NODE_FIELDS = Set.of("id", "type", "version", "attrs", "content");
    private static final Pattern UUID_V4 = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}");
    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    // JSON nesting counts objects and child arrays, not just semantic nodes. The separate
    // bound also protects opaque attributes, which do not contribute to native node depth.
    private final ContentJsonReader json = new ContentJsonReader(MAX_BYTES, 128, 250_000);

    public NativeDocument read(byte[] utf8) {
        final JsonNode document;
        try {
            document = json.read(utf8);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
        if (!hasExactly(document, ENVELOPE_FIELDS) || !positiveInt(document.path("formatVersion"))
                || document.path("formatVersion").intValue() != 1
                || !"doc".equals(document.path("root").path("type").textValue())) {
            throw invalid();
        }
        validateScalarSizes(document);
        if (new CanonicalJsonHasher().canonicalBytes(document).length > MAX_BYTES) {
            throw invalid();
        }
        var pending = new ArrayDeque<Visit>();
        var ids = new HashSet<UUID>();
        pending.add(new Visit(document.path("root"), 1, NativeNodeSchema.Slot.ROOT, false, false));
        int count = 0;
        boolean unsupported = false;
        while (!pending.isEmpty()) {
            Visit visit = pending.removeLast();
            JsonNode node = visit.node();
            if (++count > MAX_NODES || visit.depth() > MAX_DEPTH || !node.isObject()
                    || !node.path("id").isTextual() || !UUID_V4.matcher(node.path("id").textValue()).matches()
                    || !ids.add(UUID.fromString(node.path("id").textValue()))
                    || !node.path("type").isTextual() || !TYPE.matcher(node.path("type").textValue()).matches()
                    || !positiveInt(node.path("version")) || !node.path("attrs").isObject()
                    || !node.path("content").isArray()) {
                throw invalid();
            }
            String type = node.path("type").textValue();
            boolean opaque = visit.opaque() || !NativeNodeSchema.supports(type, node.path("version").intValue());
            NativeNodeSchema.Slot slot = NativeNodeSchema.Slot.OPAQUE;
            if (opaque) {
                unsupported = true;
            } else {
                if (!hasExactly(node, NODE_FIELDS)) {
                    throw invalid();
                }
                slot = NativeNodeSchema.validate(node, visit.slot(), visit.insideLink());
            }
            for (JsonNode child : node.path("content")) {
                pending.add(new Visit(child, visit.depth() + 1, slot, opaque,
                        visit.insideLink() || "link".equals(type)));
            }
        }
        return new NativeDocument(document, count, unsupported);
    }

    static boolean positiveInt(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToInt() && node.intValue() > 0;
    }

    private static boolean hasExactly(JsonNode node, Set<String> fields) {
        return node.isObject() && node.size() == fields.size()
                && node.properties().stream().allMatch(property -> fields.contains(property.getKey()));
    }

    private static void validateScalarSizes(JsonNode document) {
        var pending = new ArrayDeque<JsonNode>();
        pending.add(document);
        while (!pending.isEmpty()) {
            JsonNode value = pending.removeLast();
            if (value.isTextual()) {
                requireScalarSize(value.textValue());
            } else if (value.isObject()) {
                for (var property : value.properties()) {
                    requireScalarSize(property.getKey());
                    pending.add(property.getValue());
                }
            } else if (value.isArray()) {
                value.forEach(pending::add);
            }
        }
    }

    private static void requireScalarSize(String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_SCALAR_BYTES) {
            throw invalid();
        }
    }

    static IllegalArgumentException invalid() {
        // Never include node IDs, paths, values, parser messages, or their causes.
        return new IllegalArgumentException("Invalid native document");
    }

    private record Visit(JsonNode node, int depth, NativeNodeSchema.Slot slot,
                         boolean opaque, boolean insideLink) {
    }
}
