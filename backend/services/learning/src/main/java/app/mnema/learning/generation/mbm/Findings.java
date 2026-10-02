package app.mnema.learning.generation.mbm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Collects findings while parsing; ordering and the error cap are applied once at the end. */
final class Findings {

    /** Contract bound: a failed compilation reports at most this many errors. */
    static final int MAX_ERRORS = 20;
    /** Collection bound so that hostile input cannot grow the list; far above {@link #MAX_ERRORS}. */
    private static final int COLLECT_CAP = 1_000;
    private static final Comparator<MbmFinding> ORDER = Comparator
            .comparingInt(MbmFinding::line)
            .thenComparingInt(finding -> finding.column() == null ? 0 : finding.column());

    private final List<MbmFinding> errors = new ArrayList<>();
    private final List<MbmFinding> warnings = new ArrayList<>();

    void error(int line, Integer column, MbmCode code, String attribute) {
        if (errors.size() < COLLECT_CAP) {
            errors.add(new MbmFinding(line, column, code, attribute));
        }
    }

    void warning(int line, Integer column, MbmCode code) {
        if (warnings.size() < COLLECT_CAP) {
            warnings.add(new MbmFinding(line, column, code, null));
        }
    }

    int errorCount() {
        return errors.size();
    }

    boolean hasErrors() {
        return !errors.isEmpty();
    }

    /** Errors ordered by (line, column), at most {@link #MAX_ERRORS}; the sort is stable. */
    List<MbmFinding> sortedErrors() {
        return errors.stream().sorted(ORDER).limit(MAX_ERRORS).toList();
    }

    List<MbmFinding> sortedWarnings() {
        return warnings.stream().sorted(ORDER).toList();
    }
}
