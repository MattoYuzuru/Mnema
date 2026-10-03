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
 * artifact fails with {@code INVALID_OUTPUT}). Usage is estimated; cost is zero.
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
        } else if (request.output() == OutputContract.JSON && StubExercises.isExerciseRequest(prompt)) {
            text = StubExercises.answer(prompt, repair);
        } else if (request.output() == OutputContract.JSON) {
            text = "{\"stub\":true,\"digest\":\"" + fingerprint.substring(0, 16) + "\"}";
        } else {
            text = documents.get(Integer.parseInt(fingerprint.substring(0, 6), 16) % documents.size());
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
