package app.mnema.learning.capability;

import app.mnema.learning.platform.api.CapabilityUnavailableException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static app.mnema.learning.support.ContractFixtures.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LearningCapabilitiesTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SemanticAssessmentProvider SEMANTIC = (rubric, response) ->
            new SemanticAssessmentProvider.Judgement(SemanticAssessmentProvider.Level.UNSURE, "test");
    private static final SpeechToTextProvider SPEECH = asset -> new SpeechToTextProvider.Transcription("text");

    @Test
    void flagsOffMeanDisabledRegardlessOfProviders() {
        LearningCapabilities capabilities = capabilities(false, false, true, true);
        assertThat(capabilities.aiAssessment()).isEqualTo(new LearningCapabilities.Status(false,
                LearningCapabilities.Reason.DISABLED));
        assertThat(capabilities.speechToText().reason()).isEqualTo(LearningCapabilities.Reason.DISABLED);
        assertThatThrownBy(capabilities::requireAiAssessment).isInstanceOf(CapabilityUnavailableException.class);
        assertThatThrownBy(capabilities::requireSpeechToText).isInstanceOf(CapabilityUnavailableException.class);
    }

    @Test
    void aFlagWithoutAProviderStaysUnavailableAndNeverFakesSuccess() {
        LearningCapabilities capabilities = capabilities(true, true, false, false);
        assertThat(capabilities.aiAssessment().available()).isFalse();
        assertThat(capabilities.aiAssessment().reason()).isEqualTo(LearningCapabilities.Reason.PROVIDER_NOT_CONFIGURED);
        assertThat(capabilities.speechToText().reason()).isEqualTo(LearningCapabilities.Reason.PROVIDER_NOT_CONFIGURED);
        assertThatThrownBy(capabilities::requireAiAssessment).isInstanceOf(CapabilityUnavailableException.class);
        assertThatThrownBy(capabilities::requireSpeechToText).isInstanceOf(CapabilityUnavailableException.class);
    }

    @Test
    void aProviderWithoutItsFlagIsStillDisabledAndBothTogetherAreAvailable() {
        LearningCapabilities providerOnly = capabilities(false, false, true, true);
        assertThat(providerOnly.aiAssessment().available()).isFalse();
        LearningCapabilities both = capabilities(true, true, true, true);
        assertThat(both.aiAssessment()).isEqualTo(new LearningCapabilities.Status(true, null));
        assertThat(both.speechToText()).isEqualTo(new LearningCapabilities.Status(true, null));
        both.requireAiAssessment();
        both.requireSpeechToText();
        // the two capabilities are independent
        LearningCapabilities onlyAi = capabilities(true, false, true, true);
        assertThat(onlyAi.aiAssessment().available()).isTrue();
        assertThat(onlyAi.speechToText().available()).isFalse();
    }

    @Test
    void theEndpointPayloadMatchesTheContractFixtures() {
        CapabilityController.Capabilities disabled = new CapabilityController(capabilities(false, false, false, false))
                .read().getBody();
        assertThat(JSON.<JsonNode>valueToTree(disabled)).isEqualTo(fixture("mechanics.json").path("capabilities"));
        CapabilityController.Capabilities flagOnly = new CapabilityController(capabilities(true, false, false, false))
                .read().getBody();
        JsonNode expected = fixture("mechanics.json").path("capabilitiesFlagWithoutProvider");
        assertThat(JSON.<JsonNode>valueToTree(flagOnly)).isEqualTo(expected);
        var response = new CapabilityController(capabilities(false, false, false, false)).read();
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("private, no-store");
        // an available capability reports an explicit null reason and no provider detail
        JsonNode available = JSON.valueToTree(new CapabilityController(capabilities(true, true, true, true)).read().getBody());
        assertThat(available.path("aiAssessment").path("available").booleanValue()).isTrue();
        assertThat(available.path("aiAssessment").path("reason").isNull()).isTrue();
        assertThat(available.path("aiAssessment").size()).isEqualTo(2);
    }

    private static LearningCapabilities capabilities(boolean ai, boolean speech, boolean aiProvider, boolean speechProvider) {
        var factory = new StaticListableBeanFactory();
        if (aiProvider) factory.addBean("semantic", SEMANTIC);
        if (speechProvider) factory.addBean("speech", SPEECH);
        return new LearningCapabilities(ai, speech, factory.getBeanProvider(SemanticAssessmentProvider.class),
                factory.getBeanProvider(SpeechToTextProvider.class));
    }
}
