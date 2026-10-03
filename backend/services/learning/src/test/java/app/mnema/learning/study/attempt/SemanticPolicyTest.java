package app.mnema.learning.study.attempt;

import app.mnema.learning.capability.SemanticAssessmentProvider.CriterionGrade;
import app.mnema.learning.capability.SemanticAssessmentProvider.Flag;
import app.mnema.learning.capability.SemanticAssessmentProvider.Run;
import app.mnema.learning.capability.SemanticAssessmentProvider.Verdict;
import app.mnema.learning.catalog.exercise.Rubric;
import app.mnema.learning.catalog.exercise.Rubric.Tier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static app.mnema.learning.capability.SemanticAssessmentProvider.Verdict.CONTRADICTED;
import static app.mnema.learning.capability.SemanticAssessmentProvider.Verdict.MET;
import static app.mnema.learning.capability.SemanticAssessmentProvider.Verdict.NOT_MET;
import static app.mnema.learning.capability.SemanticAssessmentProvider.Verdict.PARTLY;
import static app.mnema.learning.capability.SemanticAssessmentProvider.Verdict.UNCLEAR;
import static app.mnema.learning.study.attempt.SemanticPolicy.Judgement.COMPLETE;
import static app.mnema.learning.study.attempt.SemanticPolicy.Judgement.INSUFFICIENT;
import static app.mnema.learning.study.attempt.SemanticPolicy.Judgement.PARTIAL;
import static app.mnema.learning.study.attempt.SemanticStrictness.S1;
import static app.mnema.learning.study.attempt.SemanticStrictness.S2;
import static app.mnema.learning.study.attempt.SemanticStrictness.S3;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The versioned policy {@code ai-semantic-v1}: the strictness table, the overrides, provider uncertainty and the mapping to evidence. */
class SemanticPolicyTest {
    // c1, c2 CORE (weight 3 each); d1 (weight 2), d2 (weight 1) DETAIL; t1 TERM
    private static final Rubric RUBRIC = rubric(3, 3, 2, 1);
    private static final Rubric EVEN_DETAIL = rubric(3, 3, 1, 1);

    private static Rubric rubric(int core1, int core2, int detail1, int detail2) {
        return new Rubric("Эталон", List.of(
                new Rubric.Criterion(UUID.randomUUID(), "c1", Tier.CORE, core1),
                new Rubric.Criterion(UUID.randomUUID(), "c2", Tier.CORE, core2),
                new Rubric.Criterion(UUID.randomUUID(), "d1", Tier.DETAIL, detail1),
                new Rubric.Criterion(UUID.randomUUID(), "d2", Tier.DETAIL, detail2),
                new Rubric.Criterion(UUID.randomUUID(), "t1", Tier.TERM, 1)), List.of("Ошибка"), List.of("синоним"));
    }

    private static Run run(Rubric rubric, Set<Flag> flags, Verdict... verdicts) {
        List<CriterionGrade> grades = new ArrayList<>();
        for (int index = 0; index < verdicts.length; index++) {
            boolean quote = verdicts[index] == MET || verdicts[index] == PARTLY;
            grades.add(new CriterionGrade(rubric.criteria().get(index).criterionId(), verdicts[index],
                    quote ? "цитата " + (index + 1) : null, "заметка " + (index + 1)));
        }
        return new Run(grades, flags);
    }

    private static Run run(Verdict... verdicts) { return run(RUBRIC, Set.of(), verdicts); }

    private static SemanticPolicy.Judgement judge(SemanticStrictness strictness, Verdict... verdicts) {
        SemanticPolicy.Outcome outcome = SemanticPolicy.aggregate(RUBRIC, strictness, List.of(run(verdicts)));
        assertThat(outcome).isInstanceOf(SemanticPolicy.Graded.class);
        return ((SemanticPolicy.Graded) outcome).judgement();
    }

    // ------------------------------------------------------------------------------------------- the table

