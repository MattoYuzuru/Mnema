package app.mnema.learning.generation.mbm;

import app.mnema.learning.catalog.content.NativeDocument;
import app.mnema.learning.catalog.content.NativeDocumentReader;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** native-v1 to MBM and back: handles keep top-level node IDs; what MBM cannot say exactly is refused. */
class MbmRendererTest {

    private static final MbmRenderer RENDERER = new MbmRenderer();
    private static final MbmCompiler COMPILER = new MbmCompiler();
    private static final NativeDocumentReader READER = new NativeDocumentReader();
    private static final MbmOptions.Capabilities ALL = new MbmOptions.Capabilities(true, true);

    private final AtomicInteger ids = new AtomicInteger();

    // ------------------------------------------------------------------ native DSL

    private ObjectNode node(String type, String attrs, JsonNode... children) {
        ObjectNode node = MbmFixtures.JSON.createObjectNode();
        node.put("id", String.format("5a000000-0000-4000-8000-%012x", ids.incrementAndGet()));
        node.put("type", type);
        node.put("version", 1);
        node.set("attrs", MbmFixtures.JSON.readTree(attrs));
        ArrayNode content = node.putArray("content");
        for (JsonNode child : children) {
            content.add(child);
        }
        return node;
    }

    private ObjectNode text(String text, String... marks) {
        ObjectNode attrs = MbmFixtures.JSON.createObjectNode().put("text", text);
        ArrayNode array = attrs.putArray("marks");
        for (String mark : marks) {
            array.add(mark);
        }
        return node("text", attrs.toString());
    }

    private ObjectNode paragraph(JsonNode... inline) {
        return node("paragraph", "{}", inline);
    }

    private ObjectNode paragraph(String text) {
        return paragraph(text(text));
    }

    private ObjectNode doc(JsonNode... blocks) {
        return node("doc", "{}", blocks);
    }

    private NativeDocument read(ObjectNode root) {
        ObjectNode envelope = MbmFixtures.JSON.createObjectNode();
        envelope.put("formatVersion", 1);
        envelope.set("root", root);
        return READER.read(MbmFixtures.JSON.writeValueAsBytes(envelope));
    }

    private static List<JsonNode> blocks(ObjectNode root) {
        var list = new ArrayList<JsonNode>();
        root.path("content").forEach(list::add);
        return list;
    }

    private static MbmResult.Success recompile(MbmRendering rendering) {
        MbmOptions options = new MbmOptions(MbmOptions.Mode.EDIT, List.copyOf(rendering.links()), ALL, List.of(), 8, 0,
                rendering.handles(), Set.of(), null);
        MbmResult result = COMPILER.compile(rendering.text(), options, new RandomIdAllocator());
        assertThat(result).as(rendering.text()).isInstanceOf(MbmResult.Success.class);
        assertThat(((MbmResult.Success) result).warnings()).isEmpty();
        return (MbmResult.Success) result;
    }

    /** Renders the blocks, compiles the result as an edit and requires the same block IDs and shapes back. */
    private MbmRendering roundTrip(JsonNode... blocks) {
        ObjectNode root = doc(blocks);
        MbmRendering rendering = RENDERER.renderBlocks(blocks(root), MbmRenderer.Options.withHandles());
        MbmResult.Success success = recompile(rendering);
        JsonNode range = success.document().path("root").path("content");
        assertThat(range).hasSize(blocks.length);
        for (int i = 0; i < blocks.length; i++) {
            assertThat(range.get(i).path("id").stringValue()).isEqualTo(blocks[i].path("id").stringValue());
            assertThat(NativeShape.same(blocks[i], range.get(i))).as(rendering.text()).isTrue();
        }
        return rendering;
    }

    // ------------------------------------------------------------------ round trip

    @Test
    void aCompiledDocumentRendersAndRecompilesKeepingEveryTopLevelNodeId() throws IOException {
        String source = MbmFixtures.source("valid", "lesson-vocabulary");
        MbmOptions create = MbmFixtures.options(MbmFixtures.json("valid", "lesson-vocabulary", ".meta.json"));
        var first = (MbmResult.Success) COMPILER.compile(source, create, new RandomIdAllocator());
        NativeDocument document = READER.read(MbmFixtures.JSON.writeValueAsBytes(first.document()));

        MbmRendering rendering = RENDERER.render(document, MbmRenderer.Options.withHandles());
        assertThat(rendering.text()).startsWith("[[b1]] # ").contains("\n\n[[b2]] ").contains("::audio{slot=\"a1\" lang=\"ja\"");
        assertThat(rendering.handles().keySet()).containsExactly("b1", "b2", "b3", "b4", "b5", "b6", "b7", "b8", "b9");
        assertThat(rendering.links()).containsExactly("https://jisho.org/");
        assertThat(rendering.slotKeys()).containsExactly("a1", "i1");

        MbmResult.Success second = recompile(rendering);
        JsonNode before = first.document().path("root").path("content");
        JsonNode after = second.document().path("root").path("content");
        assertThat(after).hasSize(before.size());
        for (int i = 0; i < before.size(); i++) {
            assertThat(after.get(i).path("id")).isEqualTo(before.get(i).path("id"));
            assertThat(NativeShape.same(before.get(i), after.get(i))).as("block " + i).isTrue();
        }
        assertThat(second.slots()).extracting(MbmSlot::slotKey).containsExactly("a1", "i1");
    }

