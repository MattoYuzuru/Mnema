package app.mnema.learning.ai;

import java.time.Duration;

/**
 * One speech provider: a single bounded synthesis call. Breakers, budget, permits, the journal and routing belong to {@link RoutedSpeechSynthesis};
 * an adapter never throws for provider problems and never logs text, a key or a provider message.
 */
interface SpeechAdapter {
    /** The provider id of routes, the journal, metrics and breakers ({@code google}, {@code yandex}). */
    String provider();

    /** A key (and folder) are present, the kill switch is on and the transport exists (a proxied provider needs an active proxy). */
    boolean configured();

    AiProperties.EgressMode egress();

    /** Whether the provider speaks {@code lang} (a BCP 47 tag); a provider that does not is skipped by routing, never an error. */
    boolean supports(String lang);

    /** The provider's own name of an abstract voice ({@code female}, {@code male}); part of the cache key. */
    String voiceName(String voice);

    /** {@code wav} or {@code mp3}: the format asked for, part of the cache key. */
    String format();

    AiResult<SpeechSynthesis.Audio> synthesize(String model, String modelVersion, SpeechSynthesis.Request request, Duration budget);

    /** The cost of one answered call in micro-US-dollars, from the price table or the per-character rate; never from provider text. */
    long costMicros(SpeechSynthesis.Audio audio);

    /** Token counts for the telemetry line; zero for providers that bill by character. */
    default Usage usage(SpeechSynthesis.Audio audio) { return Usage.ZERO; }
}