    @Test
    void s1IsLenient() {
        assertThat(judge(S1, MET, PARTLY, NOT_MET, NOT_MET, NOT_MET)).as("every core at least partly, one met").isEqualTo(COMPLETE);
        assertThat(judge(S1, MET, MET, NOT_MET, NOT_MET, NOT_MET)).isEqualTo(COMPLETE);
        assertThat(judge(S1, PARTLY, PARTLY, MET, MET, MET)).as("no core met: only partial").isEqualTo(PARTIAL);
        assertThat(judge(S1, MET, NOT_MET, MET, MET, MET)).as("one core missing: partial").isEqualTo(PARTIAL);
        assertThat(judge(S1, NOT_MET, PARTLY, NOT_MET, NOT_MET, NOT_MET)).isEqualTo(PARTIAL);
        assertThat(judge(S1, NOT_MET, NOT_MET, MET, MET, MET)).as("no core point at all").isEqualTo(INSUFFICIENT);
    }

    @Test
    void s2NeedsEveryCorePointAndHalfOfTheDetailWeight() {
        assertThat(judge(S2, MET, MET, MET, NOT_MET, NOT_MET)).as("2 of 3 detail weight").isEqualTo(COMPLETE);
        assertThat(judge(S2, MET, MET, NOT_MET, MET, NOT_MET)).as("1 of 3 detail weight").isEqualTo(PARTIAL);
        assertThat(judge(S2, MET, PARTLY, MET, MET, MET)).as("a core point only partly").isEqualTo(PARTIAL);
        assertThat(judge(S2, PARTLY, PARTLY, MET, MET, MET)).isEqualTo(PARTIAL);
        assertThat(judge(S2, MET, NOT_MET, MET, MET, MET)).as("a core point missing").isEqualTo(INSUFFICIENT);
        assertThat(judge(S2, MET, MET, PARTLY, PARTLY, NOT_MET)).as("partly is not met for the detail weight").isEqualTo(PARTIAL);
        // exactly 50 % is enough
        SemanticPolicy.Outcome half = SemanticPolicy.aggregate(EVEN_DETAIL, S2,
                List.of(run(EVEN_DETAIL, Set.of(), MET, MET, MET, NOT_MET, NOT_MET)));
        assertThat(((SemanticPolicy.Graded) half).judgement()).isEqualTo(COMPLETE);
    }

    @Test
    void s3NeedsEveryCoreEightyPercentOfDetailAndEveryTerm() {
        assertThat(judge(S3, MET, MET, MET, MET, MET)).isEqualTo(COMPLETE);
        assertThat(judge(S3, MET, MET, MET, MET, NOT_MET)).as("a term missing").isEqualTo(PARTIAL);
        assertThat(judge(S3, MET, MET, MET, MET, PARTLY)).as("a term only partly").isEqualTo(PARTIAL);
        assertThat(judge(S3, MET, MET, MET, NOT_MET, MET)).as("2 of 3 is below 80 %").isEqualTo(PARTIAL);
        assertThat(judge(S3, MET, PARTLY, MET, MET, MET)).isEqualTo(PARTIAL);
        assertThat(judge(S3, MET, NOT_MET, MET, MET, MET)).isEqualTo(INSUFFICIENT);
        // 80 % exactly: weights 4 + 1, only the 4 met
        Rubric eighty = new Rubric("Эталон", List.of(
                new Rubric.Criterion(UUID.randomUUID(), "c1", Tier.CORE, 1), new Rubric.Criterion(UUID.randomUUID(), "c2", Tier.CORE, 1),
                new Rubric.Criterion(UUID.randomUUID(), "d1", Tier.DETAIL, 3), new Rubric.Criterion(UUID.randomUUID(), "d2", Tier.DETAIL, 3),
                new Rubric.Criterion(UUID.randomUUID(), "d3", Tier.DETAIL, 3), new Rubric.Criterion(UUID.randomUUID(), "d4", Tier.DETAIL, 3),
                new Rubric.Criterion(UUID.randomUUID(), "d5", Tier.DETAIL, 3)), List.of(), List.of());
        assertThat(((SemanticPolicy.Graded) SemanticPolicy.aggregate(eighty, S3,
                List.of(run(eighty, Set.of(), MET, MET, MET, MET, MET, MET, NOT_MET)))).judgement()).as("12 of 15 = 80 %").isEqualTo(COMPLETE);
        assertThat(((SemanticPolicy.Graded) SemanticPolicy.aggregate(eighty, S3,
                List.of(run(eighty, Set.of(), MET, MET, MET, MET, MET, NOT_MET, NOT_MET)))).judgement()).as("9 of 15").isEqualTo(PARTIAL);
        // a rubric without TERM and DETAIL points is vacuously complete on those tiers
        Rubric coreOnly = new Rubric("Эталон", List.of(new Rubric.Criterion(UUID.randomUUID(), "c1", Tier.CORE, 1),
                new Rubric.Criterion(UUID.randomUUID(), "c2", Tier.CORE, 1)), List.of(), List.of());
        assertThat(((SemanticPolicy.Graded) SemanticPolicy.aggregate(coreOnly, S3, List.of(run(coreOnly, Set.of(), MET, MET)))).judgement())
                .isEqualTo(COMPLETE);
    }