    @Test
    void everyBlockTypeSurvivesTheRoundTrip() {
        ObjectNode link = node("link", "{\"href\":\"https://a.example/x\"}", text("site", "strong"));
        ObjectNode firstItem = node("list_item", "{}", paragraph("one"));
        MbmRendering rendering = roundTrip(
                node("heading", "{\"level\":2}", text("Title "), text("em", "em")),
                paragraph(text("plain "), text("bold", "strong"), text(" and "), text("x", "code"), text(" "), link,
                        node("ruby", "{\"base\":\"漢字\",\"reading\":\"かんじ\"}")),
                node("bullet_list", "{}", firstItem, node("list_item", "{}", paragraph("two"))),
                node("ordered_list", "{\"order\":4}", node("list_item", "{}", paragraph("a")), node("list_item", "{}", paragraph("b"))),
                node("blockquote", "{}", paragraph("q1"), paragraph("q2")),
                node("divider", "{}"),
                node("table", "{\"caption\":\"cap\",\"columns\":[\"A\",\"B|\"],\"rows\":[[\"1\",\"x\\\\y\"],[\"\",\"3\"]]}"),
                node("mermaid", "{\"source\":\"graph LR\\n  A --> B\\n```\",\"title\":\"t\",\"description\":\"d\"}"),
                node("audio", "{\"assetId\":\"" + UUID.randomUUID() + "\",\"title\":\"a \\\"t\\\"\",\"transcript\":\"hello\",\"lang\":\"en\"}"),
                node("image", "{\"assetId\":\"" + UUID.randomUUID() + "\",\"alt\":\"cat\"}"),
                node("video", "{\"assetId\":\"" + UUID.randomUUID() + "\",\"title\":\"v\"}"),
                node("bullet_list", "{}", node("list_item", "{}", paragraph())));
        assertThat(rendering.links()).containsExactly("https://a.example/x");
        assertThat(rendering.slotKeys()).containsExactly("a1", "i1", "v1");
        assertThat(rendering.text()).contains("> q1\n>\n> q2").contains("4. a\n5. b").contains("````mermaid");
    }

    @Test
    void textThatLooksLikeMarkupIsEscapedAndSurvives() {
        roundTrip(paragraph("# not a heading"), paragraph("> not a quote"), paragraph("- not a list"),
                paragraph("12. not a list"), paragraph(":: not a directive"), paragraph("--- not a divider"),
                paragraph("stars * and ** and [brackets] and {braces} and `ticks` and back\\slash"),
                paragraph("[[b1]] not a handle"));
    }

    @Test
    void codeSpansPickADelimiterThatDoesNotOccurInTheCode() {
        roundTrip(paragraph(text("a`b", "code")), paragraph(text("a``b ` c", "code"), text(" tail")),
                paragraph(text("bold code", "strong", "code")));
    }

    @Test
    void editWhitespaceAroundMarkedTextIsMovedOutsideTheDelimiters() {
        roundTrip(paragraph(text("a "), text(" b ", "strong"), text(" c")), paragraph(text("  lead and trail  ")));
    }

    @Test
    void emptyParagraphsAreAddressedByAHandleOnlyLine() {
        MbmRendering rendering = roundTrip(paragraph(), paragraph("after"));
        assertThat(rendering.text()).startsWith("[[b1]]\n\n[[b2]] after");
    }

    @Test
    void renderingWithoutHandlesSeparatesAdjacentListsAndNumbersFromTheFirstHandle() {
        ObjectNode root = doc(node("bullet_list", "{}", node("list_item", "{}", paragraph("a"))),
                node("bullet_list", "{}", node("list_item", "{}", paragraph("b"))), paragraph("c"));
        MbmRendering plain = RENDERER.renderBlocks(blocks(root), new MbmRenderer.Options(false, 1, Set.of()));
        assertThat(plain.text()).isEqualTo("- a\n\n\n- b\n\nc");
        assertThat(plain.handles()).isEmpty();
        var compiled = (MbmResult.Success) COMPILER.compile(plain.text(), MbmOptions.create(), new RandomIdAllocator());
        assertThat(compiled.document().path("root").path("content")).hasSize(3);

        MbmRendering numbered = RENDERER.renderBlocks(blocks(root), new MbmRenderer.Options(true, 7, Set.of()));
        assertThat(numbered.handles().keySet()).containsExactly("b7", "b8", "b9");
        assertThat(numbered.handles().get("b9").type()).isEqualTo("paragraph");
    }

