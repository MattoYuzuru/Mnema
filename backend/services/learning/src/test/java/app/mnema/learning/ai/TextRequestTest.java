package app.mnema.learning.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextRequestTest {
    private static TextRequest with(List<TextRequest.Segment> segments, int max, double temperature, Duration deadline, OpaqueUserKey key, int attempt) {
        return new TextRequest(AiRoute.TEXT_FAST, segments, OutputContract.MBM_TEXT, max, temperature, deadline, key, null, null, attempt);
    }

    @Test
    void cacheableSegmentsMustFormALeadingRun() {
        assertThatThrownBy(() -> with(List.of(TextRequest.Segment.user("a", false), TextRequest.Segment.user("b", true)), 10, 0.5,
                Duration.ofSeconds(1), AiTestSupport.KEY, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with(List.of(), 10, 0.5, Duration.ofSeconds(1), AiTestSupport.KEY, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void limitsAreValidated() {
        List<TextRequest.Segment> one = List.of(TextRequest.Segment.user("a", false));
        for (Runnable invalid : List.<Runnable>of(
                () -> with(one, 0, 0.5, Duration.ofSeconds(1), AiTestSupport.KEY, 1),
                () -> with(one, 70_000, 0.5, Duration.ofSeconds(1), AiTestSupport.KEY, 1),
                () -> with(one, 10, 2.5, Duration.ofSeconds(1), AiTestSupport.KEY, 1),
                () -> with(one, 10, Double.NaN, Duration.ofSeconds(1), AiTestSupport.KEY, 1),
                () -> with(one, 10, 0.5, Duration.ZERO, AiTestSupport.KEY, 1),
                () -> with(one, 10, 0.5, Duration.ofHours(2), AiTestSupport.KEY, 1),
                () -> with(one, 10, 0.5, null, AiTestSupport.KEY, 1),
                () -> with(one, 10, 0.5, Duration.ofSeconds(1), AiTestSupport.KEY, 0),
                () -> new OpaqueUserKey("user@example.com"), () -> new OpaqueUserKey(null), () -> new OpaqueUserKey("x".repeat(65)))) {
            assertThatThrownBy(invalid::run).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aMissingUserKeyIsRejectedAndNoPersonalDataSurvivesToString() {
        assertThatThrownBy(() -> with(List.of(TextRequest.Segment.user("a", false)), 10, 0.5, Duration.ofSeconds(1), null, 1))
                .isInstanceOf(NullPointerException.class);
        TextRequest request = new TextRequest(AiRoute.TEXT_FAST, List.of(TextRequest.Segment.user("СЕКРЕТНЫЙ-ТЕКСТ", false)),
                OutputContract.MBM_TEXT, 10, 0.5, Duration.ofSeconds(1), AiTestSupport.KEY, null, null, 1);
        assertThat(request.toString()).doesNotContain("СЕКРЕТНЫЙ-ТЕКСТ").doesNotContain(AiTestSupport.USER_KEY).contains("chars=15");
        assertThat(AiTestSupport.KEY.toString()).doesNotContain(AiTestSupport.USER_KEY);
        assertThat(AiTestSupport.KEY).isEqualTo(new OpaqueUserKey(AiTestSupport.USER_KEY)).hasSameHashCodeAs(new OpaqueUserKey(AiTestSupport.USER_KEY));
        assertThat(AiTestSupport.KEY).isNotEqualTo("x");
        var response = new TextResponse("СЕКРЕТНЫЙ-ОТВЕТ", TextResponse.FinishReason.STOP, Usage.ZERO, 0, null, new TextResponse.RouteUsed("p", "m"));
        assertThat(response.toString()).doesNotContain("СЕКРЕТНЫЙ-ОТВЕТ").contains("chars=15");
    }

    @Test
    void aRepairAddsOneVolatileSegmentAndKeepsTheCacheablePrefix() {
        TextRequest request = AiTestSupport.request();
        TextRequest repaired = request.withRepair("MBM_HEADING_LEVEL <b> & более");
        assertThat(repaired.segments().subList(0, 3)).isEqualTo(request.segments());
        TextRequest.Segment added = repaired.segments().get(3);
        assertThat(added.cacheable()).isFalse();
        assertThat(added.text()).startsWith(TextRequest.REPAIR_PREFIX).contains("MBM_HEADING_LEVEL &lt;b&gt; &amp; более");
        assertThat(request.withRepair(null).segments().get(3).text()).startsWith(TextRequest.REPAIR_PREFIX);
        assertThat(request.withRepair("x".repeat(5_000)).segments().get(3).text().length()).isLessThan(2_300);
    }

    @Test
    void theFingerprintCoversTheRequestShapeButNotTheUserKey() {
        TextRequest request = AiTestSupport.request();
        assertThat(request.fingerprint()).matches("[0-9a-f]{64}").isEqualTo(AiTestSupport.request().fingerprint());
        assertThat(request.withRoute(AiRoute.ASSESS).fingerprint()).isNotEqualTo(request.fingerprint());
        assertThat(request.withRepair("x").fingerprint()).isNotEqualTo(request.fingerprint());
        TextRequest otherKey = new TextRequest(request.route(), request.segments(), request.output(), request.maxOutputTokens(),
                request.temperature(), request.deadline(), new OpaqueUserKey("k2.other"), null, null, 1);
        assertThat(otherKey.fingerprint()).isEqualTo(request.fingerprint());
        assertThat(request.streaming()).isFalse();
        assertThat(request.withListener(text -> { }).streaming()).isTrue();
    }

    @Test
    void routesKnowTheirCapabilityAndEscalation() {
        assertThat(AiRoute.TEXT_FAST.escalation()).isEqualTo(AiRoute.TEXT_STRONG);
        assertThat(AiRoute.TEXT_STRONG.escalation()).isNull();
        assertThat(AiRoute.ASSESS.capability()).isEqualTo(AiCapability.ASSESS);
        assertThat(AiCapability.IMAGE_SEARCH.label()).isEqualTo("image_search");
    }

    @Test
    void failuresAndResultsValidateTheirShape() {
        assertThat(new AiFailure.RateLimited(null).retryAfter()).isEqualTo(Duration.ZERO);
        assertThat(new AiFailure.RateLimited(Duration.ofSeconds(-3)).retryAfter()).isEqualTo(Duration.ZERO);
        assertThat(List.of(new AiFailure.Timeout(), new AiFailure.BudgetExhausted(), new AiFailure.CircuitOpen(),
                new AiFailure.NotConfigured("x"), new AiFailure.Refusal("x"), new AiFailure.InvalidOutput("x"),
                new AiFailure.Transient("x"), new AiFailure.RateLimited(Duration.ZERO)).stream().map(AiFailure::outcome))
                .containsExactly("TIMEOUT", "BUDGET_EXHAUSTED", "CIRCUIT_OPEN", "NOT_CONFIGURED", "REFUSAL", "INVALID_OUTPUT",
                        "TRANSIENT", "RATE_LIMITED");
        assertThatThrownBy(() -> new AiResult.Ok<String>(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AiResult.Failed<String>(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Usage(-1, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextResponse("t", TextResponse.FinishReason.STOP, Usage.ZERO, -1, null,
                new TextResponse.RouteUsed("p", "m"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theTokenEstimateIsConservativeForNonLatinScripts() {
        assertThat(TokenCounter.estimate(null)).isZero();
        assertThat(TokenCounter.estimate("")).isZero();
        assertThat(TokenCounter.estimate("abcdefg")).isEqualTo(2);
        assertThat(TokenCounter.estimate("Привет, мир")).isGreaterThan(TokenCounter.estimate("Hello, world"));
        assertThat(TokenCounter.estimate("日本語のテスト")).isEqualTo(7);
        assertThat(TokenCounter.estimate("😀")).isEqualTo(1);
        assertThat(TokenCounter.estimate("x".repeat(3500))).isEqualTo(1000);
    }
}
