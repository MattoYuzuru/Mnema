package app.mnema.learning.ai;

import app.mnema.learning.capability.LearningCapabilities;
import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shipped {@code application.properties} bound by Spring: default routes, price table and providers, with a fake key
 * in the properties (never a network call: nothing here generates).
 */
@SpringBootTest(properties = {"learning.runtime.roles=all", "learning.features.ai-generation.enabled=true", "learning.ai.provider=",
        "learning.ai.providers.deepseek.api-key=test-key-not-real", "learning.ai.providers.gigachat.auth-key=",
        "learning.ai.providers.openrouter.api-key=", "learning.ai.user-key.secret=0123456789abcdef0123456789abcdef", "spring.datasource.hikari.maximum-pool-size=2"})
class AiKeyedConfigurationIntegrationTest extends PostgresIntegrationTest {
    // Keys belong to a worker-capable role; isolate its database so it can never pick up work from another test and call a fake-key provider.
    private static final String DATABASE = createDatabase("ai_keyed_" + UUID.randomUUID().toString().replace("-", ""));
    @org.springframework.test.context.DynamicPropertySource
    static void isolatedDatabase(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> DATABASE);
        registry.add("spring.flyway.url", () -> DATABASE);
    }
    @Autowired private AiProperties properties;
    @Autowired private LearningCapabilities capabilities;
    @Autowired private UserKeys userKeys;

    @Test
    void aKeyAndTheUserKeySecretMakeAiGenerationAvailableAndTheDefaultsBind() {
        assertThat(capabilities.aiGeneration()).isEqualTo(new LearningCapabilities.Status(true, null));
        // owner decision 2026-10-04: OpenRouter (the same DeepSeek models) is the fallback of the direct route, GigaChat stays last
        assertThat(properties.routes().textFast()).containsExactly("deepseek:deepseek-flash", "openrouter:deepseek/deepseek-v4.1-flash",
                "gigachat:GigaChat-2");
        assertThat(properties.routes().textStrong()).containsExactly("deepseek:deepseek-v4-pro", "openrouter:deepseek/deepseek-v4-pro");
        assertThat(properties.routes().assess()).containsExactly("deepseek:deepseek-flash", "openrouter:deepseek/deepseek-v4.1-flash",
                "gigachat:GigaChat-2");
        assertThat(properties.models()).extracting(AiProperties.Model::id).contains("deepseek-flash", "deepseek-v4-pro", "GigaChat-2",
                "deepseek/deepseek-v4.1-flash", "deepseek/deepseek-v4-pro");
        assertThat(properties.providers().get("deepseek").baseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(properties.providers().get("deepseek").enabled()).isTrue();
        assertThat(properties.permits().text()).isEqualTo(16);
        assertThat(properties.permits().video()).isEqualTo(1);
        assertThat(properties.breaker().failureThreshold()).isEqualTo(5);
        assertThat(properties.prompt().version()).isEqualTo("v1");
        assertThat(userKeys.opaque(UUID.randomUUID()).value()).startsWith("k1.");
    }
}
