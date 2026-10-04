package app.mnema.learning.ai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The configuration of image search: what binds, which sources exist and are configured, and what is refused. */
class ImageSearchWiringTest {
    private static AiProperties bind(Map<String, Object> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bind("learning.ai", AiProperties.class).get();
    }

    private static final Map<String, Object> BASE = Map.of(
            "learning.ai.providers.pixabay.base-url", "https://pixabay.com/api/",
            "learning.ai.providers.pixabay.api-key", "KEY",
            "learning.ai.providers.openverse.base-url", "https://api.openverse.org/v1/",
            "learning.ai.providers.openverse.client-id", "ID",
            "learning.ai.providers.openverse.client-secret", "SECRET",
            "learning.ai.providers.openverse.egress", "proxy",
            "learning.ai.providers.wikimedia.base-url", "https://commons.wikimedia.org/w/api.php");

    @Test
    void theSourcesBindWithTheirCredentialsAndOpenverseDefaultsToTheProxyOnlyWhenConfiguredSo() {
        AiProperties bound = bind(BASE);

        assertThat(bound.providers().get("pixabay").apiKey()).isEqualTo("KEY");
        assertThat(bound.providers().get("openverse").clientId()).isEqualTo("ID");
        assertThat(bound.providers().get("openverse").clientSecret()).isEqualTo("SECRET");
        assertThat(bound.providers().get("openverse").egress()).isEqualTo(AiProperties.EgressMode.PROXY);
        assertThat(bound.providers().get("wikimedia").egress()).isEqualTo(AiProperties.EgressMode.DIRECT);
        assertThat(bound.providers().get("openverse").toString()).doesNotContain("ID\"").doesNotContain("SECRET");
        assertThat(bound.providers().get("pixabay").toString()).doesNotContain("KEY");
    }

    @Test
    void withoutAnActiveProxyOpenverseHasNoTransportAndIsNotConfiguredAndTheOthersAreAsConfigured() {
        AiProperties noProxy = bind(BASE);
        try (EgressClients clients = EgressClients.create(noProxy)) {
            List<ImageSource> sources = AiConfiguration.imageSources(noProxy, ImageSearchSettings.defaults(), clients, Clock.systemUTC());

            assertThat(sources).extracting(ImageSource::provider).containsExactly("pixabay", "openverse", "wikimedia");
            assertThat(sources).extracting(ImageSource::configured).containsExactly(true, false, true);
        }
        Map<String, Object> withProxy = new java.util.HashMap<>(BASE);
        withProxy.put("learning.ai.egress.proxy-url", "http://203.0.113.7:3128");
        AiProperties proxied = bind(withProxy);
        try (EgressClients clients = EgressClients.create(proxied)) {
            List<ImageSource> sources = AiConfiguration.imageSources(proxied, ImageSearchSettings.defaults(), clients, Clock.systemUTC());
            assertThat(sources).extracting(ImageSource::configured).containsExactly(true, true, true);
            assertThat(sources.get(1).egress()).isEqualTo(AiProperties.EgressMode.PROXY);
        }
    }

    @Test
    void aSourceWithoutItsCredentialsOrOutsideTheListIsNotAskedAndTheOrderIsTheConfiguredOne() {
        Map<String, Object> values = new java.util.HashMap<>(BASE);
        values.put("learning.ai.providers.pixabay.api-key", "");
        AiProperties properties = bind(values);
        ImageSearchSettings settings = new ImageSearchSettings(List.of("wikimedia", "pixabay"), Duration.ofHours(24), "Mnema/1.0 (x)", Duration.ofSeconds(5),
                Duration.ofSeconds(5));
        try (EgressClients clients = EgressClients.create(properties)) {
            List<ImageSource> sources = AiConfiguration.imageSources(properties, settings, clients, Clock.systemUTC());
            assertThat(sources).extracting(ImageSource::provider).containsExactly("wikimedia", "pixabay");
            assertThat(sources).extracting(ImageSource::configured).containsExactly(true, false);
        }
        // no provider entry at all: no source
        try (EgressClients clients = EgressClients.create(bind(Map.of("learning.ai.providers.deepseek.base-url", "https://api.deepseek.com")))) {
            assertThat(AiConfiguration.imageSources(bind(Map.of("learning.ai.providers.deepseek.base-url", "https://api.deepseek.com")),
                    ImageSearchSettings.defaults(), clients, Clock.systemUTC())).isEmpty();
        }
    }

    @Test
    void theSettingsBindWithDefaultsAndRefuseWhatWouldBreakTheTermsOrTheEtiquette() {
        ImageSearchSettings defaults = new Binder(new MapConfigurationPropertySource(Map.of())).bind("learning.ai.image-search", ImageSearchSettings.class)
                .orElseGet(ImageSearchSettings::defaults);
        assertThat(defaults.sources()).containsExactly("pixabay", "openverse", "wikimedia");
        assertThat(defaults.cacheTtl()).isEqualTo(Duration.ofHours(24));
        ImageSearchSettings bound = new Binder(new MapConfigurationPropertySource(Map.of("learning.ai.image-search.sources", "Wikimedia, pixabay,wikimedia",
                "learning.ai.image-search.user-agent", "Mnema/2 (https://example.org)"))).bind("learning.ai.image-search", ImageSearchSettings.class).get();
        assertThat(bound.sources()).containsExactly("wikimedia", "pixabay");
        assertThat(bound.userAgent()).isEqualTo("Mnema/2 (https://example.org)");

        assertThatThrownBy(() -> new ImageSearchSettings(List.of("pexels"), Duration.ofHours(24), "Mnema/1.0 (x)", Duration.ofSeconds(5), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        // Pixabay requires the answers to be cached for 24 hours
        assertThatThrownBy(() -> new ImageSearchSettings(List.of(), Duration.ofHours(1), "Mnema/1.0 (x)", Duration.ofSeconds(5), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageSearchSettings(List.of(), Duration.ofDays(30), "Mnema/1.0 (x)", Duration.ofSeconds(5), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageSearchSettings(List.of(), Duration.ofHours(24), "", Duration.ofSeconds(5), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageSearchSettings(List.of(), Duration.ofHours(24), "Mnema/1.0\r\nX: y", Duration.ofSeconds(5), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageSearchSettings(List.of(), Duration.ofHours(24), "Mnema/1.0 (x)", Duration.ZERO, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageSearchSettings(List.of(), Duration.ofHours(24), "Mnema/1.0 (x)", Duration.ofSeconds(5), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRequestIsBoundedAndNormalised() {
        var request = new ImageSearch.Request("  fox  ", null, 5, null, null, 0);
        assertThat(request.query()).isEqualTo("fox");
        assertThat(request.lang()).isEqualTo("en");
        assertThat(request.excludeKeys()).isEmpty();
        assertThat(request.attempt()).isEqualTo(1);
        assertThatThrownBy(() -> new ImageSearch.Request("x", "en", 0, null, null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageSearch.Request("x", "en", 51, null, null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ImageSearch.Source.WIKIMEDIA.label()).isEqualTo("Wikimedia Commons");
    }
}
