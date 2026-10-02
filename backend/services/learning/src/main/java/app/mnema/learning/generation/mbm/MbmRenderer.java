package app.mnema.learning.generation.mbm;

import app.mnema.learning.catalog.content.NativeDocument;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Renders native-v1 blocks as MBM: the edit context of the AI layer, or an outline. Every top-level block gets a
 * handle {@code [[bN]]}; compiling the (possibly rewritten) text with {@link MbmRendering#handles()} keeps the node ID
 * of every block whose type is unchanged.
 *
 * <p>Rendering is lossless, with three documented normalizations, or refused: each block is rendered, compiled again
 * and compared with the original ({@link NativeShape}). The normalizations are the ones MBM itself applies: marks are
 * a set (their order is not kept), whitespace at the edges of a block's inline content and at the edges of marked text
 * moves or disappears, and table cells are trimmed. Anything else that does not survive the round trip makes the whole
 * rendering throw {@link MbmUnsupportedContentException}: a node MBM has no syntax for, any attribute it cannot carry
 * (including {@code lang} and {@code dir} on a paragraph, a text node or a link, or attributes of the root), a node
 * with a version other than 1 or with extension fields, a line break inside text, bold italic text whose asterisks
 * would merge, emphasis that touches a word, a heading of level 4 to 6, a media node without the text its directive
 * needs, and so on. An edit therefore never silently changes what the user wrote.
 *
 * <p>Media nodes are rendered as directives with a new slot key: an image as {@code mode="search"} with its alt text
 * as query, an audio with its transcript as the spoken text, a video with its transcript or title as prompt. Compiling
 * such a directive allocates a fresh asset; whether the existing asset is reused is a rule of the media tasks, not of
 * this class.
 */
public final class MbmRenderer {

    /**
     * @param handles whether top-level blocks get {@code [[bN]]} handles
     * @param firstHandle number of the first handle ({@code b1} by default)
     * @param reservedSlotKeys slot keys of the artifact that generated keys must avoid
     */
    public record Options(boolean handles, int firstHandle, Set<String> reservedSlotKeys) {

        public Options {
            if (firstHandle < 0) {
                throw new IllegalArgumentException("firstHandle must not be negative");
            }
            reservedSlotKeys = Set.copyOf(reservedSlotKeys);
        }

        public static Options withHandles() {
            return new Options(true, 1, Set.of());
        }
    }

    private static final MbmCompiler VERIFIER = new MbmCompiler();
    private static final String VERIFY_HANDLE = "b1";

    /** Renders the top-level blocks of a validated document. */
    public MbmRendering render(NativeDocument document, Options options) {
        JsonNode root = document.toJson().path("root");
        if (!root.path("attrs").isEmpty() || !NativeShape.isPlainV1(root, false)) {
            // MBM has no syntax for attributes of the root; they would be dropped when the range is spliced back
            throw new MbmUnsupportedContentException(List.of(
                    new MbmUnsupportedContentException.Block(root.path("id").stringValue(""), "doc")));
        }
        List<JsonNode> blocks = new ArrayList<>();
        root.path("content").forEach(blocks::add);
        return renderBlocks(blocks, options);
    }

    /** Renders a range: the top-level blocks of the target of an edit, in order. */
    public MbmRendering renderBlocks(List<JsonNode> blocks, Options options) {
        var texts = new ArrayList<String>();
        var handles = new LinkedHashMap<String, MbmOptions.Handle>();
        var links = new LinkedHashSet<String>();
        var slotKeys = new ArrayList<String>();
        var unsupported = new ArrayList<MbmUnsupportedContentException.Block>();
        var keys = new SlotKeys(options.reservedSlotKeys());
        int number = options.firstHandle();
        List<String> kinds = new ArrayList<>();
        for (JsonNode block : blocks) {
            String type = block.path("type").stringValue("");
            try {
                var blockLinks = new LinkedHashSet<String>();
                var blockSlots = new ArrayList<String>();
                if (!NativeShape.isPlainV1(block, true)) {
                    throw new UnrenderableException();
                }
                String body = new BlockWriter(keys, blockLinks, blockSlots).block(block);
                verify(block, body, blockLinks);
                String handle = "b" + number++;
                texts.add(options.handles() ? withHandle(handle, body) : body);
                kinds.add(type);
                if (options.handles()) {
                    handles.put(handle, new MbmOptions.Handle(UUID.fromString(block.path("id").stringValue()), type));
                }
                links.addAll(blockLinks);
                slotKeys.addAll(blockSlots);
            } catch (UnrenderableException exception) {
                unsupported.add(new MbmUnsupportedContentException.Block(block.path("id").stringValue(""), type));
            }
        }
        if (!unsupported.isEmpty()) {
            throw new MbmUnsupportedContentException(unsupported);
        }
        var text = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            if (i > 0) {
                boolean sameList = !options.handles() && isList(kinds.get(i)) && kinds.get(i).equals(kinds.get(i - 1));
                text.append(sameList ? "\n\n\n" : "\n\n");
            }
            text.append(texts.get(i));
        }
        return new MbmRendering(text.toString(), handles, links, slotKeys);
    }

    private static boolean isList(String type) {
        return type.equals("bullet_list") || type.equals("ordered_list");
    }

    private static String withHandle(String handle, String body) {
        return body.isEmpty() ? "[[" + handle + "]]" : "[[" + handle + "]] " + body;
    }

    /** Compiles the rendered block with its handle and requires the same block (and node ID) back. */
    private static void verify(JsonNode block, String body, Set<String> links) {
        UUID id = UUID.fromString(block.path("id").stringValue());
        var options = MbmOptions.edit(Map.of(VERIFY_HANDLE, new MbmOptions.Handle(id, block.path("type").stringValue())))
                .withAllowedLinks(List.copyOf(links))
                .withCapabilities(new MbmOptions.Capabilities(true, true))
                .withExistingSlotKeys(Set.of());
        MbmResult result = VERIFIER.compile(withHandle(VERIFY_HANDLE, body), options, new RandomIdAllocator());
        if (!(result instanceof MbmResult.Success success)
                || !success.warnings().isEmpty()
                || success.document().path("root").path("content").size() != 1) {
            throw new UnrenderableException();
        }
        JsonNode compiled = success.document().path("root").path("content").get(0);
        if (!id.toString().equals(compiled.path("id").stringValue()) || !NativeShape.same(block, compiled)) {
            throw new UnrenderableException();
        }
    }

    /** Internal: the block cannot be written as MBM. */
    private static final class UnrenderableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnrenderableException() {
            super(null, null, false, false);
        }
    }

    /** Generated slot keys: {@code a1, a2, ...}, {@code i1, ...}, {@code v1, ...}, never a reserved or used one. */
    private static final class SlotKeys {
        private final Set<String> taken;
        private final Map<String, Integer> counters = new java.util.HashMap<>();

        SlotKeys(Set<String> reserved) {
            taken = new HashSet<>(reserved);
        }

        String next(String prefix) {
            while (true) {
                String key = prefix + counters.merge(prefix, 1, Integer::sum);
                if (taken.add(key)) {
                    return key;
                }
            }
        }
    }

    // ------------------------------------------------------------------ writer

    private static final class BlockWriter {
        private final SlotKeys keys;
        private final Set<String> links;
        private final List<String> slotKeys;

        BlockWriter(SlotKeys keys, Set<String> links, List<String> slotKeys) {
            this.keys = keys;
            this.links = links;
            this.slotKeys = slotKeys;
        }

        String block(JsonNode node) {
            JsonNode attrs = node.path("attrs");
            return switch (node.path("type").stringValue("")) {
                case "heading" -> "#".repeat(Math.max(1, attrs.path("level").intValue(1))) + " " + inline(node);
                case "paragraph" -> lineStart(inline(node));
                case "bullet_list" -> list(node, false);
                case "ordered_list" -> list(node, true);
                case "blockquote" -> quote(node);
                case "divider" -> "---";
                case "table" -> table(node);
                case "mermaid" -> mermaid(node);
                case "audio" -> audio(node);
                case "image" -> image(node);
                case "video" -> video(node);
                default -> throw new UnrenderableException();
            };
        }

        private String list(JsonNode node, boolean ordered) {
            var out = new StringBuilder();
            int number = node.path("attrs").path("order").intValue(1);
            for (JsonNode item : node.path("content")) {
                if (!out.isEmpty()) {
                    out.append('\n');
                }
                JsonNode paragraph = item.path("content").path(0);
                out.append(ordered ? (number++) + ". " : "- ").append(inline(paragraph).replace("\n", " "));
            }
            return out.toString();
        }

        private String quote(JsonNode node) {
            var out = new StringBuilder();
            for (JsonNode paragraph : node.path("content")) {
                if (!out.isEmpty()) {
                    out.append("\n>\n");
                }
                out.append("> ").append(inline(paragraph));
            }
            return out.toString();
        }

        private String table(JsonNode node) {
            JsonNode attrs = node.path("attrs");
            var out = new StringBuilder("::table{caption=").append(quoted(attrs.path("caption").stringValue(""))).append("}\n");
            List<String> header = new ArrayList<>();
            attrs.path("columns").forEach(column -> header.add(column.stringValue("")));
            out.append(row(header)).append('\n').append("|").append("---|".repeat(header.size()));
            for (JsonNode row : attrs.path("rows")) {
                List<String> cells = new ArrayList<>();
                row.forEach(cell -> cells.add(cell.stringValue("")));
                out.append('\n').append(row(cells));
            }
            return out.toString();
        }

        private static String row(List<String> cells) {
            var out = new StringBuilder("|");
            for (String cell : cells) {
                out.append(' ').append(cell.replace("\\", "\\\\").replace("|", "\\|")).append(" |");
            }
            return out.toString();
        }

        private String mermaid(JsonNode node) {
            JsonNode attrs = node.path("attrs");
            String source = attrs.path("source").stringValue("");
            int ticks = 3;
            for (String line : source.split("\n", -1)) {
                int run = 0;
                while (run < line.length() && line.charAt(run) == '`') {
                    run++;
                }
                ticks = Math.max(ticks, run + 1);
            }
            String fence = "`".repeat(ticks);
            return "::mermaid{title=" + quoted(attrs.path("title").stringValue("")) + " description="
                    + quoted(attrs.path("description").stringValue("")) + "}\n" + fence + "mermaid\n" + source + "\n" + fence;
        }

        private String audio(JsonNode node) {
            JsonNode attrs = node.path("attrs");
            String key = slot("a");
            return "::audio{slot=" + quoted(key) + " lang=" + quoted(attrs.path("lang").stringValue(""))
                    + " title=" + quoted(attrs.path("title").stringValue("")) + "} " + attrs.path("transcript").stringValue("");
        }

        private String image(JsonNode node) {
            String alt = node.path("attrs").path("alt").stringValue("");
            String key = slot("i");
            return "::image{slot=" + quoted(key) + " mode=\"search\" alt=" + quoted(alt) + "} "
                    + truncate(alt.replace('\n', ' '), BlockParser.MAX_PROMPT);
        }

        private String video(JsonNode node) {
            JsonNode attrs = node.path("attrs");
            String title = attrs.path("title").stringValue("");
            String prompt = attrs.has("transcript") ? attrs.path("transcript").stringValue("") : title;
            return "::video{slot=" + quoted(slot("v")) + " title=" + quoted(title) + "} "
                    + truncate(prompt.replace('\n', ' '), BlockParser.MAX_PROMPT);
        }

        private String slot(String prefix) {
            String key = keys.next(prefix);
            slotKeys.add(key);
            return key;
        }

        private static String truncate(String value, int max) {
            return value.length() <= max ? value : value.substring(0, max);
        }

        private static String quoted(String value) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }

        // -------------------------------------------------------------- inline

        private String inline(JsonNode parent) {
            var out = new StringBuilder();
            for (JsonNode child : parent.path("content")) {
                JsonNode attrs = child.path("attrs");
                switch (child.path("type").stringValue("")) {
                    case "text" -> out.append(text(attrs));
                    case "ruby" -> out.append('{').append(attrs.path("base").stringValue("")).append('|')
                            .append(attrs.path("reading").stringValue("")).append('}');
                    case "link" -> {
                        String href = attrs.path("href").stringValue("");
                        links.add(href);
                        out.append('[').append(inline(child)).append("](").append(href).append(')');
                    }
                    default -> throw new UnrenderableException();
                }
            }
            return out.toString();
        }

        private static String text(JsonNode attrs) {
            String text = attrs.path("text").stringValue("");
            if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
                // MBM has no hard break and joins lines with a space: a line break in text cannot round trip
                throw new UnrenderableException();
            }
            Set<String> marks = new HashSet<>();
            attrs.path("marks").forEach(mark -> marks.add(mark.stringValue("")));
            if (marks.contains("code")) {
                return decorate(code(text), marks);
            }
            String core = text.strip();
            if (core.isEmpty() || (!marks.contains("strong") && !marks.contains("em"))) {
                return escape(text);
            }
            int start = text.indexOf(core);
            return escape(text.substring(0, start)) + decorate(escape(core), marks)
                    + escape(text.substring(start + core.length()));
        }

        private static String decorate(String body, Set<String> marks) {
            String open = (marks.contains("strong") ? "**" : "") + (marks.contains("em") ? "*" : "");
            return open + body + new StringBuilder(open).reverse();
        }

        /** A code span whose delimiter is the shortest backtick run not occurring in the content. */
        private static String code(String text) {
            var runs = new HashSet<Integer>();
            int run = 0;
            for (int i = 0; i <= text.length(); i++) {
                if (i < text.length() && text.charAt(i) == '`') {
                    run++;
                } else {
                    if (run > 0) {
                        runs.add(run);
                    }
                    run = 0;
                }
            }
            int length = 1;
            while (runs.contains(length)) {
                length++;
            }
            String ticks = "`".repeat(length);
            return ticks + text + ticks;
        }

        private static String escape(String text) {
            var out = new StringBuilder(text.length() + 8);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\\' || c == '*' || c == '`' || c == '[' || c == ']' || c == '{') {
                    out.append('\\');
                }
                out.append(c);
            }
            return out.toString();
        }

        /** Escapes the first characters of a paragraph that would otherwise start another block. */
        private static String lineStart(String text) {
            String line = text.stripLeading();
            if (line.isEmpty()) {
                return line;
            }
            char first = line.charAt(0);
            if (first == '#' || first == '>' || first == '-' || (first == ':' && line.startsWith("::"))) {
                return "\\" + line;
            }
            int digits = 0;
            while (digits < line.length() && Character.isDigit(line.charAt(digits))) {
                digits++;
            }
            if (digits > 0 && line.startsWith(". ", digits)) {
                return line.substring(0, digits) + "\\" + line.substring(digits);
            }
            return line;
        }
    }
}
