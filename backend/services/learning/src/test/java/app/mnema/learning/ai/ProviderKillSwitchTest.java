package app.mnema.learning.ai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code learning.ai.providers.<id>.enabled=false}, the per-provider kill switch, for every kind of provider the layer has: text, speech synthesis, speech to
 * text, web search and an image source. Each is configured with everything it needs (a key, a folder, a proxy) and is then switched off, which is the only
 * difference between the two runs; the switched-off one has no usable adapter, so its capability falls back or reports {@code PROVIDER_NOT_CONFIGURED}.
 * Also the credential placeholder of an {@code api} process whose worker holds the keys.
 */
class ProviderKillSwitchTest {
    private static final Map<String, Object> BASE = Map.ofEntries(
            Map.entry("learning.ai.egress.proxy-url", "http://127.0.0.1:3128"),
            Map.entry("learning.ai.providers.deepseek.base-url", "https://api.deepseek.com"),
            Map.entry("learning.ai.providers.deepseek.api-key", "K"),
            Map.entry("learning.ai.providers.google.base-url", "https://generativelanguage.googleapis.com"),
            Map.entry("learning.ai.providers.google.api-key", "K"),
            Map.entry("learning.ai.providers.google.egress", "proxy"),
            Map.entry("learning.ai.providers.yandex-search.base-url", "https://searchapi.api.cloud.yandex.net"),
            Map.entry("learning.ai.providers.yandex-search.api-key", "K"),
            Map.entry("learning.ai.providers.pixabay.base-url", "https://pixabay.com/api/"),
            Map.entry("learning.ai.providers.pixabay.api-key", "K"),
            Map.entry("learning.ai.models[0].provider", "deepseek"), Map.entry("learning.ai.models[0].id", "deepseek-flash"),
            Map.entry("learning.ai.models[0].hit-micros-per-million", "1"), Map.entry("learning.ai.models[0].miss-micros-per-million", "1"),
            Map.entry("learning.ai.models[0].output-micros-per-million", "1"),
            Map.entry("learning.ai.models[1].provider", "google"), Map.entry("learning.ai.models[1].id", "gemini-3.8-flash-tts"),
            Map.entry("learning.ai.models[1].hit-micros-per-million", "1"), Map.entry("learning.ai.models[1].miss-micros-per-million", "1"),
            Map.entry("learning.ai.models[1].output-micros-per-million", "1"),
            Map.entry("learning.ai.models[2].provider", "google"), Map.entry("learning.ai.models[2].id", "gemini-3.5-flash-lite"),
            Map.entry("learning.ai.models[2].hit-micros-per-million", "1"), Map.entry("learning.ai.models[2].miss-micros-per-million", "1"),
            Map.entry("learning.ai.models[2].output-micros-per-million", "1"));

    private static AiProperties bind(String off) {
        Map<String, Object> values = new HashMap<>(BASE);
        if (off != null) values.put("learning.ai.providers." + off + ".enabled", "false");
        return new Binder(new MapConfigurationPropertySource(values)).bind("learning.ai", AiProperties.class).get();
    }

    private static boolean text(AiProperties properties) {
        try (EgressClients clients = EgressClients.create(properties)) {
            return AiConfiguration.adapters(properties, clients, Clock.systemUTC()).containsKey("deepseek");
        }
    }

    private static boolean speech(AiProperties properties) {
        try (EgressClients clients = EgressClients.create(properties)) {
            return AiConfiguration.speechAdapters(properties, SpeechSettings.defaults(), clients, BigDecimal.valueOf(85), Clock.systemUTC())
                    .get("google").configured();
        }
    }

    private static boolean transcription(AiProperties properties) {
        try (EgressClients clients = EgressClients.create(properties)) {
            return AiConfiguration.transcriptionAdapters(properties, SttSettings.defaults(), clients, Clock.systemUTC()).get("google").configured();
        }
    }

    private static boolean search(AiProperties properties) {
        try (EgressClients clients = EgressClients.create(properties)) {
            ResearchSettings withFolder = new ResearchSettings(15, 30, 5, java.time.Duration.ofSeconds(10), java.time.Duration.ofSeconds(90), "b1gFOLDER", "225",
                    new BigDecimal("0.488"), new BigDecimal("0.005"));
            return AiConfiguration.webSearchAdapters(properties, withFolder, clients, BigDecimal.valueOf(85), Clock.systemUTC()).get("yandex").configured();
        }
    }

    private static boolean image(AiProperties properties) {
        try (EgressClients clients = EgressClients.create(properties)) {
            return AiConfiguration.imageSources(properties, ImageSearchSettings.defaults(), clients, Clock.systemUTC()).getFirst().configured();
        }
    }

    @Test
    void everyKindOfProviderIsUsableWithItsConfigurationAndNotOnceItsSwitchIsOff() {
        AiProperties on = bind(null);
        assertThat(text(on)).isTrue();
        assertThat(speech(on)).isTrue();
        assertThat(transcription(on)).isTrue();
        assertThat(search(on)).isTrue();
        assertThat(image(on)).isTrue();

        assertThat(text(bind("deepseek"))).as("text").isFalse();
        assertThat(speech(bind("google"))).as("speech synthesis").isFalse();
        assertThat(transcription(bind("google"))).as("speech to text").isFalse();
        assertThat(search(bind("yandex-search"))).as("web search").isFalse();
        assertThat(image(bind("pixabay"))).as("image source").isFalse();
        // one provider's switch leaves the others alone
        assertThat(text(bind("google"))).isTrue();
        assertThat(speech(bind("deepseek"))).isTrue();
    }

    @Test
    void anApiProcessWhoseWorkerHoldsTheKeysAssumesThemAndNeverPrintsOrKeepsARealOne() {
        Map<String, Object> values = new HashMap<>(BASE);
        values.remove("learning.ai.providers.deepseek.api-key");
        AiProperties keyless = new Binder(new MapConfigurationPropertySource(values)).bind("learning.ai", AiProperties.class).get();
        assertThat(text(keyless)).as("no key, no adapter").isTrue();
        try (EgressClients clients = EgressClients.create(keyless)) {
            assertThat(AiConfiguration.adapters(keyless, clients, Clock.systemUTC()).get("deepseek").configured()).isFalse();
            AiProperties held = keyless.withWorkerHeldCredentials();
            assertThat(AiConfiguration.adapters(held, clients, Clock.systemUTC()).get("deepseek").configured()).isTrue();
            assertThat(held.userKey().configured()).isTrue();
            assertThat(held.providers().get("deepseek").toString()).doesNotContain(AiProperties.WORKER_HELD);
            // a key that is set is kept, never replaced
            assertThat(held.providers().get("pixabay").apiKey()).isEqualTo("K");
            assertThat(keyless.withWorkerHeldCredentials().providers().get("google").egress()).isEqualTo(AiProperties.EgressMode.PROXY);
        }
    }

    @Test
    void theModeIsValidatedAndOnlyAnApiProcessMayAssumeAWorkersKeys() {
        AiConfiguration configuration = new AiConfiguration();
        AiProperties properties = bind(null);
        assertThat(configuration.effectiveAi(properties, "local", "all").properties()).isSameAs(properties);
        assertThat(configuration.effectiveAi(properties, " Worker ", "api").properties()).isNotSameAs(properties);
        assertThatThrownBy(() -> configuration.effectiveAi(properties, "worker", "all")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> configuration.effectiveAi(properties, "worker", "worker")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> configuration.effectiveAi(properties, "remote", "api")).isInstanceOf(IllegalStateException.class);
    }
}
