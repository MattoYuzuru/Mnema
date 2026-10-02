package app.mnema.learning.ai;

/** Text-to-speech port (AI-09). Interface only: no implementation exists, so the capability stays unavailable. */
public interface SpeechSynthesis {
    AiResult<Audio> synthesize(Request request);

    /** {@code voice} is an abstract voice name, {@code lang} a BCP 47 tag. */
    record Request(String text, String lang, String voice, String format) { }

    record Audio(byte[] bytes, String mimeType, int billedCharacters) { }
}
