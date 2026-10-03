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
    private static final int MAX_ACCEPTED = 10;

    private ExerciseRepairList() { }

    /**
     * @param findings the findings of the last answer, positions relative to that answer ({@code -1}: the whole answer)
     * @param needed how many exercises the next answer must hold (the rejected ones plus the ones that were missing)
     */
    public static String format(List<ExerciseFinding> findings, int needed) {
        return format(findings, needed, List.of());
    }

    /**
     * @param accepted the first lines of the exercises already accepted: the model is told what is kept (and must not repeat), as the
     *                 answer it replaces is asked for again from the start
     */
    public static String format(List<ExerciseFinding> findings, int needed, List<String> accepted) {
        StringBuilder text = new StringBuilder("Верни json {\"exercises\": […]} ровно из ").append(needed)
                .append(" упражнений: только замены отклонённым, принятые не повторяй.");
        if (!accepted.isEmpty()) {
            append(text, "\nУже приняты, не повторяй их:");
            for (String line : accepted.stream().limit(MAX_ACCEPTED).toList()) append(text, "\n- " + clip(line));
        }
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

    private static String clip(String line) {
        String flat = line.replaceAll("\\s+", " ");
        return flat.length() <= 80 ? flat : flat.substring(0, 80) + "…";
    }

    private static void append(StringBuilder text, String line) {
        if (text.length() + line.length() <= MAX_CHARACTERS) text.append(line);
    }
}
