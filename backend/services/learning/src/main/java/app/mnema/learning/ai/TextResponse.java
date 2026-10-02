package app.mnema.learning.ai;

import java.util.Objects;

/**
 * A successful text call.
 *
 * @param costMicros cost in micro-US-dollars from the configured price table (zero for the Stub)
 * @param providerRequestId the provider's completion id, for support tickets; null when absent
 * @param route the provider and model that actually answered
 */
public record TextResponse(String text, FinishReason finishReason, Usage usage, long costMicros,
                           String providerRequestId, RouteUsed route) {
    public TextResponse {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(finishReason, "finishReason");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(route, "route");
        if (costMicros < 0) throw new IllegalArgumentException("cost must not be negative");
    }

    public enum FinishReason { STOP, LENGTH, OTHER }

    public record RouteUsed(String provider, String model) {
        public RouteUsed {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(model, "model");
        }
    }
}
