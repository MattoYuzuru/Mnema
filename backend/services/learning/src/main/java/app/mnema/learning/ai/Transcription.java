package app.mnema.learning.ai;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Speech-to-text port (AI-15, #298): one short clip of a learner's voice in, text out. Implementations never throw for provider problems and never
 * log the audio, the text, a key or a provider message. Not to be called inside a database transaction.
 *
 * <p>The failures the caller maps: {@link AiFailure.InvalidOutput} with detail {@code unsupported_audio} (no route entry could decode the clip),
 * {@link AiFailure.Refusal} with detail {@code too_long} (the provider measured more than the 60 seconds of a clip), anything else is
 * «unavailable» (every route failed, the breaker is open, the budget is spent, the deadline passed, no entry lies in the consented region).
 */
public interface Transcription {
    /** The container types a speech input may have (the recorder's, contracts/speech): the base type, without parameters. */
    Set<String> MIME_TYPES = Set.of("audio/mp4", "audio/mpeg", "audio/ogg", "audio/webm");

    /** The longest clip, in seconds (the contract's cap of {@code X-Audio-Duration-Ms}). */
    int MAX_SECONDS = 60;

    /** Transcribes {@code request}; {@code Failed} only when every candidate of the route failed or the request cannot be served at all. */
    AiResult<Transcript> transcribe(Request request);

    /** Whether at least one route entry can be called now (a key or a base URL and, for a proxied provider, an active proxy; the Stub). */
    default boolean configured() { return true; }

    /** False while the breaker of every usable route entry is open: the capability is then {@code TEMPORARILY_UNAVAILABLE}. */
    default boolean healthy() { return true; }

    /**
     * Where a clip in {@code lang} (a BCP 47 tag, or blank for none) would be processed now: the region of the first usable route entry. Empty when
     * nothing can serve it. It never calls a provider.
     */
    Optional<Region> region(String lang);

    /** Whether the answer of a scripted transcript (the {@code X-Stub-Transcript} header) is honoured: only the Stub does. */
    default boolean scriptable() { return false; }

    /** Where the audio is processed: {@code RU} (a self-hosted container in Russia) or {@code ABROAD} (a foreign provider through the egress gateway). */
    enum Region { RU, ABROAD }

    /** What the clip is for; only the Stub looks at it (its default text), a provider never sees it. */
    enum Purpose { COMPOSER, EDIT, CAPTURE, STUDY_ANSWER }

    /**
     * @param audio the recording as the recorder made it, at most 2 MiB
     * @param mimeType one of {@link #MIME_TYPES}
     * @param lang a BCP 47 hint, or null for none (the provider detects the language)
     * @param hints up to 60 terms of the deck the clip is for, as recognition hints; never personal data
     * @param userKey the opaque HMAC key of the account, the only identity a provider sees
     * @param deadline how long the whole call, fall-backs included, may take
     * @param declaredMs the recorder's measurement of the clip, 1..60000
     * @param purpose what the transcript is for
     * @param script the answer a harness scripted ({@code X-Stub-Transcript}); ignored by every real adapter, null in production
     * @param allowedRegion the widest region the learner's consent covers: {@code RU} lets only route entries that process in Russia see the clip,
     *                      {@code ABROAD} lets every entry. Null is {@code RU}, the strictest
     */
    record Request(byte[] audio, String mimeType, String lang, List<String> hints, OpaqueUserKey userKey, Duration deadline, int declaredMs,
                   Purpose purpose, String script, Region allowedRegion) {
        public Request {
            if (audio == null || audio.length == 0) throw new IllegalArgumentException("audio");
            if (mimeType == null || !MIME_TYPES.contains(mimeType)) throw new IllegalArgumentException("mimeType");
            if (declaredMs < 1 || declaredMs > MAX_SECONDS * 1000) throw new IllegalArgumentException("declaredMs");
            if (deadline == null || deadline.isNegative() || deadline.isZero()) throw new IllegalArgumentException("deadline");
            lang = lang == null || lang.isBlank() ? null : lang.strip();
            hints = hints == null ? List.of() : List.copyOf(hints);
            purpose = purpose == null ? Purpose.COMPOSER : purpose;
            allowedRegion = allowedRegion == null ? Region.RU : allowedRegion;
        }

        /** Never prints the audio, the hints or the script. */
        @Override
        public String toString() { return "Request[" + mimeType + ", " + audio.length + " bytes, " + declaredMs + " ms, " + (lang == null ? "-" : lang) + "]"; }
    }

    /**
     * @param text the words spoken, trimmed; blank when the clip holds no speech
     * @param seconds the metered duration in whole seconds, at least 1 (the provider's own measurement when it gives one, else the recorder's)
     * @param lang the language the provider detected, or the hint, or null
     * @param garbled the provider doubts the recognition (a low confidence); the grader then sends a spoken answer to self-check
     */
    record Transcript(String text, int seconds, String lang, boolean garbled) {
        public Transcript {
            text = text == null ? "" : text.strip();
            seconds = Math.max(1, seconds);
        }

        /** Never prints the text. */
        @Override
        public String toString() { return "Transcript[" + text.length() + " chars, " + seconds + " s, " + (lang == null ? "-" : lang) + (garbled ? ", garbled" : "") + "]"; }
    }
}
