package app.mnema.learning.ai;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.UUID;

/**
 * One structured log line and the Micrometer series of every provider call. Only identifiers, enums and counts are
 * emitted: never a prompt, a response, a key or the user key.
 * Series: {@code mnema_ai_calls_total{provider,model,capability,outcome}}, {@code mnema_ai_call_seconds} and
 * {@code mnema_ai_cost_micros_total} (both tagged provider, model, capability).
 */
final class AiTelemetry {
    private static final Logger LOG = LoggerFactory.getLogger(AiTelemetry.class);
    private final MeterRegistry registry;

    AiTelemetry(MeterRegistry registry) { this.registry = registry; }

    void record(AiCapability capability, String provider, String model, UUID stepId, String outcome, Duration latency,
                Usage usage, long costMicros) {
        String cap = capability.label();
        registry.counter("mnema_ai_calls_total", "provider", provider, "model", model, "capability", cap,
                "outcome", outcome).increment();
        Timer.builder("mnema_ai_call_seconds").tags("provider", provider, "model", model, "capability", cap)
                .register(registry).record(latency);
        if (costMicros > 0) {
            registry.counter("mnema_ai_cost_micros_total", "provider", provider, "model", model, "capability", cap)
                    .increment(costMicros);
        }
        LOG.info("ai_call provider={} model={} capability={} step_id={} outcome={} latency_ms={} in_hit={} in_miss={} out={} "
                        + "cost_micros={}", provider, model, cap, stepId == null ? "-" : stepId, outcome, latency.toMillis(),
                usage.cacheHitTokens(), usage.cacheMissTokens(), usage.completionTokens(), costMicros);
    }
}
