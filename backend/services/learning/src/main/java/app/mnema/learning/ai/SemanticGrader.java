package app.mnema.learning.ai;

import app.mnema.learning.ai.prompt.AssembledPrompt;
import app.mnema.learning.ai.prompt.PromptAssembler;
import app.mnema.learning.ai.prompt.PromptBlocks;
import app.mnema.learning.ai.prompt.PromptException;
import app.mnema.learning.ai.prompt.PromptTask;
import app.mnema.learning.ai.prompt.PromptValues;
import app.mnema.learning.ai.prompt.Redactor;
import app.mnema.learning.capability.SemanticAssessmentProvider;
import app.mnema.learning.catalog.exercise.Rubric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * The {@code ai-semantic} grader on top of {@link TextGeneration}: the {@code assess} route (non-thinking Flash, never the
 * strong route), the rubric and the exercise in the cacheable prefix, the learner answer as one JSON string of untrusted data
 * ({@code ai/prompts/v1/assessment.md}, verbatim). The model returns a verdict, a quote and a note per rubric point; nothing
 * here computes a grade.
 *
 * <p>The output is validated by the server: every rubric point exactly once, a verdict from the enum, and for {@code MET} and
 * {@code PARTLY} a quote that is a verbatim substring of the answer (whitespace, case and the entities the prompt escaping
 * adds normalized), otherwise the verdict is downgraded to {@code UNCLEAR}. Output that does not parse or does not fit the
 * schema is sent back once with the findings; a second failure is {@link GradeOutcome.Unavailable}. One run uses
 * temperature 0.2, two parallel runs 0.3. The prompt names the rubric points {@code c1..cN} (the ids of the rubric are UUIDs
 * the model never sees); no personal data enters the prompt (the renderer redacts), and the provider user key is the HMAC of
 * the account. Each call is journaled by the router without any text.
 */
