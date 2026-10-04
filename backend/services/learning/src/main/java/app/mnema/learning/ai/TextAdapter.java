package app.mnema.learning.ai;

import java.time.Duration;

/**
 * One provider's text transport. {@link #attempt} performs exactly one provider call and never throws for provider
 * problems; retries, fallback, journaling and breakers belong to the router.
 */
interface TextAdapter {
    /** Provider id used in routes, journal, metrics and breakers. */
    String provider();

    /** How the provider is reached: the {@code egress} log field and metric tag. */
    default AiProperties.EgressMode egress() { return AiProperties.EgressMode.DIRECT; }

    /** Whether the adapter can call its provider right now (enabled and credentials present). */
    boolean configured();

    /**
     * @param budget the time left for this attempt, connect included; zero or less is already a timeout
     */
    AiResult<TextResponse> attempt(String model, TextRequest request, Duration budget);
}
