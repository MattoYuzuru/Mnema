package app.mnema.learning.study.attempt;

import app.mnema.learning.capability.SemanticAssessmentProvider.CriterionGrade;
import app.mnema.learning.capability.SemanticAssessmentProvider.Flag;
import app.mnema.learning.capability.SemanticAssessmentProvider.Run;
import app.mnema.learning.capability.SemanticAssessmentProvider.Verdict;
import app.mnema.learning.catalog.exercise.Rubric;
import app.mnema.learning.catalog.exercise.Rubric.Tier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The versioned, pure aggregation policy {@code ai-semantic-v1}: the model's verdicts per rubric point and a
 * {@link SemanticStrictness} in, a judgement (or provider uncertainty) out. It reads no clock, database or provider.
 *
 * <p>Thresholds (research §5.3, normative), over the merged verdicts of the runs:
 * <table>
 *   <caption>Judgement by strictness</caption>
 *   <tr><th>level</th><th>COMPLETE</th><th>PARTIAL</th></tr>
 *   <tr><td>S1</td><td>every CORE at least PARTLY and at least one CORE MET</td><td>at least one CORE at least PARTLY</td></tr>
 *   <tr><td>S2</td><td>every CORE MET and at least 50% of the DETAIL weight MET</td><td>every CORE at least PARTLY</td></tr>
 *   <tr><td>S3</td><td>every CORE MET, at least 80% of the DETAIL weight, every TERM MET</td><td>every CORE at least PARTLY</td></tr>
 * </table>
 * Anything else is INSUFFICIENT. At every level an off-topic answer, any CONTRADICTED point, or no CORE point at least PARTLY is
 * INSUFFICIENT («рецепт блинов» is not accepted at S1 either). An UNCLEAR point outside CORE counts as not met.
 *
 * <p>Provider uncertainty is not a judgement: garbled speech, passes that disagree on the off-topic flag or on a CORE point by more
 * than one step (MET and NOT_MET, say), and an UNCLEAR CORE point are {@link Uncertain}, and the learner rates themselves. A
 * contradiction or an off-topic answer that both passes agree on is certain, whatever else is unclear. When the passes differ
 * by one step the lower verdict stands.
 */
final class SemanticPolicy {
    static final String ID = "ai-semantic-v1";
    static final String RUBRIC = "RUBRIC_V1";

    private SemanticPolicy() { }

    enum Judgement { COMPLETE, PARTIAL, INSUFFICIENT }

    /** Why the grade is not a result. */
    enum Reason { ASR_GARBLED, RUNS_DISAGREE, CORE_UNCLEAR }

    record Covered(UUID criterionId, String description, String quote, boolean partial) { }

    record Missing(UUID criterionId, String description, boolean partial) { }

    record Contradicted(UUID criterionId, String description, String note) { }

    sealed interface Outcome { }

    /** A judgement with what the learner is shown; {@code injection} records that the answer addressed the grader. */
    record Graded(Judgement judgement, List<Covered> covered, List<Missing> missing, List<Contradicted> contradicted,
                  boolean injection) implements Outcome {
        AttemptEvaluation.Result result() {
            return switch (judgement) {
                case COMPLETE -> AttemptEvaluation.Result.CORRECT;
                case PARTIAL -> AttemptEvaluation.Result.PARTIAL;
                case INSUFFICIENT -> AttemptEvaluation.Result.INCORRECT;
            };
        }
    }

    record Uncertain(Reason reason) implements Outcome { }

    private record Merged(Rubric.Criterion point, Verdict verdict, String quote, String note) { }

