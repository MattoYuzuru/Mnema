package app.mnema.learning.ai;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** The series the runbook and {@code scripts/ai-ops/metrics_snapshot.py} read: calls, cost and the p50 and p95 of the latency, per provider and capability. */
class AiTelemetryTest {
    @Test
    void aCallRecordsItsCounterItsCostAndItsLatencyPercentiles() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiTelemetry telemetry = new AiTelemetry(registry);
        telemetry.record(AiCapability.TEXT, "deepseek", "deepseek-flash", null, "OK", Duration.ofMillis(1_500), Usage.ZERO, 1_200, AiProperties.EgressMode.DIRECT);
        telemetry.record(AiCapability.TEXT, "deepseek", "deepseek-flash", null, "TIMEOUT", Duration.ofSeconds(8), Usage.ZERO, 0, AiProperties.EgressMode.DIRECT);

        assertThat(registry.get("mnema_ai_calls_total").tag("outcome", "OK").counter().count()).isEqualTo(1);
        assertThat(registry.get("mnema_ai_calls_total").tag("outcome", "TIMEOUT").counter().count()).isEqualTo(1);
        assertThat(registry.get("mnema_ai_cost_micros_total").tag("capability", "text").counter().count()).isEqualTo(1_200);
        assertThat(registry.get("mnema_ai_call_seconds").timer().count()).isEqualTo(2);
        assertThat(registry.find("mnema_ai_call_seconds.percentile").tag("phi", "0.95").gauge()).as("p95 for the snapshot script").isNotNull();
        assertThat(registry.find("mnema_ai_call_seconds.percentile").tag("phi", "0.5").gauge()).isNotNull();
    }
}
