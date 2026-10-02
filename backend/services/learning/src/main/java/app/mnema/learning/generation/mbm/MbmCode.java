package app.mnema.learning.generation.mbm;

/**
 * Stable MBM v1 finding codes. The rule texts are the lines the repair prompt shows; they are kept identical to
 * {@code contracts/generation/mbm-v1/codes.json} (a test compares both).
 */
public enum MbmCode {
    MBM_EMPTY_DOCUMENT(Severity.ERROR, "The document contains no block."),
    MBM_DOCUMENT_TOO_LARGE(Severity.ERROR, "The source is larger than 256 KiB, or the compiled document is rejected by a NativeDocumentReader size limit (1 MiB, 10,000 nodes, depth 32), or the source is too deeply nested or too complex to parse."),
    MBM_HEADING_LEVEL(Severity.ERROR, "Headings are # to ### only; split the topic or use a paragraph."),
    MBM_NESTED_LIST(Severity.ERROR, "Lists are one level; write nested items as separate paragraphs or a table."),
    MBM_CODE_LANGUAGE_INVALID(Severity.ERROR, "The info string of a fenced code block is empty or one lowercase identifier such as sql, python or c++ (at most 32 characters of a-z, 0-9, + # . -); a diagram is ::mermaid, not ```mermaid."),
    MBM_EMPTY_CODE_BLOCK(Severity.ERROR, "A fenced code block needs at least one nonblank line of code between its fences."),
    MBM_UNTERMINATED_FENCE(Severity.ERROR, "Every opening fence needs a closing fence on its own line."),
    MBM_NESTED_LINK(Severity.ERROR, "A link label cannot contain another link."),
    MBM_UNKNOWN_DIRECTIVE(Severity.ERROR, "Only ::table, ::mermaid, ::audio, ::image, ::video and ::sources exist."),
    MBM_UNKNOWN_ATTRIBUTE(Severity.ERROR, "The directive does not define this attribute."),
    MBM_MISSING_ATTRIBUTE(Severity.ERROR, "A required attribute is missing or blank (alt, title, caption, description, slot, lang, mode)."),
    MBM_DUPLICATE_ATTRIBUTE(Severity.ERROR, "An attribute may appear once."),
    MBM_INVALID_ATTRIBUTE_VALUE(Severity.ERROR, "The value is outside the allowed set or lexical profile (mode: search|generate; voice: female|male; lang: BCP 47 profile of native-v1)."),
    MBM_ATTRIBUTE_SYNTAX(Severity.ERROR, "Attributes are {key=\"value\" ...} with double-quoted values; escape \\\" and \\\\ only."),
    MBM_DIRECTIVE_BODY_MISSING(Severity.ERROR, "The directive needs its body: text after the brace (audio, image, video), a pipe table (table), a fenced source (mermaid) or [n] URL lines (sources)."),
    MBM_UNEXPECTED_DIRECTIVE_TEXT(Severity.ERROR, "Text after the brace is allowed only for ::audio, ::image and ::video."),
    MBM_VALUE_TOO_LONG(Severity.ERROR, "An attribute or body exceeds the bound (alt 4096, title and caption 1024, description 8192, mermaid or code source 16384, table cell 4096, image or video query or prompt 300 UTF-16 code units)."),
    MBM_INVALID_SLOT_KEY(Severity.ERROR, "A slot key matches [a-z][a-z0-9_]{0,31}."),
    MBM_DUPLICATE_SLOT(Severity.ERROR, "A slot key is unique in the document and among the slots outside an edited range."),
    MBM_CAPABILITY_OFF(Severity.ERROR, "The capability behind this directive or mode (videoGeneration, imageGeneration) is not enabled for the session."),
    MBM_TABLE_MALFORMED(Severity.ERROR, "A table is a header row, a separator row (|---|) with the same cell count, then body rows."),
    MBM_TABLE_HEADER_BLANK(Severity.ERROR, "Every column heading must be nonblank."),
    MBM_TABLE_TOO_WIDE(Severity.ERROR, "A table has at most 12 columns."),
    MBM_TABLE_TOO_TALL(Severity.ERROR, "A table has at most 100 body rows."),
    MBM_TABLE_RAGGED(Severity.ERROR, "Every body row has exactly as many cells as the header."),
    MBM_SOURCE_NOT_IN_RESEARCH(Severity.ERROR, "Every ::sources line is [n] URL where n and the exact URL belong to one result of the session research."),
    MBM_UNKNOWN_HANDLE(Severity.ERROR, "A [[handle]] must be one of the handles given for the edited range; new documents have none."),
    MBM_DUPLICATE_HANDLE(Severity.ERROR, "A handle may be used on one block only."),
    MBM_TOO_MANY_MEDIA(Severity.ERROR, "At most 8 media directives per artifact (the media counts declared in the spec cap it lower); the estimate prices only the declared counts."),
    MBM_AUDIO_TEXT_TOO_LONG(Severity.ERROR, "The text of ::audio is at most 600 characters; split it into several clips."),
    MBM_EDIT_HANDLE_OMITTED(Severity.ERROR, "Every handle of the edited range must appear in the output; removing a block is not an AI action. Reordering is allowed."),
    MBM_LINK_NOT_ALLOWED(Severity.WARNING, "The link URL is not in the allowlist: the label stays as text and the URL is dropped."),
    MBM_HANDLE_TYPE_CHANGED(Severity.WARNING, "The block with this handle changed type: a new node ID was allocated."),
    MBM_LITERAL_DELIMITER(Severity.WARNING, "A run of three or more asterisks, or an asterisk run that could open emphasis but has no closer, stays literal text."),
    MBM_LINK_REJECTED_BY_PROFILE(Severity.WARNING, "An allowlist or research URL violates the native-v1 href profile and was dropped before compilation (reported at line 0).");

    /** ERROR findings stop compilation (no document); WARNING findings accompany a compiled document. */
    public enum Severity { ERROR, WARNING }

    private final Severity severity;
    private final String rule;

    MbmCode(Severity severity, String rule) {
        this.severity = severity;
        this.rule = rule;
    }

    public Severity severity() {
        return severity;
    }

    /** The compact one-line rule used by the repair prompt. */
    public String rule() {
        return rule;
    }
}
