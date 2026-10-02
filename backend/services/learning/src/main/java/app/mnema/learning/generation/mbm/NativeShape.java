package app.mnema.learning.generation.mbm;

import app.mnema.learning.platform.json.CanonicalJsonHasher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * A canonical, identity-free form of a native-v1 subtree, used to prove that rendering a block as MBM and compiling it
 * again reproduces the block. Identifiers and asset IDs are dropped; adjacent text with equal marks is merged; marks
 * are a set; whitespace at the edge of marked text is unmarked, the edges of a block's inline content and table cells
 * are trimmed (MBM normalizes all of this); a first list number of 1 is the default. Everything else, including every
 * attribute of every node ({@code lang}, {@code dir}, ...) and line breaks inside text, must be equal.
 */
final class NativeShape {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final CanonicalJsonHasher CANONICAL = new CanonicalJsonHasher();

    private NativeShape() {
    }

    static boolean same(JsonNode left, JsonNode right) {
        return java.util.Arrays.equals(CANONICAL.canonicalBytes(of(left)), CANONICAL.canonicalBytes(of(right)));
    }

    static ObjectNode of(JsonNode node) {
        String type = node.path("type").stringValue("");
        ObjectNode shape = JSON.createObjectNode();
        shape.put("type", type);
        ObjectNode attrs = shape.putObject("attrs");
        node.path("attrs").properties().forEach(entry -> {
            boolean dropped = entry.getKey().equals("assetId")
                    || ("order".equals(entry.getKey()) && entry.getValue().intValue(0) == 1)
                    || ("table".equals(type) && entry.getKey().equals("rows"));
            if (!dropped) {
                attrs.set(entry.getKey(), entry.getValue());
            }
        });
        if ("table".equals(type)) {
            ArrayNode rows = attrs.putArray("rows");
            node.path("attrs").path("rows").forEach(row -> {
                ArrayNode cells = rows.addArray();
                row.forEach(cell -> cells.add(cell.stringValue("").strip()));
            });
        }
        ArrayNode content = shape.putArray("content");
        boolean inline = type.equals("paragraph") || type.equals("heading");
        if (inline) {
            inlines(node.path("content"), content, true);
        } else {
            node.path("content").forEach(child -> content.add(of(child)));
        }
        return shape;
    }

    private record Piece(String text, TreeSet<String> marks, JsonNode other) {
    }

    private static final Set<String> NODE_FIELDS = Set.of("id", "type", "version", "attrs", "content");

    /**
     * Whether the node, and with {@code deep} its whole subtree, is a plain version-1 node: exactly the five core
     * fields at most and {@code version} 1. Future versions and extension fields are not interpreted by MBM.
     */
    static boolean isPlainV1(JsonNode node, boolean deep) {
        var pending = new ArrayDeque<JsonNode>();
        pending.add(node);
        while (!pending.isEmpty()) {
            JsonNode current = pending.removeLast();
            if (!current.isObject() || current.path("version").intValue(0) != 1
                    || !current.properties().stream().allMatch(property -> NODE_FIELDS.contains(property.getKey()))) {
                return false;
            }
            if (deep) {
                current.path("content").forEach(pending::add);
            }
        }
        return true;
    }

    private static void inlines(JsonNode children, ArrayNode out, boolean trimEdges) {
        var pieces = new ArrayList<Piece>();
        for (JsonNode child : children) {
            String type = child.path("type").stringValue("");
            if (type.equals("text") && onlyTextAndMarks(child.path("attrs"))) {
                addText(pieces, child.path("attrs").path("text").stringValue(""), marks(child));
            } else if (type.equals("text")) {
                // lang, dir or anything else on a text node: kept in full so it can never be dropped silently
                ObjectNode text = of(child);
                text.set("marks", sortedMarks(child));
                pieces.add(new Piece(null, null, text));
            } else if (type.equals("link")) {
                ObjectNode link = JSON.createObjectNode();
                link.put("type", "link");
                link.set("attrs", child.path("attrs").deepCopy());
                inlines(child.path("content"), link.putArray("content"), false);
                pieces.add(new Piece(null, null, link));
            } else {
                pieces.add(new Piece(null, null, of(child)));
            }
        }
        if (trimEdges) {
            trim(pieces);
        }
        for (Piece piece : pieces) {
            if (piece.other() != null) {
                out.add(piece.other());
            } else if (!piece.text().isEmpty()) {
                ObjectNode text = out.addObject();
                text.put("type", "text");
                text.put("text", piece.text());
                ArrayNode marks = text.putArray("marks");
                piece.marks().forEach(marks::add);
            }
        }
    }

    private static boolean onlyTextAndMarks(JsonNode attrs) {
        return attrs.properties().stream().allMatch(property -> property.getKey().equals("text") || property.getKey().equals("marks"));
    }

    private static ArrayNode sortedMarks(JsonNode text) {
        ArrayNode marks = JSON.createArrayNode();
        marks(text).forEach(marks::add);
        return marks;
    }

    /** Appends text, merging with the previous piece of equal marks; edge whitespace of marked text is unmarked. */
    private static void addText(List<Piece> pieces, String text, TreeSet<String> marks) {
        if (!marks.isEmpty() && !marks.contains("code")) {
            String core = text.strip();
            if (core.isEmpty()) {
                marks = new TreeSet<>();
            } else {
                int start = text.indexOf(core);
                add(pieces, text.substring(0, start), new TreeSet<>());
                add(pieces, core, marks);
                add(pieces, text.substring(start + core.length()), new TreeSet<>());
                return;
            }
        }
        add(pieces, text, marks);
    }

    private static void add(List<Piece> pieces, String text, TreeSet<String> marks) {
        if (text.isEmpty()) {
            return;
        }
        if (!pieces.isEmpty()) {
            Piece last = pieces.get(pieces.size() - 1);
            if (last.other() == null && last.marks().equals(marks)) {
                pieces.set(pieces.size() - 1, new Piece(last.text() + text, marks, null));
                return;
            }
        }
        pieces.add(new Piece(text, marks, null));
    }

    private static void trim(List<Piece> pieces) {
        while (!pieces.isEmpty() && pieces.get(0).other() == null && pieces.get(0).text().isBlank()
                && pieces.get(0).marks().isEmpty()) {
            pieces.remove(0);
        }
        if (!pieces.isEmpty() && pieces.get(0).other() == null && pieces.get(0).marks().isEmpty()) {
            pieces.set(0, new Piece(pieces.get(0).text().stripLeading(), pieces.get(0).marks(), null));
        }
        int last = pieces.size() - 1;
        while (last >= 0 && pieces.get(last).other() == null && pieces.get(last).text().isBlank()
                && pieces.get(last).marks().isEmpty()) {
            pieces.remove(last--);
        }
        if (last >= 0 && pieces.get(last).other() == null && pieces.get(last).marks().isEmpty()) {
            pieces.set(last, new Piece(pieces.get(last).text().stripTrailing(), pieces.get(last).marks(), null));
        }
    }

    private static TreeSet<String> marks(JsonNode text) {
        var marks = new TreeSet<String>();
        text.path("attrs").path("marks").forEach(mark -> marks.add(mark.stringValue("")));
        return marks;
    }
}
