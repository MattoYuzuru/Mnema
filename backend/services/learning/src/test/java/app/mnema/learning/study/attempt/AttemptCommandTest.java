package app.mnema.learning.study.attempt;

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

class AttemptCommandTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static JsonNode fixture;

    @BeforeAll
    static void load() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/study/attempts.json"))) root = root.getParent();
        fixture = JSON.readTree(Files.readString(root.resolve("contracts/study/attempts.json")));
    }

    @Test
    void parsesEveryContractResponseWithoutClientAuthorityFields() {
        assertThat(read(fixture.path("typedSubmit")).response()).isInstanceOf(AttemptCommand.TextResponse.class);
        assertThat(read(fixture.path("selfCheckSubmit")).response())
                .isInstanceOf(AttemptCommand.SelfCheckResponse.class);
        assertThat(read(fixture.path("choiceSubmit")).response()).isInstanceOf(AttemptCommand.ChoiceResponse.class);
        assertThat(read(fixture.path("cancelSubmit")).response()).isInstanceOf(AttemptCommand.CancelResponse.class);
    }

    @Test
    void sharedMultipleChoiceAndPairCheckFixtureExecutesTheCanonicalCommands() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("contracts/study/choice-and-pairs.json"))) root = root.getParent();
        JsonNode document = JSON.readTree(Files.readString(root.resolve("contracts/study/choice-and-pairs.json")));
        AttemptCommand command = read(document.path("multipleSubmit"));
        assertThat(((AttemptCommand.ChoiceResponse) command.response()).optionIds()).hasSize(2);
        PairCheckCommand pair = PairCheckCommand.read(new ByteArrayInputStream(
                document.path("pairCheck").toString().getBytes(StandardCharsets.UTF_8)));
        assertThat(pair.presentationId()).isEqualTo(command.presentationId());
    }

    @Test
    void rejectsUnknownFieldsDuplicateHintsAndUnboundedDiagnostics() {
        ObjectNode authority = fixture.path("typedSubmit").deepCopy();
        authority.put("mode", "SCHEDULED");
        assertThatThrownBy(() -> read(authority)).isInstanceOf(InvalidRequestException.class);
        ObjectNode duplicate = fixture.path("typedSubmit").deepCopy();
        duplicate.withArray("hintsUsed").add("REVEAL").add("REVEAL");
        assertThatThrownBy(() -> read(duplicate)).isInstanceOf(InvalidRequestException.class);
        ObjectNode duration = fixture.path("typedSubmit").deepCopy();
        duration.put("durationMs", 3_600_001);
        assertThatThrownBy(() -> read(duration)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void choiceResponseRejectsEmptyDuplicateAndLegacyScalarSelections() {
        ObjectNode root = fixture.path("choiceSubmit").deepCopy();
        ObjectNode response = root.withObject("response");
        response.withArray("optionIds").removeAll();
        assertThatThrownBy(() -> read(root)).isInstanceOf(InvalidRequestException.class);
        String id = java.util.UUID.randomUUID().toString();
        response.withArray("optionIds").add(id).add(id);
        assertThatThrownBy(() -> read(root)).isInstanceOf(InvalidRequestException.class);
        response.remove("optionIds"); response.put("optionId", id);
        assertThatThrownBy(() -> read(root)).isInstanceOf(InvalidRequestException.class);
    }

    private static AttemptCommand read(JsonNode value) {
        return AttemptCommand.read(new ByteArrayInputStream(value.toString().getBytes(StandardCharsets.UTF_8)));
    }
}
