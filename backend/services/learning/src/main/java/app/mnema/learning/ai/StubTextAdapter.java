package app.mnema.learning.ai;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Deterministic text provider for local runs and CI: no network, no key. The answer is a pure function of the request
 * (SHA-256 of {@link TextRequest#fingerprint()}): MBM output is one of a few valid documents copied from
 * {@code contracts/generation/mbm-v1/valid}, JSON output is a small fixed-shape object. A marker anywhere in the prompt
 * simulates a failure so pipelines can be exercised without a provider:
 * {@code [[stub:rate-limit]]}, {@code [[stub:transient]]}, {@code [[stub:timeout]]}, {@code [[stub:refusal]]},
 * {@code [[stub:invalid]]} (empty content) and {@code [[stub:invalid-mbm]]} (a document the MBM compiler rejects).
 * The two {@code invalid} markers stop applying once the request carries a repair segment, so a repair succeeds.
 *
 * <p>An exercise request (JSON output whose prompt carries the exercise task) is answered with a valid
 * {@code {"exercises": [...]}} built from the material in the prompt ({@link StubExercises}); two markers in the material text
 * break it: {@code [[stub:broken-key]]} (the first exercise of the first answer has two correct options in a SINGLE choice, the
 * repair is valid) and {@code [[stub:broken-key-always]]} (also broken after the repair and on the strong route, so the
 * artifact fails with {@code INVALID_OUTPUT}). A grading request (the prompt carries the {@code <grader>} rules and a learner answer) is
 * answered by {@link StubAssessments}, which documents the {@code [[stub:assess-*]]} markers of the learner answer
 * ({@code complete}, {@code shallow}, {@code partial}, {@code contradicted}, {@code offtopic}, {@code unclear}, {@code asr},
 * {@code disagree}, {@code injection}, {@code invalid}, and {@code slow}, which waits 8 s for the harness) and the lexical heuristic that decides without one. An edit request (the prompt carries {@code <task kind="edit">}) is answered by {@link StubEdits}:
 * the target blocks with their handles, each plain paragraph with one sentence added. An intent request ({@code <task kind="intent">}) is
 * answered by {@link StubIntents} (a keyword mapping of the request) and an exercise revision ({@code <task kind="exercise-edit">}) by
 * {@link StubExerciseEdits} (the exercise with one sentence added). A plan request ({@code <task kind="plan">}) is answered by {@link StubPlans}
 * (one item per target or note; {@code [[stub:plan-invalid]]} breaks the first answer, {@code [[stub:plan-invalid-always]]} every one). A research request
 * ({@code <task kind="research">}) is answered by {@link StubResearch} (the queries within the cap; the markers of {@link StubWebSearch} travel into them, and
 * {@code [[stub:research-invalid]]}, {@code [[stub:research-invalid-always]]} and {@code [[stub:research-over]]} break the planner). A material whose prompt carries
 * {@code <search_result>} blocks is closed with a citing sentence and a {@code ::sources} block of exactly those results. Usage is estimated; cost is zero.
 */
final class StubTextAdapter implements TextAdapter {
    static final String PROVIDER = "stub";
    private static final List<String> DOCUMENTS = List.of("headings", "blockquote-divider", "lists", "ruby", "table");
    private static final int CHUNK = 48;

    private final List<String> documents;
    private final String invalidDocument;

    StubTextAdapter() {
        this.documents = DOCUMENTS.stream().map(StubTextAdapter::resource).toList();
        this.invalidDocument = resource("invalid");
    }

    @Override public String provider() { return PROVIDER; }

    @Override public boolean configured() { return true; }

    @Override
    public AiResult<TextResponse> attempt(String model, TextRequest request, Duration budget) {
        String prompt = joined(request);
        boolean repair = request.segments().stream().anyMatch(segment -> segment.text().startsWith(TextRequest.REPAIR_PREFIX));
        if (prompt.contains("[[stub:rate-limit]]")) return AiResult.failed(new AiFailure.RateLimited(Duration.ofSeconds(1)));
        if (prompt.contains("[[stub:transient]]")) return AiResult.failed(new AiFailure.Transient("stub_transient"));
        if (prompt.contains("[[stub:timeout]]")) return AiResult.failed(new AiFailure.Timeout());
        if (prompt.contains("[[stub:refusal]]")) return AiResult.failed(new AiFailure.Refusal("stub_refusal"));
        if (!repair && prompt.contains("[[stub:invalid]]")) return AiResult.failed(new AiFailure.InvalidOutput("stub_invalid"));
        String fingerprint = request.fingerprint();
        String text;
        if (!repair && prompt.contains("[[stub:invalid-mbm]]")) {
            text = invalidDocument;
        } else if (request.output() == OutputContract.MBM_TEXT && StubEdits.isEditRequest(prompt)) {
            text = StubEdits.answer(prompt);
        } else if (request.output() == OutputContract.JSON && StubAssessments.isAssessmentRequest(prompt)) {
            if (StubAssessments.slow(prompt)) {
                Duration wait = StubAssessments.SLOW.compareTo(budget) < 0 ? StubAssessments.SLOW : budget;
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return AiResult.failed(new AiFailure.Transient("interrupted"));
                }
                if (wait.compareTo(StubAssessments.SLOW) < 0) return AiResult.failed(new AiFailure.Timeout());
            }
            text = StubAssessments.answer(prompt, request.attempt());
        } else if (request.output() == OutputContract.JSON && StubIntents.isIntentRequest(prompt)) {
            text = StubIntents.answer(prompt, repair);
        } else if (request.output() == OutputContract.JSON && StubResearch.isResearchRequest(prompt)) {
            text = StubResearch.answer(prompt, repair);
        } else if (request.output() == OutputContract.JSON && StubPlans.isPlanRequest(prompt)) {
            text = StubPlans.answer(prompt, repair);
        } else if (request.output() == OutputContract.JSON && StubExerciseEdits.isExerciseEditRequest(prompt)) {
            text = StubExerciseEdits.answer(prompt, repair);
        } else if (request.output() == OutputContract.JSON && StubExercises.isExerciseRequest(prompt)) {
            text = StubExercises.answer(prompt, repair);
        } else if (request.output() == OutputContract.JSON) {
            text = "{\"stub\":true,\"digest\":\"" + fingerprint.substring(0, 16) + "\"}";
        } else {
            text = withSources(documents.get(Integer.parseInt(fingerprint.substring(0, 6), 16) % documents.size()), prompt);
        }
        if (request.streaming()) {
            for (int start = 0; start < text.length(); start += CHUNK) {
                request.listener().onDelta(text.substring(start, Math.min(text.length(), start + CHUNK)));
            }
        }
        int cached = request.segments().stream().filter(TextRequest.Segment::cacheable)
                .mapToInt(segment -> TokenCounter.estimate(segment.text())).sum();
        int all = request.segments().stream().mapToInt(segment -> TokenCounter.estimate(segment.text())).sum();
        return AiResult.ok(new TextResponse(text, TextResponse.FinishReason.STOP,
                new Usage(all, cached, all - cached, TokenCounter.estimate(text)), 0, "stub-" + fingerprint.substring(0, 12),
                new TextResponse.RouteUsed(PROVIDER, model)));
    }

    private static final java.util.regex.Pattern SEARCH_RESULT = java.util.regex.Pattern.compile("<search_result n=\"(\\d{1,3})\" url=\"([^\"]*)\"");

    /**
     * A prompt that carries search results (the research step found some) gets a sentence citing the first one and a {@code ::sources} block listing
     * every result, as a model that was told to cite its sources would write: the numbers and the URLs are the ones of the prompt.
     */
    private static String withSources(String document, String prompt) {
        java.util.regex.Matcher found = SEARCH_RESULT.matcher(prompt);
        StringBuilder sources = new StringBuilder();
        String first = null;
        while (found.find()) {
            if (first == null) first = found.group(1);
            sources.append('[').append(found.group(1)).append("] ")
                    .append(found.group(2).replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")).append('\n');
        }
        if (first == null) return document;
        return document.stripTrailing() + "\n\nСведения проверены по источникам [" + first + "].\n\n::sources\n" + sources;
    }

    private static String joined(TextRequest request) {
        var out = new StringBuilder();
        request.segments().forEach(segment -> out.append(segment.text()).append('\n'));
        return out.toString();
    }

    private static String resource(String name) {
        try (InputStream in = StubTextAdapter.class.getResourceAsStream("/ai/stub/" + name + ".mbm")) {
            if (in == null) throw new IllegalStateException("Missing stub document " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unreadable stub document " + name, exception);
        }
    }
}
