package app.mnema.learning.capability;

import app.mnema.learning.catalog.exercise.Rubric;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Port of the {@code ai-semantic} evaluator: the model's verdict per rubric point for one learner answer, nothing more.
 *
 * <p>A provider never judges: the final grade, the strictness level and the evidence mapping are computed by the server
 * ({@code ai-semantic-v1}), and only the baseline reducer writes {@code StudyState}. Provider uncertainty is not a result
 * either: runs that disagree, an unclear core point or garbled speech make the server send the learner to self-check, and
 * a provider failure is {@link GradeOutcome.Unavailable}, never a learner error. The implementation never throws for a
 * provider problem and never runs inside a database transaction.
 */
public interface SemanticAssessmentProvider {
    /** Grades {@code request.runs()} independent passes of one answer, within {@code request.deadline()}. */
    GradeOutcome grade(GradeRequest request);

    /** Where the answer text came from; speech is a transcript that may hold recognition errors. */
    enum AnswerSource { TYPED, SPEECH }

    /** The model's verdict for one rubric point. */
    enum Verdict { MET, PARTLY, NOT_MET, CONTRADICTED, UNCLEAR }

    /** A note about the answer as a whole. */
    enum Flag { OFF_TOPIC, INJECTION, ASR_GARBLED, WRONG_LANGUAGE, TOO_SHORT }

    /**
     * One grading request.
     *
     * @param accountId the learner, used only to derive the opaque provider user key
     * @param attemptId correlates the provider-call journal rows with the attempt (no text is journaled)
     * @param exercisePrompt the question as the learner saw it
     * @param materialFragment quoted material, empty when there is none
     * @param runs 1 for the lenient first levels, 2 when the server needs run agreement
     * @param deadline the whole grading including every run
     */
    record GradeRequest(UUID accountId, UUID attemptId, String exercisePrompt, String materialFragment, Rubric rubric,
                        String answer, AnswerSource source, String feedbackLanguage, int runs, Duration deadline) {
        public GradeRequest {
            if (runs < 1 || runs > 2) throw new IllegalArgumentException("runs is 1 or 2");
            if (deadline == null || deadline.isNegative() || deadline.isZero()) throw new IllegalArgumentException("deadline");
        }

        /** No answer, prompt or rubric text. */
        @Override
        public String toString() {
            return "GradeRequest[attemptId=" + attemptId + ", runs=" + runs + ", source=" + source + ", answerChars="
                    + (answer == null ? 0 : answer.length()) + "]";
        }
    }

    /**
     * One validated verdict. A quote is present only for {@code MET} and {@code PARTLY} and is a verbatim substring of the
     * answer (whitespace and case normalized); a verdict whose quote did not check out has already been downgraded to
     * {@code UNCLEAR}.
     */
    record CriterionGrade(UUID criterionId, Verdict verdict, String quote, String note) { }

    /** One pass: exactly one grade per rubric point, in rubric order, and the flags the model raised. */
    record Run(List<CriterionGrade> criteria, Set<Flag> flags) {
        public Run {
            criteria = List.copyOf(criteria);
            flags = Set.copyOf(flags);
        }
    }

    sealed interface GradeOutcome {
        /** Every requested run produced valid output. */
        record Graded(List<Run> runs) implements GradeOutcome {
            public Graded { runs = List.copyOf(runs); }
        }

        /** No usable grade: a provider failure, an invalid answer after the repair, an open circuit or the deadline. */
        record Unavailable(String reason) implements GradeOutcome { }
    }
}
