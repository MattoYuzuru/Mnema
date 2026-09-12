package app.mnema.learning.catalog.content;

import app.mnema.learning.platform.json.CanonicalJsonHasher;
import app.mnema.learning.platform.json.ContentJsonReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NativeDocumentReaderTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private final NativeDocumentReader reader = new NativeDocumentReader();

    @Test
    void sharedMultilingualEditorFixturePreservesEverySemanticFieldAndIdentity() throws Exception {
        byte[] fixture = Files.readAllBytes(contractRoot().resolve("valid/mixed.json"));
        NativeDocument document = reader.read(fixture);
        assertThat(document.nodeCount()).isEqualTo(35);
        assertThat(document.hasUnsupportedContent()).isTrue();
        assertThat(reader.read(new CanonicalJsonHasher().canonicalBytes(document.toJson())).toJson())
                .isEqualTo(document.toJson());
        assertThat(document.toJson().toString()).contains("漢字", "العِلْمُ", "futureFlag", "onerror");
    }

    @Test
    void validatedSnapshotCannotBeChangedByCallerAndOptionalAttributesAreNotInvented() {
        ObjectNode tree = document(paragraph(2, text(3, "Материал")));
        NativeDocument validated = read(tree);
        assertThat(validated.hasUnsupportedContent()).isFalse();
        assertThat(validated.nodeCount()).isEqualTo(3);
        ((ObjectNode) validated.toJson()).removeAll();
        tree.removeAll();
        assertThat(validated.toJson().path("root").path("content").path(0).path("attrs")).isEmpty();
        assertThat(validated.toJson().path("formatVersion").intValue()).isEqualTo(1);
    }

    @Test
    void unknownSubtreePreservesExtensionFieldsAndDoesNotInterpretKnownLookingDescendants() {
        ObjectNode future = node(2, "future_widget");
        future.put("extension", "<script>not executable</script>");
        future.withObject("attrs").put("unknown", true);
        ObjectNode descendant = node(3, "text");
        descendant.withObject("attrs").put("notText", 0.1);
        children(future).add(descendant);
        ObjectNode original = document(future);
        assertSemanticEquality(read(original).toJson(), original);
        assertThat(read(original).hasUnsupportedContent()).isTrue();
        future.put("type", "paragraph").put("version", 2);
        assertSemanticEquality(read(document(future)).toJson(), document(future));
    }

    @Test
    void futureDocumentVersionIsPreservedAsAnOpaqueWholeDocument() {
        ObjectNode original = document(node(2, "text"));
        ((ObjectNode) original.path("root")).put("version", 2).put("future", true);
        assertSemanticEquality(read(original).toJson(), original);
        assertThat(read(original).hasUnsupportedContent()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"doc", "blockquote", "bullet_list", "ordered_list", "list_item", "link"})
    void rejectsEmptyRequiredContainers(String type) {
        ObjectNode empty = node(2, type);
        if (type.equals("doc")) {
            invalid(JSON.objectNode().put("formatVersion", 1).set("root", empty));
        } else {
            invalid(document(empty));
        }
    }

    @Test
    void rejectsWrongParentSlotsNestedDocumentsAndLinks() {
        invalid(document(text(3, "inline")));
        invalid(document(node(2, "list_item")));
        invalid(document(paragraph(2, paragraph(3))));
        invalid(document(node(2, "doc", paragraph(3))));
        invalid(document(node(2, "bullet_list", paragraph(3))));
        invalid(document(node(2, "bullet_list", node(3, "list_item", node(4, "divider")))));
        ObjectNode link = link(3, "https://example.test", text(4, "first"));
        children(link).add(link(5, "https://example.test", text(6, "second")));
        invalid(document(paragraph(2, link)));
        invalid(document(paragraph(2, node(3, "divider", node(4, "future")))));
        invalid(document(node(2, "divider", node(3, "future"))));
    }

    @Test
    void acceptsListsRubyHeadingsAndOrderedMarksWithoutNormalization() {
        ObjectNode heading = node(2, "heading", text(3, "Название"));
        heading.withObject("attrs").put("level", 6).put("lang", "ru-RU").put("dir", "rtl");
        ObjectNode ruby = node(6, "ruby");
        ruby.withObject("attrs").put("base", "漢字").put("reading", "かんじ");
        ObjectNode list = node(4, "ordered_list", node(5, "list_item", paragraph(7, ruby)));
        list.withObject("attrs").put("order", 3);
        ObjectNode marked = text(9, "محتوى");
        marked.withObject("attrs").putArray("marks").add("code").add("strong").add("em");
        ObjectNode original = document(heading, list, node(8, "blockquote", paragraph(10, marked)), node(11, "divider"));
        assertSemanticEquality(read(original).toJson(), original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.test", "javascript:alert(1)", "//example.test", "https://",
            "https://u:p@example.test", "https://@example.test", "https://example.test:65536", "https://example.test:0",
            "https://example.test/я", "https://пример.рф", "https://example.test/\n",
            "https://[fe80::1%25en0]/", "https://0177.0.0.1/", "https://2130706433/", "https://127.1/",
            "https://example.test:", "https://example.test.", "https://example..test", "https://a_b.test/",
            "https://example.test/%zz", "https://0x7f.0.0.1/", "https://1.2.3.256/", "https://[x]/",
            "https://example.test\\@evil.test", " https://example.test", "https://exa mple.test"})
    void rejectsUnsafeOrAmbiguousLinks(String url) {
        invalid(document(paragraph(2, link(3, url, text(4, "link")))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.test/path?q=%3Cscript%3E#anchor", "HTTPS://example.test", "https://[::1]:443/",
            "https://127.0.0.1/", "https://xn--e1afmkfd.xn--p1ai/path", "https://example.test/%D1%8F"})
    void acceptsHttpsSyntaxWithoutFetchingOrMakingAnAuthorizationClaim(String url) {
        assertThat(read(document(paragraph(2, link(3, url, text(4, "link"))))).nodeCount()).isEqualTo(4);
    }

    @Test
    void futureParagraphInAListRemainsOpaqueRatherThanBeingInterpretedAsSupported() {
        ObjectNode future = paragraph(4).put("version", 2);
        future.withObject("attrs").put("future", true);
        ObjectNode document = document(node(2, "bullet_list", node(3, "list_item", future)));
        assertThat(read(document).hasUnsupportedContent()).isTrue();
        assertSemanticEquality(read(document).toJson(), document);
    }

    @Test
    void rejectsInvalidAttrsMarksVersionsAndTypesWithoutLeakingData() {
        ObjectNode paragraph = paragraph(2);
        paragraph.withObject("attrs").put("onclick", "PRIVATE");
        invalid(document(paragraph));
        paragraph.withObject("attrs").removeAll();
        paragraph.withObject("attrs").put("dir", "wrong");
        invalid(document(paragraph));
        paragraph.withObject("attrs").removeAll();
        paragraph.withObject("attrs").putNull("lang");
        invalid(document(paragraph));
        paragraph.withObject("attrs").put("lang", "bad_tag");
        invalid(document(paragraph));
        paragraph.withObject("attrs").put("lang", "");
        invalid(document(paragraph));
        ObjectNode text = text(3, "PRIVATE");
        text.withObject("attrs").putArray("marks").add("strong").add("strong");
        invalid(document(paragraph(2, text)));
        text.withObject("attrs").putArray("marks").add("unknown");
        invalid(document(paragraph(2, text)));
        text.withObject("attrs").put("marks", true);
        invalid(document(paragraph(2, text)));
        invalid(document(paragraph(2, text(3, ""))));
        invalid(document(paragraph(2, node(3, "ruby"))));
        invalid(document(node(2, "heading")));
        for (String version : new String[]{"0", "-1", "1.5", "2147483648", "null", "true"}) {
            String value = document(paragraph(2)).toString().replace("\"version\":1", "\"version\":" + version);
            invalidBytes(value.getBytes(StandardCharsets.UTF_8));
        }
        invalid(document(node(2, "Bad-Type")));
        ObjectNode extra = paragraph(2);
        extra.put("privateExtra", true);
        invalid(document(extra));
        ObjectNode envelope = document(paragraph(2));
        envelope.put("editorState", "PRIVATE");
        invalid(envelope);
        invalid(document(paragraph(2)).put("formatVersion", 2));
    }

    @Test
    void rejectsDuplicateIdsIncludingCaseVariantsAndOpaqueDescendants() {
        ObjectNode first = paragraph(2);
        first.put("id", "abcdefab-abcd-4abc-abcd-abcdefabcdef");
        ObjectNode second = first.deepCopy();
        second.put("id", first.path("id").textValue().toUpperCase(Locale.ROOT));
        invalid(document(first, second));
        invalid(document(node(2, "future", paragraph(2))));
        for (String id : new String[]{"1-1-4-8-1", "00000000-0000-0000-0000-000000000000",
                "00000000-0000-7000-8000-000000000002", "00000000-0000-4000-7000-000000000002"}) {
            invalid(document(paragraph(2).put("id", id)));
        }
    }

    @Test
    void enforcesLargeScalarBoundaryWithoutConfusingPhysicalBlockLimits() {
        String exact = "я".repeat(NativeDocumentReader.MAX_SCALAR_BYTES / 2);
        assertThat(read(document(paragraph(2, text(3, exact)))).nodeCount()).isEqualTo(3);
        invalid(document(paragraph(2, text(3, exact + "a"))));
        ObjectNode future = node(2, "future");
        future.withObject("attrs").put("nested", exact + "a");
        invalid(document(future));
        future.withObject("attrs").removeAll();
        future.put("a".repeat(NativeDocumentReader.MAX_SCALAR_BYTES + 1), true);
        invalid(document(future));
    }

    @Test
    void nativeDepthIncludesOpaqueDescendantsAndHasAnExactBoundary() {
        ObjectNode tree = node(32, "future");
        for (int level = 31; level >= 2; level--) {
            tree = node(level, "future", tree);
        }
        assertThat(read(document(tree)).nodeCount()).isEqualTo(32);
        invalid(document(node(33, "future", tree)));
    }

    @Test
    void nativeNodeCountHasAnExactBoundaryIncludingOpaqueNodes() {
        ObjectNode document = document();
        ArrayNode blocks = children((ObjectNode) document.path("root"));
        for (int id = 2; id <= NativeDocumentReader.MAX_NODES; id++) {
            blocks.add(node(id, "x"));
        }
        assertThat(read(document).nodeCount()).isEqualTo(NativeDocumentReader.MAX_NODES);
        blocks.add(node(NativeDocumentReader.MAX_NODES + 1, "x"));
        assertThat(document.toString().getBytes(StandardCharsets.UTF_8).length).isLessThan(NativeDocumentReader.MAX_BYTES);
        invalid(document);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ru", "RU-ru", "zh-Hant-TW", "en-001", "sl-rozaj", "de-CH-1901", "sh"})
    void preservesLanguageProfileOnInlineSpansWithoutLocaleCanonicalization(String lang) {
        ObjectNode text = text(3, "я العربية 日本語");
        text.withObject("attrs").put("lang", lang).put("dir", "auto");
        ObjectNode document = document(paragraph(2, text));
        assertSemanticEquality(read(document).toJson(), document);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abcd", "x-private", "i-klingon", "en-GB-oed", "en-u-ca-gregory", "en_US",
            "ru-", "sl-rozaj-ROZAJ", "рус", "en-1234-1234"})
    void rejectsLanguageOutsideTheSharedCoreProfile(String lang) {
        ObjectNode paragraph = paragraph(2);
        paragraph.withObject("attrs").put("lang", lang);
        invalid(document(paragraph));
    }

    @Test
    void nearLimitFixtureIsNotAccidentallyRejectedByParserTokenBudget() {
        ObjectNode document = document();
        for (int index = 0; index < 3500; index++) {
            children((ObjectNode) document.path("root"))
                    .add(paragraph(2 + index * 2, text(3 + index * 2, "Проверяемая строка.")));
        }
        assertThat(read(document).nodeCount()).isEqualTo(7001);
    }

    @Test
    void rejectsIngressAmbiguityAndCanonicalOutputExpansion() {
        invalidBytes("{\"formatVersion\":1,\"formatVersion\":1}".getBytes(StandardCharsets.UTF_8));
        invalidBytes(new byte[NativeDocumentReader.MAX_BYTES + 1]);
        ObjectNode future = node(2, "future");
        future.withObject("attrs").put("data", "");
        String base = document(future).toString();
        String expanded = base.replace("\"data\":\"\"", "\"data\":[" + "1e-128,".repeat(9000) + "0]");
        assertThat(expanded.length()).isLessThan(NativeDocumentReader.MAX_BYTES);
        invalidBytes(expanded.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void sharedLexicalCorpusBindsServerAndFutureEditorValidation() throws Exception {
        JsonNode vectors = new ContentJsonReader(16_384, 16, 5000)
                .read(Files.readAllBytes(contractRoot().resolve("lexical-vectors.json")));
        for (String field : new String[]{"lang", "href"}) {
            for (String outcome : new String[]{"accept", "reject"}) {
                for (JsonNode vector : vectors.path(field).path(outcome)) {
                    ObjectNode paragraph = paragraph(2);
                    if (field.equals("lang")) {
                        paragraph.withObject("attrs").put("lang", vector.textValue());
                    } else {
                        children(paragraph).add(link(3, vector.textValue(), text(4, "link")));
                    }
                    if (outcome.equals("accept")) {
                        assertSemanticEquality(read(document(paragraph)).toJson(), document(paragraph));
                    } else {
                        invalid(document(paragraph));
                    }
                }
            }
        }
    }

    private static Path contractRoot() {
        Path root = Path.of(System.getProperty("user.dir"));
        for (int level = 0; level < 4 && !Files.isDirectory(root.resolve("contracts")); level++) {
            root = root.getParent();
        }
        return root.resolve("contracts/content/native-v1");
    }

    private NativeDocument read(JsonNode document) {
        return reader.read(document.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void assertSemanticEquality(JsonNode actual, JsonNode expected) {
        CanonicalJsonHasher hasher = new CanonicalJsonHasher();
        assertThat(hasher.canonicalBytes(actual)).isEqualTo(hasher.canonicalBytes(expected));
    }

    private void invalid(JsonNode document) {
        invalidBytes(document.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void invalidBytes(byte[] bytes) {
        assertThatThrownBy(() -> reader.read(bytes)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid native document").hasNoCause();
    }

    private static ObjectNode document(ObjectNode... blocks) {
        return JSON.objectNode().put("formatVersion", 1).set("root", node(1, "doc", blocks));
    }

    private static ObjectNode paragraph(int id, ObjectNode... content) {
        return node(id, "paragraph", content);
    }

    private static ObjectNode text(int id, String value) {
        ObjectNode node = node(id, "text");
        node.withObject("attrs").put("text", value);
        return node;
    }

    private static ObjectNode link(int id, String href, ObjectNode... content) {
        ObjectNode node = node(id, "link", content);
        node.withObject("attrs").put("href", href);
        return node;
    }

    private static ObjectNode node(int id, String type, ObjectNode... content) {
        ObjectNode node = JSON.objectNode().put("id", "00000000-0000-4000-8000-%012x".formatted(id))
                .put("type", type).put("version", 1);
        node.putObject("attrs");
        ArrayNode children = node.putArray("content");
        for (ObjectNode child : content) {
            children.add(child);
        }
        return node;
    }

    private static ArrayNode children(ObjectNode node) {
        return (ArrayNode) node.path("content");
    }
}