    @Test
    void generatedSlotKeysAvoidReservedKeys() {
        ObjectNode root = doc(node("image", "{\"assetId\":\"" + UUID.randomUUID() + "\",\"alt\":\"a\"}"),
                node("image", "{\"assetId\":\"" + UUID.randomUUID() + "\",\"alt\":\"b\"}"));
        MbmRendering rendering = RENDERER.renderBlocks(blocks(root), new MbmRenderer.Options(true, 1, Set.of("i1", "i3")));
        assertThat(rendering.slotKeys()).containsExactly("i2", "i4");
    }

    @Test
    void longImageAndVideoPromptsAreTruncatedButAudioMustFitExactly() {
        String longAlt = "a".repeat(400);
        MbmRendering image = roundTrip(node("image", "{\"assetId\":\"" + UUID.randomUUID() + "\",\"alt\":\"" + longAlt + "\"}"));
        assertThat(image.text()).endsWith(" " + "a".repeat(300));
        MbmRendering video = roundTrip(node("video", "{\"assetId\":\"" + UUID.randomUUID() + "\",\"title\":\"t\"}"));
        assertThat(video.text()).endsWith("} t");
        assertThatThrownBy(() -> RENDERER.renderBlocks(blocks(doc(node("audio", "{\"assetId\":\"" + UUID.randomUUID()
                + "\",\"title\":\"t\",\"transcript\":\"" + "x".repeat(601) + "\",\"lang\":\"en\"}"))), MbmRenderer.Options.withHandles()))
                .isInstanceOf(MbmUnsupportedContentException.class);
    }

    // ------------------------------------------------------------------ refusals

    private void assertRefused(JsonNode... blocks) {
        ObjectNode root = doc(blocks);
        assertThatThrownBy(() -> RENDERER.renderBlocks(blocks(root), MbmRenderer.Options.withHandles()))
                .isInstanceOfSatisfying(MbmUnsupportedContentException.class, exception -> {
                    assertThat(exception.blocks()).isNotEmpty();
                    assertThat(exception.getMessage()).doesNotContain("secret");
                });
    }

    @Test
    void whatMbmCannotSayExactlyIsRefusedBeforeAnyModelCall() {
        assertRefused(node("youtube", "{\"videoId\":\"abcdefghijk\",\"title\":\"secret\"}"));
        assertRefused(node("heading", "{\"level\":4}", text("secret")));
        assertRefused(node("paragraph", "{\"lang\":\"ru\"}", text("secret")));
        assertRefused(node("paragraph", "{\"dir\":\"rtl\"}", text("secret")));
        assertRefused(paragraph(text("secret", "strong", "em")));
        assertRefused(paragraph(text("pre"), text("secret", "strong"), text("post")));
        assertRefused(node("table", "{\"caption\":\"c\",\"summary\":\"s\",\"columns\":[\"A\"],\"rows\":[]}"));
        assertRefused(node("table", "{\"caption\":\"c\",\"columns\":[\"A\"],\"rows\":[[\"line\\nbreak\"]]}"));
        assertRefused(node("image", "{\"assetId\":\"" + UUID.randomUUID() + "\",\"alt\":\"a\",\"caption\":\"secret\"}"));
        assertRefused(node("audio", "{\"assetId\":\"" + UUID.randomUUID() + "\",\"title\":\"t\"}"));
        assertRefused(node("mermaid", "{\"source\":\"a\",\"title\":\"multi\\nline\",\"description\":\"d\"}"));
        assertRefused(node("blockquote", "{}", node("bullet_list", "{}", node("list_item", "{}", paragraph("secret")))));
        assertRefused(node("bullet_list", "{}", node("list_item", "{}", paragraph("a"), paragraph("b"))));
        assertRefused(node("video", "{\"assetId\":\"" + UUID.randomUUID() + "\",\"title\":\"t\",\"transcript\":\"secret\"}"));
        assertRefused(node("opaque_future", "{}"));
        assertRefused(paragraph(node("link", "{\"href\":\"https://a.example/a)b\"}", text("x"))));
        assertRefused(paragraph(node("ruby", "{\"base\":\"a|b\",\"reading\":\"c\"}")));
        assertRefused(paragraph(text("`edge", "code")));
    }

