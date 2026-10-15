package app.mnema.learning.community;

import app.mnema.learning.platform.api.InvalidRequestException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecommendationContractTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode contract() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("contracts/community/recommendations.json"))) root = root.getParent();
        return JSON.readTree(Files.readString(root.resolve("contracts/community/recommendations.json")));
    }

    private static ByteArrayInputStream input(JsonNode body) {
        return new ByteArrayInputStream(body.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void executesPublishedCommandExamplesAndConstants() throws Exception {
        JsonNode contract = contract();
        var event = RecommendationCommands.event(input(contract.path("event")));
        assertThat(event.kind()).isEqualTo("DETAIL_VIEW");
        assertThat(event.rankVersion()).isEqualTo(RecommendationService.VERSION);
        assertThat(event.position()).isEqualTo(1);
        assertThat(RecommendationCommands.exposure(input(contract.path("exposure"))).codes()).containsExactly(event.code());
        assertThat(RecommendationCommands.selection(input(contract.path("select").path("request"))).topics()).containsExactly("spanish", "english");
        JsonNode reset = RecommendationCommands.read(input(contract.path("reset").path("request")), java.util.Set.of("commandId"));
        assertThat(RecommendationCommands.command(reset)).isEqualTo(UUID.fromString(contract.path("reset").path("request").path("commandId").stringValue()));
        assertThat(contract.path("constants").path("rankVersion").stringValue()).isEqualTo(RecommendationService.VERSION);
        assertThat(contract.path("constants").path("candidateMax").intValue()).isEqualTo(100);
        assertThat(contract.path("constants").path("shelfMax").intValue()).isEqualTo(12);
        assertThat(contract.path("recommendations").path("decks").get(0).path("addedCount").intValue()).isEqualTo(AudienceCounts.shown(1200));
    }

    @Test
    void boundaryAllowsNullablePositionsAndRejectsUnknownVocabulary() throws Exception {
        ObjectNode event = (ObjectNode) contract().path("event").deepCopy();
        event.putNull("position");
        assertThat(RecommendationCommands.event(input(event)).position()).isNull();
        for (String field : java.util.List.of("kind", "surface", "rankVersion")) {
            ObjectNode bad = event.deepCopy().put(field, "unknown");
            assertThatThrownBy(() -> RecommendationCommands.event(input(bad))).isInstanceOf(InvalidRequestException.class);
        }
        event.put("commandId", "00000000-0000-1000-8000-000000000001");
        assertThatThrownBy(() -> RecommendationCommands.event(input(event))).isInstanceOf(InvalidRequestException.class);
        ObjectNode exposure = (ObjectNode) contract().path("exposure").deepCopy();
        ((ObjectNode) exposure.path("items").get(0)).putNull("position");
        assertThat(RecommendationCommands.exposure(input(exposure)).codes()).hasSize(1);
    }

    @Test
    void exposureAndSelectionAreDistinctBoundedArrays() throws Exception {
        ObjectNode exposure = (ObjectNode) contract().path("exposure").deepCopy();
        ((tools.jackson.databind.node.ArrayNode) exposure.path("items")).add(exposure.path("items").get(0).deepCopy());
        assertThatThrownBy(() -> RecommendationCommands.exposure(input(exposure))).isInstanceOf(InvalidRequestException.class);
        exposure.putArray("items");
        assertThatThrownBy(() -> RecommendationCommands.exposure(input(exposure))).isInstanceOf(InvalidRequestException.class);
        ObjectNode selection = JSON.createObjectNode().put("commandId", UUID.randomUUID().toString());
        var topics = selection.putArray("topicIds");
        for (int i = 0; i < 21; i++) topics.add("topic-" + i);
        assertThatThrownBy(() -> RecommendationCommands.selection(input(selection))).isInstanceOf(InvalidRequestException.class);
    }
}