    @ParameterizedTest
    @EnumSource(SemanticStrictness.class)
    void anOffTopicAnswerAKnownContradictionOrNoCorePointIsInsufficientAtEveryLevel(SemanticStrictness strictness) {
        SemanticPolicy.Outcome offTopic = SemanticPolicy.aggregate(RUBRIC, strictness,
                List.of(run(RUBRIC, Set.of(Flag.OFF_TOPIC), MET, MET, MET, MET, MET)));
        assertThat(((SemanticPolicy.Graded) offTopic).judgement()).as("OFF_TOPIC wins over any verdict").isEqualTo(INSUFFICIENT);
        assertThat(judge(strictness, MET, MET, MET, MET, CONTRADICTED)).as("a contradicted term").isEqualTo(INSUFFICIENT);
        assertThat(judge(strictness, CONTRADICTED, MET, MET, MET, MET)).isEqualTo(INSUFFICIENT);
        assertThat(judge(strictness, NOT_MET, NOT_MET, MET, MET, MET)).as("no core point credited").isEqualTo(INSUFFICIENT);
        // the pancake recipe: nothing is met and the model flags it
        SemanticPolicy.Outcome pancakes = SemanticPolicy.aggregate(RUBRIC, strictness,
                List.of(run(RUBRIC, Set.of(Flag.OFF_TOPIC), NOT_MET, NOT_MET, NOT_MET, NOT_MET, NOT_MET)));
        SemanticPolicy.Graded graded = (SemanticPolicy.Graded) pancakes;
        assertThat(graded.judgement()).isEqualTo(INSUFFICIENT);
        assertThat(graded.covered()).isEmpty();
        assertThat(graded.missing()).hasSize(5);
    }

    // ----------------------------------------------------------------------------------------- uncertainty

    @Test
    void providerUncertaintyIsNeverAResult() {
        assertThat(SemanticPolicy.aggregate(RUBRIC, S1, List.of(run(MET, UNCLEAR, MET, MET, MET)))).isEqualTo(
                new SemanticPolicy.Uncertain(SemanticPolicy.Reason.CORE_UNCLEAR));
        assertThat(SemanticPolicy.aggregate(RUBRIC, S1, List.of(run(RUBRIC, Set.of(Flag.ASR_GARBLED), MET, MET, MET, MET, MET)))).isEqualTo(
                new SemanticPolicy.Uncertain(SemanticPolicy.Reason.ASR_GARBLED));
        // garbled speech is uncertain even when the answer also looks off topic
        assertThat(SemanticPolicy.aggregate(RUBRIC, S2, List.of(run(RUBRIC, Set.of(Flag.ASR_GARBLED, Flag.OFF_TOPIC), NOT_MET,
                NOT_MET, NOT_MET, NOT_MET, NOT_MET), run(NOT_MET, NOT_MET, NOT_MET, NOT_MET, NOT_MET))))
                .isEqualTo(new SemanticPolicy.Uncertain(SemanticPolicy.Reason.ASR_GARBLED));
        // an unclear point outside the core only counts as not met
        SemanticPolicy.Graded detailUnclear = (SemanticPolicy.Graded) SemanticPolicy.aggregate(RUBRIC, S2,
                List.of(run(MET, MET, UNCLEAR, MET, MET)));
        assertThat(detailUnclear.judgement()).isEqualTo(PARTIAL);
        assertThat(detailUnclear.missing()).extracting(SemanticPolicy.Missing::description).contains("d1");
        // a contradiction both runs agree on is certain, whatever else is unclear
        assertThat(((SemanticPolicy.Graded) SemanticPolicy.aggregate(RUBRIC, S2, List.of(run(CONTRADICTED, UNCLEAR, MET, MET, MET),
                run(CONTRADICTED, MET, MET, MET, MET)))).judgement()).isEqualTo(INSUFFICIENT);
        // an unclear core point after a clear contradiction elsewhere in one run only is uncertain
        assertThat(SemanticPolicy.aggregate(RUBRIC, S1, List.of(run(MET, UNCLEAR, MET, MET, MET)))).isInstanceOf(SemanticPolicy.Uncertain.class);
    }

