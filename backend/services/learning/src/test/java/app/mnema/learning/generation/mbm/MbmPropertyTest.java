package app.mnema.learning.generation.mbm;

import app.mnema.learning.catalog.content.NativeDocument;
import app.mnema.learning.catalog.content.NativeDocumentReader;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-style tests with a seeded {@link Random} (no extra dependency): whatever the input, the compiler returns a
 * result of the error contract, never throws, and every success is a valid native document whose slots, positions and
 * renderings are consistent.
 */
class MbmPropertyTest {

    private static final MbmCompiler COMPILER = new MbmCompiler();
    private static final NativeDocumentReader READER = new NativeDocumentReader();
    private static final MbmRenderer RENDERER = new MbmRenderer();
    private static final String[] TOKENS = {
            "# ", "## ", "### ", "#### ", "- ", "1. ", "7. ", "> ", ">", "---", "\n", "\n", "\n\n", "\n\n\n", " ", "  ", "\t",
            "::table{caption=\"c\"}\n| a | b |\n|---|---|\n| 1 | 2 |\n", "::table{caption=\"c\"}\n| a |\n", "::mermaid{title=\"t\" description=\"d\"}\n```mermaid\ng\n```\n",
            "::audio{slot=\"a1\" lang=\"ja\" title=\"t\"} 行く", "::audio{slot=\"a2\" lang=\"ru\" voice=\"male\" title=\"t\"} текст",
            "::image{slot=\"i1\" mode=\"search\" alt=\"a\"} cat", "::image{slot=\"i2\" mode=\"generate\" alt=\"a\"} cat",
            "::video{slot=\"v1\" title=\"t\"} film", "::sources\n[1] https://a.example/\n", "::chart{}", "::", "{", "}", "{a|b}", "{漢字|かんじ}",
            "**", "*", "***", "`", "``", "\\", "\\*", "[", "]", "](https://a.example/)", "](https://b.example/)", "[x](https://a.example/)", "(", ")",
            "|", "[[b1]] ", "[[b2]] ", "[[b9]] ", "[[b1]]", "```", "```mermaid\n", "x", "word", "Привет", "行く", "𝔘", "\u0000", "\uD800", " ", "😀",
            "slot=\"a\"", "\"", "=", ":", "::audio{", "title=\"t\"", "https://a.example/"};

    private static final String MARKUP = "#-*`[]{}()|>:\\\n\r \"=";

    private static String randomSource(Random random) {
        int count = random.nextInt(60);
        var source = new StringBuilder();
        for (int i = 0; i < count; i++) {
            source.append(TOKENS[random.nextInt(TOKENS.length)]);
        }
        return source.toString();
    }

    private static MbmOptions randomOptions(Random random) {
        MbmOptions base = random.nextBoolean() ? MbmOptions.create() : MbmOptions.edit(Map.of(
                "b1", new MbmOptions.Handle(UUID.fromString("7d000000-0000-4000-8000-000000000001"), "paragraph"),
                "b2", new MbmOptions.Handle(UUID.fromString("7d000000-0000-4000-8000-000000000002"), "heading")));
        return base.withAllowedLinks(List.of("https://a.example/", "http://insecure.example/"))
                .withCapabilities(new MbmOptions.Capabilities(random.nextBoolean(), random.nextBoolean()))
                .withResearch(List.of(new MbmOptions.ResearchSource(1, "https://a.example/", "A")))
                .withMaxMedia(random.nextInt(10)).withExistingMediaCount(random.nextInt(3));
    }

