package app.mnema.learning.study.attempt;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The settings of the assessment and its sweeper. */
class AssessmentSettingsTest {
    @Test
    void theDefaultsAreTheOwnersDecision() {
        AssessmentSettings settings = new AssessmentSettings(Duration.ofSeconds(20), Duration.ofSeconds(2), 16, " ru ");
        assertThat(settings.deadline()).isEqualTo(Duration.ofSeconds(20));
        assertThat(settings.feedbackLanguage()).isEqualTo("ru");
    }

    @Test
    void outOfRangeSettingsStopTheStart() {
        assertThatThrownBy(() -> new AssessmentSettings(Duration.ofMillis(999), Duration.ofSeconds(2), 16, "ru")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssessmentSettings(Duration.ofMinutes(6), Duration.ofSeconds(2), 16, "ru")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssessmentSettings(Duration.ofSeconds(20), Duration.ZERO, 16, "ru")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssessmentSettings(Duration.ofSeconds(20), Duration.ofSeconds(-1), 16, "ru")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssessmentSettings(Duration.ofSeconds(20), Duration.ofSeconds(2), 0, "ru")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssessmentSettings(Duration.ofSeconds(20), Duration.ofSeconds(2), 257, "ru")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssessmentSettings(Duration.ofSeconds(20), Duration.ofSeconds(2), 16, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssessmentSettings(Duration.ofSeconds(20), Duration.ofSeconds(2), 16, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AssessmentSettings(Duration.ofSeconds(20), Duration.ofSeconds(2), 16, "x".repeat(17))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSweeperAsksTheServiceAndSurvivesItsFailures() {
        AssessmentService service = mock(AssessmentService.class);
        AssessmentSweeper sweeper = new AssessmentSweeper(service);
        sweeper.sweep();
        verify(service).expireOverdue();
        when(service.expireOverdue()).thenThrow(new IllegalStateException("db down"));
        sweeper.sweep();
        verify(service, never()).prepare(org.mockito.ArgumentMatchers.any());
    }
}
