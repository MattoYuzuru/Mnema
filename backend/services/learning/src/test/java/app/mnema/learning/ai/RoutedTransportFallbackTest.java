package app.mnema.learning.ai;

import app.mnema.learning.ai.FakeProvider.Reply;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;

/** Real adapters over loopback servers: a provider that never answers must not eat the deadline, the next one is tried. */
class RoutedTransportFallbackTest {
    @Test
    void aSilentPrimaryTimesOutAtFirstByteAndTheFallbackServesTheCall() {
        try (var silent = FakeProvider.start(); var healthy = FakeProvider.start()) {
            // three attempts on the silent provider (transient-attempts), each ending at the 300 ms first-byte limit
            silent.enqueue(Reply.fixtureJson("chat-ok.json").delayed(1_500), Reply.fixtureJson("chat-ok.json").delayed(1_500),
                    Reply.fixtureJson("chat-ok.json").delayed(1_500));
            healthy.enqueue(Reply.fixtureJson("openrouter-chat-ok.json"));
            AiProperties base = AiTestSupport.properties("", AiTestSupport.routes(List.of("deepseek:deepseek-flash", "gigachat:GigaChat-2"),
                    List.of(), List.of()), Map.of());
            try (var http = new ChatHttp(base.transport())) {
                var deepseek = new OpenAiCompatibleAdapter("deepseek", OpenAiCompatibleAdapter.Dialect.DEEPSEEK,
                        java.net.URI.create(silent.baseUrl()), BearerSource.staticKey("k"), http, Map.of("deepseek-flash", AiTestSupport.FLASH),
                        Clock.systemUTC());
                var gigachat = new OpenAiCompatibleAdapter("gigachat", OpenAiCompatibleAdapter.Dialect.GIGACHAT,
                        java.net.URI.create(healthy.baseUrl()), BearerSource.staticKey("k"), http, Map.of("GigaChat-2", AiTestSupport.GIGA),
                        Clock.systemUTC());
                var router = new RoutedTextGeneration(new AiRouting(base, Map.of("deepseek", deepseek, "gigachat", gigachat)),
                        new BreakerRegistry(Clock.systemUTC(), base.breaker()), new AiBudget(base.budget(), (c, s) -> 0, Clock.systemUTC()),
                        new CallJournal() {
                            @Override public UUID begin(Intent intent) { return UUID.randomUUID(); }

                            @Override public void finish(UUID callId, Outcome outcome) { }
                        }, new AiTelemetry(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), base, Clock.systemUTC(),
                        duration -> { }, RandomGenerator.getDefault());

                long started = System.nanoTime();
                AiResult<TextResponse> result = router.generate(AiTestSupport.request());

                assertThat(result).isInstanceOf(AiResult.Ok.class);
                assertThat(((AiResult.Ok<TextResponse>) result).value().route().provider()).isEqualTo("gigachat");
                assertThat(silent.requests()).hasSize(3);
                assertThat(healthy.requests()).hasSize(1);
                // 20 s were available; the fallback answered after roughly three first-byte limits, not after the deadline
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
            }
        }
    }
}
