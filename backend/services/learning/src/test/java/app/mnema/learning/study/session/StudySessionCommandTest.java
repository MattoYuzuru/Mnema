package app.mnema.learning.study.session;

import app.mnema.learning.platform.api.InvalidRequestException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
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
        ObjectNode unknown = (ObjectNode) fixture.path("startScheduled").deepCopy();
        unknown.put("timezone", "Pacific/Honolulu");
        assertThatThrownBy(() -> read(unknown)).isInstanceOf(InvalidRequestException.class);

        ObjectNode oversized = (ObjectNode) fixture.path("startScheduled").deepCopy();
        oversized.withObject("budget").put("maxPresentations", 101);
        assertThatThrownBy(() -> read(oversized)).isInstanceOf(InvalidRequestException.class);

        ObjectNode excessiveNew = (ObjectNode) fixture.path("startScheduled").deepCopy();
        excessiveNew.withObject("budget").put("maxNewObjectives", 21);
        assertThatThrownBy(() -> read(excessiveNew)).isInstanceOf(InvalidRequestException.class);

        ObjectNode missingSource = (ObjectNode) fixture.path("startReplay").deepCopy();
        missingSource.remove("sourceSessionId");
        assertThatThrownBy(() -> read(missingSource)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void budgetNumbersMustBeJsonIntegersNotFractionsDecimalsOrStrings() {
        for (String field : new String[]{"maxPresentations", "maxNewObjectives"}) {
            for (String invalid : new String[]{"1.5", "5.5", "5.0", "\"1\"", "null", "true", "[]", "1e0", "3000000000"}) {
                String body = fixture.path("startScheduled").toString()
                        .replaceFirst("\"" + field + "\":\\d+", "\"" + field + "\":" + invalid);
                assertThatThrownBy(() -> StudySessionCommand.read(
                        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))))
                        .as(field + "=" + invalid).isExactlyInstanceOf(InvalidRequestException.class);
            }
        }
        ObjectNode valid = (ObjectNode) fixture.path("startScheduled").deepCopy();
        valid.withObject("budget").put("maxPresentations", 5).put("maxNewObjectives", 2);
        assertThat(read(valid).maxPresentations()).isEqualTo(5);
        assertThat(read(valid).maxNewObjectives()).isEqualTo(2);
    }

    private static StudySessionCommand read(JsonNode value) {
        return StudySessionCommand.read(new ByteArrayInputStream(value.toString().getBytes(StandardCharsets.UTF_8)));
    }
}
