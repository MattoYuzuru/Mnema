package app.mnema.learning.ai;

/**
 * Whether an AI capability can serve a call right now, for {@code GET /api/capabilities}. It never calls a provider.
 */
public interface AiAvailability {
    enum State { AVAILABLE, NOT_CONFIGURED, TEMPORARILY_UNAVAILABLE }

    /** Text generation ({@code aiGeneration}). */
    State text();

    /** A capability served by a port bean ({@code present}); only the daily budget can make it temporary. */
    State port(AiCapability capability, boolean present);
}
