package app.mnema.learning.ai.eval;

import app.mnema.learning.ai.AiResult;
import app.mnema.learning.ai.EvalStack;
import app.mnema.learning.ai.TextGeneration;
import app.mnema.learning.ai.TextRequest;
import app.mnema.learning.ai.TextResponse;
import app.mnema.learning.ai.Usage;
import app.mnema.learning.ai.eval.GoldenCorpus.Fixture;
import app.mnema.learning.ai.eval.GoldenCorpus.Kind;
import app.mnema.learning.ai.eval.GoldenJudge.Judgement;
import app.mnema.learning.generation.GoldenPipeline.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the golden eval runner honest in CI without a network: a small spread of the real corpus goes through the real pipeline pieces on the
 * Stub route and two heuristic judges, the report and the owner sample are written, and the judge and text arithmetic is checked on scripted
 * answers. The live run (needs keys) is {@link GoldenEvalRunner}.
 */
class GoldenEvalSmokeTest {
    @TempDir
    Path directory;

    @Test
    void aSpreadOfTheCorpusRunsEndToEndOnTheStub() throws IOException {
        GoldenEval.Options options = new GoldenEval.Options(GoldenEval.Mode.STUB, GoldenEval.allKinds(), java.util.Set.of(), java.util.Set.of(), false, 5, 3, 0, directory);
        GoldenEval.Outcome outcome;
        try (EvalStack stack = EvalStack.create(false)) {
            outcome = GoldenEval.run(options, stack.text(), Duration.ofSeconds(30),
                    List.of(new GoldenJudge.Heuristic("judge-a", 0.0), new GoldenJudge.Heuristic("judge-b", 0.5)), "stub");
        }
        assertThat(outcome.rows()).hasSize(20);
        assertThat(outcome.rows()).allSatisfy(row -> assertThat(row.result()).as(row.fixture().id() + " " + row.error()).isNotNull());
        // the Stub builds a MATCH or an ORDER from the words of the first block, which is a one-word title or a script without spaces, and then
        // falls back to a self-check: the one failure it may have (a real model is not limited so)
        assertThat(outcome.rows().stream().filter(row -> !row.result().valid())).allSatisfy(row -> {
            assertThat(row.fixture().kind()).isEqualTo(Kind.EXERCISE);
            assertThat(row.result().failure()).isEqualTo("EXERCISE:MECHANIC_NOT_ALLOWED");
        });
        JsonNode report = outcome.report();
        assertThat(report.path("overall").path("fixtures").intValue()).isEqualTo(20);
        assertThat(report.path("overall").path("validAfterRepairRate").doubleValue()).isGreaterThanOrEqualTo(0.8);
        assertThat(report.path("byKind").propertyNames()).containsExactlyInAnyOrder("material-from-notes", "material-from-prompt", "edit", "exercise");
        assertThat(report.path("thresholds")).isNotEmpty();
        assertThat(report.path("judging").path("interJudge").path("fixturesJudgedByBoth").intValue()).isGreaterThanOrEqualTo(18);
        assertThat(report.path("boundaries")).isNotEmpty();
        // the report names ids and numbers, never an input or an output
        String json = Files.readString(directory.resolve("report.json"));
        outcome.rows().stream().filter(row -> row.result().valid()).forEach(row -> assertThat(json).doesNotContain(row.result().output().strip()));
        assertThat(Files.readString(directory.resolve("report.md"))).contains("## Thresholds").contains("## Boundaries of the corpus");
        String review = Files.readString(directory.resolve("owner-review.md"));
        assertThat(review).contains("- [ ] accept   - [ ] reject").containsPattern("Sample: \\d+ of 20 fixtures");
    }

    @Test
    void theStubOutputsKeepTheDeterministicChecksMeaningful() {
        GoldenEval.Options options = new GoldenEval.Options(GoldenEval.Mode.STUB, java.util.Set.of(Kind.EDIT), java.util.Set.of(), java.util.Set.of(), true, 0, 2, 0, directory);
        List<Fixture> selected = GoldenEval.select(options);
        assertThat(selected).hasSize(18).allMatch(Fixture::heldOut).allMatch(fixture -> fixture.kind() == Kind.EDIT);
    }

    // ----------------------------------------------------------------------------------------------- the judge

    private static final Fixture MATERIAL = GoldenCorpus.load().stream().filter(fixture -> fixture.kind() == Kind.MATERIAL_FROM_NOTES).findFirst().orElseThrow();
    private static final Fixture EDIT = GoldenCorpus.load().stream().filter(fixture -> fixture.kind() == Kind.EDIT).findFirst().orElseThrow();
    private static final Fixture EXERCISE = GoldenCorpus.load().stream().filter(fixture -> fixture.kind() == Kind.EXERCISE).findFirst().orElseThrow();

    private static Result result(Fixture fixture) {
        return new Result(fixture, true, true, 1, false, "", List.of(10L), 0, 0, 0, 0, "# Title\n\nText.", "Before text.", 1, 1, Map.of());
    }

    /** A model that returns the scripted answers in order. */
    private static GoldenJudge judge(String... answers) {
        Queue<String> queue = new ArrayDeque<>(List.of(answers));
        TextGeneration text = request -> AiResult.ok(new TextResponse(queue.isEmpty() ? "not json" : queue.remove(), TextResponse.FinishReason.STOP,
                new Usage(100, 0, 100, 20), 1_000, null, new TextResponse.RouteUsed("openrouter", "judge-model")));
        return new GoldenJudge.LlmJudge("judge-model", text, Duration.ofSeconds(5));
    }

