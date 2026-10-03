package app.mnema.learning.catalog.exercise;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ExerciseNewMarksTest {
    @Test
    void aTtlShorterThanASecondIsRefusedAtStartup() {
        org.springframework.jdbc.core.simple.JdbcClient jdbc = mock(org.springframework.jdbc.core.simple.JdbcClient.class);
        assertThatThrownBy(() -> new ExerciseNewMarks(jdbc, Duration.ofMillis(999))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExerciseNewMarks(jdbc, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new ExerciseNewMarks(jdbc, Duration.ofSeconds(1))).doesNotThrowAnyException();
    }
}
