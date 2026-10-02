package app.mnema.learning.ai;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * The provider layer assembled by hand for the opt-in eval and live tests (no Spring, no database): the Stub, or the
 * real routes with keys read from the environment ({@code MNEMA_AI_DEEPSEEK_API_KEY}, optional
 * {@code MNEMA_AI_GIGACHAT_AUTH_KEY}). Key values are never printed or stored.
 */
public final class EvalStack implements AutoCloseable {
    private final ChatHttp http;
    private final TextGeneration text;

    private EvalStack(ChatHttp http, TextGeneration text) {
        this.http = http;
        this.text = text;
    }

    public static EvalStack create(boolean live) {
        Map<String, AiProperties.Provider> providers = new LinkedHashMap<>();
        if (live) {
            providers.put("deepseek", new AiProperties.Provider(true, "https://api.deepseek.com",
                    env("MNEMA_AI_DEEPSEEK_API_KEY"), "", "", ""));
            providers.put("gigachat", new AiProperties.Provider(true, "https://gigachat.devices.sberbank.ru/api/v1", "",
                    "https://ngw.devices.sberbank.ru:9443/api/v2/oauth", env("MNEMA_AI_GIGACHAT_AUTH_KEY"), ""));
        }
        AiProperties base = AiTestSupport.properties(live ? "" : "stub", AiTestSupport.routes(
                List.of("deepseek:deepseek-flash", "gigachat:GigaChat-2"), List.of("deepseek:deepseek-v4-pro"),
                List.of("deepseek:deepseek-flash")), providers);
        // Real latencies: the production transport limits, not the short ones of the loopback tests.
        AiProperties properties = new AiProperties(base.provider(), base.routes(), base.providers(), base.models(),
                new AiProperties.Transport(Duration.ofSeconds(5), Duration.ofSeconds(60), 4 * 1024 * 1024),
                new AiProperties.Retry(3, 6, Duration.ofMillis(500), Duration.ofSeconds(8), Duration.ofSeconds(30)),
                base.breaker(), base.permits(), new AiProperties.Budget("Europe/Moscow", 0, 0, 0, 0, 0, 0, 0, Duration.ofSeconds(10)),
                base.userKey(), base.prompt());
        ChatHttp http = new ChatHttp(properties.transport());
        var routing = new AiRouting(properties, AiConfiguration.adapters(properties, http, Clock.systemUTC()));
        var router = new RoutedTextGeneration(routing, new BreakerRegistry(Clock.systemUTC(), properties.breaker()),
                new AiBudget(properties.budget(), (capability, since) -> 0, Clock.systemUTC()), new CallJournal() {
                    @Override public UUID begin(Intent intent) { return UUID.randomUUID(); }

                    @Override public void finish(UUID callId, Outcome outcome) { }
                }, new AiTelemetry(new SimpleMeterRegistry()), properties, Clock.systemUTC(), Sleeper.SYSTEM,
                RandomGenerator.getDefault());
        return new EvalStack(http, router);
    }

    public TextGeneration text() { return text; }

    @Override
    public void close() { http.close(); }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }
}
