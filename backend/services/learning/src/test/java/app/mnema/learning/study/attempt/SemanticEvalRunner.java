package app.mnema.learning.study.attempt;

import app.mnema.learning.ai.AiProperties;
import app.mnema.learning.ai.AiResult;
import app.mnema.learning.ai.EvalStack;
import app.mnema.learning.ai.SemanticGrader;
import app.mnema.learning.ai.TextGeneration;
import app.mnema.learning.ai.UserKeys;
import app.mnema.learning.ai.prompt.PromptAssembler;
import app.mnema.learning.ai.prompt.PromptLibrary;
import app.mnema.learning.capability.SemanticAssessmentProvider.AnswerSource;
import app.mnema.learning.capability.SemanticAssessmentProvider.GradeOutcome;
import app.mnema.learning.capability.SemanticAssessmentProvider.GradeRequest;
import app.mnema.learning.capability.SemanticAssessmentProvider.Run;
import app.mnema.learning.catalog.exercise.Rubric;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline eval of the AI assessment on the golden set; skipped unless {@code MNEMA_AI_EVAL} is {@code stub} or {@code live}, so CI
 * (and {@code quality}) never run it. Run it the way the AI-02 eval is run:
 *
 * <pre>
 * MNEMA_AI_EVAL=stub ./gradlew :services:learning:cleanTest :services:learning:test --tests '*SemanticEvalRunner*'
 * MNEMA_AI_EVAL=live MNEMA_AI_DEEPSEEK_API_KEY=... ./gradlew :services:learning:cleanTest :services:learning:test --tests '*SemanticEvalRunner*'
 * </pre>
 * (Gradle does not treat the environment as a test input, so {@code cleanTest} forces a rerun; the key is read from the environment and
 * never printed or written.) Every answer of {@code contracts/study/assessment-golden} goes through the real grader and the real
 * {@code assess} route (the Stub when not live): once as a single run (the S1 path) and once as a pair of parallel runs (the S2 and S3
 * path); the server policy turns the verdicts into judgements per strictness, as in production. The report
 * {@code build/reports/assessment-eval/report.json} (and {@code report.md}) carries only identifiers and numbers, never an answer or a
 * quote: agreement with the labels and quadratic weighted kappa per strictness, false-accept of off-topic answers and bags of terms,
 * leniency, the share of self-check (provider uncertainty), latency p50 and p95 of the single run and of the pair, and cost.
 * The thresholds of research §6 are targets for the owner's gate, not assertions here: only the live structure is asserted
 * (every call answered or failed with a typed reason), plus, with the Stub, that the pipeline works end to end.
 */
@EnabledIfEnvironmentVariable(named = "MNEMA_AI_EVAL", matches = "stub|live")
class SemanticEvalRunner {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> JUDGEMENTS = List.of("INSUFFICIENT", "PARTIAL", "COMPLETE");

    /** Counts what the router reports for each call: latency, tokens, cost. */
    private static final class Metered implements TextGeneration {
        private final TextGeneration delegate;
        final AtomicLong calls = new AtomicLong();
        final AtomicLong failures = new AtomicLong();
        final AtomicLong cost = new AtomicLong();
        final AtomicLong hit = new AtomicLong();
        final AtomicLong miss = new AtomicLong();
        final AtomicLong completion = new AtomicLong();

        Metered(TextGeneration delegate) { this.delegate = delegate; }

        @Override
        public AiResult<app.mnema.learning.ai.TextResponse> generate(app.mnema.learning.ai.TextRequest request) {
            AiResult<app.mnema.learning.ai.TextResponse> result = delegate.generate(request);
            calls.incrementAndGet();
            if (result instanceof AiResult.Ok<app.mnema.learning.ai.TextResponse> ok) {
                cost.addAndGet(ok.value().costMicros());
                hit.addAndGet(ok.value().usage().cacheHitTokens());
                miss.addAndGet(ok.value().usage().cacheMissTokens());
                completion.addAndGet(ok.value().usage().completionTokens());
            } else {
                failures.incrementAndGet();
            }
            return result;
        }
    }

    private record Result(String exercise, String id, String kind, Map<String, String> expected, Map<String, String> actual) { }