    @Test
    void anyInputWithinTheLimitsYieldsAResultOfTheErrorContractAndNeverThrows() {
        var random = new Random(283L);
        int successes = 0;
        int failures = 0;
        for (int i = 0; i < 4_000; i++) {
            String source = randomSource(random);
            MbmOptions options = randomOptions(random);
            MbmResult result = COMPILER.compile(source, options, new MbmFixtures.SequentialIds());
            int lines = source.replace("\r\n", "\n").split("\n", -1).length;
            if (result instanceof MbmResult.Failure failure) {
                failures++;
                assertErrors(failure.errors(), lines, source);
            } else if (result instanceof MbmResult.Success success) {
                successes++;
                assertSuccess(success, lines, source, options);
            }
        }
        assertThat(successes).isGreaterThan(200);
        assertThat(failures).isGreaterThan(200);
    }

    private static void assertErrors(List<MbmFinding> errors, int lines, String source) {
        assertThat(errors).as(source).isNotEmpty().hasSizeLessThanOrEqualTo(20);
        int previousLine = 0;
        int previousColumn = 0;
        for (MbmFinding error : errors) {
            assertThat(error.severity()).as(source).isEqualTo(MbmCode.Severity.ERROR);
            assertThat(error.line()).as(source).isBetween(1, lines);
            int column = error.column() == null ? 0 : error.column();
            assertThat(error.line() > previousLine || (error.line() == previousLine && column >= previousColumn))
                    .as(source + " order").isTrue();
            previousLine = error.line();
            previousColumn = column;
        }
        assertThat(MbmRepairList.format(errors).lines().count()).isBetween(1L, 20L);
    }

    private static void assertSuccess(MbmResult.Success success, int lines, String source, MbmOptions options) {
        NativeDocument validated = READER.read(MbmFixtures.JSON.writeValueAsBytes(success.document()));
        assertThat(validated.nodeCount()).as(source).isEqualTo(success.nodeCount()).isLessThanOrEqualTo(NativeDocumentReader.MAX_NODES);
        for (MbmFinding warning : success.warnings()) {
            assertThat(warning.severity()).isEqualTo(MbmCode.Severity.WARNING);
            assertThat(warning.line()).as(source).isBetween(0, lines);
        }
        Map<String, JsonNode> nodes = new LinkedHashMap<>();
        index(success.document().path("root"), nodes);
        Set<String> slotKeys = new HashSet<>();
        for (MbmSlot slot : success.slots()) {
            assertThat(slotKeys.add(slot.slotKey())).as(source).isTrue();
            JsonNode node = nodes.get(slot.nodeId().toString());
            assertThat(node).as(source).isNotNull();
            assertThat(node.path("type").stringValue()).isEqualTo(slot.kind().name().toLowerCase(java.util.Locale.ROOT));
            assertThat(node.path("attrs").path("assetId").stringValue()).isEqualTo(slot.assetId().toString());
        }
        assertThat(success.slots().size()).isLessThanOrEqualTo(MbmOptions.MEDIA_CEILING);
        assertOnlyTrustedLinks(success.document(), options, source);
        assertRenderingRoundTrip(validated, success, source);
    }

    /** Security invariant: a link node exists only for an allowlist or research URL that the href profile accepted. */
    private static void assertOnlyTrustedLinks(JsonNode document, MbmOptions options, String source) {
        Set<String> trusted = new HashSet<>();
        options.allowedLinks().stream().filter(NativeProfile::acceptsHref).forEach(trusted::add);
        options.research().stream().map(MbmOptions.ResearchSource::url).filter(NativeProfile::acceptsHref).forEach(trusted::add);
        Map<String, JsonNode> nodes = new LinkedHashMap<>();
        index(document.path("root"), nodes);
        nodes.values().stream().filter(node -> node.path("type").stringValue("").equals("link"))
                .forEach(link -> assertThat(trusted).as(source).contains(link.path("attrs").path("href").stringValue()));
    }

    private static void index(JsonNode node, Map<String, JsonNode> out) {
        out.put(node.path("id").stringValue(), node);
        node.path("content").forEach(child -> index(child, out));
    }