    @Test
    void aMaterialIsAcceptableOnlyWithGoodScoresAndNoCriticalError() {
        Judgement good = judge("{\"facts\":5,\"faithfulness\":5,\"language\":4,\"usefulness\":4,\"criticalError\":false,\"reason\":\"ok\"}").judge(MATERIAL, result(MATERIAL));
        assertThat(good.acceptable()).isTrue();
        assertThat(good.scores()).containsEntry("facts", 5.0);
        assertThat(good.costMicros()).isEqualTo(1_000);

        Judgement weak = judge("{\"facts\":3,\"faithfulness\":5,\"language\":5,\"usefulness\":5,\"criticalError\":false}").judge(MATERIAL, result(MATERIAL));
        assertThat(weak.acceptable()).as("a missing fact is not acceptable").isFalse();

        Judgement critical = judge("{\"facts\":5,\"faithfulness\":5,\"language\":5,\"usefulness\":5,\"criticalError\":true}").judge(MATERIAL, result(MATERIAL));
        assertThat(critical.acceptable()).isFalse();
        assertThat(critical.criticalError()).isTrue();
    }

    @Test
    void anUnreadableAnswerIsAskedAgainOnceThenNoVerdict() {
        Judgement second = judge("this is not json", "```json\n{\"facts\":5,\"faithfulness\":5,\"language\":5,\"usefulness\":5,\"criticalError\":false}\n```").judge(MATERIAL, result(MATERIAL));
        assertThat(second.answered()).isTrue();
        assertThat(second.calls()).isEqualTo(2);
        Judgement none = judge("nope", "still nope").judge(MATERIAL, result(MATERIAL));
        assertThat(none.answered()).isFalse();
        assertThat(none.calls()).isEqualTo(2);
    }

    @Test
    void exercisesAreScoredOnTheirOwnDimensions() {
        Judgement verdict = judge("{\"correctness\":5,\"unambiguity\":4,\"quality\":4,\"language\":5,\"criticalError\":false}").judge(EXERCISE, result(EXERCISE));
        assertThat(verdict.acceptable()).isTrue();
        assertThat(verdict.scores()).containsKeys("correctness", "unambiguity", "quality", "language");
    }

    @Test
    void anEditIsAPairwiseCheckInBothOrders() {
        // order 1 shows (original, rewrite): B wins; order 2 shows (rewrite, original): A wins -> the rewrite wins in both
        Judgement win = judge(
                "{\"follows\":{\"A\":2,\"B\":5},\"factsKept\":{\"A\":true,\"B\":true},\"criticalError\":{\"A\":false,\"B\":false},\"winner\":\"B\"}",
                "{\"follows\":{\"A\":4,\"B\":2},\"factsKept\":{\"A\":true,\"B\":true},\"criticalError\":{\"A\":false,\"B\":false},\"winner\":\"A\"}").judge(EDIT, result(EDIT));
        assertThat(win.pairwise()).isEqualTo("WIN");
        assertThat(win.acceptable()).isTrue();
        assertThat(win.scores()).containsEntry("follows", 4.5);
        assertThat(win.calls()).isEqualTo(2);

        // the judge always picks position A: that is a bias, not a verdict
        Judgement biased = judge(
                "{\"follows\":{\"A\":5,\"B\":5},\"factsKept\":{\"A\":true,\"B\":true},\"criticalError\":{\"A\":false,\"B\":false},\"winner\":\"A\"}",
                "{\"follows\":{\"A\":5,\"B\":5},\"factsKept\":{\"A\":true,\"B\":true},\"criticalError\":{\"A\":false,\"B\":false},\"winner\":\"A\"}").judge(EDIT, result(EDIT));
        assertThat(biased.pairwise()).isEqualTo("POSITION_BIASED");
        assertThat(biased.acceptable()).isFalse();

        // the rewrite loses its facts, and one order flags a critical error
        Judgement lost = judge(
                "{\"follows\":{\"A\":4,\"B\":5},\"factsKept\":{\"A\":true,\"B\":false},\"criticalError\":{\"A\":false,\"B\":true},\"winner\":\"B\"}",
                "{\"follows\":{\"A\":5,\"B\":4},\"factsKept\":{\"A\":false,\"B\":true},\"criticalError\":{\"A\":true,\"B\":false},\"winner\":\"A\"}").judge(EDIT, result(EDIT));
        assertThat(lost.acceptable()).isFalse();
        assertThat(lost.criticalError()).isTrue();
    }

    // ------------------------------------------------------------------------------------------------ the text

    @Test
    void theCopyRunCountsSharedWordsAndCharacters() {
        List<String> exemplar = GoldenText.tokens("Настаивайте две–четыре минуты и не перестаивайте.", false);
        List<String> copy = GoldenText.tokens("Совет: настаивайте две четыре минуты и не перестаивайте, иначе горько", false);
        assertThat(GoldenText.longestCommonRun(copy, exemplar)).isEqualTo(7);
        assertThat(GoldenText.longestCommonRun(GoldenText.tokens("совсем другое", false), exemplar)).isZero();
        // Japanese is counted in characters, kana and kanji alike, and Latin words stay words
        assertThat(GoldenText.tokens("お茶はtea", true)).containsExactly("お", "茶", "は", "tea");
        assertThat(GoldenText.units("緑茶：約80℃。", true)).isEqualTo(4);
    }
}