public final class SemanticGrader implements SemanticAssessmentProvider, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SemanticGrader.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UserKeys STUB_KEYS = UserKeys.withSecret("stub-user-key-secret-not-for-production", "stub");
    private static final int MAX_OUTPUT_TOKENS = 1_200;
    private static final double SINGLE_TEMPERATURE = 0.2;
    private static final double PAIR_TEMPERATURE = 0.3;
    private static final int MAX_NOTE = 240;
    private static final int MAX_QUOTE = 400;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern ELLIPSIS = Pattern.compile("…|\\.{3}");
    private static final Pattern EDGE = Pattern.compile("^[\\s\"'«»“”„‘’`.…]+|[\\s\"'«»“”„‘’`.…]+$");

    private final TextGeneration text;
    private final PromptAssembler prompts;
    private final UserKeys userKeys;
    private final boolean stub;
    private final ExecutorService runs = Executors.newVirtualThreadPerTaskExecutor();

    /** @param stub true when the text port is the Stub: a missing user-key secret is then fine (a fixed non-secret key is used) */
    public SemanticGrader(TextGeneration text, PromptAssembler prompts, UserKeys userKeys, boolean stub) {
        this.text = text;
        this.prompts = prompts;
        this.userKeys = userKeys;
        this.stub = stub;
    }

    @Override
    public GradeOutcome grade(GradeRequest request) {
        long started = System.nanoTime();
        TextRequest base;
        try {
            base = baseRequest(request);
        } catch (PromptException | IllegalStateException exception) {
            LOG.warn("semantic_grade_unavailable attempt_id={} reason=prompt error_type={}", request.attemptId(),
                    exception.getClass().getSimpleName());
            return new GradeOutcome.Unavailable("PROMPT");
        }
        List<CompletableFuture<Pass>> passes = new ArrayList<>();
        for (int run = 1; run <= request.runs(); run++) {
            int number = run;
            passes.add(CompletableFuture.supplyAsync(() -> pass(request, base, number, started), runs));
        }
        List<Run> graded = new ArrayList<>();
        String failure = null;
        for (CompletableFuture<Pass> future : passes) {
            Pass pass = future.join();
            if (pass.run() == null) failure = failure == null ? pass.failure() : failure;
            else graded.add(pass.run());
        }
        if (failure != null) {
            LOG.info("semantic_grade attempt_id={} runs={} outcome=UNAVAILABLE reason={} latency_ms={}", request.attemptId(),
                    request.runs(), failure, Duration.ofNanos(System.nanoTime() - started).toMillis());
            return new GradeOutcome.Unavailable(failure);
        }
        LOG.info("semantic_grade attempt_id={} runs={} outcome=GRADED latency_ms={}", request.attemptId(), request.runs(),
                Duration.ofNanos(System.nanoTime() - started).toMillis());
        return new GradeOutcome.Graded(graded);
    }

    private record Pass(Run run, String failure) { }

    private Pass pass(GradeRequest request, TextRequest base, int number, long started) {
        String finding = null;
        for (int round = 0; round < 2; round++) {
            Duration left = remaining(request, started);
            if (left.compareTo(Duration.ofMillis(50)) < 0) return new Pass(null, "DEADLINE");
            TextRequest shaped = round == 0 ? base : base.withRepair(finding);
            TextRequest call = new TextRequest(shaped.route(), shaped.segments(), shaped.output(), shaped.maxOutputTokens(),
                    shaped.temperature(), left, shaped.userKey(), null, request.attemptId(), number);
            AiResult<TextResponse> result = text.generate(call);
            if (result instanceof AiResult.Failed<TextResponse> failed) return new Pass(null, reason(failed.failure()));
            Parsed parsed = parse(((AiResult.Ok<TextResponse>) result).value().text(), request);
            if (parsed.run() != null) return new Pass(parsed.run(), null);
            finding = parsed.finding();
        }
        return new Pass(null, "INVALID_OUTPUT");
    }

    private static Duration remaining(GradeRequest request, long started) {
        Duration left = request.deadline().minus(Duration.ofNanos(System.nanoTime() - started));
        return left.isNegative() ? Duration.ZERO : left;
    }

    private static String reason(AiFailure failure) {
        return switch (failure) {
            case AiFailure.Timeout ignored -> "TIMEOUT";
            case AiFailure.RateLimited ignored -> "RATE_LIMITED";
            case AiFailure.Transient ignored -> "PROVIDER_ERROR";
            case AiFailure.InvalidOutput ignored -> "INVALID_OUTPUT";
            case AiFailure.Refusal ignored -> "REFUSAL";
            case AiFailure.BudgetExhausted ignored -> "BUDGET";
            case AiFailure.NotConfigured ignored -> "NOT_CONFIGURED";
            case AiFailure.CircuitOpen ignored -> "CIRCUIT_OPEN";
        };
    }

    // ------------------------------------------------------------------------------------------------ request

    private TextRequest baseRequest(GradeRequest request) {
        Rubric rubric = request.rubric();
        List<String> criteria = new ArrayList<>();
        for (int index = 0; index < rubric.criteria().size(); index++) {
            criteria.add("c" + (index + 1) + " · " + rubric.criteria().get(index).description());
        }
        if (!rubric.acceptableTerms().isEmpty()) {
            criteria.add("(допустимые синонимы и переводы терминов: " + String.join(", ", rubric.acceptableTerms()) + ")");
        }
        List<String> misconceptions = new ArrayList<>();
        for (String misconception : rubric.misconceptions()) misconceptions.add("- " + misconception);
        PromptValues values = PromptValues.create().text("exercise.prompt", request.exercisePrompt())
                .text("exercise.reference", rubric.referenceAnswer()).block("criteria_lines", PromptBlocks.lines(criteria))
                .block("misconception_lines", PromptBlocks.lines(misconceptions))
                .text("material_fragment", request.materialFragment() == null || request.materialFragment().isBlank()
                        ? "нет" : request.materialFragment())
                .text("feedback_language", request.feedbackLanguage()).text("answer_source", request.source().name())
                .text("learner_answer_json", request.answer());
        AssembledPrompt prompt = prompts.assemble(PromptTask.ASSESSMENT, values);
        UserKeys keys = userKeys.configured() ? userKeys : stub ? STUB_KEYS : userKeys;
        return new TextRequest(AiRoute.ASSESS, prompt.segments(), OutputContract.JSON, MAX_OUTPUT_TOKENS,
                request.runs() == 1 ? SINGLE_TEMPERATURE : PAIR_TEMPERATURE, request.deadline(), keys.opaque(request.accountId()),
                null, request.attemptId(), 1);
    }

    // ------------------------------------------------------------------------------------------------ output

    private record Parsed(Run run, String finding) { }

    private static Parsed parse(String raw, GradeRequest request) {
        JsonNode root;
        try {
            root = JSON.readTree(raw);
        } catch (JacksonException exception) {
            return new Parsed(null, "ответ не является json");
        }
        if (!root.isObject() || !root.path("criteria").isArray()) return new Parsed(null, "нет массива criteria");
        List<Rubric.Criterion> points = request.rubric().criteria();
        UUID[] byPrompt = points.stream().map(Rubric.Criterion::criterionId).toArray(UUID[]::new);
        CriterionGrade[] grades = new CriterionGrade[points.size()];
        String answer = normalize(request.answer());
        String redacted = normalize(Redactor.redact(request.answer()));
        for (JsonNode item : root.path("criteria")) {
            int index = index(item.path("id").stringValue(null), points.size());
            if (index < 0) return new Parsed(null, "неизвестный id критерия");
            if (grades[index] != null) return new Parsed(null, "критерий c" + (index + 1) + " повторяется");
            Verdict verdict = verdict(item.path("verdict").stringValue(null));
            if (verdict == null) return new Parsed(null, "недопустимый verdict у c" + (index + 1));
            String quote = null;
            if (verdict == Verdict.MET || verdict == Verdict.PARTLY) {
                quote = verified(item.path("quote").stringValue(null), answer, redacted);
                if (quote == null) verdict = Verdict.UNCLEAR;
            }
            grades[index] = new CriterionGrade(byPrompt[index], verdict, quote, note(item.path("note").stringValue(null)));
        }
        for (int index = 0; index < grades.length; index++) {
            if (grades[index] == null) return new Parsed(null, "нет критерия c" + (index + 1));
        }
        return new Parsed(new Run(List.of(grades), flags(root.path("flags"))), null);
    }

    private static int index(String id, int size) {
        if (id == null || id.length() < 2 || id.charAt(0) != 'c') return -1;
        try {
            int number = Integer.parseInt(id.substring(1));
            return number >= 1 && number <= size && ("c" + number).equals(id) ? number - 1 : -1;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static Verdict verdict(String value) {
        if (value == null) return null;
        try {
            return Verdict.valueOf(value.strip());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static Set<Flag> flags(JsonNode node) {
        Set<Flag> flags = EnumSet.noneOf(Flag.class);
        if (!node.isArray()) return flags;
        for (JsonNode item : node) {
            if (!item.isString()) continue;
            try {
                flags.add(Flag.valueOf(item.stringValue().strip()));
            } catch (IllegalArgumentException unknown) {
                // a flag this server does not know says nothing the policy could act on
            }
        }
        return flags;
    }

    private static String note(String value) {
        if (value == null) return "";
        String clean = WHITESPACE.matcher(value.replaceAll("\\p{Cntrl}", " ")).replaceAll(" ").strip();
        return clean.length() <= MAX_NOTE ? clean : clean.substring(0, MAX_NOTE);
    }

    /**
     * The quote as the learner wrote it when it is a verbatim fragment of the answer (or of its redacted form), else null. The
     * model sees the answer with {@code & < >} escaped, so a quote with those entities is read back as the character.
     */
    static String verified(String quote, String normalizedAnswer, String normalizedRedacted) {
        if (quote == null || quote.isBlank()) return null;
        for (String candidate : new String[] {quote, unescape(quote)}) {
            if (contained(candidate, normalizedAnswer) || contained(candidate, normalizedRedacted)) {
                String clean = EDGE.matcher(WHITESPACE.matcher(candidate).replaceAll(" ")).replaceAll("").strip();
                return clean.length() <= MAX_QUOTE ? clean : clean.substring(0, MAX_QUOTE);
            }
        }
        return null;
    }

    /** True when every non-empty fragment of the quote (split at an ellipsis) occurs in the answer, in order. */
    private static boolean contained(String quote, String normalizedAnswer) {
        int from = 0;
        boolean any = false;
        for (String fragment : ELLIPSIS.split(quote)) {
            String needle = normalize(EDGE.matcher(fragment).replaceAll(""));
            if (needle.isEmpty()) continue;
            any = true;
            int at = normalizedAnswer.indexOf(needle, from);
            if (at < 0) return false;
            from = at + needle.length();
        }
        return any;
    }

    /** NFC, collapsed whitespace, case folded: what a quote is compared on. */
    static String normalize(String value) {
        String composed = Normalizer.normalize(value, Normalizer.Form.NFC);
        return WHITESPACE.matcher(composed).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
    }

    private static String unescape(String value) {
        return value.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    @Override
    public void close() {
        runs.close();
    }
}