    /** A compiled document either renders as MBM that recompiles to the same top-level IDs, or is refused. */
    private static void assertRenderingRoundTrip(NativeDocument document, MbmResult.Success success, String source) {
        MbmRendering rendering;
        try {
            rendering = RENDERER.render(document, MbmRenderer.Options.withHandles());
        } catch (MbmUnsupportedContentException refused) {
            assertThat(refused.blocks()).isNotEmpty();
            return;
        }
        MbmOptions options = new MbmOptions(MbmOptions.Mode.EDIT, List.copyOf(rendering.links()),
                new MbmOptions.Capabilities(true, true), List.of(), 8, 0, rendering.handles(), Set.of(), null);
        MbmResult again = COMPILER.compile(rendering.text(), options, new RandomIdAllocator());
        assertThat(again).as(source + " -> " + rendering.text()).isInstanceOf(MbmResult.Success.class);
        assertSameBlocks(success.document(), ((MbmResult.Success) again).document(), source);
    }

    /** The same top-level IDs in the same order, and the same content modulo IDs ({@link NativeShape}). */
    private static void assertSameBlocks(JsonNode before, JsonNode after, String source) {
        JsonNode left = before.path("root").path("content");
        JsonNode right = after.path("root").path("content");
        assertThat(right.size()).as(source).isEqualTo(left.size());
        for (int i = 0; i < left.size(); i++) {
            assertThat(right.get(i).path("id")).as(source).isEqualTo(left.get(i).path("id"));
            assertThat(NativeShape.same(left.get(i), right.get(i))).as(source + " block " + i).isTrue();
        }
    }

    @Test
    void arbitraryCharactersNeverEscapeTheContract() {
        var random = new Random(77L);
        for (int i = 0; i < 1_500; i++) {
            var source = new StringBuilder();
            int length = random.nextInt(300);
            for (int j = 0; j < length; j++) {
                source.append(switch (random.nextInt(4)) {
                    case 0 -> (char) random.nextInt(0x80);
                    case 1 -> (char) random.nextInt(0x10000);
                    case 2 -> MARKUP.charAt(random.nextInt(MARKUP.length()));
                    default -> (char) (0x4E00 + random.nextInt(200));
                });
            }
            MbmResult result = COMPILER.compile(source.toString(), randomOptions(random), new MbmFixtures.SequentialIds());
            assertThat(result).isInstanceOfAny(MbmResult.Success.class, MbmResult.Failure.class);
        }
    }

