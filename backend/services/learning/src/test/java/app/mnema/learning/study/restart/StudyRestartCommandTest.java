package app.mnema.learning.study.restart;

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

class StudyRestartCommandTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static JsonNode fixture;

    @BeforeAll
    static void load() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/study/restart.json"))) root = root.getParent();
        fixture = JSON.readTree(Files.readString(root.resolve("contracts/study/restart.json")));
    }

    @Test
    void parsesSharedRequestAndRejectsDuplicatesOrClientSelectedObjectives() {
        StudyRestartCommand command = read(fixture.path("request"));
        assertThat(command.memberKeys()).hasSize(1);

        ObjectNode duplicate = fixture.path("request").deepCopy();
        duplicate.withArray("memberKeys").add(duplicate.path("memberKeys").get(0));
        assertThatThrownBy(() -> read(duplicate)).isInstanceOf(InvalidRequestException.class);
        ObjectNode authority = fixture.path("request").deepCopy();
        authority.putArray("objectiveIds");
        assertThatThrownBy(() -> read(authority)).isInstanceOf(InvalidRequestException.class);
    }

    private static StudyRestartCommand read(JsonNode value) {
        return StudyRestartCommand.read(new ByteArrayInputStream(value.toString().getBytes(StandardCharsets.UTF_8)));
    }
}