    @Test
    void twoRunsAgreeOrTheLowerVerdictStandsOrTheyDisagreeToUncertainty() {
        // a difference of one step: the lower verdict stands
        SemanticPolicy.Graded lower = (SemanticPolicy.Graded) SemanticPolicy.aggregate(RUBRIC, S2,
                List.of(run(MET, MET, MET, MET, MET), run(MET, PARTLY, MET, MET, MET)));
        assertThat(lower.judgement()).as("the second core point is only partly in one run").isEqualTo(PARTIAL);
        assertThat(lower.covered()).filteredOn(SemanticPolicy.Covered::partial).hasSize(1);
        // MET against NOT_MET on a core point: the runs disagree
        assertThat(SemanticPolicy.aggregate(RUBRIC, S2, List.of(run(MET, MET, MET, MET, MET), run(NOT_MET, MET, MET, MET, MET))))
                .isEqualTo(new SemanticPolicy.Uncertain(SemanticPolicy.Reason.RUNS_DISAGREE));
        assertThat(SemanticPolicy.aggregate(RUBRIC, S3, List.of(run(MET, PARTLY, MET, MET, MET), run(MET, CONTRADICTED, MET, MET, MET))))
                .as("PARTLY against CONTRADICTED is two steps").isEqualTo(new SemanticPolicy.Uncertain(SemanticPolicy.Reason.RUNS_DISAGREE));
        // one run says off topic, the other does not
        assertThat(SemanticPolicy.aggregate(RUBRIC, S2, List.of(run(RUBRIC, Set.of(Flag.OFF_TOPIC), NOT_MET, NOT_MET, NOT_MET, NOT_MET, NOT_MET),
                run(NOT_MET, NOT_MET, NOT_MET, NOT_MET, NOT_MET)))).isEqualTo(new SemanticPolicy.Uncertain(SemanticPolicy.Reason.RUNS_DISAGREE));
        // both runs off topic: certain
        assertThat(((SemanticPolicy.Graded) SemanticPolicy.aggregate(RUBRIC, S3, List.of(
                run(RUBRIC, Set.of(Flag.OFF_TOPIC), NOT_MET, NOT_MET, NOT_MET, NOT_MET, NOT_MET),
                run(RUBRIC, Set.of(Flag.OFF_TOPIC), NOT_MET, NOT_MET, NOT_MET, NOT_MET, NOT_MET)))).judgement()).isEqualTo(INSUFFICIENT);
        // outside the core any difference is just the lower verdict, with no uncertainty
        SemanticPolicy.Graded detail = (SemanticPolicy.Graded) SemanticPolicy.aggregate(RUBRIC, S2,
                List.of(run(MET, MET, MET, MET, MET), run(MET, MET, NOT_MET, NOT_MET, MET)));
        assertThat(detail.judgement()).isEqualTo(PARTIAL);
        // NOT_MET against CONTRADICTED is one step, the lower one stands and a contradiction is INSUFFICIENT
        assertThat(((SemanticPolicy.Graded) SemanticPolicy.aggregate(RUBRIC, S2, List.of(run(MET, MET, MET, NOT_MET, MET),
                run(MET, MET, MET, CONTRADICTED, MET)))).judgement()).isEqualTo(INSUFFICIENT);
        assertThatThrownBy(() -> SemanticPolicy.aggregate(RUBRIC, S1, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFeedbackListsSayWhatTheLearnerHadAndWhatIsMissing() {
        SemanticPolicy.Graded graded = (SemanticPolicy.Graded) SemanticPolicy.aggregate(RUBRIC, S1,
                List.of(run(RUBRIC, Set.of(Flag.INJECTION), MET, PARTLY, NOT_MET, UNCLEAR, MET)));
        assertThat(graded.injection()).isTrue();
        assertThat(graded.covered()).extracting(SemanticPolicy.Covered::description, SemanticPolicy.Covered::quote, SemanticPolicy.Covered::partial)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("c1", "цитата 1", false), org.assertj.core.groups.Tuple.tuple("c2", "цитата 2", true),
                        org.assertj.core.groups.Tuple.tuple("t1", "цитата 5", false));
        assertThat(graded.missing()).extracting(SemanticPolicy.Missing::description, SemanticPolicy.Missing::partial)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("c2", true), org.assertj.core.groups.Tuple.tuple("d1", false),
                        org.assertj.core.groups.Tuple.tuple("d2", false));
        SemanticPolicy.Graded contradicted = (SemanticPolicy.Graded) SemanticPolicy.aggregate(RUBRIC, S1,
                List.of(run(MET, MET, CONTRADICTED, MET, MET)));
        assertThat(contradicted.contradicted()).singleElement().satisfies(point -> {
            assertThat(point.description()).isEqualTo("d1");
            assertThat(point.note()).isEqualTo("заметка 3");
        });
        assertThat(contradicted.judgement()).isEqualTo(INSUFFICIENT);
        assertThat(contradicted.covered()).hasSize(4);
    }

    // --------------------------------------------------------------------------------------- the mapping

    @Test
    void theJudgementMapsToEvidenceAndNeverToHigh() {
        assertThat(S1.evidence()).isEqualTo(AttemptEvaluation.EvidenceClass.LOW);
        assertThat(S2.evidence()).isEqualTo(AttemptEvaluation.EvidenceClass.MEDIUM);
        assertThat(S3.evidence()).isEqualTo(AttemptEvaluation.EvidenceClass.MEDIUM);
        for (SemanticStrictness strictness : SemanticStrictness.values()) assertThat(strictness.evidence()).isNotEqualTo(AttemptEvaluation.EvidenceClass.HIGH);
        var result = new java.util.EnumMap<SemanticPolicy.Judgement, AttemptEvaluation.Result>(SemanticPolicy.Judgement.class);
        for (SemanticPolicy.Judgement judgement : SemanticPolicy.Judgement.values()) {
            result.put(judgement, new SemanticPolicy.Graded(judgement, List.of(), List.of(), List.of(), false).result());
        }
        assertThat(result).containsEntry(COMPLETE, AttemptEvaluation.Result.CORRECT).containsEntry(PARTIAL, AttemptEvaluation.Result.PARTIAL)
                .containsEntry(INSUFFICIENT, AttemptEvaluation.Result.INCORRECT);
        assertThat(result.values()).as("a provider never produces UNSURE").doesNotContain(AttemptEvaluation.Result.UNSURE);
        assertThat(SemanticPolicy.ID).isEqualTo("ai-semantic-v1");
        assertThat(SemanticPolicy.RUBRIC).isEqualTo("RUBRIC_V1");
        assertThat(EnumSet.allOf(SemanticPolicy.Reason.class)).hasSize(3);
    }

    // ------------------------------------------------------------------------------------------ strictness

    @ParameterizedTest
    @CsvSource({
            // assessed, level, streak, firstAttemptAtExercise, expected
            "false, 0, 0, true,  S1",
            "false, 0, 0, false, S1",
            "true,  0, 0, false, S1",
            "true,  1, 0, false, S1",
            "true,  1, 1, true,  S1",
            "true,  2, 0, false, S2",
            "true,  3, 1, false, S2",
            "true,  2, 2, false, S3",
            "true,  3, 3, false, S3",
            "true,  4, 0, false, S3",
            "true,  7, 5, false, S3",
            "true,  4, 0, true,  S2",
            "true,  2, 2, true,  S2",
            "true,  6, 6, true,  S2",
            "true,  3, 0, true,  S2",
            "false, 5, 5, true,  S1"})
    void theStrictnessIsAFunctionOfTheObjectiveStateAndCappedForAFirstAttempt(boolean assessed, int level, int streak, boolean first,
                                                                              SemanticStrictness expected) {
        assertThat(SemanticStrictness.select(assessed, level, streak, first)).isEqualTo(expected);
    }

    @Test
    void theLenientLevelRunsOnceAndTheStrictOnesTwice() {
        assertThat(S1.runs()).isEqualTo(1);
        assertThat(S2.runs()).isEqualTo(2);
        assertThat(S3.runs()).isEqualTo(2);
        assertThat(S2.stricterThan(S1)).isTrue();
        assertThat(S3.stricterThan(S2)).isTrue();
        assertThat(S1.stricterThan(S1)).isFalse();
        assertThat(S3.stricterThan(S3)).isFalse();
        assertThat(S1.stricterThan(S3)).isFalse();
    }
}
