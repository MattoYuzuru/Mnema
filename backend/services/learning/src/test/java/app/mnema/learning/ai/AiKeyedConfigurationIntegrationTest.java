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
@SpringBootTest(properties = {"learning.features.ai-generation.enabled=true", "learning.ai.provider=",
        "learning.ai.providers.deepseek.api-key=test-key-not-real", "learning.ai.providers.gigachat.auth-key=",
        "learning.ai.providers.openrouter.api-key=", "learning.ai.user-key.secret=0123456789abcdef0123456789abcdef", "spring.datasource.hikari.maximum-pool-size=2"})
class AiKeyedConfigurationIntegrationTest extends PostgresIntegrationTest {
    @Autowired private AiProperties properties;
    @Autowired private LearningCapabilities capabilities;
    @Autowired private UserKeys userKeys;

    @Test
    void aKeyAndTheUserKeySecretMakeAiGenerationAvailableAndTheDefaultsBind() {
        assertThat(capabilities.aiGeneration()).isEqualTo(new LearningCapabilities.Status(true, null));
        assertThat(properties.routes().textFast()).containsExactly("deepseek:deepseek-flash", "gigachat:GigaChat-2");
        assertThat(properties.routes().textStrong()).containsExactly("deepseek:deepseek-v4-pro");
        assertThat(properties.models()).extracting(AiProperties.Model::id).contains("deepseek-flash", "deepseek-v4-pro", "GigaChat-2");
        assertThat(properties.providers().get("deepseek").baseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(properties.providers().get("deepseek").enabled()).isTrue();
        assertThat(properties.permits().text()).isEqualTo(16);
        assertThat(properties.permits().video()).isEqualTo(1);
        assertThat(properties.breaker().failureThreshold()).isEqualTo(5);
        assertThat(properties.prompt().version()).isEqualTo("v1");
        assertThat(userKeys.opaque(UUID.randomUUID()).value()).startsWith("k1.");
    }
}
