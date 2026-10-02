package app.mnema.learning.generation.mbm;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The compact error list of the repair prompt: one line per finding, {@code line -> rule}, in report order. A line
 * names the code and the attribute, never the offending content. Findings about the options (line 0) are shown as
 * {@code options}. Duplicates are shown once and the list is capped at {@link #MAX_CHARACTERS} characters.
 */
public final class MbmRepairList {

    /** The list is cut after the last whole line that fits: the repair prompt must stay small. */
    public static final int MAX_CHARACTERS = 4_096;

    private MbmRepairList() {
    }

    public static String format(List<MbmFinding> findings) {
        Set<String> lines = new LinkedHashSet<>();
        for (MbmFinding finding : findings) {
            var line = new StringBuilder(finding.line() == 0 ? "options" : "L" + finding.line());
            line.append(" -> ").append(finding.code().name());
            if (finding.attribute() != null) {
                line.append(" (").append(finding.attribute()).append(')');
            }
            line.append(": ").append(finding.code().rule());
            lines.add(line.toString());
        }
        var out = new StringBuilder();
        for (String line : lines) {
            if (out.length() + line.length() + 1 > MAX_CHARACTERS) {
                break;
            }
            out.append(out.isEmpty() ? "" : "\n").append(line);
        }
        return out.toString();
    }
}
