package app.mnema.learning.generation.mbm;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic repairs of harmless model slips, applied before {@link MbmCompiler#compile} (the compiler itself never
 * rewrites its input, so the golden contract stays exact). Nothing that changes meaning is repaired: unknown
 * directives, bad attributes and unmatched structures stay errors for the one repair call of the pipeline.
 * Fenced regions (code and {@code ::mermaid} sources) are never touched; only the language of a code fence is lowercased.
 *
 * <ul>
 *   <li>{@link Fix#LINE_ENDINGS}: {@code \r\n} and {@code \r} become {@code \n};
 *   <li>{@link Fix#TRAILING_SPACES}: trailing whitespace of a line is removed;
 *   <li>{@link Fix#HEADING_LEVEL}: {@code ####} and deeper become {@code ###};
 *   <li>{@link Fix#BLANK_LINES}: three or more consecutive blank lines become two (two blank lines still end a list);
 *   <li>{@link Fix#EDGE_BLANK_LINES}: blank lines before the first and after the last block are removed;
 *   <li>{@link Fix#CODE_LANGUAGE_CASE}: the language of a code fence is lowercased when it is otherwise a valid identifier
 *       ({@code ```SQL} becomes {@code ```sql}).
 * </ul>
 */
public final class MbmAutoFixer {

    /** The kinds of repair, reported in {@link Fixed#applied()}. */
    public enum Fix { LINE_ENDINGS, TRAILING_SPACES, HEADING_LEVEL, BLANK_LINES, EDGE_BLANK_LINES, CODE_LANGUAGE_CASE }

    /** The repaired text and which repairs changed it. */
    public record Fixed(String text, Set<Fix> applied) {

        public Fixed {
            applied = Set.copyOf(applied);
        }
    }

    private static final Pattern DEEP_HEADING = Pattern.compile("#{4,} .*", Pattern.DOTALL);
    // A fence may follow a [[handle]] (edit mode); the handle is not part of the fence.
    private static final Pattern FENCE_OPEN = Pattern.compile("(?:\\[\\[[A-Za-z][0-9]+]] )?(`{3,})([^`]*)");
    private static final Pattern CODE_LANGUAGE_ANY_CASE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9+#.-]{0,31}");

    private MbmAutoFixer() {
    }

    public static Fixed fix(String source) {
        Set<Fix> applied = EnumSet.noneOf(Fix.class);
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        if (!normalized.equals(source)) {
            applied.add(Fix.LINE_ENDINGS);
        }
        var out = new ArrayList<String>();
        int fence = 0;
        int blanks = 0;
        for (String line : normalized.split("\n", -1)) {
            if (fence > 0) {
                out.add(line);
                if (line.stripTrailing().length() >= fence && line.stripTrailing().chars().allMatch(c -> c == '`')) {
                    fence = 0;
                }
                continue;
            }
            String fixed = line.stripTrailing();
            if (!fixed.equals(line)) {
                applied.add(Fix.TRAILING_SPACES);
            }
            if (DEEP_HEADING.matcher(fixed).matches()) {
                fixed = "### " + fixed.substring(fixed.indexOf(' ') + 1);
                applied.add(Fix.HEADING_LEVEL);
            }
            var opening = FENCE_OPEN.matcher(fixed);
            if (opening.matches()) {
                fence = opening.group(1).length();
                String info = opening.group(2).strip();
                if (CODE_LANGUAGE_ANY_CASE.matcher(info).matches() && !info.equals(info.toLowerCase(Locale.ROOT))) {
                    fixed = fixed.substring(0, opening.start(2)) + info.toLowerCase(Locale.ROOT);
                    applied.add(Fix.CODE_LANGUAGE_CASE);
                }
            }
            blanks = fixed.isEmpty() ? blanks + 1 : 0;
            if (blanks > 2) {
                applied.add(Fix.BLANK_LINES);
            } else {
                out.add(fixed);
            }
        }
        return new Fixed(edges(out, applied), applied);
    }

    private static String edges(List<String> lines, Set<Fix> applied) {
        int start = 0;
        int end = lines.size();
        while (start < end && lines.get(start).isBlank()) {
            start++;
        }
        while (end > start && lines.get(end - 1).isBlank()) {
            end--;
        }
        if (start > 0 || end < lines.size() - 1) {
            applied.add(Fix.EDGE_BLANK_LINES);
        }
        return start == end ? "" : String.join("\n", lines.subList(start, end)) + "\n";
    }
}
