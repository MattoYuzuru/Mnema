package app.mnema.learning.speech;

import app.mnema.learning.ai.Transcription;
import app.mnema.learning.platform.api.ProblemExtension;

/**
 * The account has not consented (or not for the processing region the input would use): {@code 409 SPEECH_CONSENT_REQUIRED}. The members tell the
 * client what to ask for: the {@code required} version and {@code processing} region of the disclosure.
 */
public final class SpeechConsentRequiredException extends RuntimeException implements ProblemExtension.ProblemExtensionSource {
    private static final long serialVersionUID = 1L;

    private final transient ProblemExtension extension;

    SpeechConsentRequiredException(String version, Transcription.Region processing) {
        super("Speech consent required", null, false, false);
        this.extension = ProblemExtension.builder().put("version", version).put("processing", processing).build();
    }

    @Override
    public ProblemExtension extension() { return extension; }
}
