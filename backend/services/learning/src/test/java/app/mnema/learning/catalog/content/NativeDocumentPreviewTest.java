package app.mnema.learning.catalog.content;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NativeDocumentPreviewTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void skipsEmptyBlocksAndOpaquePayloadsAndReadsFirstContentInOrder() {
        var doc = document();
        var children = doc.withObject("root").withArray("content");
        children.add(block("paragraph", "   "));
        var opaque = node("future_widget");
        opaque.withObject("attrs").put("text", "Internal media identifier");
        children.add(opaque);
        children.add(block("heading", "  Париж\n —  столица Франции "));
        children.add(block("paragraph", "Later paragraph"));
        assertThat(NativeDocumentPreview.title(new NativeDocumentReader().read(doc.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .isEqualTo("Париж — столица Франции");
    }

    @Test
    void boundsUnicodeWithoutSplittingSupplementaryCharacters() {
        var doc = document();
        doc.withObject("root").withArray("content").add(block("paragraph", "😀".repeat(300)));
        String title = NativeDocumentPreview.title(new NativeDocumentReader().read(doc.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(title.codePointCount(0, title.length())).isEqualTo(240);
        assertThat(title).isEqualTo("😀".repeat(240));
        var empty = document(); empty.withObject("root").withArray("content").add(block("paragraph", " "));
        assertThat(NativeDocumentPreview.title(new NativeDocumentReader().read(empty.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .isEmpty();
    }

    @Test
    void includesRubyBaseWithoutPronunciationMarkup() {
        var doc = document(); var paragraph = node("paragraph"); var ruby = node("ruby");
        ruby.withObject("attrs").put("base", "日本").put("reading", "にほん");
        paragraph.withArray("content").add(ruby); doc.withObject("root").withArray("content").add(paragraph);
        assertThat(NativeDocumentPreview.title(new NativeDocumentReader().read(doc.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .isEqualTo("日本");
    }

    private ObjectNode document() {
        var doc = json.createObjectNode().put("formatVersion", 1);
        doc.set("root", node("doc")); return doc;
    }
    private ObjectNode block(String type, String text) {
        var block = node(type);
        if ("heading".equals(type)) block.withObject("attrs").put("level", 1);
        var leaf = node("text"); leaf.withObject("attrs").put("text", text).putArray("marks");
        block.withArray("content").add(leaf); return block;
    }
    private ObjectNode node(String type) {
        var node = json.createObjectNode().put("id", UUID.randomUUID().toString()).put("type", type).put("version", 1);
        node.putObject("attrs"); node.putArray("content"); return node;
    }
}
