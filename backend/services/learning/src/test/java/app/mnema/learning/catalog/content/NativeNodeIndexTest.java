package app.mnema.learning.catalog.content;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NativeNodeIndexTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void aCodeBlockProjectsToItsSourceVerbatimAndBlocksJoinWithLineFeeds() {
        String source = "SELECT 1;\n\n\tFROM t   \n";
        ObjectNode paragraph = node(3, "paragraph");
        paragraph.withArray("content").add(text(4, "before"));
        ObjectNode code = node(2, "code_block");
        code.withObject("attrs").put("lang", "sql").put("source", source);
        ObjectNode doc = node(1, "doc");
        doc.withArray("content").add(code).add(paragraph);
        NativeNodeIndex index = NativeNodeIndex.of(read(doc, true));
        assertThat(index.text(id(2))).contains(source);
        assertThat(index.text(id(1))).contains(source + "\nbefore");
    }

    @Test
    void aRetainedOpaqueCodeBlockProjectsToNothingAndNeverLeaksIntoInlineText() {
        ObjectNode legacy = node(2, "code_block");
        legacy.withObject("attrs").put("language", "kotlin").put("source", "secret");
        ObjectNode inline = node(3, "paragraph");
        inline.withArray("content").add(text(4, "visible"));
        ObjectNode misplaced = node(5, "code_block");
        misplaced.withObject("attrs").put("source", "misplaced");
        inline.withArray("content").add(misplaced);
        ObjectNode doc = node(1, "doc");
        doc.withArray("content").add(legacy).add(inline);
        NativeNodeIndex index = NativeNodeIndex.of(read(doc, false));
        assertThat(index.text(id(2))).contains("");
        assertThat(index.text(id(3))).contains("visible");
        assertThat(index.text(id(1))).contains("visible");
    }

    @Test
    void aCodeBlockIsNeverTheTitleOfAnItem() {
        ObjectNode code = node(2, "code_block");
        code.withObject("attrs").put("source", "SELECT 1;");
        ObjectNode doc = node(1, "doc");
        doc.withArray("content").add(code);
        assertThat(NativeDocumentPreview.title(read(doc, true))).isEmpty();
        ObjectNode heading = node(3, "heading");
        heading.withObject("attrs").put("level", 1);
        heading.withArray("content").add(text(4, "Plan"));
        doc.withArray("content").add(heading);
        assertThat(NativeDocumentPreview.title(read(doc, true))).isEqualTo("Plan");
    }

    private static NativeDocument read(ObjectNode root, boolean strict) {
        ObjectNode envelope = JSON.createObjectNode().put("formatVersion", 1);
        envelope.set("root", root);
        byte[] bytes = envelope.toString().getBytes(StandardCharsets.UTF_8);
        NativeDocumentReader reader = new NativeDocumentReader();
        return strict ? reader.read(bytes) : reader.readRetained(bytes);
    }

    private static ObjectNode text(int number, String value) {
        ObjectNode node = node(number, "text");
        node.withObject("attrs").put("text", value);
        return node;
    }

    private static ObjectNode node(int number, String type) {
        ObjectNode node = JSON.createObjectNode().put("id", id(number).toString()).put("type", type).put("version", 1);
        node.putObject("attrs");
        ArrayNode content = node.putArray("content");
        assertThat(content).isEmpty();
        return node;
    }

    private static UUID id(int number) {
        return UUID.fromString("00000000-0000-4000-8000-%012x".formatted(number));
    }
}
