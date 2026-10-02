package app.mnema.learning.ai;

import app.mnema.learning.ai.FakeProvider.Reply;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;

/** Keys, prompts, user keys and provider error bodies must never reach a log line, on any outcome. */
class AiLogSafetyTest {
    private static final String KEY = "sk-LIVE-SECRET-VALUE-1234567890";
    private static final String CANARY = "ПРОМПТ-КАНАРЕЙКА-98765";

    @Test
    void noOutcomeLeaksAKeyAPromptOrAProviderMessage() {
        var appender = new ListAppender<ILoggingEvent>();
        var root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level previous = root.getLevel();
        root.setLevel(Level.DEBUG);
        appender.start();
        root.addAppender(appender);
        try (var provider = FakeProvider.start()) {
            provider.enqueue(Reply.fixtureJson("chat-ok.json"), Reply.status(401), Reply.status(429, "Retry-After", "0"),
                    Reply.status(500), Reply.json(200, FakeProvider.fixture("chat-malformed.txt")),
                    Reply.fixtureJson("chat-content-filter.json"), Reply.sse("stream-error-chunk.sse"),
                    Reply.fixtureJson("chat-empty-content.json"));
            var properties = AiTestSupport.properties("", AiTestSupport.routes(List.of("deepseek:deepseek-flash"), List.of(), List.of()),
                    Map.of("deepseek", AiTestSupport.provider(provider.baseUrl(), KEY)));
            try (var http = new ChatHttp(properties.transport())) {
                var adapter = new OpenAiCompatibleAdapter("deepseek", OpenAiCompatibleAdapter.Dialect.DEEPSEEK,
                        URI.create(provider.baseUrl()), BearerSource.staticKey(KEY), http,
                        Map.of("deepseek-flash", AiTestSupport.FLASH), Clock.systemUTC());
                var router = new RoutedTextGeneration(new AiRouting(properties, Map.of("deepseek", adapter)),
                        new BreakerRegistry(Clock.systemUTC(), properties.breaker()),
                        new AiBudget(properties.budget(), (capability, since) -> 0, Clock.systemUTC()), new CallJournal() {
                            @Override public UUID begin(Intent intent) { return UUID.randomUUID(); }

                            @Override public void finish(UUID callId, Outcome outcome) { }
                        }, new AiTelemetry(new SimpleMeterRegistry()), properties, Clock.systemUTC(), duration -> { },
                        RandomGenerator.getDefault());
                for (int index = 0; index < 8; index++) {
                    var request = new TextRequest(AiRoute.TEXT_FAST, List.of(TextRequest.Segment.system(CANARY, true),
                            TextRequest.Segment.user(CANARY + " задача", false)), OutputContract.MBM_TEXT, 100, 0.5,
                            Duration.ofSeconds(10), AiTestSupport.KEY, index == 6 ? text -> { } : null, null, 1);
                    router.generate(request);
                }
            }
        } finally {
            root.detachAppender(appender);
            root.setLevel(previous);
        }

        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            lines.add(event.getFormattedMessage());
            if (event.getThrowableProxy() != null) lines.add(ThrowableProxyUtil.asString(event.getThrowableProxy()));
        }
        assertThat(lines).anyMatch(line -> line.startsWith("ai_call provider=deepseek model=deepseek-flash capability=text step_id=- outcome=OK "));
        assertThat(lines).anyMatch(line -> line.contains("outcome=NOT_CONFIGURED"));
        assertThat(lines).anyMatch(line -> line.contains("outcome=REFUSAL"));
        String all = String.join("\n", lines);
        assertThat(all).doesNotContain(KEY).doesNotContain("SECRET").doesNotContain(CANARY).doesNotContain(AiTestSupport.USER_KEY)
                .doesNotContain("secret-looking-detail").doesNotContain("Bearer").doesNotContain("Заголовок");
        // value objects that travel through logs and exception messages never print secrets or text
        var request = new TextRequest(AiRoute.TEXT_FAST, List.of(TextRequest.Segment.system(CANARY, true)), OutputContract.MBM_TEXT,
                10, 0.5, Duration.ofSeconds(1), AiTestSupport.KEY, null, null, 1);
        var response = new TextResponse(CANARY, TextResponse.FinishReason.STOP, Usage.ZERO, 0, null, new TextResponse.RouteUsed("p", "m"));
        var provider = new AiProperties.Provider(true, "https://api.example.com", KEY, "https://auth.example.com", KEY, "S");
        assertThat(String.join("\n", request.toString(), request.segments().get(0).toString(), response.toString(), provider.toString(),
                AiTestSupport.KEY.toString(), new AiProperties.UserKey(KEY + KEY, "k1").toString()))
                .doesNotContain(CANARY).doesNotContain(KEY).doesNotContain(AiTestSupport.USER_KEY);
        String okLine = lines.stream().filter(line -> line.contains("outcome=OK")).findFirst().orElseThrow();
        assertThat(okLine).contains("in_hit=2000 in_miss=500 out=300 cost_micros=522").contains("latency_ms=");
    }
}
