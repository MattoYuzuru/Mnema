package app.mnema.learning.study.retention;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudyRetentionWorkerTest {
    private static StudyRetentionSettings settings(boolean enabled) {
        return new StudyRetentionSettings(enabled, Duration.ofDays(7), 100, 500, 2000, 100, Duration.ofSeconds(30), Duration.ofMinutes(5),
                Duration.ofMinutes(2));
    }

    @Test
    void scheduledTickRunsOnePassWhenEnabled() {
        StudyRetentionPass pass = mock(StudyRetentionPass.class);
        when(pass.run()).thenReturn(new StudyRetentionPass.Result(1, 0, 0, false));
        new StudyRetentionWorker(pass, settings(true)).purge();
        verify(pass).run();
    }

    @Test
    void theKillSwitchStopsEveryPass() {
        StudyRetentionPass pass = mock(StudyRetentionPass.class);
        new StudyRetentionWorker(pass, settings(false)).purge();
        verify(pass, never()).run();
    }

    @Test
    void aFailingPassIsCountedAndNeverEscapesTheScheduler() {
        StudyRetentionPass pass = mock(StudyRetentionPass.class);
        RuntimeException failure = new IllegalStateException("database down");
        when(pass.run()).thenThrow(failure);
        new StudyRetentionWorker(pass, settings(true)).purge();
        verify(pass).failed(failure);
    }

    @Test
    void theScheduleExistsOnlyForTheWorkerAndAllRoles() {
        for (String role : new String[] {"worker", "all", " WORKER ", ""}) {
            String roles = role.isEmpty() ? "learning.runtime.roles=all" : "learning.runtime.roles=" + role;
            contextRunner().withPropertyValues(roles).run(context -> assertThat(context).as(roles).hasSingleBean(StudyRetentionWorker.class));
        }
        contextRunner().withPropertyValues("learning.runtime.roles=api")
                .run(context -> assertThat(context).doesNotHaveBean(StudyRetentionWorker.class));
        // no role configured at all is the all-in-one process
        contextRunner().run(context -> assertThat(context).hasSingleBean(StudyRetentionWorker.class));
    }

    @Test
    void thePassItselfIsAWorkerRoleBeanToo() {
        new ApplicationContextRunner().withUserConfiguration(StudyRetentionPass.class)
                .withPropertyValues("learning.runtime.roles=api")
                .run(context -> assertThat(context).doesNotHaveBean(StudyRetentionPass.class));
    }

    private static ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner().withUserConfiguration(StudyRetentionWorker.class)
                .withBean(StudyRetentionPass.class, () -> mock(StudyRetentionPass.class))
                .withBean(StudyRetentionSettings.class, () -> settings(true));
    }

    private static StudyRetentionSettings build(Duration horizon, int sessions, int rows, int chunk, int sweep, Duration maxRun, Duration interval,
                                                Duration delay) {
        return new StudyRetentionSettings(true, horizon, sessions, rows, chunk, sweep, maxRun, interval, delay);
    }

    @Test
    void policyIsValidatedAtStart() {
        assertThat(settings(true).snapshotHorizon()).isEqualTo(Duration.ofDays(7));
        // five days is the floor: the 60-hour worst case of the replay window (offsets up to +-18:00), the 24-hour replay child, and margin
        build(Duration.ofDays(5), 1, 1, 10, 1, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ZERO);
        Duration day = Duration.ofDays(1);
        for (Duration bad : new Duration[] {Duration.ofDays(5).minusSeconds(1), Duration.ofDays(3), Duration.ofDays(91), null}) {
            assertThatThrownBy(() -> build(bad, 100, 500, 2000, 100, Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofMinutes(2)))
                    .as("horizon " + bad).isInstanceOf(IllegalArgumentException.class);
        }
        for (int[] bad : new int[][] {{0, 500, 2000, 100}, {1001, 500, 2000, 100}, {100, 0, 2000, 100}, {100, 5001, 2000, 100},
                {100, 500, 9, 100}, {100, 500, 10_001, 100}, {100, 500, 2000, 0}, {100, 500, 2000, 1001}}) {
            assertThatThrownBy(() -> build(Duration.ofDays(7), bad[0], bad[1], bad[2], bad[3], Duration.ofSeconds(30), Duration.ofMinutes(5),
                    Duration.ofMinutes(2))).as(java.util.Arrays.toString(bad)).isInstanceOf(IllegalArgumentException.class);
        }
        for (Duration bad : new Duration[] {Duration.ofMillis(999), Duration.ofMinutes(6)}) {
            assertThatThrownBy(() -> build(Duration.ofDays(7), 100, 500, 2000, 100, bad, Duration.ofMinutes(5), Duration.ofMinutes(2)))
                    .as("max-run " + bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> build(Duration.ofDays(7), 100, 500, 2000, 100, Duration.ofSeconds(30), Duration.ZERO, Duration.ofMinutes(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> build(Duration.ofDays(7), 100, 500, 2000, 100, Duration.ofSeconds(30), day.plusSeconds(1), Duration.ofMinutes(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> build(Duration.ofDays(7), 100, 500, 2000, 100, Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSwitchIsReadFromTheEnvironmentVariableSpringDerivesFromTheKey() {
        // LEARNING_STUDY_RETENTION_ENABLED=false is what the deployment sets (relaxed binding of an operating-system variable)
        var variables = new org.springframework.core.env.SystemEnvironmentPropertySource("environment",
                new java.util.HashMap<String, Object>(java.util.Map.of("LEARNING_STUDY_RETENTION_ENABLED", "false",
                        "LEARNING_STUDY_RETENTION_SNAPSHOT_HORIZON", "P9D")));
        assertThat(variables.getProperty("learning.study.retention.enabled")).isEqualTo("false");
        assertThat(variables.getProperty("learning.study.retention.snapshot-horizon")).isEqualTo("P9D");
        var sources = new org.springframework.core.env.MutablePropertySources();
        sources.addFirst(variables);
        var resolver = new org.springframework.core.env.PropertySourcesPropertyResolver(sources);
        assertThat(resolver.getProperty("learning.study.retention.enabled", Boolean.class)).isFalse();
    }
}
