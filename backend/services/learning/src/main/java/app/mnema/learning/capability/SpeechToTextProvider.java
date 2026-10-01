package app.mnema.learning.capability;

import java.util.UUID;

/**
 * Seam for a future speech-to-text provider that turns a learner recording into answer text.
 *
 * <p>No implementation exists. The produced text enters the normal evaluation path exactly as typed text
 * would; a provider never grades and never writes {@code StudyState}.
 */
public interface SpeechToTextProvider {
    /** Transcribe an owner-scoped recording; failure is UNAVAILABLE evaluation, never a learner error. */
    Transcription transcribe(UUID recordingAssetId);

    record Transcription(String text) { }
}
