package app.mnema.learning.capability;

import app.mnema.learning.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An {@code api} process whose worker holds the provider keys ({@code learning.runtime.provider-credentials=worker}): with not a single key or secret
 * in its environment, {@code /api/capabilities} reports every capability from the routes, flags, kill switches and egress configuration it shares with the
 * worker, instead of {@code PROVIDER_NOT_CONFIGURED} everywhere.
 */
@SpringBootTest(properties = {"learning.runtime.roles=api", "learning.runtime.provider-credentials=worker", "learning.ai.provider=",
        "learning.features.ai-assessment.enabled=true", "learning.features.speech-to-text.enabled=true", "learning.features.ai-generation.enabled=true",
        "learning.features.text-to-speech.enabled=true", "learning.features.image-search.enabled=true", "learning.features.web-search.enabled=true",
        // hermetic: nothing exported in the developer's shell reaches this context; the proxy address and the folders are shared, non-secret configuration
        "learning.ai.providers.deepseek.api-key=", "learning.ai.providers.gigachat.auth-key=", "learning.ai.providers.openrouter.api-key=",
        "learning.ai.providers.google.api-key=", "learning.ai.providers.yandex.api-key=", "learning.ai.providers.yandex-search.api-key=",
        "learning.ai.providers.pixabay.api-key=", "learning.ai.providers.openverse.client-id=", "learning.ai.providers.openverse.client-secret=",
        "learning.ai.providers.perplexity.api-key=", "learning.ai.user-key.secret=", "learning.ai.egress.proxy-url=http://127.0.0.1:3128",
        "learning.ai.egress.user=", "learning.ai.egress.password=", "learning.ai.research.yandex-folder-id=b1gTESTFOLDER",
        "spring.datasource.hikari.maximum-pool-size=2"})
class ApiRoleCapabilitiesIntegrationTest extends PostgresIntegrationTest {
    @Autowired private LearningCapabilities capabilities;

    @Test
    void everyCapabilityIsReportedFromConfigurationWithoutAKeyOnThisHost() {
        LearningCapabilities.Status available = new LearningCapabilities.Status(true, null);
        assertThat(capabilities.aiGeneration()).isEqualTo(available);
        assertThat(capabilities.aiAssessment()).isEqualTo(available);
        assertThat(capabilities.speechToText()).isEqualTo(available);
        assertThat(capabilities.textToSpeech()).isEqualTo(available);
        assertThat(capabilities.imageSearch()).isEqualTo(available);
        assertThat(capabilities.webSearch()).isEqualTo(available);
        // what no one configures stays what it is
        assertThat(capabilities.videoGeneration().available()).isFalse();
    }
}
