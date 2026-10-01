package app.mnema.learning.capability;

import app.mnema.learning.platform.api.CapabilityUnavailableException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Server-owned, fail-closed capability gate. A capability is available only when its flag is on and a
 * provider bean exists; the flags alone never grant it. Nothing here reaches a provider, so the answer
 * carries no secrets or provider details.
 */
@Component
public final class LearningCapabilities {
    private final boolean aiAssessmentEnabled;
    private final boolean speechToTextEnabled;
    private final boolean semanticProvider;
    private final boolean speechProvider;

    public LearningCapabilities(
            @Value("${learning.features.ai-assessment.enabled:false}") boolean aiAssessmentEnabled,
            @Value("${learning.features.speech-to-text.enabled:false}") boolean speechToTextEnabled,
            ObjectProvider<SemanticAssessmentProvider> semanticProviders,
            ObjectProvider<SpeechToTextProvider> speechProviders) {
        this.aiAssessmentEnabled = aiAssessmentEnabled;
        this.speechToTextEnabled = speechToTextEnabled;
        this.semanticProvider = semanticProviders.getIfAvailable() != null;
        this.speechProvider = speechProviders.getIfAvailable() != null;
    }

    public Status aiAssessment() { return status(aiAssessmentEnabled, semanticProvider); }

    public Status speechToText() { return status(speechToTextEnabled, speechProvider); }

    public void requireAiAssessment() {
        if (!aiAssessment().available()) throw new CapabilityUnavailableException();
    }

    public void requireSpeechToText() {
        if (!speechToText().available()) throw new CapabilityUnavailableException();
    }

    private static Status status(boolean enabled, boolean provider) {
        if (!enabled) return new Status(false, Reason.DISABLED);
        return provider ? new Status(true, null) : new Status(false, Reason.PROVIDER_NOT_CONFIGURED);
    }

    public enum Reason { DISABLED, PROVIDER_NOT_CONFIGURED }

    /** Availability and, when unavailable, why; {@code reason} is null while available. */
    public record Status(boolean available, Reason reason) { }
}