    @Test
    void run() throws IOException {
        boolean live = "live".equals(System.getenv("MNEMA_AI_EVAL"));
        if (live) Assumptions.assumeTrue(System.getenv("MNEMA_AI_DEEPSEEK_API_KEY") != null
                && !System.getenv("MNEMA_AI_DEEPSEEK_API_KEY").isBlank(), "MNEMA_AI_DEEPSEEK_API_KEY is not set");
        List<Result> results = new ArrayList<>();
        List<Long> single = new ArrayList<>();
        List<Long> pair = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        Metered metered;
        try (EvalStack stack = EvalStack.create(live)) {
            metered = new Metered(stack.text());
            PromptAssembler assembler = new PromptAssembler(PromptLibrary.fromClasspath("v1"), new AiProperties.Prompt("v1", 32_000, 25_000));
            try (SemanticGrader grader = new SemanticGrader(metered, assembler,
                    UserKeys.withSecret("0123456789abcdef0123456789abcdef", "k1"), !live)) {
                for (JsonNode exercise : AssessmentGoldenPolicyTest.exercises()) {
                    Rubric rubric = AssessmentGoldenPolicyTest.rubric(exercise);
                    for (JsonNode answer : exercise.path("answers")) {
                        String id = answer.path("id").stringValue(null);
                        Map<String, String> actual = new LinkedHashMap<>();
                        AnswerSource source = AnswerSource.valueOf(answer.path("answerSource").stringValue(null));
                        GradeOutcome one = timed(grader, rubric, exercise, answer, source, 1, single);
                        GradeOutcome two = timed(grader, rubric, exercise, answer, source, 2, pair);
                        actual.put("S1", label(rubric, SemanticStrictness.S1, one, id, unavailable, source == AnswerSource.SPEECH));
                        actual.put("S2", label(rubric, SemanticStrictness.S2, two, id, unavailable, source == AnswerSource.SPEECH));
                        actual.put("S3", label(rubric, SemanticStrictness.S3, two, id, unavailable, source == AnswerSource.SPEECH));
                        Map<String, String> expected = new LinkedHashMap<>();
                        for (String level : List.of("S1", "S2", "S3")) expected.put(level, answer.path("expected").path(level).stringValue(null));
                        results.add(new Result(exercise.path("exerciseId").stringValue(null), id, answer.path("kind").stringValue(null), expected, actual));
                    }
                }
            }
        }
        ObjectNode report = report(live, results, single, pair, unavailable, metered);
        write(report);

        assertThat(results).hasSize(144);
        assertThat(metered.calls.get()).as("a single run and a pair for every answer").isGreaterThanOrEqualTo(360);
        if (!live) {
            assertThat(unavailable).as("the Stub always answers").isEmpty();
            assertThat(results).filteredOn(entry -> entry.kind().equals("off-topic")).allSatisfy(entry ->
                    assertThat(entry.actual().values()).containsOnly("INSUFFICIENT"));
        }
    }

    private static GradeOutcome timed(SemanticGrader grader, Rubric rubric, JsonNode exercise, JsonNode answer, AnswerSource source, int runs,
                                      List<Long> latencies) {
        long started = System.nanoTime();
        GradeOutcome outcome = grader.grade(new GradeRequest(UUID.nameUUIDFromBytes(answer.path("id").stringValue(null).getBytes(java.nio.charset.StandardCharsets.UTF_8)), UUID.randomUUID(),
                exercise.path("prompt").stringValue(null), "", rubric, answer.path("text").stringValue(null), source,
                exercise.path("language").stringValue(null), runs, Duration.ofSeconds(20)));
        latencies.add((System.nanoTime() - started) / 1_000_000);
        return outcome;
    }

    private static String label(Rubric rubric, SemanticStrictness strictness, GradeOutcome outcome, String id, List<String> unavailable,
                                boolean speech) {
        if (outcome instanceof GradeOutcome.Unavailable failed) {
            unavailable.add(id + ":" + strictness + ":" + failed.reason());
            return "UNAVAILABLE";
        }
        List<Run> runs = ((GradeOutcome.Graded) outcome).runs();
        SemanticPolicy.Outcome result = SemanticPolicy.aggregate(rubric, strictness, runs, speech);
        return result instanceof SemanticPolicy.Graded graded ? graded.judgement().name() : "SELF_CHECK";
    }

    // ------------------------------------------------------------------------------------------------- report

