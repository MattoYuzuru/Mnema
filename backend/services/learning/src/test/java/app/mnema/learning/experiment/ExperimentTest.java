package app.mnema.learning.experiment;

import app.mnema.learning.platform.api.RateLimitedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A/B infrastructure: a deterministic account-to-variant function, a split that matches its weights, fail-closed control, and counters without personal data. */
class ExperimentTest {
    private static MockEnvironment environment(String key, String variants, boolean enabled) {
        return new MockEnvironment().withProperty("learning.experiments." + key + ".variants", variants)
                .withProperty("learning.experiments." + key + ".enabled", Boolean.toString(enabled));
    }

    private static ExperimentAssignments assignments(String secret) {
        return new ExperimentAssignments(new ExperimentSettings(environment("plans_year_first", "control:50,plans_year_first:50", true)), secret);
    }

    @Test
    void theSameAccountAlwaysGetsTheSameVariantAndTheAssignmentIsPerExperiment() {
        ExperimentAssignments assignments = assignments("secret-1");
        UUID account = UUID.randomUUID();

        String first = assignments.variant(account, "plans_year_first");

        for (int repeat = 0; repeat < 50; repeat++) assertThat(assignments.variant(account, "plans_year_first")).isEqualTo(first);
        assertThat(assignments(" secret-1".strip()).variant(account, "plans_year_first")).isEqualTo(first);
        assertThat(assignments.variants(account)).containsExactly(Map.entry("plans_year_first", first));
        assertThat(first).isIn("control", "plans_year_first");
    }

    @Test
    void theSplitOfTenThousandAccountsMatchesTheWeightsWithinFivePoints() {
        ExperimentAssignments assignments = new ExperimentAssignments(new ExperimentSettings(
                environment("pricing", "control:50,a:30,b:20", true)), "secret-1");
        Map<String, Integer> counts = new HashMap<>();
        for (int index = 0; index < 10_000; index++) counts.merge(assignments.variant(UUID.randomUUID(), "pricing"), 1, Integer::sum);

        assertThat(counts.get("control") / 100.0).isBetween(45.0, 55.0);
        assertThat(counts.get("a") / 100.0).isBetween(25.0, 35.0);
        assertThat(counts.get("b") / 100.0).isBetween(15.0, 25.0);
    }

    @Test
    void anotherSecretReshufflesAndNoSecretMeansEverybodyIsControl() {
        UUID[] accounts = new UUID[200];
        for (int index = 0; index < accounts.length; index++) accounts[index] = UUID.randomUUID();
        int differing = 0;
        for (UUID account : accounts) {
            if (!assignments("one").variant(account, "plans_year_first").equals(assignments("two").variant(account, "plans_year_first"))) differing++;
        }

        assertThat(differing).isPositive();
        for (UUID account : accounts) {
            assertThat(assignments("").variant(account, "plans_year_first")).isEqualTo("control");
            assertThat(assignments("one").variant(account, "no_such_experiment")).isEqualTo("control");
        }
    }

    @Test
    void aDisabledExperimentIsNotExposedAndNeverBucketed() {
        ExperimentAssignments assignments = new ExperimentAssignments(new ExperimentSettings(
                environment("plans_year_first", "control:50,plans_year_first:50", false)), "secret");

        assertThat(assignments.variants(UUID.randomUUID())).isEmpty();
        assertThat(assignments.known("plans_year_first")).isFalse();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ExperimentEvents events = new ExperimentEvents(assignments, meters);
        assertThat(events.record(UUID.randomUUID(), "plans_year_first", ExperimentEvents.Event.EXPOSURE)).isFalse();
        assertThat(events.trackedAccounts()).isZero();
        assertThat(meters.getMeters()).isEmpty();
    }

    @Test
    void definitionsThatWouldSkewAResultFailAtStartup() {
        for (String variants : new String[] {"control:50,b:40", "control:60,b:60", "a:50,b:50", "control:100,control:0", "control:50,b", "control:50,B:50",
                "control:0,b:100", "control:50,control:50", "", "control:abc,b:50"}) {
            assertThatThrownBy(() -> new ExperimentSettings(environment("exp", variants, true))).as(variants)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new ExperimentSettings(environment("Bad-Key", "control:100", true))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ExperimentSettings(environment("solo", "control:100", true)).enabled()).containsKey("solo");
    }

