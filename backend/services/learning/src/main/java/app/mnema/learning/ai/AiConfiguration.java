package app.mnema.learning.ai;

import app.mnema.learning.ai.prompt.PromptAssembler;
import app.mnema.learning.ai.prompt.PromptLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.random.RandomGenerator;

/** Wires the provider layer. Providers without a base URL or switched off do not get an adapter. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProperties.class)
class AiConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(AiConfiguration.class);

    @Bean(destroyMethod = "close")
    ChatHttp aiChatHttp(AiProperties properties) { return new ChatHttp(properties.transport()); }

    @Bean
    AiRouting aiRouting(AiProperties properties, ChatHttp http) {
        return new AiRouting(properties, adapters(properties, http, Clock.systemUTC()));
    }

    @Bean
    BreakerRegistry aiBreakers(AiProperties properties) {
        return new BreakerRegistry(new MonotonicClock(), properties.breaker());
    }

    @Bean
    AiBudget aiBudget(AiProperties properties, JdbcCallJournal journal) {
        return new AiBudget(properties.budget(), journal, Clock.systemUTC());
    }

    @Bean
    TextGeneration textGeneration(AiRouting routing, BreakerRegistry breakers, AiBudget budget, JdbcCallJournal journal,
                                  MeterRegistry meters, AiProperties properties) {
        return new RoutedTextGeneration(routing, breakers, budget, journal, new AiTelemetry(meters), properties,
                new MonotonicClock(), Sleeper.SYSTEM, RandomGenerator.getDefault());
    }

    @Bean
    AiAvailability aiAvailability(AiRouting routing, BreakerRegistry breakers, AiBudget budget, UserKeys userKeys,
                                  AiProperties properties) {
        return new DefaultAiAvailability(routing, breakers, budget, userKeys, AiProperties.STUB.equals(properties.provider()));
    }

    @Bean
    UserKeys userKeys(AiProperties properties) { return new UserKeys(properties.userKey()); }

    @Bean
    PromptLibrary promptLibrary(AiProperties properties) {
        return PromptLibrary.fromClasspath(properties.prompt().version());
    }

    @Bean
    PromptAssembler promptAssembler(PromptLibrary library, AiProperties properties) {
        return new PromptAssembler(library, properties.prompt());
    }

    static Map<String, TextAdapter> adapters(AiProperties properties, ChatHttp http, Clock clock) {
        Map<String, TextAdapter> adapters = new LinkedHashMap<>();
        if (AiProperties.STUB.equals(properties.provider())) {
            // Only this explicit setting registers the Stub; it must never be active in production.
            LOG.warn("ai_stub_active learning.ai.provider=stub: every AI text call is answered by the deterministic Stub");
            adapters.put(StubTextAdapter.PROVIDER, new StubTextAdapter());
        }
        Map<String, Map<String, AiProperties.Model>> prices = new HashMap<>();
        for (AiProperties.Model model : properties.models()) {
            prices.computeIfAbsent(model.provider(), key -> new HashMap<>()).put(model.id(), model);
        }
        for (var entry : Map.of("deepseek", OpenAiCompatibleAdapter.Dialect.DEEPSEEK,
                "gigachat", OpenAiCompatibleAdapter.Dialect.GIGACHAT,
                "openrouter", OpenAiCompatibleAdapter.Dialect.OPENROUTER).entrySet()) {
            AiProperties.Provider provider = properties.providers().get(entry.getKey());
            if (provider == null || !provider.enabled() || provider.baseUrl().isEmpty()) continue;
            BearerSource bearer = entry.getValue() == OpenAiCompatibleAdapter.Dialect.GIGACHAT
                    ? new GigaChatTokens(provider.authUrl().isEmpty() ? null : URI.create(provider.authUrl()),
                            provider.authKey(), provider.scope(), http, clock)
                    : BearerSource.staticKey(provider.apiKey());
            adapters.put(entry.getKey(), new OpenAiCompatibleAdapter(entry.getKey(), entry.getValue(),
                    URI.create(provider.baseUrl()), bearer, http, prices.getOrDefault(entry.getKey(), Map.of()), clock));
        }
        return adapters;
    }
}
