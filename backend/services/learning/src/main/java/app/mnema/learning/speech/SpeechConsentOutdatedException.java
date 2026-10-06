package app.mnema.learning.speech;

import app.mnema.learning.ai.Transcription;
import app.mnema.learning.platform.api.ProblemExtension;

/** A consent was sent for another version or processing region than the current ones: {@code 409 SPEECH_CONSENT_OUTDATED}, with what is required now. */
public final class SpeechConsentOutdatedException extends RuntimeException implements ProblemExtension.ProblemExtensionSource {
    private static final long serialVersionUID = 1L;

    private final transient ProblemExtension extension;

    SpeechConsentOutdatedException(String version, Transcription.Region processing) {
        super("Speech consent outdated", null, false, false);
        this.extension = ProblemExtension.builder().put("version", version).put("processing", processing).build();
    }

    @Override
    public ProblemExtension extension() { return extension; }
}