    @Test
    void everyRefusedBlockIsReportedNotOnlyTheFirst() {
        ObjectNode root = doc(node("youtube", "{\"videoId\":\"abcdefghijk\",\"title\":\"t\"}"), paragraph("fine"),
                node("heading", "{\"level\":5}", text("x")));
        assertThatThrownBy(() -> RENDERER.renderBlocks(blocks(root), MbmRenderer.Options.withHandles()))
                .isInstanceOfSatisfying(MbmUnsupportedContentException.class, exception ->
                        assertThat(exception.blocks()).extracting(MbmUnsupportedContentException.Block::type)
                                .containsExactly("youtube", "heading"));
    }

    @Test
    void optionsRejectANegativeFirstHandle() {
        assertThatThrownBy(() -> new MbmRenderer.Options(true, -1, Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(RENDERER.renderBlocks(List.of(), MbmRenderer.Options.withHandles()).text()).isEmpty();
        assertThat(read(doc(paragraph("x"))).nodeCount()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ review fixes: never drop data

    @Test
    void inlineAttributesOfTextAndLinksAreNeverDropped() {
        assertRefused(paragraph(node("text", "{\"text\":\"secret\",\"marks\":[],\"lang\":\"fr\"}")));
        assertRefused(paragraph(node("text", "{\"text\":\"secret\",\"marks\":[\"em\"],\"dir\":\"rtl\"}")));
        assertRefused(paragraph(node("link", "{\"href\":\"https://a.example/\",\"lang\":\"fr\"}", text("secret"))));
        assertRefused(paragraph(node("ruby", "{\"base\":\"a\",\"reading\":\"b\",\"lang\":\"ja\"}")));
    }

    @Test
    void futureVersionsAndExtensionFieldsAreRefused() {
        ObjectNode future = paragraph("secret");
        future.put("version", 2);
        assertRefused(future);
        ObjectNode nested = node("blockquote", "{}", paragraph("a"));
        ((ObjectNode) nested.path("content").get(0)).put("version", 3);
        assertRefused(nested);
        ObjectNode extended = paragraph("secret");
        extended.put("extension", "x");
        assertRefused(extended);
        ObjectNode deep = node("bullet_list", "{}", node("list_item", "{}", paragraph(text("secret"))));
        ((ObjectNode) deep.path("content").get(0).path("content").get(0).path("content").get(0)).put("extra", true);
        assertRefused(deep);
    }

    @Test
    void anEmptyParagraphKeepsItsAttributesOrIsRefused() {
        assertRefused(node("paragraph", "{\"lang\":\"fr\"}"));
        assertRefused(node("paragraph", "{\"dir\":\"ltr\"}"));
    }

    @Test
    void rootAttributesAndOpaqueNodesBlockAWholeDocumentRender() {
        ObjectNode withLang = doc(paragraph("a"));
        ((ObjectNode) withLang.path("attrs")).put("lang", "fr");
        assertThatThrownBy(() -> RENDERER.render(read(withLang), MbmRenderer.Options.withHandles()))
                .isInstanceOfSatisfying(MbmUnsupportedContentException.class, exception ->
                        assertThat(exception.blocks()).extracting(MbmUnsupportedContentException.Block::type).containsExactly("doc"));
        ObjectNode opaque = doc(paragraph("a"), node("paragraph", "{}", text("secret")));
        ((ObjectNode) opaque.path("content").get(1)).put("version", 2);
        assertThatThrownBy(() -> RENDERER.render(read(opaque), MbmRenderer.Options.withHandles()))
                .isInstanceOf(MbmUnsupportedContentException.class);
        assertThat(RENDERER.render(read(doc(paragraph("a"))), MbmRenderer.Options.withHandles()).text()).isEqualTo("[[b1]] a");
    }

    @Test
    void aLineBreakInsideTextIsRefusedNotTurnedIntoASpace() {
        assertRefused(paragraph("two\nlines"));
        assertRefused(paragraph("two\rlines"));
        assertRefused(paragraph(text("code\nspan", "code")));
    }

    @Test
    void unicodeLineSeparatorsInTextSurviveTheRoundTrip() {
        roundTrip(paragraph("a\u0085b"), paragraph("a\u2028b"), paragraph("a\u2029b"), paragraph("\u0085lead"),
                node("heading", "{\"level\":1}", text("h\u2028x")), node("blockquote", "{}", paragraph("q\u0085")));
    }

    @Test
    void whitespaceAndMarkOrderAreTheOnlyDocumentedNormalizations() {
        roundTrip(paragraph(text("  lead")), paragraph(text("code then bold", "code", "strong")),
                node("table", "{\"caption\":\"c\",\"columns\":[\"A\"],\"rows\":[[\" padded \"]]}"));
    }
}
