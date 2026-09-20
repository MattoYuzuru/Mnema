package app.mnema.learning.study.session;

import app.mnema.learning.platform.api.InvalidRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StudySessionCommandTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static JsonNode fixture;

    @BeforeAll
    static void loadFixture() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/study/session.json"))) root = root.getParent();
        fixture = JSON.readTree(Files.readString(root.resolve("contracts/study/session.json")));
    }

    @Test
    void parsesEveryModeFromTheSharedContract() {
        StudySessionCommand scheduled = read(fixture.path("startScheduled"));
        StudySessionCommand replay = read(fixture.path("startReplay"));
        StudySessionCommand practice = read(fixture.path("startPractice"));

        assertThat(scheduled.mode()).isEqualTo(StudySessionCommand.Mode.SCHEDULED);
        assertThat(scheduled.maxPresentations()).isEqualTo(20);
        assertThat(scheduled.maxNewObjectives()).isEqualTo(5);
        assertThat(replay.sourceSessionId()).isNotNull();
        assertThat(practice.includeNew()).isFalse();
        assertThat(practice.practiceOrder()).isEqualTo(StudySessionCommand.PracticeOrder.WEAKEST_FIRST);
    }

    @Test
    void rejectsUnknownFieldsInvalidBudgetsAndModeSpecificSpoofing() {
        ObjectNode unknown = fixture.path("startScheduled").deepCopy();
        unknown.put("timezone", "Pacific/Honolulu");
        assertThatThrownBy(() -> read(unknown)).isInstanceOf(InvalidRequestException.class);

        ObjectNode oversized = fixture.path("startScheduled").deepCopy();
        oversized.withObject("budget").put("maxPresentations", 101);
        assertThatThrownBy(() -> read(oversized)).isInstanceOf(InvalidRequestException.class);

        ObjectNode excessiveNew = fixture.path("startScheduled").deepCopy();
        excessiveNew.withObject("budget").put("maxNewObjectives", 21);
        assertThatThrownBy(() -> read(excessiveNew)).isInstanceOf(InvalidRequestException.class);

        ObjectNode missingSource = fixture.path("startReplay").deepCopy();
        missingSource.remove("sourceSessionId");
        assertThatThrownBy(() -> read(missingSource)).isInstanceOf(InvalidRequestException.class);
    }

    private static StudySessionCommand read(JsonNode value) {
        return StudySessionCommand.read(new ByteArrayInputStream(value.toString().getBytes(StandardCharsets.UTF_8)));
    }
}