    private static ObjectNode report(boolean live, List<Result> results, List<Long> single, List<Long> pair, List<String> unavailable,
                                     Metered metered) {
        ObjectNode report = JSON.createObjectNode().put("generatedAt", Instant.now().toString()).put("mode", live ? "live" : "stub")
                .put("promptVersion", "v1").put("policy", SemanticPolicy.ID).put("answers", results.size());
        ObjectNode levels = report.putObject("strictness");
        for (String level : List.of("S1", "S2", "S3")) {
            int agree = 0;
            int graded = 0;
            int selfCheck = 0;
            int lenient = 0;
            int strict = 0;
            int expectedGraded = 0;
            double[][] matrix = new double[3][3];
            for (Result result : results) {
                String expected = result.expected().get(level);
                String actual = result.actual().get(level);
                if (actual.equals(expected)) agree++;
                if (actual.equals("SELF_CHECK") || actual.equals("UNAVAILABLE")) selfCheck++;
                int actualRank = JUDGEMENTS.indexOf(actual);
                int expectedRank = JUDGEMENTS.indexOf(expected);
                if (expectedRank >= 0) expectedGraded++;
                if (actualRank >= 0 && expectedRank >= 0) {
                    graded++;
                    matrix[expectedRank][actualRank]++;
                    if (actualRank > expectedRank) lenient++;
                    if (actualRank < expectedRank) strict++;
                }
            }
            ObjectNode node = levels.putObject(level);
            node.put("agreement", ratio(agree, results.size()));
            node.put("quadraticWeightedKappa", kappa(matrix));
            node.put("selfCheckShare", ratio(selfCheck, results.size()));
            node.put("lenientShare", ratio(lenient, graded));
            node.put("strictShare", ratio(strict, graded));
            node.put("gradedAgainstGradedLabels", graded).put("labelledGraded", expectedGraded);
        }
        ObjectNode falseAccept = report.putObject("falseAccept");
        for (String kind : List.of("off-topic", "bag-of-terms", "misconception", "injection", "verbose-wrong")) {
            int accepted = 0;
            int total = 0;
            for (Result result : results) {
                if (!result.kind().equals(kind)) continue;
                for (String level : List.of("S1", "S2", "S3")) {
                    total++;
                    String actual = result.actual().get(level);
                    if (actual.equals("COMPLETE") || actual.equals("PARTIAL")) accepted++;
                }
            }
            falseAccept.put(kind, ratio(accepted, total));
        }
        ObjectNode kinds = report.putObject("agreementByKind");
        for (String kind : results.stream().map(Result::kind).distinct().toList()) {
            ObjectNode node = kinds.putObject(kind);
            for (String level : List.of("S1", "S2", "S3")) {
                int agree = 0;
                int total = 0;
                for (Result result : results) {
                    if (!result.kind().equals(kind)) continue;
                    total++;
                    if (result.actual().get(level).equals(result.expected().get(level))) agree++;
                }
                node.put(level, ratio(agree, total));
            }
        }
        ObjectNode latency = report.putObject("latencyMillis");
        latency.putObject("singleRun").put("p50", percentile(single, 50)).put("p95", percentile(single, 95)).put("count", single.size());
        latency.putObject("pairOfRuns").put("p50", percentile(pair, 50)).put("p95", percentile(pair, 95)).put("count", pair.size());
        long inputTokens = metered.hit.get() + metered.miss.get();
        report.putObject("cost").put("providerCalls", metered.calls.get()).put("failedCalls", metered.failures.get())
                .put("costMicros", metered.cost.get()).put("costMicrosPerAnswer", results.isEmpty() ? 0 : metered.cost.get() / results.size())
                .put("cacheHitShare", ratio(metered.hit.get(), inputTokens)).put("completionTokens", metered.completion.get());
        ObjectNode failed = report.putObject("unavailable");
        failed.put("count", unavailable.size());
        var reasons = failed.putArray("items");
        unavailable.forEach(reasons::add);
        ObjectNode mistakes = report.putObject("disagreements");
        var items = mistakes.putArray("items");
        for (Result result : results) {
            for (String level : List.of("S1", "S2", "S3")) {
                if (!result.actual().get(level).equals(result.expected().get(level))) {
                    items.addObject().put("answer", result.id()).put("strictness", level).put("expected", result.expected().get(level))
                            .put("actual", result.actual().get(level));
                }
            }
        }
        return report;
    }

    private static double ratio(long part, long whole) { return whole == 0 ? 0 : Math.round(1_000.0 * part / whole) / 1_000.0; }

    private static long percentile(List<Long> values, int percent) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(percent / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    /** Quadratic weighted kappa of a 3x3 confusion matrix (rows: label, columns: grader). */
    private static double kappa(double[][] matrix) {
        double total = 0;
        for (double[] row : matrix) for (double value : row) total += value;
        if (total == 0) return 0;
        double[] rows = new double[3];
        double[] columns = new double[3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
            rows[i] += matrix[i][j];
            columns[j] += matrix[i][j];
        }
        double observed = 0;
        double expected = 0;
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
            double weight = Math.pow(i - j, 2) / 4.0;
            observed += weight * matrix[i][j] / total;
            expected += weight * rows[i] * columns[j] / (total * total);
        }
        return expected == 0 ? 1 : Math.round(1_000.0 * (1 - observed / expected)) / 1_000.0;
    }

    private static void write(ObjectNode report) throws IOException {
        Path directory = Path.of("build/reports/assessment-eval");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("report.json"), report.toPrettyString() + "\n");
        StringBuilder text = new StringBuilder("# Assessment eval (").append(report.path("mode").stringValue(null)).append(")\n\n");
        report.path("strictness").properties().forEach(entry -> text.append(String.format(Locale.ROOT,
                "- %s: agreement %.3f, QWK %.3f, self-check %.3f, lenient %.3f, strict %.3f%n", entry.getKey(),
                entry.getValue().path("agreement").doubleValue(), entry.getValue().path("quadraticWeightedKappa").doubleValue(),
                entry.getValue().path("selfCheckShare").doubleValue(), entry.getValue().path("lenientShare").doubleValue(),
                entry.getValue().path("strictShare").doubleValue())));
        text.append("\nFalse accept: ").append(report.path("falseAccept")).append("\n");
        text.append("Latency (ms): ").append(report.path("latencyMillis")).append("\n");
        text.append("Cost: ").append(report.path("cost")).append("\n");
        Files.writeString(directory.resolve("report.md"), text.toString());
    }
}
