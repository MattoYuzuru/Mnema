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
@EnableConfigurationProperties({AiProperties.class, ImageSearchSettings.class, SpeechSettings.class, SttSettings.class, ResearchSettings.class})
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

    /**
     * The speech synthesis port (#297): the Stub with {@code learning.ai.provider=stub}, else the {@code learning.ai.routes.tts} entries over Gemini (a key
     * and, with {@code egress=proxy}, an active proxy) and SpeechKit (a key and a folder). The bean always exists; whether any entry can be called is
     * {@link SpeechSynthesis#configured()}, which the {@code textToSpeech} capability reads.
     */
    @Bean
    SpeechSynthesis speechSynthesis(AiProperties properties, SpeechSettings settings, EgressClients clients, BreakerRegistry breakers, AiBudget budget,
                                    JdbcCallJournal journal, MeterRegistry meters,
                                    @org.springframework.beans.factory.annotation.Value("${learning.generation.usd-rub-rate:85}") java.math.BigDecimal usdRubRate) {
        if (AiProperties.STUB.equals(properties.provider())) {
            LOG.warn("ai_stub_active learning.ai.provider=stub: speech synthesis is answered by the deterministic Stub");
            return new StubSpeechSynthesis();
        }
        Map<String, SpeechAdapter> adapters = speechAdapters(properties, settings, clients, usdRubRate, Clock.systemUTC());
        adapters.forEach((id, adapter) -> LOG.info("ai_speech_provider provider={} egress={} state={}", id, adapter.egress().label(),
                adapter.configured() ? "configured" : "not_configured"));
        return new RoutedSpeechSynthesis(properties, settings, adapters, breakers, budget, journal, new AiTelemetry(meters));
    }

    /**
     * The web search port of the research step (#299): the Stub with {@code learning.ai.provider=stub}, else the {@code learning.ai.routes.search} entries over
     * Yandex Search (a key and a folder, direct) and Perplexity (a key and, with {@code egress=proxy}, an active proxy). The bean always exists; whether any
     * entry can be called is {@link WebSearch#configured()}, which the {@code webSearch} capability reads.
     */
    @Bean
    WebSearch webSearch(AiProperties properties, ResearchSettings settings, EgressClients clients, BreakerRegistry breakers, AiBudget budget,
                        JdbcCallJournal journal, MeterRegistry meters,
                        @org.springframework.beans.factory.annotation.Value("${learning.generation.usd-rub-rate:85}") java.math.BigDecimal usdRubRate) {
        if (AiProperties.STUB.equals(properties.provider())) {
            LOG.warn("ai_stub_active learning.ai.provider=stub: web search is answered by the deterministic Stub");
            return new StubWebSearch();
        }
        Map<String, WebSearchAdapter> adapters = webSearchAdapters(properties, settings, clients, usdRubRate, Clock.systemUTC());
        adapters.forEach((id, adapter) -> LOG.info("ai_search_provider provider={} egress={} state={}", id, adapter.egress().label(),
                adapter.configured() ? "configured" : "not_configured"));
        return new RoutedWebSearch(properties, settings, adapters, breakers, budget, journal, new AiTelemetry(meters));
    }

    /** The web search adapters whose provider entry ({@code yandex-search}, {@code perplexity}) exists; a proxied provider without an active proxy has no transport. */
    static Map<String, WebSearchAdapter> webSearchAdapters(AiProperties properties, ResearchSettings settings, EgressClients clients,
                                                           java.math.BigDecimal usdRubRate, Clock clock) {
        Map<String, WebSearchAdapter> adapters = new LinkedHashMap<>();
        AiProperties.Provider yandex = properties.providers().get("yandex-search");
        if (yandex != null && yandex.egress() != AiProperties.EgressMode.DIRECT) {
            // a Russian counterparty is reached from this server, never through the gateway
            throw new IllegalArgumentException("learning.ai.providers.yandex-search.egress must be direct");
        }
        if (yandex != null) adapters.put(YandexWebSearch.PROVIDER, new YandexWebSearch(yandex, clients.http(yandex.egress()), settings, usdRubRate, clock));
        AiProperties.Provider perplexity = properties.providers().get("perplexity");
        if (perplexity != null) adapters.put(PerplexityWebSearch.PROVIDER, new PerplexityWebSearch(perplexity, clients.http(perplexity.egress()), settings, clock));
        return adapters;
    }

    /**
     * The speech-to-text port (#298): the Stub with {@code learning.ai.provider=stub}, else the {@code learning.ai.routes.stt} / {@code stt-ru} entries over
     * the self-hosted OpenAI-compatible container ({@code selfhost}: a base URL) and Gemini ({@code google}: a key and, with {@code egress=proxy}, an
     * active proxy). The bean always exists; whether any entry can be called is {@link Transcription#configured()}, which the {@code speechToText}
     * capability reads.
     */
    @Bean
    Transcription transcription(AiProperties properties, SttSettings settings, EgressClients clients, BreakerRegistry breakers, AiBudget budget,
                                JdbcCallJournal journal, MeterRegistry meters) {
        if (AiProperties.STUB.equals(properties.provider())) {
            LOG.warn("ai_stub_active learning.ai.provider=stub: speech to text is answered by the deterministic Stub");
            return new StubTranscription();
        }
        Map<String, TranscriptionAdapter> adapters = transcriptionAdapters(properties, settings, clients, Clock.systemUTC());
        adapters.forEach((id, adapter) -> LOG.info("ai_stt_provider provider={} egress={} state={}", id, adapter.egress().label(),
                adapter.configured() ? "configured" : "not_configured"));
        return new RoutedTranscription(properties, settings, adapters, breakers, budget, journal, new AiTelemetry(meters), meters);
    }

    /** The transcription adapters whose provider entry exists; a proxied provider without an active proxy has no transport and so is not configured. */
    static Map<String, TranscriptionAdapter> transcriptionAdapters(AiProperties properties, SttSettings settings, EgressClients clients, Clock clock) {
        Map<String, TranscriptionAdapter> adapters = new LinkedHashMap<>();
        Map<String, AiProperties.Model> prices = new HashMap<>();
        for (AiProperties.Model model : properties.models()) {
            if (model.provider().equals(GeminiTranscription.PROVIDER)) prices.put(model.id(), model);
        }
        AiProperties.Provider google = properties.providers().get(GeminiTranscription.PROVIDER);
        if (google != null) adapters.put(GeminiTranscription.PROVIDER, new GeminiTranscription(google, clients.http(google.egress()), settings, prices::get, clock));
        AiProperties.Provider selfhost = properties.providers().get(SelfHostTranscription.PROVIDER);
        if (selfhost != null) adapters.put(SelfHostTranscription.PROVIDER, new SelfHostTranscription(selfhost, clients.http(selfhost.egress()), settings, clock));
        return adapters;
    }

    /** The speech adapters whose provider entry exists; a proxied provider without an active proxy has no transport and so is not configured. */
    static Map<String, SpeechAdapter> speechAdapters(AiProperties properties, SpeechSettings settings, EgressClients clients, java.math.BigDecimal usdRubRate,
                                                     Clock clock) {
        Map<String, SpeechAdapter> adapters = new LinkedHashMap<>();
        Map<String, AiProperties.Model> prices = new HashMap<>();
        for (AiProperties.Model model : properties.models()) {
            if (model.provider().equals(GeminiSpeechSynthesis.PROVIDER)) prices.put(model.id(), model);
        }
        AiProperties.Provider google = properties.providers().get(GeminiSpeechSynthesis.PROVIDER);
        if (google != null) adapters.put(GeminiSpeechSynthesis.PROVIDER, new GeminiSpeechSynthesis(google, clients.http(google.egress()), settings, prices::get, clock));
        AiProperties.Provider yandex = properties.providers().get(YandexSpeechSynthesis.PROVIDER);
        if (yandex != null) adapters.put(YandexSpeechSynthesis.PROVIDER, new YandexSpeechSynthesis(yandex, clients.http(yandex.egress()), settings, usdRubRate, clock));
        return adapters;
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
