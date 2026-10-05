package app.mnema.learning.ai;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The speech adapters of a configuration: the egress proxy, the kill switch and the keys decide which route entries can be called. */
class SpeechWiringTest {
    private static AiProperties.Provider google(boolean enabled, String key) {
        return new AiProperties.Provider(enabled, "https://generativelanguage.googleapis.com", key, "", "", "", AiProperties.EgressMode.PROXY);
    }

    private static AiProperties.Provider yandex(String key) {
        return new AiProperties.Provider(true, "https://tts.api.cloud.yandex.net", key, "", "", "", AiProperties.EgressMode.DIRECT);
    }

    private static AiProperties withProxy(AiProperties base, AiProperties.Egress egress) {
        return new AiProperties(base.provider(), base.routes(), base.providers(), base.models(), base.transport(), base.retry(), base.breaker(), base.permits(),
                base.budget(), base.userKey(), base.prompt(), egress);
    }

    private boolean configured(AiProperties properties, SpeechSettings settings) {
        try (EgressClients clients = EgressClients.create(properties)) {
            Map<String, SpeechAdapter> adapters = AiConfiguration.speechAdapters(properties, settings, clients, SpeechTestSupport.RATE, Clock.systemUTC());
            return new RoutedSpeechSynthesis(properties, settings, adapters, new BreakerRegistry(Clock.systemUTC(), properties.breaker()),
                    new AiBudget(properties.budget(), (capability, since) -> 0, Clock.systemUTC()), new SpeechTestSupport.RecordingJournal(),
                    new AiTelemetry(new io.micrometer.core.instrument.simple.SimpleMeterRegistry())).configured();
        }
    }

    @Test
    void geminiThroughTheProxyIsNotConfiguredWithoutAnActiveProxyAndIsWithOne() {
        AiProperties base = SpeechTestSupport.properties(List.of("google:gemini-3.8-flash-tts"), List.of(), Map.of("google", google(true, "k")));
        SpeechSettings settings = SpeechSettings.defaults();

        assertThat(configured(base, settings)).isFalse();
        assertThat(configured(withProxy(base, new AiProperties.Egress("http://proxy.invalid:3128", "", "", false)), settings)).isFalse();
        assertThat(configured(withProxy(base, new AiProperties.Egress("http://proxy.invalid:3128", "", "", true)), settings)).isTrue();
        // the per-provider kill switch
        AiProperties off = SpeechTestSupport.properties(List.of("google:gemini-3.8-flash-tts"), List.of(), Map.of("google", google(false, "k")));
        assertThat(configured(withProxy(off, new AiProperties.Egress("http://proxy.invalid:3128", "", "", true)), settings)).isFalse();
        // no key
        AiProperties keyless = SpeechTestSupport.properties(List.of("google:gemini-3.8-flash-tts"), List.of(), Map.of("google", google(true, "")));
        assertThat(configured(withProxy(keyless, new AiProperties.Egress("http://proxy.invalid:3128", "", "", true)), settings)).isFalse();
    }

    @Test
    void yandexNeedsAKeyAndAFolderButNoProxy() {
        AiProperties properties = SpeechTestSupport.properties(List.of(), List.of("yandex:speechkit-v1"), Map.of("yandex", yandex("k")));
        SpeechSettings withFolder = new SpeechSettings(SpeechSettings.defaults().cacheTtl(), 600, "v1", "s", "Kore", "Charon", "alena", "filipp", "b1gFOLDER",
                java.math.BigDecimal.valueOf(1342), java.time.Duration.ofSeconds(45), java.time.Duration.ofMinutes(2));
        assertThat(configured(properties, SpeechSettings.defaults())).isFalse();
        assertThat(configured(properties, withFolder)).isTrue();
        assertThat(configured(SpeechTestSupport.properties(List.of(), List.of("yandex:speechkit-v1"), Map.of("yandex", yandex(""))), withFolder)).isFalse();
    }

    @Test
    void aProviderWithoutAnEntryHasNoAdapter() {
        AiProperties properties = SpeechTestSupport.properties(List.of(), List.of(), Map.of());
        try (EgressClients clients = EgressClients.create(properties)) {
            assertThat(AiConfiguration.speechAdapters(properties, SpeechSettings.defaults(), clients, SpeechTestSupport.RATE, Clock.systemUTC())).isEmpty();
        }
    }
}
