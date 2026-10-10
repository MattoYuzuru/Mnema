package app.mnema.learning.platform.jobs;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobSettingsTest {
    @Test
    void defaultsAreTheDocumentedPolicy() {
        JobSettings settings = new JobSettings(new MockEnvironment());

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.sweepInterval()).isEqualTo(Duration.ofSeconds(2));
        assertThat(settings.retention()).isEqualTo(Duration.ofDays(7));
        assertThat(settings.maxConcurrency()).isEqualTo(4);
        assertThat(settings.queue("copy")).isEqualTo(new JobSettings.Queue(true, 2, Duration.ofSeconds(60), 5, Duration.ofSeconds(10), Duration.ofMinutes(10)));
    }

    @Test
    void aQueueIsConfiguredByItsOwnPrefixAndDotsInItsNameAreKept() {
        MockEnvironment environment = new MockEnvironment().withProperty("learning.jobs.enabled", "false").withProperty("learning.jobs.retention", "PT12H")
                .withProperty("learning.jobs.sweep-interval", "PT0.5S")
                .withProperty("learning.jobs.copy.materialize.enabled", " False ").withProperty("learning.jobs.copy.materialize.concurrency", "4")
                .withProperty("learning.jobs.copy.materialize.lease", "PT30S").withProperty("learning.jobs.copy.materialize.max-attempts", "3")
                .withProperty("learning.jobs.copy.materialize.backoff", "PT1S").withProperty("learning.jobs.copy.materialize.backoff-max", "PT1S");
        JobSettings settings = new JobSettings(environment);

        assertThat(settings.enabled()).isFalse();
        assertThat(settings.retention()).isEqualTo(Duration.ofHours(12));
        assertThat(settings.sweepInterval()).isEqualTo(Duration.ofMillis(500));
        assertThat(settings.queue("copy.materialize")).isEqualTo(new JobSettings.Queue(false, 4, Duration.ofSeconds(30), 3, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThat(settings.queue("other").enabled()).isTrue();
    }

    @Test
    void outOfRangeAndMalformedValuesAreRefused() {
        assertThatThrownBy(() -> new JobSettings(new MockEnvironment().withProperty("learning.jobs.enabled", "maybe"))).hasMessageContaining("learning.jobs.enabled");
        assertThatThrownBy(() -> new JobSettings(new MockEnvironment().withProperty("learning.jobs.sweep-interval", "PT0S"))).hasMessageContaining("sweep-interval");
        assertThatThrownBy(() -> new JobSettings(new MockEnvironment().withProperty("learning.jobs.sweep-interval", "PT6M"))).hasMessageContaining("sweep-interval");
        assertThatThrownBy(() -> new JobSettings(new MockEnvironment().withProperty("learning.jobs.retention", "PT1M"))).hasMessageContaining("retention");
        assertThatThrownBy(() -> new JobSettings(new MockEnvironment().withProperty("learning.jobs.retention", "a week"))).hasMessageContaining("retention");
        for (String bad : new String[] {"0", "65", "-1", "many"}) {
            assertThatThrownBy(() -> new JobSettings(new MockEnvironment().withProperty("learning.jobs.max-concurrency", bad)))
                    .hasMessageContaining("learning.jobs.max-concurrency");
        }
        assertThat(new JobSettings(new MockEnvironment().withProperty("learning.jobs.max-concurrency", "1")).maxConcurrency()).isEqualTo(1);

        for (String[] bad : new String[][] {{"concurrency", "0"}, {"concurrency", "65"}, {"concurrency", "two"}, {"lease", "PT4S"}, {"lease", "PT16M"},
                {"lease", "soon"}, {"max-attempts", "0"}, {"max-attempts", "101"}, {"backoff", "PT0.05S"}, {"backoff", "PT2H"}, {"backoff-max", "P2D"},
                {"enabled", "1"}}) {
            JobSettings settings = new JobSettings(new MockEnvironment().withProperty("learning.jobs.q." + bad[0], bad[1]));
            assertThatThrownBy(() -> settings.queue("q")).as("%s=%s", bad[0], bad[1]).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("learning.jobs.q." + bad[0]);
        }
    }

    @Test
    void theCapOfTheBackoffMayNotBeBelowItsStart() {
        JobSettings settings = new JobSettings(new MockEnvironment().withProperty("learning.jobs.q.backoff", "PT1M").withProperty("learning.jobs.q.backoff-max", "PT30S"));

        assertThatThrownBy(() -> settings.queue("q")).hasMessageContaining("backoff-max");
    }

    @Test
    void aQueueNameMustHaveTheShapeOfTheTable() {
        JobSettings settings = new JobSettings(new MockEnvironment());

        for (String bad : new String[] {null, "", "Copy", "1copy", "a b", "a/b", "x".repeat(64)}) {
            assertThatThrownBy(() -> settings.queue(bad)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(settings.queue("a".repeat(63)).concurrency()).isEqualTo(2);
    }

    @Test
    void settingsThatGovernNoServedQueueAreReportedByName() {
        MockEnvironment environment = new MockEnvironment().withProperty("learning.jobs.enabled", "true").withProperty("learning.jobs.max-concurrency", "3")
                .withProperty("learning.jobs.copy.materialize.enabled", "false").withProperty("learning.jobs.copy.materialize.lease", "PT30S")
                .withProperty("learning.jobs.cpoy.materialize.enabled", "false").withProperty("learning.jobs.copy.materialize.enabld", "false")
                .withProperty("learning.jobs.sweep-intervall", "PT1S").withProperty("learning.other.enabled", "false");
        JobSettings settings = new JobSettings(environment);

        assertThat(settings.unmatchedKeys(java.util.Set.of("copy.materialize"))).containsExactly("learning.jobs.copy.materialize.enabld",
                "learning.jobs.cpoy.materialize.enabled", "learning.jobs.sweep-intervall");
        assertThat(settings.unmatchedKeys(java.util.Set.of())).contains("learning.jobs.copy.materialize.enabled", "learning.jobs.copy.materialize.lease");
        assertThat(new JobSettings(new org.springframework.core.env.StandardEnvironment()).unmatchedKeys(java.util.Set.of())).isEmpty();
    }
}