    @Test
    void lexicalProfilesFollowTheSharedNativeVectorsAndRandomValuesNeverEscapeTheContract() throws java.io.IOException {
        JsonNode vectors = MbmFixtures.JSON.readTree(java.nio.file.Files.readString(
                MbmFixtures.MBM.resolve("../../content/native-v1/lexical-vectors.json")));
        for (JsonNode lang : vectors.path("lang").path("accept")) {
            assertThat(compileAudio(lang.stringValue())).as(lang.stringValue()).isInstanceOf(MbmResult.Success.class);
        }
        for (JsonNode lang : vectors.path("lang").path("reject")) {
            MbmResult result = compileAudio(lang.stringValue());
            assertThat(result).as(lang.stringValue()).isInstanceOf(MbmResult.Failure.class);
            assertThat(((MbmResult.Failure) result).errors()).extracting(MbmFinding::attribute).containsExactly("lang");
        }
        for (JsonNode href : vectors.path("href").path("accept")) {
            assertThat(linkResult(href.stringValue()).document().toString()).as(href.stringValue()).contains("\"type\":\"link\"");
        }
        for (JsonNode href : vectors.path("href").path("reject")) {
            MbmResult.Success result = linkResult(href.stringValue());
            assertThat(result.document().toString()).as(href.stringValue()).doesNotContain("\"type\":\"link\"");
            assertThat(result.warnings()).extracting(MbmFinding::code).contains(MbmCode.MBM_LINK_REJECTED_BY_PROFILE);
        }

        var random = new Random(5L);
        String alphabet = "abcXYZ019-_.:/?#%@ \"\\\u0000é中{}[]";
        for (int i = 0; i < 1_000; i++) {
            var value = new StringBuilder();
            for (int j = random.nextInt(80); j > 0; j--) {
                value.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            assertThat(compileAudio(value.toString())).isInstanceOfAny(MbmResult.Success.class, MbmResult.Failure.class);
            String link = "https://" + value;
            MbmResult result = COMPILER.compile("[l](" + link.replace(" ", "") + ")", MbmOptions.create().withAllowedLinks(List.of(link)),
                    new MbmFixtures.SequentialIds());
            if (result instanceof MbmResult.Success success) {
                assertOnlyTrustedLinks(success.document(), MbmOptions.create().withAllowedLinks(List.of(link)), link);
            }
        }
    }

    private static MbmResult compileAudio(String lang) {
        return COMPILER.compile("::audio{slot=\"a1\" lang=\"" + lang.replace("\\", "\\\\").replace("\"", "\\\"") + "\" title=\"t\"} x",
                MbmOptions.create(), new MbmFixtures.SequentialIds());
    }

    private static MbmResult.Success linkResult(String href) {
        MbmResult result = COMPILER.compile("[l](" + href.replaceAll("\\s", "") + ")", MbmOptions.create().withAllowedLinks(List.of(href)),
                new MbmFixtures.SequentialIds());
        return (MbmResult.Success) result;
    }

    // ------------------------------------------------------------------ independently built native trees

    private static final String[] WORDS = {"alpha", "x", " ", "  ", "*", "**", "[", "]", "{", "`", "\\", "#", "- ", "1. ", "> ", "::",
            "漢字", "a\u0085b", "a\u2028b", "é", "|", "(", ")", "\"", "---", "[[b1]]", "!"};

    private static final class NativeBuilder {
        private final Random random;
        private int counter;

        NativeBuilder(Random random) {
            this.random = random;
        }

        ObjectNode node(String type, ObjectNode attrs, JsonNode... children) {
            ObjectNode node = MbmFixtures.JSON.createObjectNode();
            node.put("id", String.format("5b000000-0000-4000-8000-%012x", ++counter));
            node.put("type", type);
            node.put("version", 1);
            if (random.nextInt(25) == 0 && !type.equals("doc") && !type.equals("list_item") && !type.equals("text")) {
                attrs.put("lang", "fr");
            }
            node.set("attrs", attrs);
            ArrayNode content = node.putArray("content");
            for (JsonNode child : children) {
                content.add(child);
            }
            return node;
        }

        ObjectNode attrs() {
            return MbmFixtures.JSON.createObjectNode();
        }

        String words(int max) {
            var out = new StringBuilder();
            for (int i = 1 + random.nextInt(max); i > 0; i--) {
                out.append(WORDS[random.nextInt(WORDS.length)]);
            }
            return out.isEmpty() ? "w" : out.toString();
        }

        /** Nonblank text for attributes native-v1 requires to be nonblank. */
        String label(int max) {
            return "n" + words(max);
        }

        List<JsonNode> inline() {
            var out = new ArrayList<JsonNode>();
            for (int i = random.nextInt(5); i > 0; i--) {
                switch (random.nextInt(6)) {
                    case 0 -> out.add(node("ruby", attrs().put("base", "漢字").put("reading", "かんじ")));
                    case 1 -> out.add(node("link", attrs().put("href", "https://a.example/x"), text()));
                    default -> out.add(text());
                }
            }
            return out;
        }

        ObjectNode text() {
            ObjectNode attrs = attrs().put("text", words(4));
            ArrayNode marks = attrs.putArray("marks");
            for (String mark : List.of("strong", "em", "code")) {
                if (random.nextInt(4) == 0) {
                    marks.add(mark);
                }
            }
            return node("text", attrs);
        }

        ObjectNode paragraph() {
            return node("paragraph", attrs(), inline().toArray(JsonNode[]::new));
        }

        ObjectNode block() {
            return switch (random.nextInt(12)) {
                case 0 -> node("heading", attrs().put("level", 1 + random.nextInt(random.nextInt(8) == 0 ? 6 : 3)), inline().toArray(JsonNode[]::new));
                case 1, 2 -> list(random.nextBoolean());
                case 3 -> node("blockquote", attrs(), paragraph(), paragraph());
                case 4 -> node("divider", attrs());
                case 5 -> node("table", attrs().put("caption", label(2)).<ObjectNode>set("columns", array("A", label(1)))
                        .<ObjectNode>set("rows", MbmFixtures.JSON.createArrayNode().add(array(" x ", ""))
                                .add(array(words(2), "|\\"))));
                case 6 -> node("mermaid", attrs().put("source", "graph\n" + words(2)).put("title", label(1)).put("description", label(1)));
                case 7 -> node("audio", attrs().put("assetId", UUID.randomUUID().toString()).put("title", label(1))
                        .put("transcript", label(3)).put("lang", "en"));
                case 8 -> node("image", attrs().put("assetId", UUID.randomUUID().toString()).put("alt", label(2)));
                case 9 -> node("video", attrs().put("assetId", UUID.randomUUID().toString()).put("title", label(2)));
                default -> paragraph();
            };
        }

        private ArrayNode array(String... values) {
            ArrayNode array = MbmFixtures.JSON.createArrayNode();
            for (String value : values) {
                array.add(value.isEmpty() ? "" : value);
            }
            return array;
        }

        ObjectNode list(boolean ordered) {
            ObjectNode attrs = attrs();
            if (ordered && random.nextBoolean()) {
                attrs.put("order", 2 + random.nextInt(8));
            }
            var items = new ArrayList<JsonNode>();
            for (int i = 1 + random.nextInt(3); i > 0; i--) {
                items.add(node("list_item", attrs(), paragraph()));
            }
            return node(ordered ? "ordered_list" : "bullet_list", attrs, items.toArray(JsonNode[]::new));
        }
    }

    /** Random valid native documents built directly (not by the compiler): render, compile again, equal or refused. */
    @Test
    void independentlyBuiltNativeTreesRoundTripModuloIdsOrAreRefused() {
        var random = new Random(2026L);
        int rendered = 0;
        int refused = 0;
        for (int i = 0; i < 1_500; i++) {
            var builder = new NativeBuilder(random);
            var blocks = new ArrayList<JsonNode>();
            for (int count = 1 + random.nextInt(5); count > 0; count--) {
                blocks.add(builder.block());
            }
            ObjectNode root = builder.node("doc", builder.attrs(), blocks.toArray(JsonNode[]::new));
            ObjectNode envelope = MbmFixtures.JSON.createObjectNode().put("formatVersion", 1);
            envelope.set("root", root);
            NativeDocument document = READER.read(MbmFixtures.JSON.writeValueAsBytes(envelope));
            MbmRendering rendering;
            try {
                rendering = RENDERER.render(document, MbmRenderer.Options.withHandles());
            } catch (MbmUnsupportedContentException exception) {
                refused++;
                continue;
            }
            rendered++;
            MbmOptions options = new MbmOptions(MbmOptions.Mode.EDIT, List.copyOf(rendering.links()),
                    new MbmOptions.Capabilities(true, true), List.of(), 8, 0, rendering.handles(), Set.of(), null);
            MbmResult again = COMPILER.compile(rendering.text(), options, new RandomIdAllocator());
            assertThat(again).as(rendering.text()).isInstanceOf(MbmResult.Success.class);
            assertThat(((MbmResult.Success) again).warnings()).as(rendering.text()).isEmpty();
            assertSameBlocks(envelope, ((MbmResult.Success) again).document(), rendering.text());
        }
        assertThat(rendered).isGreaterThan(150);
        assertThat(refused).isGreaterThan(150);
    }
}
