package app.mnema.learning.capability;

import app.mnema.learning.ai.AiAvailability;
import app.mnema.learning.ai.AiCapability;
import app.mnema.learning.platform.api.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code CAPABILITY_UNAVAILABLE} produced by {@code LearningCapabilities.require*} over HTTP: 409 with the contract's
 * {@code capability} and {@code reason} members ({@code contracts/generation/errors.json}).
 */
class CapabilityProblemTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One endpoint per capability, each guarded by the matching require method. */
    @RestController
    static class Guarded {
        private final LearningCapabilities capabilities;

        Guarded(LearningCapabilities capabilities) { this.capabilities = capabilities; }

        @GetMapping("/aiAssessment") String aiAssessment() { capabilities.requireAiAssessment(); return "ok"; }

        @GetMapping("/speechToText") String speechToText() { capabilities.requireSpeechToText(); return "ok"; }

        @GetMapping("/aiGeneration") String aiGeneration() { capabilities.requireAiGeneration(); return "ok"; }

        @GetMapping("/textToSpeech") String textToSpeech() { capabilities.requireTextToSpeech(); return "ok"; }

        @GetMapping("/imageSearch") String imageSearch() { capabilities.requireImageSearch(); return "ok"; }

        @GetMapping("/imageGeneration") String imageGeneration() { capabilities.requireImageGeneration(); return "ok"; }

        @GetMapping("/videoGeneration") String videoGeneration() { capabilities.requireVideoGeneration(); return "ok"; }

        @GetMapping("/webSearch") String webSearch() { capabilities.requireWebSearch(); return "ok"; }
    }

    private static MockMvc mvc(CapabilityFlags flags, AiAvailability ai) {
        var factory = new StaticListableBeanFactory();
        var capabilities = new LearningCapabilities(flags, factory.getBeanProvider(SemanticAssessmentProvider.class),
                factory.getBeanProvider(SpeechToTextProvider.class), ai,
                factory.getBeanProvider(app.mnema.learning.ai.SpeechSynthesis.class),
                factory.getBeanProvider(app.mnema.learning.ai.ImageSearch.class),
                factory.getBeanProvider(app.mnema.learning.ai.ImageGeneration.class),
                factory.getBeanProvider(app.mnema.learning.ai.VideoGeneration.class),
                factory.getBeanProvider(app.mnema.learning.ai.WebSearch.class));
        return MockMvcBuilders.standaloneSetup(new Guarded(capabilities)).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private static AiAvailability availability(AiAvailability.State text) {
        return new AiAvailability() {
            @Override public State text() { return text; }

            @Override public State port(AiCapability capability, boolean present) { return State.NOT_CONFIGURED; }
        };
    }

    private static JsonNode problem(MockMvc mvc, String path) throws Exception {
        return JSON.readTree(mvc.perform(get("/" + path)).andExpect(status().isConflict()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void everyRequireMethodCarriesItsCapabilityAndTheReasonOverHttp() throws Exception {
        MockMvc disabled = mvc(CapabilityFlags.off(), availability(AiAvailability.State.AVAILABLE));
        for (String key : new String[] {"aiAssessment", "speechToText", "aiGeneration", "textToSpeech", "imageSearch",
                "imageGeneration", "videoGeneration", "webSearch"}) {
            JsonNode body = problem(disabled, key);
            assertThat(body.path("code").stringValue()).as(key).isEqualTo("CAPABILITY_UNAVAILABLE");
            assertThat(body.path("capability").stringValue()).as(key).isEqualTo(key);
            assertThat(body.path("reason").stringValue()).as(key).isEqualTo("DISABLED");
        }
        MockMvc enabled = mvc(CapabilityFlags.of(true, true, true, true, true, true, true, true),
                availability(AiAvailability.State.NOT_CONFIGURED));
        for (String key : new String[] {"aiAssessment", "speechToText", "aiGeneration", "textToSpeech", "imageSearch",
                "imageGeneration", "videoGeneration", "webSearch"}) {
            assertThat(problem(enabled, key).path("reason").stringValue()).as(key).isEqualTo("PROVIDER_NOT_CONFIGURED");
        }
    }

    @Test
    void aTemporaryOutageIsReportedAsTemporarilyUnavailable() throws Exception {
        MockMvc temporary = mvc(CapabilityFlags.of(false, false, true, false, false, false, false, false),
                availability(AiAvailability.State.TEMPORARILY_UNAVAILABLE));
        JsonNode body = problem(temporary, "aiGeneration");
        assertThat(body.path("capability").stringValue()).isEqualTo("aiGeneration");
        assertThat(body.path("reason").stringValue()).isEqualTo("TEMPORARILY_UNAVAILABLE");
    }

    @Test
    void anAvailableCapabilityPassesTheGuard() throws Exception {
        MockMvc available = mvc(CapabilityFlags.of(false, false, true, false, false, false, false, false),
                availability(AiAvailability.State.AVAILABLE));
        available.perform(get("/aiGeneration")).andExpect(status().isOk());
    }

    @Test
    void theProblemEqualsTheContractExampleApartFromTheInstance() throws Exception {
        // example: capability textToSpeech, reason PROVIDER_NOT_CONFIGURED
        MockMvc mvc = mvc(CapabilityFlags.of(false, false, false, true, false, false, false, false), availability(AiAvailability.State.AVAILABLE));
        var example = (tools.jackson.databind.node.ObjectNode) errors().path("codes").path("CAPABILITY_UNAVAILABLE").path("example").deepCopy();
        var actual = (tools.jackson.databind.node.ObjectNode) problem(mvc, "textToSpeech").deepCopy();
        assertThat(actual.has("instance")).isTrue();
        example.remove("instance");
        actual.remove("instance");
        assertThat(actual).isEqualTo(example);
    }

    private static JsonNode errors() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/generation/errors.json"))) root = root.getParent();
        return JSON.readTree(Files.readString(root.resolve("contracts/generation/errors.json")));
    }
}
