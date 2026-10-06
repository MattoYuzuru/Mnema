package app.mnema.learning.ai.eval;

import app.mnema.learning.ai.EvalStack;
import app.mnema.learning.ai.eval.GoldenCorpus.Kind;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The golden eval of the AI layer (issue #300): the corpus of {@code contracts/generation/eval} through the real pipeline pieces, scored by two
 * judges of other model families. Skipped unless {@code MNEMA_AI_EVAL} is {@code stub} or {@code live}, so CI and {@code quality} never run it.
 * The Gradle task {@code goldenEval} (in {@code services/learning}) runs it with a rerun forced:
 *
 * <pre>
 * set -a; source .env; set +a      # MNEMA_AI_DEEPSEEK_API_KEY and MNEMA_AI_OPENROUTER_API_KEY, never printed
 * MNEMA_AI_EVAL=live ./gradlew :services:learning:goldenEval
 * MNEMA_AI_EVAL=stub ./gradlew :services:learning:goldenEval           # offline, proves the runner
 * </pre>
 *
 * <p>Environment knobs (all optional): {@code MNEMA_GOLDEN_KINDS} (comma separated: {@code material-from-notes}, {@code material-from-prompt},
 * {@code edit}, {@code exercise}), {@code MNEMA_GOLDEN_IDS} (only these fixture ids), {@code MNEMA_GOLDEN_TAGS} (only fixtures carrying one of these tags, e.g. {@code injection,adversarial,personal-data}), {@code MNEMA_GOLDEN_HELD_OUT=true} (the 30 % held-out fixtures only), {@code MNEMA_GOLDEN_LIMIT} (fixtures
 * per kind, spread over the file), {@code MNEMA_GOLDEN_PARALLELISM} (default 6), {@code MNEMA_GOLDEN_BUDGET_MICROS} (live only, default 2,800,000 = $2.80: no fixture
 * is started once generation and judging together have cost that much), {@code MNEMA_GOLDEN_JUDGES} (two entries
 * {@code openrouter-model=inputMicros:outputMicros}, micro-USD per million tokens), {@code MNEMA_GOLDEN_EVIDENCE_DIR} (a copy of
 * {@code report.json} and {@code report.md} goes there).
 *
 * <p>The generator is the production text route (DeepSeek direct, OpenRouter fallback). The report goes to
 * {@code build/reports/golden-eval/}; it carries identifiers and numbers only. The thresholds are the owner's gate, not assertions: only the
 * structure is asserted, so a weak model fails the gate in the report and not this build.
 */
@EnabledIfEnvironmentVariable(named = "MNEMA_AI_EVAL", matches = "stub|live")
class GoldenEvalRunner {
    /** Verified against https://openrouter.ai/api/v1/models on 2026-10-05: Google and Alibaba (Qwen) families, none of them DeepSeek. Mistral Small flagged critical errors inconsistently and
     * Mistral Large was rate-limited on OpenRouter in the pilot runs, so neither is the default. */
    private static final String DEFAULT_JUDGES = "google/gemini-3.1-flash-lite=250000:1500000,qwen/qwen3.8-flash=150000:470000";

    @Test
    void run() throws IOException {
        boolean live = "live".equals(System.getenv("MNEMA_AI_EVAL"));
        if (live) {
            Assumptions.assumeTrue(present("MNEMA_AI_DEEPSEEK_API_KEY"), "MNEMA_AI_DEEPSEEK_API_KEY is not set");
            Assumptions.assumeTrue(present("MNEMA_AI_OPENROUTER_API_KEY"), "MNEMA_AI_OPENROUTER_API_KEY is not set (the judges use OpenRouter)");
        }
        Set<Kind> kinds = new LinkedHashSet<>();
        String requested = System.getenv("MNEMA_GOLDEN_KINDS");
        if (requested == null || requested.isBlank()) kinds.addAll(GoldenEval.allKinds());
        else for (String wire : requested.split(",")) for (Kind kind : Kind.values()) if (kind.wire().equals(wire.strip())) kinds.add(kind);
        Set<String> tags = new LinkedHashSet<>();
        String tagFilter = System.getenv("MNEMA_GOLDEN_TAGS");
        if (tagFilter != null && !tagFilter.isBlank()) for (String tag : tagFilter.split(",")) tags.add(tag.strip());
        Set<String> ids = new LinkedHashSet<>();
        String idFilter = System.getenv("MNEMA_GOLDEN_IDS");
        if (idFilter != null && !idFilter.isBlank()) for (String id : idFilter.split(",")) ids.add(id.strip());
        GoldenEval.Options options = new GoldenEval.Options(live ? GoldenEval.Mode.LIVE : GoldenEval.Mode.STUB, kinds, tags, ids,
                "true".equals(System.getenv("MNEMA_GOLDEN_HELD_OUT")), integer("MNEMA_GOLDEN_LIMIT", 0), integer("MNEMA_GOLDEN_PARALLELISM", live ? 6 : 4),
                live ? integer("MNEMA_GOLDEN_BUDGET_MICROS", 2_800_000) : 0, Path.of("build/reports/golden-eval"), System.getenv().getOrDefault("MNEMA_GOLDEN_PROMPT_VERSION", "v1"));

        GoldenEval.Outcome outcome;
        List<EvalStack> stacks = new ArrayList<>();
        try {
            if (live) {
                EvalStack generator = EvalStack.production();
                stacks.add(generator);
                List<GoldenJudge> judges = new ArrayList<>();
                String configured = System.getenv("MNEMA_GOLDEN_JUDGES");
                for (String entry : (configured == null || configured.isBlank() ? DEFAULT_JUDGES : configured).split(",")) {
                    String[] parts = entry.strip().split("=");
                    String[] prices = parts[1].split(":");
                    EvalStack stack = EvalStack.judge(parts[0], Long.parseLong(prices[0]), Long.parseLong(prices[1]));
                    stacks.add(stack);
                    judges.add(new GoldenJudge.LlmJudge(parts[0], stack.text(), Duration.ofSeconds(120)));
                }
                outcome = GoldenEval.run(options, generator.text(), Duration.ofMinutes(6), judges, "deepseek:deepseek-flash > openrouter:deepseek/deepseek-v4.1-flash; strong deepseek:deepseek-v4-pro");
            } else {
                EvalStack generator = EvalStack.create(false);
                stacks.add(generator);
                outcome = GoldenEval.run(options, generator.text(), Duration.ofSeconds(30),
                        List.of(new GoldenJudge.Heuristic("stub-judge-a", 0.0), new GoldenJudge.Heuristic("stub-judge-b", 0.34)), "stub");
            }
        } finally {
            stacks.forEach(EvalStack::close);
        }

        String evidence = System.getenv("MNEMA_GOLDEN_EVIDENCE_DIR");
        if (evidence != null && !evidence.isBlank()) {
            Files.createDirectories(Path.of(evidence));
            for (String name : List.of("report.json", "report.md")) {
                Files.copy(outcome.directory().resolve(name), Path.of(evidence).resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        int skipped = outcome.report().path("spend").path("skippedForBudget").intValue();
        assertThat(outcome.rows()).as("every selected fixture produced a row, or was left out by the budget").hasSize(GoldenEval.select(options).size() - skipped);
        assertThat(outcome.rows()).as("the pipeline did not crash on any fixture").noneMatch(row -> row.result() == null);
        if (!live) {
            // the Stub builds a MATCH or an ORDER from the words of the first block (a one-word title, or a script without spaces) and
            // otherwise falls back to a self-check: its one possible failure
            assertThat(outcome.rows()).as("the Stub answers every fixture validly but some exercises")
                    .allMatch(row -> row.result().valid() || row.fixture().kind() == Kind.EXERCISE && row.result().failure().equals("EXERCISE:MECHANIC_NOT_ALLOWED"));
        }
    }

    private static boolean present(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
    }

    private static int integer(String name, int fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value.strip());
    }
}
