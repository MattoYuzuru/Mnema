package app.mnema.learning.capability;

import app.mnema.learning.ai.AiAvailability;
import app.mnema.learning.ai.AiCapability;
import app.mnema.learning.ai.ImageSearch;
import app.mnema.learning.ai.WebSearch;
import app.mnema.learning.platform.api.CapabilityUnavailableException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static app.mnema.learning.support.ContractFixtures.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LearningCapabilitiesTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SpeechToTextProvider SPEECH = asset -> new SpeechToTextProvider.Transcription("text");
    private static final ImageSearch IMAGE_SEARCH = new ImageSearch() {
        @Override public app.mnema.learning.ai.AiResult<java.util.List<Candidate>> search(Request request) { return null; }

        @Override public app.mnema.learning.ai.AiResult<Image> fetch(Candidate candidate) { return null; }
    };
    private static final WebSearch WEB_SEARCH = request -> null;

    private static final AiAvailability TEXT_AVAILABLE = availability(AiAvailability.State.AVAILABLE, Set.of());

    /** Text answers {@code text}; a port answers NOT_CONFIGURED when absent and TEMPORARILY_UNAVAILABLE for the given capabilities. */
    private static AiAvailability availability(AiAvailability.State text, Set<AiCapability> temporarilyUnavailable) {
        return new AiAvailability() {
            @Override public State text() { return text; }

            @Override public State assessment() { return text; }

            @Override public State port(AiCapability capability, boolean present) {
                if (!present) return State.NOT_CONFIGURED;
                return temporarilyUnavailable.contains(capability) ? State.TEMPORARILY_UNAVAILABLE : State.AVAILABLE;
            }
        };
    }

    @Test
    void flagsOffMeanDisabledRegardlessOfProviders() {
        LearningCapabilities capabilities = capabilities(CapabilityFlags.off(), TEXT_AVAILABLE, true, true, true);
        assertThat(capabilities.aiAssessment()).isEqualTo(new LearningCapabilities.Status(false,
                LearningCapabilities.Reason.DISABLED));
        assertThat(capabilities.speechToText().reason()).isEqualTo(LearningCapabilities.Reason.DISABLED);
        for (var status : new LearningCapabilities.Status[] {capabilities.aiGeneration(), capabilities.textToSpeech(),
                capabilities.imageSearch(), capabilities.imageGeneration(), capabilities.videoGeneration(),
                capabilities.webSearch()}) {
            assertThat(status).isEqualTo(new LearningCapabilities.Status(false, LearningCapabilities.Reason.DISABLED));
        }
        assertThatThrownBy(capabilities::requireAiAssessment).isInstanceOf(CapabilityUnavailableException.class);
        assertThatThrownBy(capabilities::requireSpeechToText).isInstanceOf(CapabilityUnavailableException.class);
    }

    @Test
    void aFlagWithoutAProviderStaysUnavailableAndNeverFakesSuccess() {
        LearningCapabilities capabilities = capabilities(allOn(), availability(AiAvailability.State.NOT_CONFIGURED, Set.of()),
                false, false, false);
        assertThat(capabilities.aiAssessment().available()).isFalse();
        assertThat(capabilities.aiAssessment().reason()).isEqualTo(LearningCapabilities.Reason.PROVIDER_NOT_CONFIGURED);
        assertThat(capabilities.speechToText().reason()).isEqualTo(LearningCapabilities.Reason.PROVIDER_NOT_CONFIGURED);
        for (var status : new LearningCapabilities.Status[] {capabilities.aiGeneration(), capabilities.textToSpeech(),
                capabilities.imageSearch(), capabilities.imageGeneration(), capabilities.videoGeneration(),
                capabilities.webSearch()}) {
            assertThat(status).isEqualTo(new LearningCapabilities.Status(false,
                    LearningCapabilities.Reason.PROVIDER_NOT_CONFIGURED));
        }
        assertThatThrownBy(capabilities::requireAiAssessment).isInstanceOf(CapabilityUnavailableException.class);
        assertThatThrownBy(capabilities::requireSpeechToText).isInstanceOf(CapabilityUnavailableException.class);
    }

    @Test
    void anImageSearchPortWithoutAnyCallableSourceIsNotConfiguredEvenWhenTheFlagIsOn() {
        var none = new ImageSearch() {
            @Override public app.mnema.learning.ai.AiResult<java.util.List<Candidate>> search(Request request) { return null; }

            @Override public app.mnema.learning.ai.AiResult<Image> fetch(Candidate candidate) { return null; }

            @Override public boolean configured() { return false; }
        };
        var factory = new StaticListableBeanFactory();
        factory.addBean("imageSearch", none);
        LearningCapabilities capabilities = new LearningCapabilities(allOn(), factory.getBeanProvider(SpeechToTextProvider.class), TEXT_AVAILABLE,
                factory.getBeanProvider(app.mnema.learning.ai.SpeechSynthesis.class), factory.getBeanProvider(ImageSearch.class),
                factory.getBeanProvider(app.mnema.learning.ai.ImageGeneration.class),
                factory.getBeanProvider(app.mnema.learning.ai.VideoGeneration.class), factory.getBeanProvider(WebSearch.class));
        assertThat(capabilities.imageSearch()).isEqualTo(new LearningCapabilities.Status(false, LearningCapabilities.Reason.PROVIDER_NOT_CONFIGURED));
        assertThatThrownBy(capabilities::requireImageSearch).isInstanceOf(CapabilityUnavailableException.class);
    }

    @Test
    void aProviderWithoutItsFlagIsStillDisabledAndBothTogetherAreAvailable() {
        LearningCapabilities providerOnly = capabilities(CapabilityFlags.off(), TEXT_AVAILABLE, true, true, true);
        assertThat(providerOnly.aiAssessment().available()).isFalse();
        LearningCapabilities both = capabilities(allOn(), TEXT_AVAILABLE, true, true, true);
        assertThat(both.aiAssessment()).isEqualTo(new LearningCapabilities.Status(true, null));
        assertThat(both.speechToText()).isEqualTo(new LearningCapabilities.Status(true, null));
        assertThat(both.aiGeneration()).isEqualTo(new LearningCapabilities.Status(true, null));
        assertThat(both.imageSearch()).isEqualTo(new LearningCapabilities.Status(true, null));
        assertThat(both.webSearch()).isEqualTo(new LearningCapabilities.Status(true, null));
        both.requireAiAssessment();
        both.requireSpeechToText();
        // the capabilities are independent
        LearningCapabilities onlyAi = capabilities(CapabilityFlags.of(true, false, false, false, false, false, false, false),
                TEXT_AVAILABLE, true, true, true);
        assertThat(onlyAi.aiAssessment().available()).isTrue();
        assertThat(onlyAi.speechToText().available()).isFalse();
        assertThat(onlyAi.aiGeneration().reason()).isEqualTo(LearningCapabilities.Reason.DISABLED);
    }

    @Test
    void anOpenCircuitOrASpentBudgetIsTemporaryAndRecoversWithoutARestart() {
        var state = new AiAvailability.State[] {AiAvailability.State.TEMPORARILY_UNAVAILABLE};
        AiAvailability live = new AiAvailability() {
            @Override public State text() { return state[0]; }

            @Override public State assessment() { return state[0]; }

            @Override public State port(AiCapability capability, boolean present) { return State.AVAILABLE; }
        };
        LearningCapabilities capabilities = capabilities(allOn(), live, false, false, false);
        assertThat(capabilities.aiGeneration()).isEqualTo(new LearningCapabilities.Status(false,
                LearningCapabilities.Reason.TEMPORARILY_UNAVAILABLE));
        state[0] = AiAvailability.State.AVAILABLE;
        assertThat(capabilities.aiGeneration()).isEqualTo(new LearningCapabilities.Status(true, null));
    }

    @Test
    void theEndpointPayloadMatchesTheStudyContractFixtures() {
        CapabilityController.Capabilities disabled = new CapabilityController(capabilities(CapabilityFlags.off(), TEXT_AVAILABLE,
                false, false, false)).read().getBody();
        assertThat(JSON.<JsonNode>valueToTree(disabled)).isEqualTo(fixture("mechanics.json").path("capabilities"));
        CapabilityController.Capabilities flagOnly = new CapabilityController(capabilities(
                CapabilityFlags.of(true, false, false, false, false, false, false, false),
                availability(AiAvailability.State.NOT_CONFIGURED, Set.of()), false, false, false)).read().getBody();
        JsonNode expected = fixture("mechanics.json").path("capabilitiesFlagWithoutProvider");
        assertThat(JSON.<JsonNode>valueToTree(flagOnly)).isEqualTo(expected);
        var response = new CapabilityController(capabilities(CapabilityFlags.off(), TEXT_AVAILABLE, false, false, false)).read();
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("private, no-store");
        // an available capability reports an explicit null reason and no provider detail
        JsonNode available = JSON.valueToTree(new CapabilityController(capabilities(allOn(), TEXT_AVAILABLE, true, true,
                true)).read().getBody());
        assertThat(available.path("aiAssessment").path("available").booleanValue()).isTrue();
        assertThat(available.path("aiAssessment").path("reason").isNull()).isTrue();
        assertThat(available.path("aiAssessment").size()).isEqualTo(2);
    }

    @Test
    void theEndpointPayloadMatchesTheGenerationContractExample() throws IOException {
        // aiGeneration available, textToSpeech flag on without an adapter, imageSearch available, imageGeneration and
        // videoGeneration off, webSearch with an adapter but a spent budget: the example of getCapabilities.
        var flags = CapabilityFlags.of(false, false, true, true, true, false, false, true);
        var capabilities = capabilities(flags, availability(AiAvailability.State.AVAILABLE, Set.of(AiCapability.SEARCH)),
                false, true, true);
        JsonNode payload = JSON.valueToTree(new CapabilityController(capabilities).read().getBody());
        assertThat(payload).isEqualTo(http().path("examples").path("capabilities"));
    }

    private static JsonNode http() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/generation/http.json"))) root = root.getParent();
        return JSON.readTree(Files.readString(root.resolve("contracts/generation/http.json")));
    }

    private static CapabilityFlags allOn() { return CapabilityFlags.of(true, true, true, true, true, true, true, true); }

    private static LearningCapabilities capabilities(CapabilityFlags flags, AiAvailability ai, boolean speechProvider,
                                                     boolean imageSearchPort, boolean webSearchPort) {
        var factory = new StaticListableBeanFactory();
        if (speechProvider) factory.addBean("speech", SPEECH);
        if (imageSearchPort) factory.addBean("imageSearch", IMAGE_SEARCH);
        if (webSearchPort) factory.addBean("webSearch", WEB_SEARCH);
        return new LearningCapabilities(flags,
                factory.getBeanProvider(SpeechToTextProvider.class), ai,
                factory.getBeanProvider(app.mnema.learning.ai.SpeechSynthesis.class), factory.getBeanProvider(ImageSearch.class),
                factory.getBeanProvider(app.mnema.learning.ai.ImageGeneration.class),
                factory.getBeanProvider(app.mnema.learning.ai.VideoGeneration.class), factory.getBeanProvider(WebSearch.class));
    }
}
