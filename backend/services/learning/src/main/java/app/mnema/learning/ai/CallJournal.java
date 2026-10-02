package app.mnema.learning.ai;

import java.util.UUID;

/**
 * Durable journal of provider calls. {@link #begin} commits an intent row before the call; {@link #finish} records the
 * outcome after it. Each is its own short transaction, and neither may run inside another transaction.
 */
interface CallJournal {
    /** @throws RuntimeException when the intent cannot be recorded; the router then does not call the provider */
    UUID begin(Intent intent);

    /** Best effort: a failure is logged by the implementation and never changes the call result. */
    void finish(UUID callId, Outcome outcome);

    record Intent(UUID stepId, int attempt, AiCapability capability, String provider, String model, String requestHash) { }

    record Outcome(String outcome, Usage usage, long costMicros, String providerRequestId, long latencyMillis) { }
}
