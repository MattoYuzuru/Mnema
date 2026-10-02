package app.mnema.learning.generation.mbm;

import app.mnema.learning.catalog.content.NativeDocumentReader;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The native-v1 lexical profile ({@code href}, {@code lang}) as the reader itself applies it. The profile has one
 * implementation, {@code NativeDocumentReader}: a candidate value is placed in a one-node probe document and the
 * reader decides. That keeps the compiler free of a second validator that could drift from the reader.
 */
final class NativeProfile {

    private static final NativeDocumentReader READER = new NativeDocumentReader();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ROOT_ID = "00000000-0000-4000-8000-000000000001";
    private static final String CHILD_ID = "00000000-0000-4000-8000-000000000002";
    private static final String LEAF_ID = "00000000-0000-4000-8000-000000000003";

    private NativeProfile() {
    }

    /** Whether {@code href} is an accepted link target. */
    static boolean acceptsHref(String href) {
        ObjectNode link = node(LEAF_ID, "link", 1);
        link.putObject("attrs").put("href", href);
        ObjectNode text = node("00000000-0000-4000-8000-000000000004", "text", 1);
        text.putObject("attrs").put("text", "x").putArray("marks");
        link.putArray("content").add(text);
        ObjectNode paragraph = node(CHILD_ID, "paragraph", 1);
        paragraph.putObject("attrs");
        paragraph.putArray("content").add(link);
        return accepts(paragraph);
    }

    /** Whether {@code lang} is an accepted language tag. */
    static boolean acceptsLang(String lang) {
        ObjectNode paragraph = node(CHILD_ID, "paragraph", 1);
        paragraph.putObject("attrs").put("lang", lang);
        paragraph.putArray("content");
        return accepts(paragraph);
    }

    private static boolean accepts(ObjectNode block) {
        ObjectNode root = node(ROOT_ID, "doc", 1);
        root.putObject("attrs");
        root.putArray("content").add(block);
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("formatVersion", 1);
        envelope.set("root", root);
        try {
            READER.read(JSON.writeValueAsBytes(envelope));
            return true;
        } catch (IllegalArgumentException | JacksonException exception) {
            return false;
        }
    }

    private static ObjectNode node(String id, String type, int version) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", id);
        node.put("type", type);
        node.put("version", version);
        node.putObject("attrs");
        node.putArray("content");
        return node;
    }
}
