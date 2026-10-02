package app.mnema.learning.ai.eval;

import app.mnema.learning.ai.AiResult;
import app.mnema.learning.ai.AiRoute;
import app.mnema.learning.ai.EvalStack;
import app.mnema.learning.ai.OutputContract;
import app.mnema.learning.ai.TextRequest;
import app.mnema.learning.ai.TextResponse;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in smoke test of the real DeepSeek route; skipped unless {@code MNEMA_AI_LIVE=true} and a key is in the environment:
 * <pre>
 * MNEMA_AI_LIVE=true MNEMA_AI_DEEPSEEK_API_KEY=... ./gradlew :services:learning:cleanTest :services:learning:test --tests '*LiveProviderTest*'
 * </pre>
 * It makes three tiny calls (plain, streamed, JSON) and asserts shape, usage and cost only; it never prints the key or
 * the model's text. CI never runs it (no variable, no network).
 */
@EnabledIfEnvironmentVariable(named = "MNEMA_AI_LIVE", matches = "true")
class LiveProviderTest {
    private static TextRequest request(String task, OutputContract output, app.mnema.learning.ai.StreamListener listener) {
        return new TextRequest(AiRoute.TEXT_FAST, List.of(TextRequest.Segment.system("Отвечай предельно кратко.", true),
                TextRequest.Segment.user(task, false)), output, 200, 0.2, Duration.ofSeconds(60), "k1.livetest", listener, null, 1);
    }

    @Test
    void deepSeekAnswersPlainStreamedAndJsonCalls() {
        String key = System.getenv("MNEMA_AI_DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(key != null && !key.isBlank(), "MNEMA_AI_DEEPSEEK_API_KEY is not set");
        try (EvalStack stack = EvalStack.create(true)) {
            AiResult<TextResponse> plain = stack.text().generate(request("Назови столицу Франции одним словом.", OutputContract.MBM_TEXT, null));
            TextResponse response = ((AiResult.Ok<TextResponse>) ok(plain)).value();
            assertThat(response.text()).isNotBlank();
            assertThat(response.usage().promptTokens()).isPositive();
            assertThat(response.usage().completionTokens()).isPositive();
            assertThat(response.costMicros()).isPositive();
            assertThat(response.route().provider()).isEqualTo("deepseek");

            List<String> deltas = new ArrayList<>();
            TextResponse streamed = ((AiResult.Ok<TextResponse>) ok(stack.text().generate(
                    request("Назови столицу Италии одним словом.", OutputContract.MBM_TEXT, deltas::add)))).value();
            assertThat(String.join("", deltas)).isEqualTo(streamed.text());
            assertThat(streamed.usage().completionTokens()).isPositive();

            TextResponse json = ((AiResult.Ok<TextResponse>) ok(stack.text().generate(
                    request("Верни json вида {\"ok\": true} и больше ничего.", OutputContract.JSON, null)))).value();
            assertThat(json.text()).contains("ok");
        }
    }

    private static AiResult<TextResponse> ok(AiResult<TextResponse> result) {
        assertThat(result).as("the live call must succeed; failure kind: %s", result instanceof AiResult.Failed<TextResponse> failed
                ? failed.failure().outcome() : "-").isInstanceOf(AiResult.Ok.class);
        return result;
    }
}
