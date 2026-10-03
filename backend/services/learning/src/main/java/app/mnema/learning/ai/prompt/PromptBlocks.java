package app.mnema.learning.ai.prompt;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The only factory of {@link PromptBlock}s for the {@code *_blocks}, {@code *_lines}, {@code allowed_links},
 * {@code document} and {@code schema} placeholders. Every part that comes from users, notes, the web or history is
 * size-checked (64 KiB each), redacted and escaped here, so a block can be inserted verbatim.
 */
public final class PromptBlocks {
    private static final Pattern ID = Pattern.compile("[A-Za-z][A-Za-z0-9_:-]{0,31}");
    private static final Pattern HANDLE = Pattern.compile("[A-Za-z][0-9]{1,6}");
    private static final Pattern URL_CHARACTERS = Pattern.compile("[^\\s<>\"]+");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int OUTLINE_FIRST_LINE = 120;

    private PromptBlocks() { }

    /** A block with no content, for example "no search results". */
    public static PromptBlock empty() { return new PromptBlock(""); }

    /** {@code <note id="N1">text</note>}. */
    public static PromptBlock note(String id, String text) {
        return new PromptBlock("<note id=\"" + checked(id) + "\">" + clean(text, "note") + "</note>");
    }

    /** {@code <exemplar id="E1" kind="starred">mbm</exemplar>}. */
    public static PromptBlock exemplar(String id, String kind, String mbm) {
        return new PromptBlock("<exemplar id=\"" + checked(id) + "\" kind=\"" + checked(kind) + "\">"
                + clean(mbm, "exemplar") + "</exemplar>");
    }

    /** {@code <search_result n="1" url="..." title="...">snippet</search_result>}. */
    public static PromptBlock searchResult(int n, String url, String title, String snippet) {
        return new PromptBlock("<search_result n=\"" + n + "\" url=\"" + PromptRenderer.escape(
                Redactor.requireWithin(url, "search result url"), true) + "\" title=\"" + clean(title, "search result title")
                + "\">" + clean(snippet, "search result snippet") + "</search_result>");
    }

    /** One URL per line; entries with whitespace, angle brackets or quotes are dropped, never repaired. */
    public static PromptBlock allowedLinks(Collection<String> urls) {
        return new PromptBlock(urls.stream().filter(url -> url != null && url.length() <= 2_048
                && URL_CHARACTERS.matcher(url).matches()).collect(Collectors.joining("\n")));
    }

    /** Free-text lines (objectives, history, neighbours...): redacted and escaped, one per line, inner newlines flattened. */
    public static PromptBlock lines(Collection<String> lines) {
        return new PromptBlock(lines.stream().map(line -> flatten(clean(line, "line"))).collect(Collectors.joining("\n")));
    }

    /** One line of {@code outline.lines}: {@code handle · title · first line (120 characters) · exercises: n}. */
    public record OutlineEntry(String handle, String title, String firstLine, int exercises) { }

    public static PromptBlock outline(List<OutlineEntry> entries) {
        return new PromptBlock(entries.stream().map(entry -> checkedHandle(entry.handle()) + " · "
                + flatten(clean(entry.title(), "outline title")) + " · " + flatten(clean(cut(entry.firstLine(), OUTLINE_FIRST_LINE),
                "outline line")) + " · exercises: " + Math.max(0, entry.exercises())).collect(Collectors.joining("\n")));
    }

    /** One top-level block of a material: its {@code [[b3]]} handle and its text. */
    public record HandleLine(String handle, String text) { }

    /** {@code <material id="m1">} with one {@code [[handle]] text} line per top-level block. */
    public static PromptBlock material(String id, List<HandleLine> lines) {
        String body = lines.stream().map(line -> "[[" + checkedHandle(line.handle()) + "]] " + flatten(clean(line.text(), "material line")))
                .collect(Collectors.joining("\n"));
        return new PromptBlock("<material id=\"" + checked(id) + "\">\n" + body + "\n</material>");
    }

    /** The edit context: {@code <context_before>}, {@code <target>} and {@code <context_after>}; neighbours may be empty. */
    public static PromptBlock document(String contextBefore, String target, String contextAfter) {
        return new PromptBlock("<context_before>" + clean(contextBefore, "context before") + "</context_before>\n<target>"
                + clean(target, "target") + "</target>\n<context_after>" + clean(contextAfter, "context after") + "</context_after>");
    }

    /** The exercise output schema, rendered by code from the contract file; it must be a JSON object. */
    public static PromptBlock schema(String jsonSchema) {
        try {
            if (!JSON.readTree(Redactor.requireWithin(jsonSchema, "schema")).isObject()) throw new PromptException("The schema must be a JSON object");
        } catch (JacksonException exception) {
            throw new PromptException("The schema is not valid JSON");
        }
        return new PromptBlock(jsonSchema);
    }

    /**
     * The exercise to revise, as the JSON of the output form inside {@code <current_exercise>}: redacted like every user text, and
     * only {@code & < >} escaped (a JSON quote stays a quote, so the model copies valid JSON; the strings of its answer are decoded
     * again before validation, as for every exercise).
     */
    public static PromptBlock currentExercise(String json) {
        return new PromptBlock("<current_exercise>\n" + PromptRenderer.escape(Redactor.redact(Redactor.requireWithin(json, "current exercise")), false)
                + "\n</current_exercise>");
    }

    /** Joins already rendered blocks. */
    public static PromptBlock join(List<PromptBlock> blocks) {
        return new PromptBlock(blocks.stream().map(PromptBlock::text).collect(Collectors.joining("\n")));
    }

    private static String clean(String text, String what) {
        return PromptRenderer.escape(Redactor.redact(Redactor.requireWithin(text == null ? "" : text, what)), true);
    }

    private static String flatten(String text) { return text.replaceAll("\\s*\\R\\s*", " "); }

    private static String cut(String text, int max) {
        String value = text == null ? "" : text;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String checked(String id) {
        if (id == null || !ID.matcher(id).matches()) throw new PromptException("Invalid block identifier");
        return id;
    }

    private static String checkedHandle(String handle) {
        if (handle == null || !HANDLE.matcher(handle).matches()) throw new PromptException("Invalid handle");
        return handle;
    }
}
