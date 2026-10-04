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
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.random.RandomGenerator;

/** Wires the provider layer. Providers without a base URL or switched off do not get an adapter. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AiProperties.class, ImageSearchSettings.class})
class AiConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(AiConfiguration.class);

    @Bean(destroyMethod = "close")
    EgressClients aiEgressClients(AiProperties properties) { return EgressClients.create(properties); }

    @Bean
    AiRouting aiRouting(AiProperties properties, EgressClients clients) {
        return new AiRouting(properties, adapters(properties, clients, Clock.systemUTC()));
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

    /**
     * The licensed image search port (#296): the Stub with {@code learning.ai.provider=stub}, else the configured sources (Pixabay with a key,
     * Openverse with client credentials and the egress proxy, Wikimedia Commons without a key). The bean always exists; whether any source can be
     * called is {@link ImageSearch#configured()}, which the {@code imageSearch} capability reads.
     */
    @Bean
    ImageSearch imageSearch(AiProperties properties, ImageSearchSettings settings, EgressClients clients, BreakerRegistry breakers, AiBudget budget,
                            JdbcCallJournal journal, MeterRegistry meters, JdbcImageSearchCache cache) {
        if (AiProperties.STUB.equals(properties.provider())) {
            LOG.warn("ai_stub_active learning.ai.provider=stub: image search is answered by the deterministic Stub");
            return new StubImageSearch();
        }
        List<ImageSource> sources = imageSources(properties, settings, clients, Clock.systemUTC());
        for (ImageSource source : sources) {
            LOG.info("ai_image_source source={} egress={} state={}", source.provider(), source.egress().label(),
                    source.configured() ? "configured" : "not_configured");
        }
        return new RoutedImageSearch(sources, cache, new SafeImageFetcher(true, ImageAddressPolicy.Resolver.SYSTEM, settings.userAgent()), clients,
                breakers, budget, journal, new AiTelemetry(meters), properties, settings);
    }

    /** The sources of {@code learning.ai.image-search.sources}, in that order; a source without a provider entry is absent. */
    static List<ImageSource> imageSources(AiProperties properties, ImageSearchSettings settings, EgressClients clients, Clock clock) {
        List<ImageSource> sources = new java.util.ArrayList<>();
        for (String name : settings.sources()) {
            AiProperties.Provider provider = properties.providers().get(name);
            if (provider == null) continue;
            ChatHttp http = clients.http(provider.egress());
            sources.add(switch (name) {
                case "pixabay" -> new PixabayImageSource(provider, http, settings.userAgent(), clock);
                case "openverse" -> new OpenverseImageSource(provider, http, settings.userAgent(), clock);
                default -> new WikimediaImageSource(provider, http, settings.userAgent(), clock);
            });
        }
        return sources;
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

    /** The {@code ai-semantic} grader; whether it may run is {@code AiAvailability.assessment()}, not the presence of this bean. */
    @Bean(destroyMethod = "close")
    SemanticGrader semanticGrader(TextGeneration text, PromptAssembler assembler, UserKeys userKeys, AiProperties properties) {
        return new SemanticGrader(text, assembler, userKeys, AiProperties.STUB.equals(properties.provider()));
    }

    static Map<String, TextAdapter> adapters(AiProperties properties, ChatHttp http, Clock clock) {
        return adapters(properties, EgressClients.direct(http), clock);
    }

    static Map<String, TextAdapter> adapters(AiProperties properties, EgressClients clients, Clock clock) {
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
            ChatHttp http = clients.http(provider.egress());
            if (provider.egress() == AiProperties.EgressMode.PROXY) {
                // Values are never logged: neither the proxy address nor its credentials.
                LOG.info("ai_egress provider={} mode=proxy state={}", entry.getKey(), http == null ? "not_configured" : "configured");
            }
            // A proxied provider without an active proxy has no adapter, so its capability is NOT_CONFIGURED and the route falls back.
            if (http == null) continue;
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
