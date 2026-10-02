package app.mnema.learning.ai;

/** Speech-to-text port (AI-15). Interface only; the domain seam {@code SpeechToTextProvider} will sit on top of it. */
public interface Transcription {
    AiResult<Transcript> transcribe(Request request);

    record Request(byte[] audio, String mimeType, String lang) { }

    record Transcript(String text, int seconds) { }
}
