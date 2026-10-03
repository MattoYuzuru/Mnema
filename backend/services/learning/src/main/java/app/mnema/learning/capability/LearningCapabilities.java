package app.mnema.learning.capability;

import app.mnema.learning.ai.AiAvailability;
import app.mnema.learning.ai.AiCapability;
import app.mnema.learning.ai.ImageGeneration;
import app.mnema.learning.ai.ImageSearch;
import app.mnema.learning.ai.SpeechSynthesis;
import app.mnema.learning.ai.VideoGeneration;
import app.mnema.learning.ai.WebSearch;
import app.mnema.learning.platform.api.CapabilityUnavailableException;
import app.mnema.learning.platform.api.ProblemExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Server-owned, fail-closed capability gate. A capability is available only when its flag is on and an adapter exists;
 * the flags alone never grant it. Nothing here reaches a provider, so the answer carries no secrets or provider details.
 * Availability of the AI capabilities is evaluated per call: an open circuit breaker or a spent daily budget turns an
 * otherwise available capability into {@code TEMPORARILY_UNAVAILABLE} and back without a restart.
 */
@Component
public final class LearningCapabilities {
    private final CapabilityFlags flags;
    private final AiAvailability ai;
    private final boolean speechProvider;
    private final boolean synthesis;
    private final boolean imageSearch;
    private final boolean imageGeneration;
    private final boolean videoGeneration;
    private final boolean webSearch;

    public LearningCapabilities(
            CapabilityFlags flags,
            ObjectProvider<SpeechToTextProvider> speechProviders,
            AiAvailability ai,
            ObjectProvider<SpeechSynthesis> synthesisPorts,
            ObjectProvider<ImageSearch> imageSearchPorts,
            ObjectProvider<ImageGeneration> imageGenerationPorts,
            ObjectProvider<VideoGeneration> videoGenerationPorts,
            ObjectProvider<WebSearch> webSearchPorts) {
        this.flags = flags;
        this.ai = ai;
        this.speechProvider = speechProviders.getIfAvailable() != null;
        this.synthesis = synthesisPorts.getIfAvailable() != null;
        this.imageSearch = imageSearchPorts.getIfAvailable() != null;
        this.imageGeneration = imageGenerationPorts.getIfAvailable() != null;
        this.videoGeneration = videoGenerationPorts.getIfAvailable() != null;
        this.webSearch = webSearchPorts.getIfAvailable() != null;
    }

    /** Semantic grading: flag, a usable adapter on the {@code assess} route (a key or the Stub) and a healthy route. */
    public Status aiAssessment() {
        return flags.aiAssessment().enabled() ? map(ai.assessment()) : new Status(false, Reason.DISABLED);
    }

    public Status speechToText() { return status(flags.speechToText().enabled(), speechProvider); }

    /** Text generation: flag, a usable adapter (key or Stub) and a healthy route. */
    public Status aiGeneration() {
        return flags.aiGeneration().enabled() ? map(ai.text()) : new Status(false, Reason.DISABLED);
    }

    public Status textToSpeech() { return port(flags.textToSpeech().enabled(), AiCapability.TTS, synthesis); }

    public Status imageSearch() { return port(flags.imageSearch().enabled(), AiCapability.IMAGE_SEARCH, imageSearch); }

    public Status imageGeneration() { return port(flags.imageGeneration().enabled(), AiCapability.IMAGE, imageGeneration); }

    public Status videoGeneration() { return port(flags.videoGeneration().enabled(), AiCapability.VIDEO, videoGeneration); }

    public Status webSearch() { return port(flags.webSearch().enabled(), AiCapability.SEARCH, webSearch); }

    /**
     * The {@code require*} methods throw {@link CapabilityUnavailableException} (409 {@code CAPABILITY_UNAVAILABLE}) carrying the
     * contract's problem members {@code capability} (the key of {@code GET /api/capabilities}) and {@code reason}
     * (including {@code TEMPORARILY_UNAVAILABLE}), see {@code contracts/generation/errors.json}.
     */
    public void requireAiAssessment() { require("aiAssessment", aiAssessment()); }

    public void requireSpeechToText() { require("speechToText", speechToText()); }

    public void requireAiGeneration() { require("aiGeneration", aiGeneration()); }

    public void requireTextToSpeech() { require("textToSpeech", textToSpeech()); }

    public void requireImageSearch() { require("imageSearch", imageSearch()); }

    public void requireImageGeneration() { require("imageGeneration", imageGeneration()); }

    public void requireVideoGeneration() { require("videoGeneration", videoGeneration()); }

    public void requireWebSearch() { require("webSearch", webSearch()); }

    private static void require(String capability, Status status) {
        if (status.available()) return;
        throw new CapabilityUnavailableException(ProblemExtension.builder().put("capability", capability)
                .put("reason", status.reason()).build());
    }

    private Status port(boolean enabled, AiCapability capability, boolean present) {
        return enabled ? map(ai.port(capability, present)) : new Status(false, Reason.DISABLED);
    }

    private static Status map(AiAvailability.State state) {
        return switch (state) {
            case AVAILABLE -> new Status(true, null);
            case NOT_CONFIGURED -> new Status(false, Reason.PROVIDER_NOT_CONFIGURED);
            case TEMPORARILY_UNAVAILABLE -> new Status(false, Reason.TEMPORARILY_UNAVAILABLE);
        };
    }

    private static Status status(boolean enabled, boolean provider) {
        if (!enabled) return new Status(false, Reason.DISABLED);
        return provider ? new Status(true, null) : new Status(false, Reason.PROVIDER_NOT_CONFIGURED);
    }

    /** {@code TEMPORARILY_UNAVAILABLE}: an open circuit with no healthy fallback, or the global daily budget is spent. */
    public enum Reason { DISABLED, PROVIDER_NOT_CONFIGURED, TEMPORARILY_UNAVAILABLE }

    /** Availability and, when unavailable, why; {@code reason} is null while available. */
    public record Status(boolean available, Reason reason) { }
}
