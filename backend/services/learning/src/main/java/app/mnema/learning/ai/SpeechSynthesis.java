package app.mnema.learning.ai;

import java.util.Optional;
import java.util.UUID;

/**
 * Text-to-speech port (AI-09, #297): one short clip of already de-identified text in a language and an abstract voice. Implementations never
 * throw for provider problems and never log the text, a key or a provider message. Not to be called inside a database transaction.
 */
public interface SpeechSynthesis {
    /** Synthesises {@code request}; {@code Failed} only when every candidate of the route failed or the request cannot be served at all. */
    AiResult<Audio> synthesize(Request request);

    /**
     * What a call for {@code lang} and {@code voice} would be served by now (the first usable route entry), for the cache key; empty when
     * nothing can serve it. It never calls a provider.
     */
    Optional<Identity> identity(String lang, String voice);

    /** Whether at least one route entry can be called now (a key and, for a proxied provider, an active proxy; the Stub). */
    default boolean configured() { return true; }

    /**
     * @param text at most {@code learning.ai.tts.max-text} characters, normalised by the caller
     * @param lang a BCP 47 tag of the spoken text
     * @param voice {@code female} or {@code male}
     * @param take the redo counter of the clip (0 for the first); the cache key includes it, a provider is not told about it
     * @param stepId the generation step for the call journal, null outside a step
     */
    record Request(String text, String lang, String voice, int take, UUID stepId, int attempt) {
        public Request {
            if (text == null || text.isBlank()) throw new IllegalArgumentException("text");
            lang = lang == null || lang.isBlank() ? "en" : lang;
            if (!"female".equals(voice) && !"male".equals(voice)) throw new IllegalArgumentException("voice");
            if (take < 0) throw new IllegalArgumentException("take");
            attempt = Math.max(1, attempt);
        }

        @Override
        public String toString() { return "Request[" + lang + ", " + voice + ", take=" + take + ", " + text.length() + " chars]"; }
    }

    /** The part of a cache key a provider decides: what produced (or would produce) the bytes. */
    record Identity(String provider, String model, String modelVersion, String format, String voice) { }

    /**
     * {@code mimeType} is {@code audio/wav} (RIFF PCM s16le) or {@code audio/mpeg}; {@code costMicros} is what the call cost in micro-US-dollars (set by
     * the router from the price table, zero for the Stub).
     */
    record Audio(byte[] bytes, String mimeType, int billedCharacters, long durationMs, Identity identity, long costMicros) {
        @Override
        public String toString() { return "Audio[" + mimeType + ", " + bytes.length + " bytes, " + durationMs + " ms, " + identity.provider() + "]"; }
    }
}