    @Test
    void twentyEnabledAssignmentsFitThePlansWireAndATwentyFirstFailsAtStartup() {
        MockEnvironment environment = new MockEnvironment();
        for (int index = 0; index < 20; index++) {
            environment.withProperty("learning.experiments.exp_" + index + ".enabled", "true")
                    .withProperty("learning.experiments.exp_" + index + ".variants", "control:100");
        }
        // Disabled definitions do not appear in the response and do not consume its 20-entry bound.
        environment.withProperty("learning.experiments.exp_20.enabled", "false")
                .withProperty("learning.experiments.exp_20.variants", "control:100");
        ExperimentAssignments assignments = new ExperimentAssignments(new ExperimentSettings(environment), "");
        assertThat(assignments.variants(UUID.randomUUID())).hasSize(20).containsOnlyKeys(
                java.util.stream.IntStream.range(0, 20).mapToObj(index -> "exp_" + index).toList());

        environment.withProperty("learning.experiments.exp_20.enabled", "true");
        assertThatThrownBy(() -> new ExperimentSettings(environment)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too many enabled experiments");
    }

    @Test
    void eventsAreCountedPerKeyVariantAndEventWithoutAnAccountInTheLabels() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ExperimentAssignments assignments = assignments("secret-1");
        ExperimentEvents events = new ExperimentEvents(assignments, meters);
        UUID account = UUID.randomUUID();
        String variant = assignments.variant(account, "plans_year_first");

        assertThat(events.record(account, "plans_year_first", ExperimentEvents.Event.EXPOSURE)).isTrue();
        events.record(account, "plans_year_first", ExperimentEvents.Event.EXPOSURE);
        events.record(account, "plans_year_first", ExperimentEvents.Event.CONVERSION);
        assertThat(events.record(account, "unknown_experiment", ExperimentEvents.Event.EXPOSURE)).isFalse();

        assertThat(meters.get("mnema_experiment_events_total").tag("key", "plans_year_first").tag("variant", variant).tag("event", "EXPOSURE")
                .counter().count()).isEqualTo(2.0);
        assertThat(meters.get("mnema_experiment_events_total").tag("event", "CONVERSION").counter().count()).isEqualTo(1.0);
        meters.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag -> assertThat(tag.getValue()).doesNotContain(account.toString())));
        assertThat(meters.getMeters()).hasSize(2);
    }

    @Test
    void anAccountThatFloodsEventsIsRateLimitedAndTheWindowSlides() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-02T09:00:00Z"));
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        ExperimentEvents events = new ExperimentEvents(assignments("secret-1"), new SimpleMeterRegistry(), clock);
        UUID account = UUID.randomUUID();
        for (int index = 0; index < ExperimentEvents.PER_MINUTE; index++) events.record(account, "plans_year_first", ExperimentEvents.Event.EXPOSURE);

        assertThatThrownBy(() -> events.record(account, "plans_year_first", ExperimentEvents.Event.EXPOSURE))
                .isInstanceOfSatisfying(RateLimitedException.class, limited -> assertThat(limited.retryAfterSeconds()).isBetween(1L, 60L));
        events.record(UUID.randomUUID(), "plans_year_first", ExperimentEvents.Event.EXPOSURE);

        now.set(Instant.parse("2026-10-02T09:01:01Z"));
        assertThat(events.record(account, "plans_year_first", ExperimentEvents.Event.EXPOSURE)).isTrue();
    }

    @Test
    void aFullTableRefusesUntrackableEventsUntilTheNextSweepCanFreeRoom() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-02T09:00:00Z"));
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ExperimentEvents events = new ExperimentEvents(assignments("secret-1"), meters, clock, 3);
        for (int index = 0; index < 3; index++) events.record(UUID.randomUUID(), "plans_year_first", ExperimentEvents.Event.EXPOSURE);
        assertThat(events.trackedAccounts()).isEqualTo(3);

        // Full and nothing has expired: a newcomer cannot bypass the per-account rate guard.
        now.set(Instant.parse("2026-10-02T09:00:30Z"));
        assertThatThrownBy(() -> events.record(UUID.randomUUID(), "plans_year_first", ExperimentEvents.Event.EXPOSURE))
                .isInstanceOfSatisfying(RateLimitedException.class, limited -> assertThat(limited.retryAfterSeconds()).isBetween(1L, 60L));
        assertThat(events.record(UUID.randomUUID(), "unknown", ExperimentEvents.Event.EXPOSURE)).isFalse();
        assertThat(events.trackedAccounts()).isEqualTo(3);

        // Once the windows have expired the next sweep frees the table for a newcomer.
        now.set(Instant.parse("2026-10-02T09:01:10Z"));
        events.record(UUID.randomUUID(), "plans_year_first", ExperimentEvents.Event.EXPOSURE);
        assertThat(events.trackedAccounts()).isEqualTo(1);
        assertThat(meters.get("mnema_experiment_events_total").counters().stream().mapToDouble(counter -> counter.count()).sum()).isEqualTo(4.0);
    }

    @Test
    void concurrentNewAccountsCannotExceedTheWindowBoundOrBypassTheGuard() throws Exception {
        int callers = 32;
        int limit = 3;
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ExperimentEvents events = new ExperimentEvents(assignments("secret-1"), meters,
                Clock.fixed(Instant.parse("2026-10-02T09:00:00Z"), ZoneOffset.UTC), limit);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var answers = new ArrayList<Future<Boolean>>();
            for (int index = 0; index < callers; index++) answers.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start not released");
                try { return events.record(UUID.randomUUID(), "plans_year_first", ExperimentEvents.Event.EXPOSURE); }
                catch (RateLimitedException expected) { return false; }
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int counted = 0;
            for (Future<Boolean> answer : answers) if (answer.get(10, TimeUnit.SECONDS)) counted++;
            assertThat(counted).isEqualTo(limit);
            assertThat(events.trackedAccounts()).isEqualTo(limit);
            assertThat(meters.get("mnema_experiment_events_total").counters().stream().mapToDouble(counter -> counter.count()).sum())
                    .isEqualTo(limit);
        } finally { start.countDown(); }
    }
}
