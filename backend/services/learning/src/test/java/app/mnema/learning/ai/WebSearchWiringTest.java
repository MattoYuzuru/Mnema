package app.mnema.learning.ai;

import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import app.mnema.learning.capability.LearningCapabilities;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The shipped {@code application.properties} of web research, bound by Spring with a fake key (never a network call: nothing here searches). */
@SpringBootTest(properties = {"learning.runtime.roles=all", "learning.features.web-search.enabled=true", "learning.ai.provider=",
        "learning.ai.providers.yandex-search.api-key=test-key-not-real", "learning.ai.research.yandex-folder-id=b1gtestfolder",
        "spring.datasource.hikari.maximum-pool-size=2"})
class WebSearchWiringTest extends PostgresIntegrationTest {
    private static final String DATABASE = createDatabase("search_wiring_" + java.util.UUID.randomUUID().toString().replace("-", ""));
    @org.springframework.test.context.DynamicPropertySource
    static void isolatedDatabase(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE);
        registry.add("spring.flyway.url", () -> DATABASE);
    }
    @Autowired private AiProperties properties;
    @Autowired private ResearchSettings research;
    @Autowired private WebSearch webSearch;
    @Autowired private LearningCapabilities capabilities;

    @Test
    void theShippedDefaultsBindYandexAsTheOnlyRouteAndAKeyAndAFolderMakeTheCapabilityAvailable() {
        assertThat(properties.routes().search()).containsExactly("yandex");
        assertThat(properties.providers().get("yandex-search").baseUrl()).isEqualTo("https://searchapi.api.cloud.yandex.net");
        assertThat(properties.providers().get("yandex-search").egress()).isEqualTo(AiProperties.EgressMode.DIRECT);
        assertThat(properties.providers().get("perplexity").egress()).isEqualTo(AiProperties.EgressMode.PROXY);
        // the key never reaches a log line or a message through toString
        assertThat(properties.providers().get("yandex-search").toString()).doesNotContain("test-key-not-real");
        assertThat(research).isEqualTo(new ResearchSettings(15, 30, 5, Duration.ofSeconds(10), Duration.ofSeconds(90), "b1gtestfolder", "225",
                new java.math.BigDecimal("0.488"), new java.math.BigDecimal("0.005")));
        assertThat(webSearch).isInstanceOf(RoutedWebSearch.class);
        assertThat(webSearch.configured()).isTrue();
        assertThat(capabilities.webSearch()).isEqualTo(new LearningCapabilities.Status(true, null));
    }

    @Test
    void routesAndSettingsBindFromTheirPropertyNames() {
        var binder = new Binder(new MapConfigurationPropertySource(Map.of("learning.ai.routes.search", "yandex, perplexity",
                "learning.ai.research.max-requests", "4", "learning.ai.research.max-results", "10", "learning.ai.research.deadline", "PT2M",
                "learning.ai.research.yandex-rub-per-request", "0.5")));
        assertThat(binder.bind("learning.ai", AiProperties.class).get().routes().search()).containsExactly("yandex", "perplexity");
        ResearchSettings bound = binder.bind("learning.ai.research", ResearchSettings.class).get();
        assertThat(bound.maxRequests()).isEqualTo(4);
        assertThat(bound.maxResults()).isEqualTo(10);
        assertThat(bound.deadline()).isEqualTo(Duration.ofMinutes(2));
        assertThat(bound.yandexRubPerRequest()).isEqualByComparingTo("0.5");
        assertThat(bound.cap("DETAILED")).isEqualTo(4);
    }
}
