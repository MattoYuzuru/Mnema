package app.mnema.learning.ai;

import java.time.Duration;

/**
 * One transcription provider: a single bounded call. Breakers, budget, permits, the journal and routing belong to {@link RoutedTranscription};
 * an adapter never throws for provider problems and never logs audio, text, a key or a provider message.
 */
interface TranscriptionAdapter {
    /** What an answered call produced: the transcript and what it consumed, for the journal and the price. */
    record Answer(Transcription.Transcript transcript, Usage usage) { }

    /** The provider id of routes, the journal, metrics and breakers ({@code selfhost}, {@code google}). */
    String provider();

    /** A base URL (and a key where the provider needs one) is present, the kill switch is on and the transport exists (a proxied provider needs an active proxy). */
    boolean configured();

    AiProperties.EgressMode egress();

    /** Where this provider processes the audio. */
    Transcription.Region region();

    /** A call that is bounded by {@code budget}; a clip type the provider cannot take is {@code InvalidOutput("unsupported_audio")}, so the route moves on. */
    AiResult<Answer> transcribe(String model, Transcription.Request request, Duration budget);

    /** The cost of one answered call in micro-US-dollars, from the price table; zero for a self-hosted provider. */
    long costMicros(String model, Answer answer);
}