    static Outcome aggregate(Rubric rubric, SemanticStrictness strictness, List<Run> runs) {
        if (runs.isEmpty()) throw new IllegalArgumentException("A grade needs at least one run");
        boolean injection = runs.stream().anyMatch(run -> run.flags().contains(Flag.INJECTION));
        if (runs.stream().anyMatch(run -> run.flags().contains(Flag.ASR_GARBLED))) return new Uncertain(Reason.ASR_GARBLED);
        boolean offTopic = runs.stream().anyMatch(run -> run.flags().contains(Flag.OFF_TOPIC));
        if (offTopic != runs.stream().allMatch(run -> run.flags().contains(Flag.OFF_TOPIC))) {
            return new Uncertain(Reason.RUNS_DISAGREE);
        }
        List<Merged> merged = new ArrayList<>();
        boolean coreDisagree = false;
        for (int index = 0; index < rubric.criteria().size(); index++) {
            Rubric.Criterion point = rubric.criteria().get(index);
            List<CriterionGrade> grades = new ArrayList<>();
            for (Run run : runs) grades.add(run.criteria().get(index));
            Merged one = merge(point, grades);
            if (point.tier() == Tier.CORE && spread(grades) > 1) coreDisagree = true;
            merged.add(one);
        }
        List<Merged> core = merged.stream().filter(point -> point.point().tier() == Tier.CORE).toList();
        boolean agreedContradiction = false;
        for (int index = 0; index < rubric.criteria().size(); index++) {
            int point = index;
            agreedContradiction |= runs.stream().allMatch(run -> run.criteria().get(point).verdict() == Verdict.CONTRADICTED);
        }
        if (offTopic || agreedContradiction) return graded(Judgement.INSUFFICIENT, merged, injection);
        if (coreDisagree) return new Uncertain(Reason.RUNS_DISAGREE);
        if (merged.stream().anyMatch(point -> point.verdict() == Verdict.CONTRADICTED)) {
            return graded(Judgement.INSUFFICIENT, merged, injection);
        }
        if (core.stream().anyMatch(point -> point.verdict() == Verdict.UNCLEAR)) return new Uncertain(Reason.CORE_UNCLEAR);
        if (core.stream().noneMatch(point -> credited(point.verdict()))) return graded(Judgement.INSUFFICIENT, merged, injection);
        boolean allCoreCredited = core.stream().allMatch(point -> credited(point.verdict()));
        boolean allCoreMet = core.stream().allMatch(point -> point.verdict() == Verdict.MET);
        Judgement judgement = switch (strictness) {
            case S1 -> allCoreCredited && core.stream().anyMatch(point -> point.verdict() == Verdict.MET)
                    ? Judgement.COMPLETE : Judgement.PARTIAL;
            case S2 -> allCoreMet && detailShare(merged, 50) ? Judgement.COMPLETE
                    : allCoreCredited ? Judgement.PARTIAL : Judgement.INSUFFICIENT;
            case S3 -> allCoreMet && detailShare(merged, 80) && allTermsMet(merged) ? Judgement.COMPLETE
                    : allCoreCredited ? Judgement.PARTIAL : Judgement.INSUFFICIENT;
        };
        return graded(judgement, merged, injection);
    }

    private static boolean credited(Verdict verdict) { return verdict == Verdict.MET || verdict == Verdict.PARTLY; }

    /** The lower of the verdicts (CONTRADICTED lowest); UNCLEAR in any pass is UNCLEAR. */
    private static Merged merge(Rubric.Criterion point, List<CriterionGrade> grades) {
        if (grades.stream().anyMatch(grade -> grade.verdict() == Verdict.UNCLEAR)) {
            return new Merged(point, Verdict.UNCLEAR, null, grades.getFirst().note());
        }
        CriterionGrade lowest = grades.getFirst();
        for (CriterionGrade grade : grades) if (rank(grade.verdict()) < rank(lowest.verdict())) lowest = grade;
        return new Merged(point, lowest.verdict(), lowest.quote(), lowest.note());
    }

    /** Distance between the passes on CONTRADICTED &lt; NOT_MET &lt; PARTLY &lt; MET; an UNCLEAR pass counts as 0 here (handled apart). */
    private static int spread(List<CriterionGrade> grades) {
        if (grades.stream().anyMatch(grade -> grade.verdict() == Verdict.UNCLEAR)) return 0;
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        for (CriterionGrade grade : grades) {
            low = Math.min(low, rank(grade.verdict()));
            high = Math.max(high, rank(grade.verdict()));
        }
        return high - low;
    }

    private static int rank(Verdict verdict) {
        return switch (verdict) {
            case CONTRADICTED -> 0;
            case NOT_MET, UNCLEAR -> 1;
            case PARTLY -> 2;
            case MET -> 3;
        };
    }

    /** True when at least {@code percent} of the DETAIL weight is MET (an absent tier is vacuously met). */
    private static boolean detailShare(List<Merged> merged, int percent) {
        int total = 0;
        int met = 0;
        for (Merged point : merged) {
            if (point.point().tier() != Tier.DETAIL) continue;
            total += point.point().weight();
            if (point.verdict() == Verdict.MET) met += point.point().weight();
        }
        return total == 0 || met * 100 >= percent * total;
    }

    private static boolean allTermsMet(List<Merged> merged) {
        return merged.stream().filter(point -> point.point().tier() == Tier.TERM)
                .allMatch(point -> point.verdict() == Verdict.MET);
    }

    private static Graded graded(Judgement judgement, List<Merged> merged, boolean injection) {
        List<Covered> covered = new ArrayList<>();
        List<Missing> missing = new ArrayList<>();
        List<Contradicted> contradicted = new ArrayList<>();
        for (Merged point : merged) {
            Rubric.Criterion criterion = point.point();
            switch (point.verdict()) {
                case MET -> covered.add(new Covered(criterion.criterionId(), criterion.description(), point.quote(), false));
                case PARTLY -> {
                    covered.add(new Covered(criterion.criterionId(), criterion.description(), point.quote(), true));
                    missing.add(new Missing(criterion.criterionId(), criterion.description(), true));
                }
                case NOT_MET, UNCLEAR -> missing.add(new Missing(criterion.criterionId(), criterion.description(), false));
                case CONTRADICTED -> contradicted.add(new Contradicted(criterion.criterionId(), criterion.description(),
                        point.note()));
            }
        }
        return new Graded(judgement, covered, missing, contradicted, injection);
    }
}
