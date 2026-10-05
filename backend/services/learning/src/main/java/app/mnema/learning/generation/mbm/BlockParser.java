package app.mnema.learning.generation.mbm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Line-based block structure of MBM v1. One iterative pass over the normalized lines; every block consumes a known
 * number of lines, so the parser needs no recursion and its cost is linear in the source. Inline text goes to
 * {@link InlineParser}. All findings are collected; nothing here throws for bad input.
 *
 * <p>Fenced blocks are scanned in one place ({@link #fence}); a fence outside {@code ::mermaid} is a
 * {@code code_block}.
 */
final class BlockParser {

    static final int MAX_AUDIO_TEXT = 600;
    static final int MAX_PROMPT = 300;
    static final int MAX_TABLE_COLUMNS = 12;
    static final int MAX_TABLE_ROWS = 100;
    private static final int MAX_ALT = 4_096;
    private static final int MAX_TITLE = 1_024;
    private static final int MAX_DESCRIPTION = 8_192;
    private static final int MAX_MERMAID_SOURCE = 16_384;
    private static final int MAX_CODE_SOURCE = 16_384;
    private static final int MAX_CELL = 4_096;
    private static final int MAX_ORDER_DIGITS = 9;

    private static final Pattern HANDLE = Pattern.compile("\\[\\[([A-Za-z][0-9]+)]](?: (.*))?", Pattern.DOTALL);
    private static final Pattern FENCE_OPEN = Pattern.compile("(`{3,})[^`]*");
    /** The info string of a code fence: the native-v1 {@code code_block} language identifier. */
    private static final Pattern CODE_LANGUAGE = Pattern.compile("[a-z0-9][a-z0-9+#.-]{0,31}");
    private static final Pattern MERMAID_OPEN = Pattern.compile("(`{3,})[ \t]*mermaid[ \t]*");
    private static final Pattern SLOT_KEY = Pattern.compile("[a-z][a-z0-9_]{0,31}");
    private static final Pattern SOURCE_LINE = Pattern.compile("\\[([0-9]{1,9})] (\\S+)");

    private record Spec(Set<String> allowed, List<String> required) {
    }

    private static final Map<String, Spec> SPECS = Map.of(
            "table", new Spec(Set.of("caption"), List.of("caption")),
            "mermaid", new Spec(Set.of("title", "description"), List.of("title", "description")),
            "audio", new Spec(Set.of("slot", "lang", "title", "voice"), List.of("slot", "lang", "title")),
            "image", new Spec(Set.of("slot", "mode", "alt"), List.of("slot", "mode", "alt")),
            "video", new Spec(Set.of("slot", "title"), List.of("slot", "title")),
            "sources", new Spec(Set.of(), List.of()));

    private enum Kind { BLANK, HEADING, DIVIDER, QUOTE, BULLET, ORDERED, DIRECTIVE, FENCE, HANDLE, INDENTED_MARKER, TEXT }

    /** The blocks that parsed cleanly, how many blocks started, and which handles were used. */
    record Parsed(List<Block> blocks, int blockStarts, Set<String> usedHandles) {
    }

    private final String[] lines;
    private final MbmOptions options;
    private final Findings findings;
    private final InlineParser inline;
    private final Map<Integer, MbmOptions.ResearchSource> research;
    private final List<Block> blocks = new ArrayList<>();
    private final Set<String> usedHandles = new HashSet<>();
    private final Set<String> slotKeys = new HashSet<>();
    private int blockStarts;
    private int mediaCount;
    private boolean mediaOverflowReported;
    private int overflowLine;
    private int audioCount;
    private int imageSearchCount;

    BlockParser(String[] lines, MbmOptions options, Findings findings, InlineParser inline,
                Map<Integer, MbmOptions.ResearchSource> research) {
        this.lines = lines;
        this.options = options;
        this.findings = findings;
        this.inline = inline;
        this.research = research;
    }

    Parsed parse() {
        int i = 0;
        while (i < lines.length) {
            i = lines[i].isBlank() ? i + 1 : block(i);
        }
        return new Parsed(blocks, blockStarts, usedHandles);
    }

    // ------------------------------------------------------------------ dispatch

    private int block(int i) {
        String line = lines[i];
        String handle = null;
        if (line.startsWith("[[")) {
            Matcher matcher = HANDLE.matcher(line);
            if (matcher.matches()) {
                handle = matcher.group(1);
                line = matcher.group(2) == null ? "" : matcher.group(2);
                lines[i] = line;
            }
        }
        blockStarts++;
        int lineNumber = i + 1;
        if (line.isBlank()) {
            // A handle with nothing after it addresses an empty paragraph (the renderer writes one for it).
            blocks.add(new Block.Paragraph(lineNumber, resolveHandle(handle, "paragraph", lineNumber), List.of()));
            return i + 1;
        }
        Kind kind = classify(line);
        DirectiveHead head = kind == Kind.DIRECTIVE ? DirectiveHead.parse(line) : null;
        UUID kept = resolveHandle(handle, nativeType(kind, head), lineNumber);
        return switch (kind) {
            case HEADING -> heading(i, kept);
            case DIVIDER -> add(new Block.Divider(lineNumber, kept), i + 1);
            case QUOTE -> quote(i, kept);
            case BULLET, ORDERED -> list(i, kind, kept);
            case DIRECTIVE -> directive(i, head, kept);
            case FENCE -> codeBlock(i, kept);
            case INDENTED_MARKER -> nestedList(i);
            default -> paragraph(i, kept);
        };
    }

    private int add(Block block, int next) {
        blocks.add(block);
        return next;
    }

    /**
     * A fenced block outside {@code ::mermaid}. The info string is empty or one language identifier (a diagram needs
     * {@code ::mermaid}: its title and description are mandatory). The source is the lines between the fences joined
     * with LF; leading, trailing and inner whitespace is kept.
     */
    private int codeBlock(int i, UUID kept) {
        int line = i + 1;
        Matcher opening = FENCE_OPEN.matcher(lines[i]);
        String info = opening.matches() ? lines[i].substring(opening.group(1).length()).strip() : "";
        Fence fence = fence(i, opening);
        if (!fence.closed()) {
            findings.error(line, 1, MbmCode.MBM_UNTERMINATED_FENCE, null);
            return fence.end();
        }
        int errorsBefore = findings.errorCount();
        if (!info.isEmpty() && (!CODE_LANGUAGE.matcher(info).matches() || info.equals("mermaid"))) {
            findings.error(line, 1, MbmCode.MBM_CODE_LANGUAGE_INVALID, "lang");
        }
        String source = String.join("\n", java.util.Arrays.asList(lines).subList(fence.bodyStart(), fence.bodyEnd()));
        if (source.isBlank()) {
            findings.error(line, 1, MbmCode.MBM_EMPTY_CODE_BLOCK, null);
        } else if (source.length() > MAX_CODE_SOURCE) {
            findings.error(line, 1, MbmCode.MBM_VALUE_TOO_LONG, null);
        }
        if (findings.errorCount() == errorsBefore) {
            blocks.add(new Block.Code(line, kept, info, source));
        }
        return fence.end();
    }

    private int nestedList(int i) {
        findings.error(i + 1, 1, MbmCode.MBM_NESTED_LIST, null);
        return i + 1;
    }

    private static String nativeType(Kind kind, DirectiveHead head) {
        return switch (kind) {
            case HEADING -> "heading";
            case DIVIDER -> "divider";
            case QUOTE -> "blockquote";
            case FENCE -> "code_block";
            case BULLET -> "bullet_list";
            case ORDERED -> "ordered_list";
            case TEXT, HANDLE -> "paragraph";
            case DIRECTIVE -> switch (head.name()) {
                case "table", "mermaid", "audio", "image", "video" -> head.name();
                case "sources" -> "heading";
                default -> null;
            };
            default -> null;
        };
    }

    /**
     * Handle rules of an edit: a known handle keeps its node ID when the block type is unchanged; a changed type is a
     * warning and a new ID; unknown and repeated handles are errors. A handle that appeared counts as present even
     * when its block has other errors.
     */
    private UUID resolveHandle(String handle, String type, int line) {
        if (handle == null) {
            return null;
        }
        MbmOptions.Handle declared = options.mode() == MbmOptions.Mode.EDIT ? options.handles().get(handle) : null;
        if (declared == null) {
            findings.error(line, 1, MbmCode.MBM_UNKNOWN_HANDLE, null);
            return null;
        }
        if (!usedHandles.add(handle)) {
            findings.error(line, 1, MbmCode.MBM_DUPLICATE_HANDLE, null);
            return null;
        }
        if (type != null && !type.equals(declared.type())) {
            findings.warning(line, 1, MbmCode.MBM_HANDLE_TYPE_CHANGED);
            return null;
        }
        return declared.nodeId();
    }

    // ------------------------------------------------------------------ line classes

    private static Kind classify(String line) {
        if (line.isBlank()) {
            return Kind.BLANK;
        }
        if (line.startsWith("[[") && HANDLE.matcher(line).matches()) {
            return Kind.HANDLE;
        }
        char first = line.charAt(0);
        if (first == '#') {
            int hashes = 0;
            while (hashes < line.length() && line.charAt(hashes) == '#') {
                hashes++;
            }
            if (hashes < line.length() && line.charAt(hashes) == ' ') {
                return Kind.HEADING;
            }
        }
        if (line.stripTrailing().equals("---")) {
            return Kind.DIVIDER;
        }
        if (first == '>') {
            return Kind.QUOTE;
        }
        if (line.startsWith("- ")) {
            return Kind.BULLET;
        }
        if (orderedMarker(line) > 0) {
            return Kind.ORDERED;
        }
        if (line.startsWith("::")) {
            return Kind.DIRECTIVE;
        }
        if (FENCE_OPEN.matcher(line).matches()) {
            return Kind.FENCE;
        }
        if (first == ' ' || first == '\t') {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("- ") || orderedMarker(trimmed) > 0) {
                return Kind.INDENTED_MARKER;
            }
        }
        return Kind.TEXT;
    }

    /** Digits of an ordered marker ({@code 12. }), 1 to 9 of them, or 0 when the line has none. */
    private static int orderedMarker(String line) {
        int digits = 0;
        while (digits < line.length() && digits <= MAX_ORDER_DIGITS && line.charAt(digits) >= '0' && line.charAt(digits) <= '9') {
            digits++;
        }
        return digits >= 1 && digits <= MAX_ORDER_DIGITS && line.startsWith(". ", digits) ? digits : 0;
    }

    private static boolean startsBlock(Kind kind) {
        return switch (kind) {
            case HEADING, DIVIDER, QUOTE, BULLET, ORDERED, DIRECTIVE, FENCE, HANDLE -> true;
            default -> false;
        };
    }

    // ------------------------------------------------------------------ text blocks

    private int heading(int i, UUID kept) {
        String line = lines[i];
        int level = 0;
        while (line.charAt(level) == '#') {
            level++;
        }
        if (level > 3) {
            findings.error(i + 1, 1, MbmCode.MBM_HEADING_LEVEL, null);
        } else {
            blocks.add(new Block.Heading(i + 1, kept, level, inline.parse(line.substring(level + 1).strip(), i + 1)));
        }
        return i + 1;
    }

    private int paragraph(int i, UUID kept) {
        var parts = new ArrayList<String>();
        parts.add(lines[i].strip());
        int j = continuation(i + 1, parts);
        blocks.add(new Block.Paragraph(i + 1, kept, inline.parse(String.join(" ", parts), i + 1)));
        return j;
    }

    /** Appends the continuation lines starting at {@code j}; returns the first line that is not one. */
    private int continuation(int j, List<String> parts) {
        while (j < lines.length && !lines[j].isBlank()) {
            Kind kind = classify(lines[j]);
            if (startsBlock(kind)) {
                break;
            }
            if (kind == Kind.INDENTED_MARKER) {
                findings.error(j + 1, 1, MbmCode.MBM_NESTED_LIST, null);
            }
            parts.add(lines[j].strip());
            j++;
        }
        return j;
    }

    private int quote(int i, UUID kept) {
        var paragraphs = new ArrayList<List<Inline>>();
        var parts = new ArrayList<String>();
        int paragraphLine = i + 1;
        int j = i;
        while (j < lines.length && lines[j].startsWith(">")) {
            String text = lines[j].substring(1);
            text = (text.startsWith(" ") ? text.substring(1) : text).strip();
            if (text.isEmpty()) {
                flushQuote(paragraphs, parts, paragraphLine);
            } else {
                if (parts.isEmpty()) {
                    paragraphLine = j + 1;
                }
                parts.add(text);
            }
            j++;
        }
        flushQuote(paragraphs, parts, paragraphLine);
        if (paragraphs.isEmpty()) {
            paragraphs.add(List.of());
        }
        blocks.add(new Block.Quote(i + 1, kept, paragraphs));
        return j;
    }

    private void flushQuote(List<List<Inline>> paragraphs, List<String> parts, int line) {
        if (!parts.isEmpty()) {
            paragraphs.add(inline.parse(String.join(" ", parts), line));
            parts.clear();
        }
    }

    /**
     * A list of consecutive items of one kind. Only the first number of an ordered list is kept: {@code order} is set
     * when it is 2 or more; 0 and 1 leave it unset (native-v1 requires a positive order and 1 is the default).
     */
    private int list(int i, Kind kind, UUID kept) {
        var items = new ArrayList<List<Inline>>();
        int order = 1;
        int j = i;
        while (true) {
            String first = lines[j];
            int markerLength = kind == Kind.BULLET ? 2 : orderedMarker(first) + 2;
            if (items.isEmpty() && kind == Kind.ORDERED) {
                order = Integer.parseInt(first.substring(0, markerLength - 2));
            }
            var parts = new ArrayList<String>();
            parts.add(first.substring(markerLength).strip());
            int k = continuation(j + 1, parts);
            items.add(inline.parse(parts.stream().filter(part -> !part.isEmpty()).collect(Collectors.joining(" ")), j + 1));
            if (k < lines.length && classify(lines[k]) == kind) {
                j = k;
            } else if (k + 1 < lines.length && lines[k].isBlank() && classify(lines[k + 1]) == kind) {
                j = k + 1;
            } else {
                blocks.add(new Block.ListBlock(i + 1, kept, kind == Kind.ORDERED, order, items));
                return k;
            }
        }
    }

    // ------------------------------------------------------------------ fences

    /** A scanned fenced region: the lines strictly between the fences and the index after the closing fence. */
    private record Fence(int bodyStart, int bodyEnd, int end, boolean closed) {
    }

    /** Scans from the opening fence at line {@code open}; an unterminated fence runs to the end of the source. */
    private Fence fence(int open, Matcher opening) {
        int ticks = opening.matches() ? opening.group(1).length() : 3;
        for (int j = open + 1; j < lines.length; j++) {
            String candidate = lines[j].stripTrailing();
            if (candidate.length() >= ticks && candidate.chars().allMatch(c -> c == '`')) {
                return new Fence(open + 1, j, j + 1, true);
            }
        }
        return new Fence(open + 1, lines.length, lines.length, false);
    }

    // ------------------------------------------------------------------ directives

    private int directive(int i, DirectiveHead head, UUID kept) {
        int line = i + 1;
        Spec spec = SPECS.get(head.name());
        if (spec == null) {
            findings.error(line, 1, MbmCode.MBM_UNKNOWN_DIRECTIVE, null);
            return i + 1;
        }
        return switch (head.name()) {
            case "table" -> table(i, head, spec, kept);
            case "mermaid" -> mermaid(i, head, spec, kept);
            case "sources" -> sources(i, head, spec, kept);
            case "audio" -> media(i, head, spec, MbmSlot.Kind.AUDIO, kept);
            case "image" -> media(i, head, spec, MbmSlot.Kind.IMAGE, kept);
            default -> media(i, head, spec, MbmSlot.Kind.VIDEO, kept);
        };
    }

    /** Reports unknown, repeated and missing attributes; returns the first value of every known attribute. */
    private Map<String, String> attributes(DirectiveHead head, Spec spec, int line) {
        var values = new LinkedHashMap<String, String>();
        var unknown = new HashSet<String>();
        for (DirectiveHead.Attribute attribute : head.attributes()) {
            if (!spec.allowed().contains(attribute.key())) {
                if (unknown.add(attribute.key())) {
                    findings.error(line, 1, MbmCode.MBM_UNKNOWN_ATTRIBUTE, attribute.key());
                }
            } else if (values.containsKey(attribute.key())) {
                findings.error(line, 1, MbmCode.MBM_DUPLICATE_ATTRIBUTE, attribute.key());
            } else {
                values.put(attribute.key(), attribute.value());
            }
        }
        for (String required : spec.required()) {
            if (present(values, required) == null) {
                findings.error(line, 1, MbmCode.MBM_MISSING_ATTRIBUTE, required);
            }
        }
        return values;
    }

    private static String present(Map<String, String> values, String key) {
        String value = values.get(key);
        return value == null || value.isBlank() ? null : value;
    }

    private String bounded(Map<String, String> values, String key, int max, int line) {
        String value = present(values, key);
        if (value != null && value.length() > max) {
            findings.error(line, 1, MbmCode.MBM_VALUE_TOO_LONG, key);
            return null;
        }
        return value;
    }

    private int table(int i, DirectiveHead head, Spec spec, UUID kept) {
        int line = i + 1;
        int end = i + 1;
        while (end < lines.length && lines[end].startsWith("|")) {
            end++;
        }
        if (head.syntaxError()) {
            findings.error(line, 1, MbmCode.MBM_ATTRIBUTE_SYNTAX, null);
            return end;
        }
        int errorsBefore = findings.errorCount();
        Map<String, String> values = attributes(head, spec, line);
        String caption = bounded(values, "caption", MAX_TITLE, line);
        if (!head.text().isEmpty()) {
            findings.error(line, 1, MbmCode.MBM_UNEXPECTED_DIRECTIVE_TEXT, null);
        }
        if (end == i + 1) {
            findings.error(line, 1, MbmCode.MBM_DIRECTIVE_BODY_MISSING, null);
            return end;
        }
        var rows = new ArrayList<List<String>>();
        List<String> columns = tableRows(i + 1, end, rows);
        if (findings.errorCount() == errorsBefore && columns != null) {
            blocks.add(new Block.Table(line, kept, caption, columns, rows));
        }
        return end;
    }

    /** Validates the table lines {@code [from, to)}; fills {@code rows} and returns the headings, or null on error. */
    private List<String> tableRows(int from, int to, List<List<String>> rows) {
        int header = from + 1;
        List<String> columns = TableText.cells(lines[from]);
        if (columns.size() > MAX_TABLE_COLUMNS) {
            findings.error(header, 1, MbmCode.MBM_TABLE_TOO_WIDE, null);
            return null;
        }
        if (from + 1 >= to || !isSeparatorOf(lines[from + 1], columns.size())) {
            findings.error(header, 1, MbmCode.MBM_TABLE_MALFORMED, null);
            return null;
        }
        boolean valid = true;
        if (columns.stream().anyMatch(String::isBlank)) {
            findings.error(header, 1, MbmCode.MBM_TABLE_HEADER_BLANK, null);
            valid = false;
        }
        if (columns.stream().anyMatch(column -> column.length() > MAX_TITLE)) {
            findings.error(header, 1, MbmCode.MBM_VALUE_TOO_LONG, null);
            valid = false;
        }
        for (int row = from + 2; row < to; row++) {
            if (row - (from + 2) >= MAX_TABLE_ROWS) {
                findings.error(row + 1, 1, MbmCode.MBM_TABLE_TOO_TALL, null);
                return null;
            }
            List<String> cells = TableText.cells(lines[row]);
            if (cells.size() != columns.size()) {
                findings.error(row + 1, 1, MbmCode.MBM_TABLE_RAGGED, null);
                valid = false;
            } else if (cells.stream().anyMatch(cell -> cell.length() > MAX_CELL)) {
                findings.error(row + 1, 1, MbmCode.MBM_VALUE_TOO_LONG, null);
                valid = false;
            } else {
                rows.add(cells);
            }
        }
        return valid ? columns : null;
    }

    private static boolean isSeparatorOf(String line, int columns) {
        if (!line.startsWith("|")) {
            return false;
        }
        List<String> cells = TableText.cells(line);
        return cells.size() == columns && TableText.isSeparator(cells);
    }

    private int mermaid(int i, DirectiveHead head, Spec spec, UUID kept) {
        int line = i + 1;
        Matcher opening = i + 1 < lines.length ? MERMAID_OPEN.matcher(lines[i + 1]) : null;
        boolean fenced = opening != null && opening.matches();
        Fence fence = fenced ? fence(i + 1, opening) : null;
        int end = fenced ? fence.end() : i + 1;
        if (head.syntaxError()) {
            findings.error(line, 1, MbmCode.MBM_ATTRIBUTE_SYNTAX, null);
            return end;
        }
        int errorsBefore = findings.errorCount();
        Map<String, String> values = attributes(head, spec, line);
        String title = bounded(values, "title", MAX_TITLE, line);
        String description = bounded(values, "description", MAX_DESCRIPTION, line);
        if (!head.text().isEmpty()) {
            findings.error(line, 1, MbmCode.MBM_UNEXPECTED_DIRECTIVE_TEXT, null);
        }
        String source = null;
        if (!fenced) {
            findings.error(line, 1, MbmCode.MBM_DIRECTIVE_BODY_MISSING, null);
        } else if (!fence.closed()) {
            findings.error(line + 1, 1, MbmCode.MBM_UNTERMINATED_FENCE, null);
        } else {
            source = String.join("\n", java.util.Arrays.asList(lines).subList(fence.bodyStart(), fence.bodyEnd()));
            if (source.isBlank()) {
                findings.error(line, 1, MbmCode.MBM_DIRECTIVE_BODY_MISSING, null);
            } else if (source.length() > MAX_MERMAID_SOURCE) {
                findings.error(line, 1, MbmCode.MBM_VALUE_TOO_LONG, null);
            }
        }
        if (findings.errorCount() == errorsBefore) {
            blocks.add(new Block.Mermaid(line, kept, source, title, description));
        }
        return end;
    }

    private int sources(int i, DirectiveHead head, Spec spec, UUID kept) {
        int line = i + 1;
        int end = i + 1;
        while (end < lines.length && !lines[end].isBlank()) {
            end++;
        }
        if (head.syntaxError()) {
            findings.error(line, 1, MbmCode.MBM_ATTRIBUTE_SYNTAX, null);
            return end;
        }
        int errorsBefore = findings.errorCount();
        attributes(head, spec, line);
        if (!head.text().isEmpty()) {
            findings.error(line, 1, MbmCode.MBM_UNEXPECTED_DIRECTIVE_TEXT, null);
        }
        if (end == i + 1) {
            findings.error(line, 1, MbmCode.MBM_DIRECTIVE_BODY_MISSING, null);
            return end;
        }
        var entries = new ArrayList<Block.Sources.Entry>();
        for (int row = i + 1; row < end; row++) {
            Matcher matcher = SOURCE_LINE.matcher(lines[row]);
            MbmOptions.ResearchSource found = null;
            if (matcher.matches()) {
                found = research.get(Integer.parseInt(matcher.group(1)));
                if (found != null && !found.url().equals(matcher.group(2))) {
                    found = null;
                }
            }
            if (found == null) {
                findings.error(row + 1, 1, MbmCode.MBM_SOURCE_NOT_IN_RESEARCH, null);
            } else {
                entries.add(new Block.Sources.Entry(found.n(), found.url(), found.title()));
            }
        }
        if (findings.errorCount() == errorsBefore) {
            blocks.add(new Block.Sources(line, kept, entries));
        }
        return end;
    }

    private int media(int i, DirectiveHead head, Spec spec, MbmSlot.Kind kind, UUID kept) {
        int line = i + 1;
        int errorsBefore = findings.errorCount();
        mediaCount++;
        int existing = options.mode() == MbmOptions.Mode.EDIT ? options.existingMediaCount() : 0;
        if (!mediaOverflowReported && existing + mediaCount > options.maxMedia()) {
            mediaOverflowReported = true;
            overflowLine = line;
            findings.error(line, 1, MbmCode.MBM_TOO_MANY_MEDIA, null);
        }
        if (head.syntaxError()) {
            findings.error(line, 1, MbmCode.MBM_ATTRIBUTE_SYNTAX, null);
            return i + 1;
        }
        Map<String, String> values = attributes(head, spec, line);
        reportKindOverflow(kind, values.get("mode"), line);
        String slot = slotKey(present(values, "slot"), line);
        String label;
        String lang = null;
        String voice = null;
        String mode = null;
        switch (kind) {
            case AUDIO -> {
                label = bounded(values, "title", MAX_TITLE, line);
                lang = present(values, "lang");
                if (lang != null && !NativeProfile.acceptsLang(lang)) {
                    findings.error(line, 1, MbmCode.MBM_INVALID_ATTRIBUTE_VALUE, "lang");
                }
                voice = values.get("voice");
                if (voice != null && !voice.equals("female") && !voice.equals("male")) {
                    findings.error(line, 1, MbmCode.MBM_INVALID_ATTRIBUTE_VALUE, "voice");
                }
            }
            case IMAGE -> {
                label = bounded(values, "alt", MAX_ALT, line);
                mode = present(values, "mode");
                if (mode != null && !mode.equals("search") && !mode.equals("generate")) {
                    findings.error(line, 1, MbmCode.MBM_INVALID_ATTRIBUTE_VALUE, "mode");
                } else if ("generate".equals(mode) && !options.capabilities().imageGeneration()) {
                    findings.error(line, 1, MbmCode.MBM_CAPABILITY_OFF, "mode");
                }
            }
            default -> {
                label = bounded(values, "title", MAX_TITLE, line);
                if (!options.capabilities().videoGeneration()) {
                    findings.error(line, 1, MbmCode.MBM_CAPABILITY_OFF, null);
                }
            }
        }
        String text = head.text();
        if (text.isEmpty()) {
            findings.error(line, 1, MbmCode.MBM_DIRECTIVE_BODY_MISSING, null);
        } else if (kind == MbmSlot.Kind.AUDIO && text.length() > MAX_AUDIO_TEXT) {
            findings.error(line, 1, MbmCode.MBM_AUDIO_TEXT_TOO_LONG, null);
        } else if (kind != MbmSlot.Kind.AUDIO && text.length() > MAX_PROMPT) {
            findings.error(line, 1, MbmCode.MBM_VALUE_TOO_LONG, null);
        }
        if (findings.errorCount() == errorsBefore) {
            blocks.add(new Block.Media(line, kept, kind, slot, label, lang, voice, mode, text));
        }
        return i + 1;
    }

    /** A second clip of a kind exceeds what the estimate prices (one per declared kind): a repairable finding. */
    private void reportKindOverflow(MbmSlot.Kind kind, String mode, int line) {
        boolean over = switch (kind) {
            case AUDIO -> ++audioCount > options.maxAudio();
            case IMAGE -> !"generate".equals(mode) && ++imageSearchCount > options.maxImageSearch();
            default -> false;
        };
        if (over && overflowLine != line) {
            findings.error(line, 1, MbmCode.MBM_TOO_MANY_MEDIA, null);
        }
    }

    /** A slot key must match the pattern and be unique among this document's slots and the artifact's other slots. */
    private String slotKey(String key, int line) {
        if (key == null) {
            return null;
        }
        if (!SLOT_KEY.matcher(key).matches()) {
            findings.error(line, 1, MbmCode.MBM_INVALID_SLOT_KEY, "slot");
            return null;
        }
        boolean existing = options.mode() == MbmOptions.Mode.EDIT && options.existingSlotKeys().contains(key);
        if (existing || !slotKeys.add(key)) {
            findings.error(line, 1, MbmCode.MBM_DUPLICATE_SLOT, "slot");
            return null;
        }
        return key;
    }
}
