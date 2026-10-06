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
 * {@code MNEMA_AI_GIGACHAT_AUTH_KEY}), or OpenRouter alone ({@link #openRouter()}, {@code MNEMA_AI_OPENROUTER_API_KEY}). Key values are
 * never printed or stored.
 */
public final class EvalStack implements AutoCloseable {
    private final ChatHttp http;
    private final TextGeneration text;

    private EvalStack(ChatHttp http, TextGeneration text) {
        this.http = http;
        this.text = text;
    }

    /** OpenRouter alone on every route, as the fallback of the direct DeepSeek route would be used (owner decision 2026-10-04). */
    public static EvalStack openRouter() {
        Map<String, AiProperties.Provider> providers = new LinkedHashMap<>();
        providers.put("openrouter", new AiProperties.Provider(true, "https://openrouter.ai/api/v1", env("MNEMA_AI_OPENROUTER_API_KEY"), "", "", ""));
        List<String> flash = List.of("openrouter:deepseek/deepseek-v4.1-flash");
        AiProperties base = AiTestSupport.properties("", AiTestSupport.routes(flash, List.of("openrouter:deepseek/deepseek-v4-pro"), flash),
                providers);
        List<AiProperties.Model> models = List.of(new AiProperties.Model("openrouter", "deepseek/deepseek-v4.1-flash", 6_000, 300_000, 1_200_000),
                new AiProperties.Model("openrouter", "deepseek/deepseek-v4-pro", 44_000, 1_320_000, 3_960_000));
        return assemble(new AiProperties(base.provider(), base.routes(), base.providers(), models, base.transport(), base.retry(),
                base.breaker(), base.permits(), base.budget(), base.userKey(), base.prompt()));
    }

    /**
     * The text route of {@code application.properties} as production runs it: DeepSeek direct (flash, then pro on the strong route) with
     * OpenRouter's DeepSeek as the fallback. Keys come from {@code MNEMA_AI_DEEPSEEK_API_KEY} and {@code MNEMA_AI_OPENROUTER_API_KEY}.
     */
    public static EvalStack production() {
        Map<String, AiProperties.Provider> providers = new LinkedHashMap<>();
        providers.put("deepseek", new AiProperties.Provider(true, "https://api.deepseek.com", env("MNEMA_AI_DEEPSEEK_API_KEY"), "", "", ""));
        providers.put("openrouter", new AiProperties.Provider(true, "https://openrouter.ai/api/v1", env("MNEMA_AI_OPENROUTER_API_KEY"), "", "", ""));
        List<String> fast = List.of("deepseek:deepseek-flash", "openrouter:deepseek/deepseek-v4.1-flash");
        List<String> strong = List.of("deepseek:deepseek-v4-pro", "openrouter:deepseek/deepseek-v4-pro");
        AiProperties base = AiTestSupport.properties("", AiTestSupport.routes(fast, strong, fast), providers);
        List<AiProperties.Model> models = List.of(AiTestSupport.FLASH, AiTestSupport.PRO,
                new AiProperties.Model("openrouter", "deepseek/deepseek-v4.1-flash", 6_000, 300_000, 1_200_000),
                new AiProperties.Model("openrouter", "deepseek/deepseek-v4-pro", 44_000, 1_320_000, 3_960_000));
        return assemble(new AiProperties(base.provider(), base.routes(), base.providers(), models, base.transport(), base.retry(),
                base.breaker(), base.permits(), base.budget(), base.userKey(), base.prompt()));
    }

    /**
     * One judge model of the golden eval, alone, through OpenRouter on every route; the prices (micro-USD per million tokens) come from
     * {@code https://openrouter.ai/api/v1/models} and are used for the cost of the judge calls only.
     */
    public static EvalStack judge(String model, long inputMicrosPerMillion, long outputMicrosPerMillion) {
        Map<String, AiProperties.Provider> providers = new LinkedHashMap<>();
        providers.put("openrouter", new AiProperties.Provider(true, "https://openrouter.ai/api/v1", env("MNEMA_AI_OPENROUTER_API_KEY"), "", "", ""));
        List<String> route = List.of("openrouter:" + model);
        AiProperties base = AiTestSupport.properties("", AiTestSupport.routes(route, route, route), providers);
        List<AiProperties.Model> models = List.of(new AiProperties.Model("openrouter", model, inputMicrosPerMillion, inputMicrosPerMillion,
                outputMicrosPerMillion));
        return assemble(new AiProperties(base.provider(), base.routes(), base.providers(), models, base.transport(), base.retry(),
                base.breaker(), base.permits(), base.budget(), base.userKey(), base.prompt()));
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
        return assemble(base);
    }

    private static EvalStack assemble(AiProperties base) {
        // Real latencies: the production transport limits, not the short ones of the loopback tests.
        AiProperties properties = new AiProperties(base.provider(), base.routes(), base.providers(), base.models(),
                new AiProperties.Transport(Duration.ofSeconds(5), Duration.ofSeconds(60), 4 * 1024 * 1024, Duration.ofSeconds(60)),
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
