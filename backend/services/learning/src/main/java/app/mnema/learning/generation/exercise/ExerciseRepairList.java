package app.mnema.learning.generation.exercise;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The compact repair message of an exercise answer: what to return (only replacements, exactly as many as were rejected or
 * missing), one line {@code упражнение N: CODE (path)} per finding and the one-line rule of every code named. It names codes
 * and member paths, never content, and stays within the repair prompt's bound ({@link #MAX_CHARACTERS}): the instruction comes
 * first so that a cut never loses it.
 */
public final class ExerciseRepairList {
    /** The repair segment is cut by the provider layer at 2000 characters; the list stays below it. */
    public static final int MAX_CHARACTERS = 1_800;

    private ExerciseRepairList() { }

    /**
     * @param findings the findings of the last answer, positions relative to that answer ({@code -1}: the whole answer)
     * @param needed how many exercises the next answer must hold (the rejected ones plus the ones that were missing)
     */
    public static String format(List<ExerciseFinding> findings, int needed) {
        StringBuilder text = new StringBuilder("Верни json {\"exercises\": […]} ровно из ").append(needed)
                .append(" упражнений: только замены отклонённым, принятые не повторяй.");
        Set<String> lines = new LinkedHashSet<>();
        Map<ExerciseCode, ExerciseCode> codes = new LinkedHashMap<>();
        for (ExerciseFinding finding : findings) {
            StringBuilder line = new StringBuilder(finding.index() < 0 ? "ответ" : "упражнение " + (finding.index() + 1))
                    .append(": ").append(finding.code().name());
            if (finding.path() != null && !finding.path().isBlank()) line.append(" (").append(finding.path()).append(')');
            lines.add(line.toString());
            codes.put(finding.code(), finding.code());
        }
        for (String line : lines) append(text, "\n" + line);
        for (ExerciseCode code : codes.keySet()) append(text, "\n" + code.name() + ": " + code.rule());
        return text.toString();
    }

    private static void append(StringBuilder text, String line) {
        if (text.length() + line.length() <= MAX_CHARACTERS) text.append(line);
    }
}
