package app.mnema.learning.ai.prompt;

import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Code-rendered blocks for the {@code *_blocks}, {@code *_lines} and {@code allowed_links} placeholders. Every part that
 * comes from users, notes, the web or history is redacted and escaped here, so a block can be inserted verbatim.
 */
public final class PromptBlocks {
    private static final Pattern ID = Pattern.compile("[A-Za-z][A-Za-z0-9_:-]{0,31}");
    private static final Pattern URL_CHARACTERS = Pattern.compile("[^\\s<>\"]+");

    private PromptBlocks() { }

    /** {@code <note id="N1">text</note>}. */
    public static String note(String id, String text) {
        return element("note", "id", id, text);
    }

    /** {@code <exemplar id="E1" kind="starred">mbm</exemplar>}. */
    public static String exemplar(String id, String kind, String mbm) {
        return "<exemplar id=\"" + checked(id) + "\" kind=\"" + checked(kind) + "\">" + clean(mbm) + "</exemplar>";
    }

    /** {@code <search_result n="1" url="..." title="...">snippet</search_result>}. */
    public static String searchResult(int n, String url, String title, String snippet) {
        return "<search_result n=\"" + n + "\" url=\"" + PromptRenderer.escape(url, true) + "\" title=\""
                + PromptRenderer.escape(Redactor.redact(title), true) + "\">" + clean(snippet) + "</search_result>";
    }

    /** One URL per line; entries with whitespace, angle brackets or quotes are dropped, never repaired. */
    public static String allowedLinks(Collection<String> urls) {
        return urls.stream().filter(url -> url != null && URL_CHARACTERS.matcher(url).matches())
                .collect(Collectors.joining("\n"));
    }

    /** Free-text lines (objectives, history, neighbours...): redacted and escaped, one per line, inner newlines flattened. */
    public static String lines(Collection<String> lines) {
        return lines.stream().map(line -> clean(line).replaceAll("\\s*\\R\\s*", " ")).collect(Collectors.joining("\n"));
    }

    /** Joins already rendered blocks. */
    public static String join(List<String> blocks) { return String.join("\n", blocks); }

    private static String element(String tag, String attribute, String id, String text) {
        return "<" + tag + " " + attribute + "=\"" + checked(id) + "\">" + clean(text) + "</" + tag + ">";
    }

    private static String clean(String text) { return PromptRenderer.escape(Redactor.redact(text == null ? "" : text), true); }

    private static String checked(String id) {
        if (id == null || !ID.matcher(id).matches()) throw new PromptException("Invalid block identifier");
        return id;
    }
}
